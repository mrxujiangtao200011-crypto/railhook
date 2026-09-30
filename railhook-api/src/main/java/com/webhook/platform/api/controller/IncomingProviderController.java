package com.webhook.platform.api.controller;

import com.webhook.platform.api.dto.IncomingProviderResponse;
import com.webhook.platform.api.service.ingress.provider.InboundProviderRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/incoming-providers")
@Tag(name = "Incoming Sources", description = "Incoming webhook source configuration")
@SecurityRequirement(name = "bearerAuth")
@SecurityRequirement(name = "apiKey")
@RequiredArgsConstructor
public class IncomingProviderController {

    private final InboundProviderRegistry providers;

    @Operation(operationId = "listIncomingProviders", summary = "List incoming providers",
            description = "Providers whose signatures an incoming source verifies in PROVIDER mode. "
                    + "GENERIC is not listed: it is verified with HMAC_GENERIC.")
    @GetMapping
    public List<IncomingProviderResponse> list() {
        return providers.all().stream()
                .map(p -> new IncomingProviderResponse(p.id(), p.displayName(), p.signatureHeader()))
                .toList();
    }
}
