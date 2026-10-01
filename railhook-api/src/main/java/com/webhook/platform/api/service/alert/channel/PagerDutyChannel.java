package com.webhook.platform.api.service.alert.channel;

import com.webhook.platform.api.domain.entity.AlertEvent;
import com.webhook.platform.api.domain.entity.AlertRule;
import com.webhook.platform.api.domain.entity.Incident;
import com.webhook.platform.api.service.alert.ConfigProperty;
import com.webhook.platform.api.service.alert.ConfigSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@Order(50)
@RequiredArgsConstructor
public class PagerDutyChannel implements AlertChannelProvider {

    private static final String ENQUEUE_URL = "https://events.pagerduty.com/v2/enqueue";

    private static final ConfigSchema SCHEMA = ConfigSchema.of(
            ConfigProperty.text("routingKey", "Routing key")
                    .required()
                    .secret()
                    .describedAs("The Events API v2 integration key of a PagerDuty service"));

    private final AlertHttpClient http;

    @Override
    public String id() {
        return "PAGERDUTY";
    }

    @Override
    public String displayName() {
        return "PagerDuty";
    }

    @Override
    public ConfigSchema configSchema() {
        return SCHEMA;
    }

    @Override
    public boolean pages() {
        return true;
    }

    @Override
    public void fire(AlertRule rule, AlertEvent event, Incident incident, ChannelConfig config) {
        String summary = event.getTitle() + (event.getMessage() != null ? ": " + event.getMessage() : "");
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("rule", rule.getName());
        details.put("alertType", rule.getAlertType());
        details.put("currentValue", event.getCurrentValue());
        details.put("thresholdValue", event.getThresholdValue());
        send(rule, Map.of(
                "routing_key", config.get("routingKey"),
                "event_action", "trigger",
                "dedup_key", IncidentKeys.dedupKey(incident),
                "payload", Map.of(
                        "summary", truncate(summary, 1024),
                        "source", "railhook/project/" + rule.getProjectId(),
                        "severity", switch (event.getSeverity()) {
                            case CRITICAL -> "critical";
                            case WARNING -> "warning";
                            case INFO -> "info";
                        },
                        "custom_details", details)));
    }

    @Override
    public void resolve(AlertRule rule, Incident incident, ChannelConfig config) {
        send(rule, Map.of(
                "routing_key", config.get("routingKey"),
                "event_action", "resolve",
                "dedup_key", IncidentKeys.dedupKey(incident)));
    }

    private void send(AlertRule rule, Map<String, Object> body) {
        http.post(ENQUEUE_URL, body);
        log.info("PagerDuty {} sent for rule '{}'", body.get("event_action"), rule.getName());
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
