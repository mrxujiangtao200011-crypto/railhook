package com.webhook.platform.api.dto;

import com.webhook.platform.api.service.alert.ConfigSchema;
import io.swagger.v3.oas.annotations.media.Schema;

public record AlertConditionResponse(
        String id,
        String displayName,
        @Schema(description = "Which of a rule's thresholdValue, windowMinutes and endpointId the condition reads")
        ConfigSchema configSchema) {
}
