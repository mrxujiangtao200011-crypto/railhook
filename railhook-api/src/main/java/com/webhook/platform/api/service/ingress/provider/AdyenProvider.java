package com.webhook.platform.api.service.ingress.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * The hex key is decoded to bytes before use. Standard webhooks sign per item, and every item
 * must verify so a forged one cannot ride along; other webhooks sign the raw body in a header.
 */
@Component
public class AdyenProvider implements InboundProvider {

    private static final String HEADER = "hmacsignature";
    private static final String SIGNATURE_FIELD = "hmacSignature";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String id() {
        return "ADYEN";
    }

    @Override
    public String displayName() {
        return "Adyen";
    }

    @Override
    public String signatureHeader() {
        return null;
    }

    // Adyen has no event id; pspReference plus eventCode identify one. Single-item notifications
    // only, or the other items of a later request would be dropped.
    @Override
    public String eventId(HttpServletRequest request, String body) {
        JsonNode root = JsonBodies.parse(body);
        JsonNode items = root == null ? null : root.get("notificationItems");
        if (items == null || !items.isArray() || items.size() != 1) {
            return null;
        }
        JsonNode item = items.get(0).get("NotificationRequestItem");
        String pspReference = JsonBodies.text(item, "pspReference");
        String eventCode = JsonBodies.text(item, "eventCode");
        return pspReference != null && eventCode != null ? pspReference + ":" + eventCode : null;
    }

    @Override
    public VerificationResult verify(String secret, byte[] body, HttpServletRequest request) {
        if (secret == null || secret.isBlank()) {
            return VerificationResult.failure("No Adyen HMAC key configured on this source");
        }

        byte[] key;
        try {
            key = HexFormat.of().parseHex(secret.trim());
        } catch (IllegalArgumentException e) {
            return VerificationResult.failure(
                    "Adyen HMAC key is not hexadecimal — paste the key exactly as the Customer Area shows it");
        }

        String headerSignature = request.getHeader(HEADER);
        if (headerSignature != null && !headerSignature.isBlank()) {
            String computed = hmacSha256Base64(key, body != null ? body : new byte[0]);
            boolean valid = MessageDigest.isEqual(
                    computed.getBytes(StandardCharsets.UTF_8),
                    headerSignature.getBytes(StandardCharsets.UTF_8));
            return valid ? VerificationResult.success(headerSignature)
                    : VerificationResult.failure("Adyen signature mismatch");
        }

        return verifyNotificationItems(key, body);
    }

    private VerificationResult verifyNotificationItems(byte[] key, byte[] body) {
        List<JsonNode> items;
        try {
            JsonNode root = MAPPER.readTree(body != null ? body : new byte[0]);
            JsonNode notificationItems = root.get("notificationItems");
            if (notificationItems == null || !notificationItems.isArray() || notificationItems.isEmpty()) {
                return VerificationResult.failure(
                        "Adyen webhook carries neither an " + HEADER + " header nor any notificationItems");
            }
            items = new ArrayList<>();
            for (JsonNode wrapper : notificationItems) {
                JsonNode item = wrapper.get("NotificationRequestItem");
                items.add(item != null ? item : wrapper);
            }
        } catch (Exception e) {
            return VerificationResult.failure("Adyen webhook body is not JSON: " + e.getMessage());
        }

        // No early exit, so the time taken does not reveal which item failed.
        boolean valid = true;
        List<String> signatures = new ArrayList<>(items.size());
        for (JsonNode item : items) {
            JsonNode additionalData = item.get("additionalData");
            JsonNode signatureNode = additionalData != null ? additionalData.get(SIGNATURE_FIELD) : null;
            if (signatureNode == null || !signatureNode.isTextual() || signatureNode.asText().isBlank()) {
                return VerificationResult.failure(
                        "Adyen notification item has no additionalData." + SIGNATURE_FIELD);
            }
            String signature = signatureNode.asText();
            signatures.add(signature);
            String computed = hmacSha256Base64(key, dataToSign(item).getBytes(StandardCharsets.UTF_8));
            valid &= MessageDigest.isEqual(
                    computed.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        }

        return valid ? VerificationResult.success(String.join(",", signatures))
                : VerificationResult.failure("Adyen signature mismatch");
    }

    // Nothing is escaped: Adyen computes signatures over unescaped values.
    static String dataToSign(JsonNode item) {
        JsonNode amount = item.get("amount");
        return String.join(":",
                text(item, "pspReference"),
                text(item, "originalReference"),
                text(item, "merchantAccountCode"),
                text(item, "merchantReference"),
                amount != null ? text(amount, "value") : "",
                amount != null ? text(amount, "currency") : "",
                text(item, "eventCode"),
                text(item, "success"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    private static String hmacSha256Base64(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(data));
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute Adyen HMAC-SHA256", e);
        }
    }
}
