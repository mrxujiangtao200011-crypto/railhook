package com.webhook.platform.api.service.alert.channel;

import com.webhook.platform.api.domain.entity.AlertEvent;
import com.webhook.platform.api.domain.entity.AlertRule;
import com.webhook.platform.api.domain.entity.Incident;
import com.webhook.platform.api.service.alert.ConfigSchema;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(10)
public class InAppChannel implements AlertChannelProvider {

    private static final ConfigSchema SCHEMA = ConfigSchema.of();

    @Override
    public String id() {
        return AlertChannelRegistry.IN_APP;
    }

    @Override
    public String displayName() {
        return "In-app";
    }

    @Override
    public ConfigSchema configSchema() {
        return SCHEMA;
    }

    @Override
    public boolean external() {
        return false;
    }

    @Override
    public void fire(AlertRule rule, AlertEvent event, Incident incident, ChannelConfig config) {
    }
}
