package com.webhook.platform.api.service.alert.channel;

import com.webhook.platform.api.domain.entity.Incident;

final class IncidentKeys {

    private IncidentKeys() {
    }

    // Stable per incident, so the resolve closes exactly the page its trigger opened.
    static String dedupKey(Incident incident) {
        return "railhook-incident-" + incident.getId();
    }
}
