package com.webhook.platform.api.service.alert.condition;

import com.webhook.platform.api.domain.entity.AlertRule;
import com.webhook.platform.api.domain.repository.DeliveryRepository;
import com.webhook.platform.api.service.alert.ConfigProperty;
import com.webhook.platform.api.service.alert.ConfigSchema;
import com.webhook.platform.common.enums.DeliveryStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@Order(30)
@RequiredArgsConstructor
public class ConsecutiveFailuresCondition implements AlertCondition {

    private static final ConfigSchema SCHEMA = ConfigSchema.of(
            ConfigProperty.integer(THRESHOLD, "Failures in a row").required()
                    .describedAs("Fires when this many of the endpoint's latest deliveries all failed"),
            ConfigProperty.text(ENDPOINT, "Endpoint").required().format(ConfigProperty.ENDPOINT_ID));

    private final DeliveryRepository deliveryRepository;

    @Override
    public String id() {
        return "CONSECUTIVE_FAILURES";
    }

    @Override
    public String displayName() {
        return "Consecutive failures";
    }

    @Override
    public ConfigSchema configSchema() {
        return SCHEMA;
    }

    @Override
    public Optional<Breach> assess(AlertRule rule, Window window) {
        if (rule.getThresholdValue() == null || rule.getEndpointId() == null) {
            return Optional.empty();
        }
        int needed = (int) Math.ceil(rule.getThresholdValue());
        if (needed <= 0) {
            return Optional.empty();
        }
        List<DeliveryStatus> recent = deliveryRepository.findRecentOutcomesByEndpointId(
                rule.getEndpointId(), PageRequest.of(0, needed));
        // A new endpoint whose first delivery failed is not "3 consecutive failures".
        if (recent.size() < needed || recent.stream().anyMatch(s -> s == DeliveryStatus.SUCCESS)) {
            return Optional.empty();
        }
        return Optional.of(new Breach(recent.size(), String.format(
                "The last %d deliveries to this endpoint all failed, threshold %d",
                recent.size(), needed)));
    }
}
