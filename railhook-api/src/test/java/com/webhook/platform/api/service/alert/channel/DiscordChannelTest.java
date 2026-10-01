package com.webhook.platform.api.service.alert.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.AlertEvent;
import com.webhook.platform.api.domain.entity.AlertRule;
import com.webhook.platform.api.domain.entity.Incident;
import com.webhook.platform.api.domain.enums.AlertSeverity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DiscordChannelTest {

    private static final String WEBHOOK = "https://discord.com/api/webhooks/123/abc";

    private final AlertHttpClient http = mock(AlertHttpClient.class);
    private final DiscordChannel channel = new DiscordChannel(http, "https://app.railhook.io/");
    private final UUID projectId = UUID.randomUUID();

    @Test
    void postsOneEmbedNamingTheRuleTheValueAndTheIncident() throws Exception {
        channel.fire(rule(), event(), Incident.builder().id(UUID.randomUUID()).build(),
                new ChannelConfig(Map.of("url", WEBHOOK)));

        JsonNode embed = sentBody().at("/embeds/0");
        assertThat(embed.get("title").asText()).isEqualTo("[CRITICAL] Payments failing");
        assertThat(embed.get("description").asText()).isEqualTo("Failure rate 80.0% over the last 5 minutes");
        assertThat(embed.get("color").asInt()).isEqualTo(0xdc2626);
        assertThat(embed.get("url").asText())
                .isEqualTo("https://app.railhook.io/admin/projects/" + projectId + "/incidents");
        assertThat(embed.get("fields").toString())
                .contains("\"Rule\"", "Payments failing", "\"Value / Threshold\"", "80.0 / 10.0");
    }

    @Test
    void anInfoAlertHasNoIncident_soItLinksToTheAlerts() throws Exception {
        AlertEvent info = event();
        info.setSeverity(AlertSeverity.INFO);

        channel.fire(rule(), info, null, new ChannelConfig(Map.of("url", WEBHOOK)));

        assertThat(sentBody().at("/embeds/0/url").asText())
                .isEqualTo("https://app.railhook.io/admin/projects/" + projectId + "/alerts");
    }

    private JsonNode sentBody() {
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(http).post(eq(WEBHOOK), body.capture());
        return new ObjectMapper().valueToTree(body.getValue());
    }

    private AlertRule rule() {
        return AlertRule.builder()
                .id(UUID.randomUUID())
                .projectId(projectId)
                .name("Payments failing")
                .alertType("FAILURE_RATE")
                .severity(AlertSeverity.CRITICAL)
                .channel("DISCORD")
                .thresholdValue(10.0)
                .build();
    }

    private AlertEvent event() {
        return AlertEvent.builder()
                .id(UUID.randomUUID())
                .severity(AlertSeverity.CRITICAL)
                .title("Payments failing")
                .message("Failure rate 80.0% over the last 5 minutes")
                .currentValue(80.0)
                .thresholdValue(10.0)
                .createdAt(Instant.parse("2026-10-01T12:00:00Z"))
                .build();
    }
}
