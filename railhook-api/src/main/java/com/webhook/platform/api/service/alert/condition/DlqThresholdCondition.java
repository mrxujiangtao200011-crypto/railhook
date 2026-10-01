package com.webhook.platform.api.service.alert.condition;

import com.webhook.platform.api.domain.entity.AlertRule;
import com.webhook.platform.api.domain.repository.DeliveryRepository;
import com.webhook.platform.api.service.alert.ConfigProperty;
import com.webhook.platform.api.service.alert.ConfigSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@Order(20)
@RequiredArgsConstructor
public class DlqThresholdCondition implements AlertCondition {

    private static final ConfigSchema SCHEMA = ConfigSchema.of(
            ConfigProperty.number(THRESHOLD, "Deliveries in the DLQ").required()
                    .describedAs("Fires when this many deliveries reached the DLQ within the window"),
            ConfigProperty.integer(WINDOW, "Window (minutes)").defaultingTo(5));

    private final DeliveryRepository deliveryRepository;

    @Override
    public String id() {
        return "DLQ_THRESHOLD";
    }

    @Override
    public String displayName() {
        return "DLQ threshold";
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
        long parked = deliveryRepository.countDlqByProjectIdSince(rule.getProjectId(), window.from());
        if (parked < threshold) {
            return Optional.empty();
        }
        return Optional.of(new Breach(parked, String.format(
                "%d deliveries reached the DLQ in the last %d minutes, threshold %.0f",
                parked, window.minutes(), threshold)));
    }
}
