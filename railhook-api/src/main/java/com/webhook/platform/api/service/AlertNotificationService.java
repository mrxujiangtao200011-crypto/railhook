package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.AlertEvent;
import com.webhook.platform.api.domain.entity.AlertRule;
import com.webhook.platform.api.domain.entity.Incident;
import com.webhook.platform.api.service.alert.ConfigProperty;
import com.webhook.platform.api.service.alert.channel.AlertChannelConfigs;
import com.webhook.platform.api.service.alert.channel.AlertChannelProvider;
import com.webhook.platform.api.service.alert.channel.AlertChannelRegistry;
import com.webhook.platform.api.service.alert.channel.EmailChannel;
import com.webhook.platform.common.security.UrlValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlertNotificationService {

    private final AlertChannelRegistry channels;
    private final AlertChannelConfigs channelConfigs;
    @Value("${app.alerts.notifications-enabled:false}")
    private final boolean enabled;

    @Async
    public void dispatch(AlertRule rule, AlertEvent event, Incident incident) {
        if (rule.getMuted()) {
            log.debug("Skipping notification for muted rule '{}'", rule.getName());
            return;
        }
        if (rule.getSnoozedUntil() != null && rule.getSnoozedUntil().isAfter(event.getCreatedAt())) {
            log.debug("Skipping notification for snoozed rule '{}'", rule.getName());
            return;
        }

        Optional<AlertChannelProvider> found = channels.find(rule.getChannel());
        if (found.isEmpty()) {
            log.warn("Unknown alert channel: {}", rule.getChannel());
            return;
        }
        AlertChannelProvider channel = found.get();
        if (!channel.external()) {
            return;
        }

        if (!enabled) {
            log.info("========== ALERT NOTIFICATION (dry-run) ==========");
            log.info("Channel: {}", channel.id());
            log.info("Rule: {} ({})", rule.getName(), rule.getAlertType());
            log.info("Severity: {}", event.getSeverity());
            log.info("Title: {}", event.getTitle());
            log.info("Message: {}", event.getMessage());
            log.info("Value: {} / Threshold: {}", event.getCurrentValue(), event.getThresholdValue());
            logTargets(rule, channel);
            log.info("=================================================");
            return;
        }

        try {
            channel.fire(rule, event, incident, channelConfigs.read(rule));
        } catch (Exception e) {
            log.error("Failed to send {} notification for rule '{}': {}",
                    channel.id(), rule.getName(), failureOf(e));
        }
    }

    @Async
    public void dispatchResolved(AlertRule rule, Incident incident) {
        Optional<AlertChannelProvider> found = channels.find(rule.getChannel()).filter(AlertChannelProvider::pages);
        if (found.isEmpty()) {
            return;
        }
        AlertChannelProvider channel = found.get();
        if (!enabled) {
            log.info("Alert notification (dry-run): {} resolve for rule '{}'", channel.id(), rule.getName());
            return;
        }
        try {
            channel.resolve(rule, incident, channelConfigs.read(rule));
        } catch (Exception e) {
            log.error("Failed to send {} resolve for rule '{}': {}", channel.id(), rule.getName(), failureOf(e));
        }
    }

    private static void logTargets(AlertRule rule, AlertChannelProvider channel) {
        for (ConfigProperty field : channel.configSchema().fields()) {
            if (field.isSecret()) {
                continue;
            }
            String value = rule.getChannelConfig().get(field.name());
            if (ConfigProperty.URI.equals(field.format())) {
                log.info("Host: {}", UrlValidator.hostOf(value));
            } else if (ConfigProperty.EMAIL_LIST.equals(field.format())) {
                log.info("Recipients: {}", EmailChannel.masked(value));
            }
        }
    }

    // A response exception's message carries the full URL, and a Slack webhook URL is its own credential.
    private static String failureOf(Exception e) {
        return e instanceof WebClientResponseException response
                ? "HTTP " + response.getStatusCode().value()
                : e.getMessage();
    }
}
