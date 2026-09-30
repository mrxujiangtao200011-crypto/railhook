package com.webhook.platform.api.controller;

import com.webhook.platform.api.dto.IncomingProviderResponse;
import com.webhook.platform.api.service.ingress.provider.TestInboundProviders;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IncomingProviderControllerTest {

    private final IncomingProviderController controller =
            new IncomingProviderController(TestInboundProviders.registry());

    @Test
    void listsEveryInstalledProviderWithTheHeaderItSignsIn() {
        List<IncomingProviderResponse> providers = controller.list();

        assertThat(providers).contains(
                new IncomingProviderResponse("STRIPE", "Stripe", "Stripe-Signature"),
                new IncomingProviderResponse("ADYEN", "Adyen", null));
        assertThat(providers).extracting(IncomingProviderResponse::id)
                .hasSize(TestInboundProviders.registry().all().size())
                .doesNotContain("GENERIC");
    }
}
