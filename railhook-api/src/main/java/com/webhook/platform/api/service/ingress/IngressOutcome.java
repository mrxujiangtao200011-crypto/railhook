package com.webhook.platform.api.service.ingress;

import com.webhook.platform.api.domain.entity.IncomingEvent;

public sealed interface IngressOutcome {

    /** Stored, or deduplicated to the event already stored. */
    record Accepted(IncomingEvent event) implements IngressOutcome {
    }

    /** Nothing is stored, forwarded or charged. */
    record Handshake(String json) implements IngressOutcome {
    }
}
