package com.webhook.platform.api.service.alert.condition;

import com.webhook.platform.api.domain.entity.AlertRule;
import com.webhook.platform.api.domain.repository.DeliveryAttemptRepository;
import com.webhook.platform.api.service.alert.ConfigProperty;
import com.webhook.platform.api.service.alert.ConfigSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@Order(40)
@RequiredArgsConstructor
public class LatencyThresholdCondition implements AlertCondition {

    private static final ConfigSchema SCHEMA = ConfigSchema.of(
            ConfigProperty.number(THRESHOLD, "p95 latency (ms)").required()
                    .describedAs("Fires when the 95th percentile of attempt latency in the window reaches this"),
            ConfigProperty.integer(WINDOW, "Window (minutes)").defaultingTo(5));

    private final DeliveryAttemptRepository attemptRepository;

    @Override
    public String id() {
        return "LATENCY_THRESHOLD";
    }

    @Override
    public String displayName() {
        return "Latency";
    }

    @Override
    public ConfigSchema configSchema() {
        return SCHEMA;
    }

    @Override
    public Optional<Breach> assess(AlertRule rule, Window window) {
        if (rule.getThresholdValue() == null) {
            return Optional.empty();
        }
        double threshold = rule.getThresholdValue();
        // p95, not the mean: the fast majority hides a slow tail in a mean.
        Long p95 = attemptRepository.findLatencyPercentileByProjectId(
                rule.getOrganizationId(), rule.getProjectId(), window.from(), window.to(), 0.95);
        if (p95 == null || p95 < threshold) {
            return Optional.empty();
        }
        return Optional.of(new Breach(p95, String.format(
                "p95 latency %d ms over the last %d minutes, threshold %.0f ms",
                p95, window.minutes(), threshold)));
    }
}
