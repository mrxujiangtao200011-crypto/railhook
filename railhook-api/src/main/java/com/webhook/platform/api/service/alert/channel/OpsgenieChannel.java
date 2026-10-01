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
import java.util.Map;

@Slf4j
@Component
@Order(60)
@RequiredArgsConstructor
public class OpsgenieChannel implements AlertChannelProvider {

    private static final ConfigSchema SCHEMA = ConfigSchema.of(
            ConfigProperty.text("apiKey", "API key")
                    .required()
                    .secret()
                    .describedAs("An API integration key from Opsgenie"),
            ConfigProperty.text("region", "Region")
                    .oneOf(List.of("US", "EU"), "US")
                    .describedAs("EU for accounts on app.eu.opsgenie.com"));

    private final AlertHttpClient http;

    @Override
    public String id() {
        return "OPSGENIE";
    }

    @Override
    public String displayName() {
        return "Opsgenie";
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
        send(rule, config, "", Map.of(
                "message", truncate(event.getTitle(), 130),
                "alias", IncidentKeys.dedupKey(incident),
                "description", event.getMessage() != null ? event.getMessage() : "",
                "source", "Railhook",
                "priority", switch (event.getSeverity()) {
                    case CRITICAL -> "P1";
                    case WARNING -> "P3";
                    case INFO -> "P5";
                }));
    }

    @Override
    public void resolve(AlertRule rule, Incident incident, ChannelConfig config) {
        send(rule, config, "/" + IncidentKeys.dedupKey(incident) + "/close?identifierType=alias",
                Map.of("source", "Railhook", "note", "The alert condition no longer holds"));
    }

    private void send(AlertRule rule, ChannelConfig config, String path, Map<String, Object> body) {
        String host = "EU".equals(config.get("region")) ? "api.eu.opsgenie.com" : "api.opsgenie.com";
        http.post("https://" + host + "/v2/alerts" + path, "GenieKey " + config.get("apiKey"), body);
        log.info("Opsgenie notification sent for rule '{}'", rule.getName());
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
