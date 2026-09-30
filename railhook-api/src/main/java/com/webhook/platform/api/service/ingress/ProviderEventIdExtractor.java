package com.webhook.platform.api.service.ingress;

import com.webhook.platform.api.service.ingress.provider.InboundProvider;
import jakarta.servlet.http.HttpServletRequest;

public final class ProviderEventIdExtractor {

    private static final String GENERIC_EVENT_ID_HEADER = "X-Webhook-Id";
    private static final int MAX_LENGTH = 255;

    private ProviderEventIdExtractor() {
    }

    /** Null means no dedup. A null provider is a GENERIC source. */
    public static String extract(InboundProvider provider, HttpServletRequest request, String body) {
        String id = provider != null
                ? provider.eventId(request, body)
                : request.getHeader(GENERIC_EVENT_ID_HEADER);
        if (id == null || id.isBlank()) {
            return null;
        }
        String trimmed = id.trim();
        return trimmed.length() <= MAX_LENGTH ? trimmed : trimmed.substring(0, MAX_LENGTH);
    }
}
