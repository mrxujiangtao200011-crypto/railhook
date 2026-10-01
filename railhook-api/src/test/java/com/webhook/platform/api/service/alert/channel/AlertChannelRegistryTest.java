package com.webhook.platform.api.service.alert.channel;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AlertChannelRegistryTest {

    @Test
    void refusesToStartWhenTwoChannelsClaimTheSameId() {
        AlertHttpClient http = mock(AlertHttpClient.class);
        DiscordChannel discord = new DiscordChannel(http, "http://localhost");
        WebhookChannel impostor = new WebhookChannel(http) {
            @Override
            public String id() {
                return "DISCORD";
            }
        };

        assertThatThrownBy(() -> new AlertChannelRegistry(List.of(new InAppChannel(), discord, impostor)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DISCORD");
    }
}
