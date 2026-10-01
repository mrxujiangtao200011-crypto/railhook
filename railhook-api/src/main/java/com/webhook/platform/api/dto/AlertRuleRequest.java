package com.webhook.platform.api.dto;

import com.webhook.platform.api.domain.enums.AlertSeverity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AlertRuleRequest {

    @NotBlank
    private String name;

    private String description;

    @NotBlank
    @Schema(description = "The id of a condition listed by GET /api/v1/alert-conditions")
    private String alertType;

    private AlertSeverity severity;

    @Schema(description = "The id of a channel listed by GET /api/v1/alert-channels; IN_APP when left out")
    private String channel;

    @Schema(description = "Settings named by the channel's configSchema. On update a setting left out is kept; "
            + "a blank one clears it, except a secret, which is kept so an edit need not re-enter it.")
    private Map<String, String> channelConfig;

    @Positive
    @Schema(description = "Required when the condition's configSchema lists it")
    private Double thresholdValue;

    @Positive
    private Integer windowMinutes;

    private UUID endpointId;

    private Boolean enabled;

    private Boolean muted;

    private Instant snoozedUntil;
}
