package com.webhook.platform.api.service.alert.condition;

import com.webhook.platform.api.domain.entity.AlertRule;
import com.webhook.platform.api.service.alert.ConfigSchema;

import java.time.Instant;
import java.util.Optional;

public interface AlertCondition {

    String THRESHOLD = "thresholdValue";
    String WINDOW = "windowMinutes";
    String ENDPOINT = "endpointId";

    /** Stored on the rule as {@code alert_type}; renaming it orphans every rule that has it. */
    String id();

    String displayName();

    ConfigSchema configSchema();

    /** Runs inside the rule's organization. Empty means the condition does not hold. */
    Optional<Breach> assess(AlertRule rule, Window window);

    record Window(Instant from, Instant to, int minutes) {
    }

    record Breach(double currentValue, String message) {
    }
}
