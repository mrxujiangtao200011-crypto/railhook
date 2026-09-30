package com.webhook.platform.api.service.ingress.provider;

import com.webhook.platform.api.service.verification.GenericHmacVerifier;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** {@code X-Hub-Signature-256: sha256=<hex hmac-sha256>} over the raw body. */
@Component
public class GitHubProvider implements InboundProvider {

    private static final String HEADER = "X-Hub-Signature-256";
    private static final String PREFIX = "sha256=";

    @Override
    public String id() {
        return "GITHUB";
    }

    @Override
    public String displayName() {
        return "GitHub";
    }

    @Override
    public String signatureHeader() {
        return HEADER;
    }

    @Override
    public String eventId(HttpServletRequest request, String body) {
        return request.getHeader("X-GitHub-Delivery");
    }

    @Override
    public VerificationResult verify(String secret, byte[] body, HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header == null || header.isBlank()) {
            return VerificationResult.failure("Missing header: " + HEADER);
        }

        if (!header.startsWith(PREFIX)) {
            return VerificationResult.failure("Invalid signature format: missing sha256= prefix");
        }

        String signature = header.substring(PREFIX.length());
        String computed = GenericHmacVerifier.computeHmacSha256(secret, body);

        boolean valid = MessageDigest.isEqual(
                computed.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
        return valid ? VerificationResult.success(header) : VerificationResult.failure("GitHub signature mismatch");
    }
}
