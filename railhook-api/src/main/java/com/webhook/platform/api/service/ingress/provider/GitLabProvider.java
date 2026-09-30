package com.webhook.platform.api.service.ingress.provider;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** GitLab does not sign the body; it sends the shared secret back verbatim in X-Gitlab-Token. */
@Component
public class GitLabProvider implements InboundProvider {

    private static final String TOKEN_HEADER = "X-Gitlab-Token";

    // The replay key: the token is identical on every request.
    private static final String EVENT_UUID_HEADER = "X-Gitlab-Event-UUID";

    @Override
    public String id() {
        return "GITLAB";
    }

    @Override
    public String displayName() {
        return "GitLab";
    }

    @Override
    public String signatureHeader() {
        return TOKEN_HEADER;
    }

    // Newest name first (webhook-id since 19.0, Idempotency-Key since 17.4); not X-Gitlab-Event-UUID,
    // which GitLab shares across a recursive webhook chain.
    @Override
    public String eventId(HttpServletRequest request, String body) {
        String deliveryId = request.getHeader("webhook-id");
        return deliveryId != null && !deliveryId.isBlank() ? deliveryId : request.getHeader("Idempotency-Key");
    }

    @Override
    public VerificationResult verify(String secret, byte[] body, HttpServletRequest request) {
        String token = request.getHeader(TOKEN_HEADER);
        if (token == null || token.isBlank()) {
            return VerificationResult.failure("Missing header: " + TOKEN_HEADER);
        }
        if (secret == null || secret.isBlank()) {
            return VerificationResult.failure("No secret token configured for this source");
        }

        boolean valid = MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                secret.getBytes(StandardCharsets.UTF_8));
        if (!valid) {
            return VerificationResult.failure("GitLab token mismatch");
        }

        String eventUuid = request.getHeader(EVENT_UUID_HEADER);
        return eventUuid != null && !eventUuid.isBlank()
                ? VerificationResult.success(eventUuid)
                : VerificationResult.success();
    }
}
