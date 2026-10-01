package com.webhook.platform.api.service.alert.channel;

import com.webhook.platform.api.domain.EmailAddresses;
import com.webhook.platform.api.domain.entity.AlertEvent;
import com.webhook.platform.api.domain.entity.AlertRule;
import com.webhook.platform.api.domain.entity.Incident;
import com.webhook.platform.api.domain.repository.MembershipRepository;
import com.webhook.platform.api.exception.DomainException;
import com.webhook.platform.api.exception.ErrorCode;
import com.webhook.platform.api.service.EmailService;
import com.webhook.platform.api.service.alert.ConfigProperty;
import com.webhook.platform.api.service.alert.ConfigSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
public class EmailChannel implements AlertChannelProvider {

    public static final int MAX_RECIPIENTS = 10;

    private static final ConfigSchema SCHEMA = ConfigSchema.of(
            ConfigProperty.text("recipients", "Recipients")
                    .format(ConfigProperty.EMAIL_LIST)
                    .describedAs("Comma-separated addresses of verified members of this organization, at most "
                            + MAX_RECIPIENTS));

    private final EmailService emailService;
    private final MembershipRepository membershipRepository;

    @Override
    public String id() {
        return "EMAIL";
    }

    @Override
    public String displayName() {
        return "Email";
    }

    @Override
    public ConfigSchema configSchema() {
        return SCHEMA;
    }

    // Verified members only, or a rule could mail anyone once a minute.
    @Override
    public Map<String, String> normalize(Map<String, String> config) {
        String recipients = config.get("recipients");
        if (recipients == null || recipients.isBlank()) {
            return config;
        }
        List<String> addresses = EmailAddresses.splitList(recipients).stream().distinct().toList();
        if (addresses.size() > MAX_RECIPIENTS || !addresses.stream().allMatch(EmailAddresses::isPlausible)) {
            throw new DomainException(ErrorCode.INVALID_REQUEST,
                    "Email recipients must be at most " + MAX_RECIPIENTS + " addresses, separated by commas");
        }
        Set<String> members = new HashSet<>(membershipRepository.findVerifiedMemberEmailsIn(addresses));
        List<String> outsiders = addresses.stream().filter(address -> !members.contains(address)).toList();
        if (!outsiders.isEmpty()) {
            throw new DomainException(ErrorCode.INVALID_REQUEST,
                    "Alert emails can only go to members of this organization who have verified their "
                            + "address. Not a verified member: " + String.join(", ", outsiders));
        }
        Map<String, String> normalized = new HashMap<>(config);
        normalized.put("recipients", String.join(",", addresses));
        return normalized;
    }

    @Override
    public void fire(AlertRule rule, AlertEvent event, Incident incident, ChannelConfig config) {
        String recipients = config.get("recipients");
        if (recipients == null || recipients.isBlank()) {
            log.warn("Email recipients empty for rule '{}'", rule.getName());
            return;
        }

        String subject = "[" + event.getSeverity() + "] " + event.getTitle() + " — Railhook Alert";

        String valueText = event.getCurrentValue() != null && event.getThresholdValue() != null
                ? String.format(Locale.ROOT, "%.1f / %.1f", event.getCurrentValue(), event.getThresholdValue())
                : "N/A";

        String html = """
            <div style="font-family: sans-serif; max-width: 520px; margin: 0 auto; padding: 32px;">
                <h2 style="color: #111;">%s Alert: %s</h2>
                <p style="color: #555; line-height: 1.5;">%s</p>
                <table style="width: 100%%; border-collapse: collapse; margin: 16px 0;">
                    <tr><td style="padding: 8px; border-bottom: 1px solid #eee; color: #888;">Rule</td>
                        <td style="padding: 8px; border-bottom: 1px solid #eee; font-weight: 600;">%s</td></tr>
                    <tr><td style="padding: 8px; border-bottom: 1px solid #eee; color: #888;">Type</td>
                        <td style="padding: 8px; border-bottom: 1px solid #eee;">%s</td></tr>
                    <tr><td style="padding: 8px; border-bottom: 1px solid #eee; color: #888;">Value / Threshold</td>
                        <td style="padding: 8px; border-bottom: 1px solid #eee; font-family: monospace;">%s</td></tr>
                    <tr><td style="padding: 8px; color: #888;">Window</td>
                        <td style="padding: 8px;">%d min</td></tr>
                </table>
                <p style="color: #999; font-size: 12px; margin-top: 24px;">
                    This alert was generated by Railhook. Log in to your dashboard to investigate.
                </p>
            </div>
            """.formatted(
                event.getSeverity(),
                escapeHtml(event.getTitle()),
                event.getMessage() != null ? escapeHtml(event.getMessage()) : "",
                escapeHtml(rule.getName()),
                rule.getAlertType(),
                valueText,
                rule.getWindowMinutes()
        );

        for (String email : recipients.split(",")) {
            String trimmed = email.trim();
            if (!trimmed.isEmpty()) {
                emailService.sendAlertEmail(trimmed, subject, html);
            }
        }

        log.info("Email alert sent to {} for rule '{}'", masked(recipients), rule.getName());
    }

    public static String masked(String recipients) {
        if (recipients == null) {
            return "(none)";
        }
        return Arrays.stream(recipients.split(","))
                .map(String::trim)
                .filter(address -> !address.isEmpty())
                .map(EmailService::maskRecipient)
                .collect(Collectors.joining(", "));
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
