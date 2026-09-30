package com.webhook.platform.api.service.ingress.provider;

import com.webhook.platform.api.service.verification.WebhookVerifierFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

// Scanned rather than listed, so a new provider is covered by every test that uses this.
public final class TestInboundProviders {

    public static final String INGRESS_BASE_URL = "https://hooks.example.com";

    private static final InboundProviderRegistry REGISTRY = scan();

    private TestInboundProviders() {
    }

    public static InboundProviderRegistry registry() {
        return REGISTRY;
    }

    public static WebhookVerifierFactory verifierFactory() {
        return new WebhookVerifierFactory(REGISTRY);
    }

    private static InboundProviderRegistry scan() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
                    Map.of("webhook.ingress-base-url", INGRESS_BASE_URL)));
            context.scan(InboundProvider.class.getPackageName());
            context.refresh();
            return context.getBean(InboundProviderRegistry.class);
        }
    }
}
