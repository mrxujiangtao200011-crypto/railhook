package com.webhook.platform.api.service.verification;

import com.webhook.platform.api.domain.entity.IncomingSource;
import com.webhook.platform.api.service.ingress.provider.InboundProvider;
import com.webhook.platform.api.service.ingress.provider.InboundProviderRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class WebhookVerifierFactory {

    private final InboundProviderRegistry providers;

    /** Null when verification is disabled. */
    public WebhookVerificationStrategy getVerifier(IncomingSource source) {
        return switch (source.getVerificationMode()) {
            case NONE -> null;
            case HMAC_GENERIC -> new GenericHmacVerifier(source.getHmacHeaderName(), source.getHmacSignaturePrefix());
            // A source can outlive its provider's class, so this fails the request rather than ingress.
            case PROVIDER -> providers.find(source.getProviderType())
                    .<WebhookVerificationStrategy>map(provider -> provider)
                    .orElseGet(() -> (secret, body, request) -> WebhookVerificationStrategy.VerificationResult
                            .failure("No verifier is installed for provider " + source.getProviderType()));
        };
    }

    public Optional<InboundProvider> provider(String providerType) {
        return providers.find(providerType);
    }

    public boolean isKnownProvider(String providerType) {
        return providers.isKnown(providerType);
    }

    // GENERIC answers no: saving it in PROVIDER mode used to throw at ingress once traffic arrived.
    public boolean supportsProviderVerification(String providerType) {
        return providers.find(providerType).isPresent();
    }
}
