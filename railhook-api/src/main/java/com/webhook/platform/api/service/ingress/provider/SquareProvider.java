package com.webhook.platform.api.service.ingress.provider;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import lombok.RequiredArgsConstructor;

/**
 * The signed URL must match the Square console exactly, so it comes from webhook.ingress-base-url,
 * not Host and X-Forwarded-Proto, which break behind a proxy.
 */
@Component
@RequiredArgsConstructor
public class SquareProvider implements InboundProvider {

    private static final String HEADER = "x-square-hmacsha256-signature";

    @Value("${webhook.ingress-base-url:}")
    private final String ingressBaseUrl;

    @Override
    public String id() {
        return "SQUARE";
    }

    @Override
    public String displayName() {
        return "Square";
    }

    @Override
    public String signatureHeader() {
        return HEADER;
    }

    @Override
    public String eventId(HttpServletRequest request, String body) {
        return JsonBodies.text(JsonBodies.parse(body), "event_id");
    }

    @Override
    public VerificationResult verify(String secret, byte[] body, HttpServletRequest request) {
        String signature = request.getHeader(HEADER);
        if (signature == null || signature.isBlank()) {
            return VerificationResult.failure("Missing header: " + HEADER);
        }
        if (secret == null || secret.isBlank()) {
            return VerificationResult.failure("No Square signature key configured on this source");
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            // Joined as bytes so nothing re-encodes the body on its way into the digest.
            mac.update(notificationUrl(request).getBytes(StandardCharsets.UTF_8));
            String computed = Base64.getEncoder().encodeToString(mac.doFinal(body != null ? body : new byte[0]));

            boolean valid = MessageDigest.isEqual(
                    computed.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
            return valid ? VerificationResult.success(signature)
                    : VerificationResult.failure("Square signature mismatch");
        } catch (Exception e) {
            return VerificationResult.failure("Square verification error: " + e.getMessage());
        }
    }

    // Query string included.
    private String notificationUrl(HttpServletRequest request) {
        String base = ingressBaseUrl != null && !ingressBaseUrl.isBlank()
                ? stripTrailingSlash(ingressBaseUrl) + request.getRequestURI()
                : request.getRequestURL().toString();
        String query = request.getQueryString();
        return query != null && !query.isBlank() ? base + "?" + query : base;
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
