package com.webhook.platform.api.service.alert.channel;

import com.webhook.platform.api.domain.entity.AlertEvent;
import com.webhook.platform.api.domain.entity.AlertRule;
import com.webhook.platform.api.domain.entity.Incident;
import com.webhook.platform.api.service.alert.ConfigProperty;
import com.webhook.platform.api.service.alert.ConfigSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
@Order(70)
@RequiredArgsConstructor
public class DiscordChannel implements AlertChannelProvider {

    private static final ConfigSchema SCHEMA = ConfigSchema.of(
            ConfigProperty.text("url", "Discord webhook URL")
                    .required()
                    .secret()
                    .format(ConfigProperty.URI)
                    .describedAs("Channel settings → Integrations → Webhooks → Copy Webhook URL"));

    private final AlertHttpClient http;
    @Value("${app.base-url:http://localhost:5173}")
    private final String appBaseUrl;

    @Override
    public String id() {
        return "DISCORD";
    }

    @Override
    public String displayName() {
        return "Discord";
    }

    @Override
    public ConfigSchema configSchema() {
        return SCHEMA;
    }

    @Override
    public void fire(AlertRule rule, AlertEvent event, Incident incident, ChannelConfig config) {
        String valueText = event.getCurrentValue() != null && event.getThresholdValue() != null
                ? String.format(Locale.ROOT, "%.1f / %.1f", event.getCurrentValue(), event.getThresholdValue())
                : "—";
        Map<String, Object> embed = new LinkedHashMap<>();
        embed.put("title", truncate("[" + event.getSeverity() + "] " + rule.getName(), 256));
        embed.put("description", truncate(event.getMessage() != null ? event.getMessage() : "", 4096));
        embed.put("url", stripTrailingSlash(appBaseUrl) + "/admin/projects/" + rule.getProjectId()
                + (incident != null ? "/incidents" : "/alerts"));
        embed.put("color", switch (event.getSeverity()) {
            case CRITICAL -> 0xdc2626;
            case WARNING -> 0xf59e0b;
            case INFO -> 0x3b82f6;
        });
        embed.put("fields", List.of(
                Map.of("name", "Rule", "value", truncate(rule.getName(), 1024), "inline", true),
                Map.of("name", "Value / Threshold", "value", valueText, "inline", true)));
        embed.put("timestamp", event.getCreatedAt().toString());

        http.post(config.get("url"), Map.of("embeds", List.of(embed)));
        log.info("Discord notification sent for rule '{}'", rule.getName());
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
