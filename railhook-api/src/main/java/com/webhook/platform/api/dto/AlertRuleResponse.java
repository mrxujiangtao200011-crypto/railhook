package com.webhook.platform.api.dto;

import com.webhook.platform.api.domain.enums.AlertSeverity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AlertRuleResponse {
    private UUID id;
    private UUID projectId;
    private String name;
    private String description;
    private String alertType;
    private AlertSeverity severity;
    private String channel;
    @Schema(description = "The channel's settings, without its secrets")
    private Map<String, String> channelConfig;
    @Schema(description = "Names of the secret settings that are stored; their values are never returned")
    private List<String> configuredSecrets;
    private Double thresholdValue;
    private Integer windowMinutes;
    private UUID endpointId;
    private Boolean enabled;
    private Boolean muted;
    private Instant snoozedUntil;
    private Instant createdAt;
    private Instant updatedAt;
}
