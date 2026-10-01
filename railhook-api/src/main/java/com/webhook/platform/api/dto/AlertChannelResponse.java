package com.webhook.platform.api.dto;

import com.webhook.platform.api.service.alert.ConfigSchema;
import io.swagger.v3.oas.annotations.media.Schema;

public record AlertChannelResponse(
        String id,
        String displayName,
        @Schema(description = "Wakes a person, so an INFO rule cannot use it") boolean pages,
        ConfigSchema configSchema) {
}
