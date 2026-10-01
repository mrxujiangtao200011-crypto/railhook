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

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
@Order(40)
@RequiredArgsConstructor
public class SlackChannel implements AlertChannelProvider {

    private static final ConfigSchema SCHEMA = ConfigSchema.of(
            ConfigProperty.text("url", "Slack webhook URL")
                    .format(ConfigProperty.URI)
                    .describedAs("An incoming webhook URL from your Slack app"));

    private final AlertHttpClient http;

    @Override
    public String id() {
        return "SLACK";
    }

    @Override
    public String displayName() {
        return "Slack";
    }

    @Override
    public ConfigSchema configSchema() {
        return SCHEMA;
    }

    @Override
    public void fire(AlertRule rule, AlertEvent event, Incident incident, ChannelConfig config) {
        String url = config.get("url");
        if (url == null || url.isBlank()) {
            log.warn("Slack webhook URL is empty for rule '{}'", rule.getName());
            return;
        }

        String color = switch (event.getSeverity()) {
            case CRITICAL -> "#dc2626";
            case WARNING -> "#f59e0b";
            case INFO -> "#3b82f6";
        };

        String valueText = event.getCurrentValue() != null && event.getThresholdValue() != null
                ? String.format(Locale.ROOT, "%.1f / %.1f", event.getCurrentValue(), event.getThresholdValue())
                : "—";

        Map<String, Object> payload = Map.of(
                "attachments", List.of(Map.of(
                        "color", color,
                        "fallback", "[" + event.getSeverity() + "] " + event.getTitle(),
                        "blocks", List.of(
                                Map.of(
                                        "type", "section",
                                        "text", Map.of(
                                                "type", "mrkdwn",
                                                "text", "*" + event.getSeverity() + " Alert: " + event.getTitle() + "*"
                                                        + (event.getMessage() != null ? "\n" + event.getMessage() : "")
                                        )
                                ),
                                Map.of(
                                        "type", "section",
                                        "fields", List.of(
                                                Map.of("type", "mrkdwn", "text", "*Rule:*\n" + rule.getName()),
                                                Map.of("type", "mrkdwn", "text", "*Value / Threshold:*\n" + valueText),
                                                Map.of("type", "mrkdwn", "text", "*Type:*\n" + rule.getAlertType()),
                                                Map.of("type", "mrkdwn", "text", "*Window:*\n" + rule.getWindowMinutes() + " min")
                                        )
                                )
                        )
                ))
        );

        http.post(url, payload);
        log.info("Slack notification sent for rule '{}'", rule.getName());
    }
}
