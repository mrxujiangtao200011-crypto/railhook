package com.webhook.platform.api.service.alert.channel;

import java.util.Map;

// Holds decrypted secrets: never log it or return it.
public record ChannelConfig(Map<String, String> values) {

    public String get(String name) {
        return values.get(name);
    }
}
