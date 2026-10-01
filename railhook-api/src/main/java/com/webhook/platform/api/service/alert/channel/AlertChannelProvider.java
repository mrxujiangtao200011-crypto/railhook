package com.webhook.platform.api.service.alert.channel;

import com.webhook.platform.api.domain.entity.AlertEvent;
import com.webhook.platform.api.domain.entity.AlertRule;
import com.webhook.platform.api.domain.entity.Incident;
import com.webhook.platform.api.service.alert.ConfigSchema;

import java.util.Map;

public interface AlertChannelProvider {

    /** Stored on the rule as {@code channel}; renaming it orphans every rule that has it. */
    String id();

    String displayName();

    ConfigSchema configSchema();

    // Wakes a person, so an INFO rule may not use it; such a channel also hears when the incident resolves.
    default boolean pages() {
        return false;
    }

    // False only for the alert history itself: nothing leaves Railhook, so there is nothing to dry-run.
    default boolean external() {
        return true;
    }

    /** The incident is null for an INFO rule, which opens none. */
    void fire(AlertRule rule, AlertEvent event, Incident incident, ChannelConfig config);

    default void resolve(AlertRule rule, Incident incident, ChannelConfig config) {
    }

    default Map<String, String> normalize(Map<String, String> config) {
        return config;
    }
}
