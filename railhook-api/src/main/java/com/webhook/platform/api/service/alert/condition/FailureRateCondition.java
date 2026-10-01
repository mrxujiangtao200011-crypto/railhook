package com.webhook.platform.api.service.alert.condition;

import com.webhook.platform.api.domain.entity.AlertRule;
import com.webhook.platform.api.domain.repository.DeliveryRepository;
import com.webhook.platform.api.service.alert.ConfigProperty;
import com.webhook.platform.api.service.alert.ConfigSchema;
import com.webhook.platform.common.enums.DeliveryStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@Order(10)
@RequiredArgsConstructor
public class FailureRateCondition implements AlertCondition {

    private static final ConfigSchema SCHEMA = ConfigSchema.of(
            ConfigProperty.number(THRESHOLD, "Failure rate (%)").required()
                    .describedAs("Fires when this share of the window's deliveries failed"),
            ConfigProperty.integer(WINDOW, "Window (minutes)").defaultingTo(5));

    private final DeliveryRepository deliveryRepository;

    @Override
    public String id() {
        return "FAILURE_RATE";
    }

    @Override
    public String displayName() {
        return "Failure rate";
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
        long total = deliveryRepository.countByProjectIdAndCreatedAtBetween(rule.getProjectId(), window.from(), window.to());
        // No traffic is not a 100% failure rate.
        if (total == 0) {
            return Optional.empty();
        }
        long failed = deliveryRepository.countByProjectIdAndStatusAndCreatedAtBetween(
                rule.getProjectId(), DeliveryStatus.FAILED, window.from(), window.to())
                + deliveryRepository.countByProjectIdAndStatusAndCreatedAtBetween(
                rule.getProjectId(), DeliveryStatus.DLQ, window.from(), window.to());
        double rate = (failed * 100.0) / total;
        if (rate < threshold) {
            return Optional.empty();
        }
        return Optional.of(new Breach(rate, String.format(
                "Failure rate %.1f%% over the last %d minutes (%d of %d deliveries failed), threshold %.1f%%",
                rate, window.minutes(), failed, total, threshold)));
    }
}
