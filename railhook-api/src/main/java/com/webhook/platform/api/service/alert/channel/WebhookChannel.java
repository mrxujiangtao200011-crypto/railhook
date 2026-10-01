package com.webhook.platform.api.service.alert.channel;

import com.webhook.platform.api.domain.entity.AlertEvent;
import com.webhook.platform.api.domain.entity.AlertRule;
import com.webhook.platform.api.domain.entity.Incident;
import com.webhook.platform.api.service.alert.ConfigProperty;
import com.webhook.platform.api.service.alert.ConfigSchema;
import com.webhook.platform.common.security.UrlValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@Order(30)
@RequiredArgsConstructor
public class WebhookChannel implements AlertChannelProvider {

    private static final ConfigSchema SCHEMA = ConfigSchema.of(
            ConfigProperty.text("url", "Webhook URL")
                    .format(ConfigProperty.URI)
                    .describedAs("Receives an alert.fired JSON body for every firing"));

    private final AlertHttpClient http;

    @Override
    public String id() {
        return "WEBHOOK";
    }

    @Override
    public String displayName() {
        return "Webhook";
    }

    @Override
    public ConfigSchema configSchema() {
        return SCHEMA;
    }

    @Override
    public void fire(AlertRule rule, AlertEvent event, Incident incident, ChannelConfig config) {
        String url = config.get("url");
        if (url == null || url.isBlank()) {
            log.warn("Webhook URL is empty for rule '{}'", rule.getName());
            return;
        }

        Map<String, Object> payload = Map.of(
                "event", "alert.fired",
                "rule", Map.of(
                        "id", rule.getId().toString(),
                        "name", rule.getName(),
                        "alertType", rule.getAlertType(),
                        "severity", rule.getSeverity().name()
                ),
                "alert", Map.of(
                        "id", event.getId().toString(),
                        "title", event.getTitle(),
                        "message", event.getMessage() != null ? event.getMessage() : "",
                        "currentValue", event.getCurrentValue() != null ? event.getCurrentValue() : 0,
                        "thresholdValue", event.getThresholdValue() != null ? event.getThresholdValue() : 0,
                        "severity", event.getSeverity().name(),
                        "createdAt", event.getCreatedAt().toString()
                ),
                "projectId", rule.getProjectId().toString()
        );

        http.post(url, payload);
        log.info("Webhook notification sent to {} for rule '{}'", UrlValidator.hostOf(url), rule.getName());
    }
}
