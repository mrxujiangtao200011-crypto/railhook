package com.webhook.platform.api.controller;

import com.webhook.platform.api.dto.AlertChannelResponse;
import com.webhook.platform.api.dto.AlertConditionResponse;
import com.webhook.platform.api.service.alert.channel.AlertChannelRegistry;
import com.webhook.platform.api.service.alert.condition.AlertConditionRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Alerts", description = "Alert rules and fired alert events")
@SecurityRequirement(name = "bearerAuth")
@SecurityRequirement(name = "apiKey")
@RequiredArgsConstructor
public class AlertCatalogController {

    private final AlertChannelRegistry channels;
    private final AlertConditionRegistry conditions;

    @Operation(operationId = "listAlertChannels", summary = "List alert channels",
            description = "Where a rule can send its alerts, and the settings each channel takes as a JSON Schema. "
                    + "A property with writeOnly is a secret: stored encrypted and never returned.")
    @GetMapping("/alert-channels")
    public List<AlertChannelResponse> channels() {
        return channels.all().stream()
                .map(c -> new AlertChannelResponse(c.id(), c.displayName(), c.pages(), c.configSchema()))
                .toList();
    }

    @Operation(operationId = "listAlertConditions", summary = "List alert conditions",
            description = "What a rule can watch, used as its alertType, and which of the rule's thresholdValue, "
                    + "windowMinutes and endpointId each condition reads.")
    @GetMapping("/alert-conditions")
    public List<AlertConditionResponse> conditions() {
        return conditions.all().stream()
                .map(c -> new AlertConditionResponse(c.id(), c.displayName(), c.configSchema()))
                .toList();
    }
}
