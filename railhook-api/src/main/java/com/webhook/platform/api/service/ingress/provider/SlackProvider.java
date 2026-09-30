package com.webhook.platform.api.service.ingress.provider;

import com.webhook.platform.api.service.verification.GenericHmacVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** Slack signs {@code v0:<timestamp>:<body>} with a five-minute tolerance. */
@Component
public class SlackProvider implements InboundProvider {

    private static final String SIGNATURE_HEADER = "X-Slack-Signature";
    private static final String TIMESTAMP_HEADER = "X-Slack-Request-Timestamp";
    private static final String VERSION = "v0";
    private static final long TOLERANCE_SECONDS = 300;

    @Override
    public String id() {
        return "SLACK";
    }

    @Override
    public String displayName() {
        return "Slack";
    }

    @Override
    public String signatureHeader() {
        return SIGNATURE_HEADER;
    }

    // Absent on url_verification challenges.
    @Override
    public String eventId(HttpServletRequest request, String body) {
        return JsonBodies.text(JsonBodies.parse(body), "event_id");
    }

    // Echoed only once verified, so nobody can claim another's URL.
    @Override
    public Optional<String> handshake(String body) {
        JsonNode root = JsonBodies.parse(body);
        if (!"url_verification".equals(JsonBodies.text(root, "type"))) {
            return Optional.empty();
        }
        return Optional.ofNullable(JsonBodies.text(root, "challenge"))
                .map(challenge -> JsonBodies.toJson(Map.of("challenge", challenge)));
    }

    @Override
    public VerificationResult verify(String secret, byte[] body, HttpServletRequest request) {
        String signatureHeader = request.getHeader(SIGNATURE_HEADER);
        String timestampHeader = request.getHeader(TIMESTAMP_HEADER);

        if (signatureHeader == null || signatureHeader.isBlank()) {
            return VerificationResult.failure("Missing header: " + SIGNATURE_HEADER);
        }
        if (timestampHeader == null || timestampHeader.isBlank()) {
            return VerificationResult.failure("Missing header: " + TIMESTAMP_HEADER);
        }

        try {
            long ts = Long.parseLong(timestampHeader);
            long now = Instant.now().getEpochSecond();
            if (Math.abs(now - ts) > TOLERANCE_SECONDS) {
                return VerificationResult.failure("Slack timestamp outside tolerance window (" + TOLERANCE_SECONDS + "s)");
            }
        } catch (NumberFormatException e) {
            return VerificationResult.failure("Invalid Slack timestamp: " + timestampHeader);
        }

        String prefix = VERSION + "=";
        if (!signatureHeader.startsWith(prefix)) {
            return VerificationResult.failure("Invalid Slack signature format: missing v0= prefix");
        }
        String signature = signatureHeader.substring(prefix.length());

        // Joined as bytes, so the body is not re-encoded.
        String computed = GenericHmacVerifier.computeHmacSha256(
                secret, VERSION + ":" + timestampHeader + ":", body);

        boolean valid = MessageDigest.isEqual(
                computed.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
        return valid ? VerificationResult.success(signatureHeader + "|" + timestampHeader) : VerificationResult.failure("Slack signature mismatch");
    }
}
