package com.webhook.platform.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A provider whose webhooks Railhook verifies natively")
public record IncomingProviderResponse(
        @Schema(description = "The value to send as an incoming source's providerType", example = "STRIPE",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String id,
        @Schema(example = "Stripe", requiredMode = Schema.RequiredMode.REQUIRED)
        String displayName,
        @Schema(description = "Null when the provider signs inside the body", example = "Stripe-Signature")
        String signatureHeader) {
}
