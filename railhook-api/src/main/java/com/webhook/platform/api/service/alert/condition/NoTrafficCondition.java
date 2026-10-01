package com.webhook.platform.api.service.alert.condition;

import com.webhook.platform.api.domain.entity.AlertRule;
import com.webhook.platform.api.domain.repository.EventRepository;
import com.webhook.platform.api.service.alert.ConfigProperty;
import com.webhook.platform.api.service.alert.ConfigSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@Order(50)
@RequiredArgsConstructor
public class NoTrafficCondition implements AlertCondition {

    private static final ConfigSchema SCHEMA = ConfigSchema.of(
            ConfigProperty.integer(WINDOW, "Silence (minutes)").required().defaultingTo(60)
                    .describedAs("Fires when the project receives no events for this long"));

    private final EventRepository eventRepository;

    @Override
    public String id() {
        return "NO_TRAFFIC";
    }

    @Override
    public String displayName() {
        return "No traffic";
    }

    @Override
    public ConfigSchema configSchema() {
        return SCHEMA;
    }

    @Override
    public Optional<Breach> assess(AlertRule rule, Window window) {
        if (eventRepository.countByProjectIdAndCreatedAtBetween(rule.getProjectId(), window.from(), window.to()) > 0) {
            return Optional.empty();
        }
        return Optional.of(new Breach(0, String.format(
                "No events in the last %d minutes", window.minutes())));
    }
}
