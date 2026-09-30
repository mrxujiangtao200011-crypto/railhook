package com.webhook.platform.api.service.ingress.provider;

import com.webhook.platform.api.service.verification.WebhookVerificationStrategy;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Optional;

public interface InboundProvider extends WebhookVerificationStrategy {

    /** Stored on the source as {@code provider_type}; renaming it orphans every source that has it. */
    String id();

    String displayName();

    /** Null when the signature is not in a header. */
    String signatureHeader();

    // A header the provider keeps across its own retries, never one a proxy sets.
    default String eventId(HttpServletRequest request, String body) {
        return null;
    }

    default Optional<String> handshake(String body) {
        return Optional.empty();
    }
}
