package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.TunnelRequestLog;
import com.webhook.platform.api.domain.entity.TunnelSession;
import com.webhook.platform.api.domain.repository.TunnelRequestLogRepository;
import com.webhook.platform.api.exception.DomainException;
import com.webhook.platform.api.exception.ErrorCode;
import com.webhook.platform.api.security.SuspensionCheck;
import com.webhook.platform.api.tenancy.TenantContext;
import com.webhook.platform.common.dto.tunnel.TunnelRequestMessage;
import com.webhook.platform.common.dto.tunnel.TunnelResponseMessage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import com.webhook.platform.api.service.ingress.HeaderSanitizer;

import java.util.concurrent.Executor;

/** Admission (tunnel up, rate limit, body size) is decided here, so a refusal never reaches the CLI. */
@Service
@Slf4j
@RequiredArgsConstructor
public class TunnelIngressService {

    private static final int MAX_BODY_SIZE = 512 * 1024;
    private static final int RATE_LIMIT_PER_SECOND = 10;

    public sealed interface Outcome {

        record Answered(TunnelResponseMessage response) implements Outcome {
        }

        record Refused(ErrorCode error, String message) implements Outcome {
        }

        record TimedOut() implements Outcome {
        }

        record Failed(String detail) implements Outcome {
        }
    }

    private final TunnelService tunnelService;
    private final RedisTunnelCoordinator redisTunnelCoordinator;
    private final RedisRateLimiterService rateLimiterService;
    private final TunnelRequestLogRepository requestLogRepository;
    private final TunnelBandwidthService bandwidthService;
    private final MeterRegistry meterRegistry;
    @Qualifier("tunnelMeteringExecutor")
    private final Executor tunnelMeteringExecutor;
    private final SuspensionCheck suspensionCheck;

    public Outcome forward(String slug, TunnelRequestMessage request, byte[] body) {
        if (!redisTunnelCoordinator.isActiveInCluster(slug)) {
            return refuse("offline", ErrorCode.TUNNEL_OFFLINE, "Tunnel is not connected");
        }
        if (!rateLimiterService.tryAcquireForSlug(slug, RATE_LIMIT_PER_SECOND)) {
            log.warn("Rate limit exceeded for tunnel slug: {}", slug);
            return refuse("rate_limited", ErrorCode.RATE_LIMIT_EXCEEDED, "Too many requests to this tunnel");
        }
        if (body != null && body.length > MAX_BODY_SIZE) {
            return refuse("payload_too_large", ErrorCode.PAYLOAD_TOO_LARGE, "Request body exceeds maximum size");
        }

        // Unscoped: the slug is the only thing naming an organization. Suspension is refused here
        // because the write interceptor never runs on this path.
        TunnelSession session;
        try {
            session = TenantContext.callAsSystem(() -> tunnelService.getActiveBySlug(slug));
        } catch (DomainException e) {
            return refuse("offline", ErrorCode.TUNNEL_OFFLINE, "Tunnel is not connected");
        }
        if (suspensionCheck.suspensionReason(session.getOrganizationId()).isPresent()) {
            log.warn("Tunnel request refused: organization {} is suspended (slug={})",
                    session.getOrganizationId(), slug);
            return refuse("suspended", ErrorCode.TUNNEL_SUSPENDED, "This tunnel is not accepting requests");
        }

        long startMs = System.currentTimeMillis();
        TunnelResponseMessage response = redisTunnelCoordinator.forwardRequest(slug, request);
        int durationMs = (int) (System.currentTimeMillis() - startMs);

        // Bandwidth is billed in bytes, not characters.
        int requestSize = body != null ? body.length : 0;
        byte[] responseBody = response != null ? response.bodyBytes() : null;
        int responseSize = responseBody != null ? responseBody.length : 0;
        recordAsync(session, slug, request, requestSize, responseSize, response, durationMs);

        if (response == null) {
            outcomeCounter("timeout").increment();
            return new Outcome.TimedOut();
        }
        if (response.getError() != null) {
            outcomeCounter("error").increment();
            return new Outcome.Failed(response.getError());
        }
        outcomeCounter("success").increment();
        return new Outcome.Answered(response);
    }

    private Outcome refuse(String outcome, ErrorCode error, String message) {
        outcomeCounter(outcome).increment();
        return new Outcome.Refused(error, message);
    }

    /** Runs inside the tunnel owner's organization, or the save fails on an unresolved tenant. */
    private void recordAsync(TunnelSession session, String slug, TunnelRequestMessage request, int requestSize,
            int responseSize, TunnelResponseMessage response, int durationMs) {
        tunnelMeteringExecutor.execute(() -> {
            try {
                TenantContext.runAs(session.getOrganizationId(), () -> {
                    bandwidthService.recordBytes(requestSize + responseSize);
                    requestLogRepository.save(TunnelRequestLog.builder()
                            .tunnelSessionId(session.getId())
                            .organizationId(session.getOrganizationId())
                            .slug(slug)
                            .requestId(request.getRequestId())
                            .method(request.getMethod())
                            .path(request.getPath())
                            .queryString(request.getQueryString())
                            // Relayed verbatim, since the local service needs the real Authorization, but stored sanitized.
                            .requestHeaders(HeaderSanitizer.sanitize(request.getHeaders()))
                            .requestBodySize(requestSize)
                            .responseStatus(response != null ? response.getStatusCode() : null)
                            .responseHeaders(response != null
                                    ? HeaderSanitizer.sanitize(response.getHeaders()) : null)
                            .responseBodySize(responseSize)
                            .durationMs(durationMs)
                            .error(response != null ? response.getError() : "timeout")
                            .build());
                });
            } catch (Exception e) {
                log.debug("Failed to log/meter tunnel request: slug={}, error={}", slug, e.getMessage());
            }
        });
    }

    private Counter outcomeCounter(String outcome) {
        return Counter.builder("tunnel_ingress_total").tag("outcome", outcome).register(meterRegistry);
    }
}
