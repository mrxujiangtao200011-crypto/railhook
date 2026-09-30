package com.webhook.platform.api.service.ingress.provider;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InboundProviderRegistryTest {

    @Test
    void findsARegisteredProviderById() {
        InboundProvider acme = provider("ACME");
        var registry = new InboundProviderRegistry(List.of(acme, provider("OTHER")));

        assertThat(registry.find("ACME")).containsSame(acme);
    }

    @Test
    void anUnknownIdGenericAndNullFindNothing() {
        var registry = new InboundProviderRegistry(List.of(provider("ACME")));

        assertThat(registry.find("NOPE")).isEmpty();
        assertThat(registry.find(InboundProviderRegistry.GENERIC)).isEmpty();
        assertThat(registry.find(null)).isEmpty();
    }

    @Test
    void allIsSortedByDisplayName() {
        var registry = new InboundProviderRegistry(List.of(provider("ZED"), provider("ACME")));

        assertThat(registry.all()).extracting(InboundProvider::id).containsExactly("ACME", "ZED");
    }

    @Test
    void genericAndRegisteredIdsAreKnown() {
        var registry = new InboundProviderRegistry(List.of(provider("ACME")));

        assertThat(registry.isKnown("ACME")).isTrue();
        assertThat(registry.isKnown(InboundProviderRegistry.GENERIC)).isTrue();
        assertThat(registry.isKnown("NOPE")).isFalse();
    }

    @Test
    void twoProvidersWithOneIdFailStartup() {
        assertThatThrownBy(() -> new InboundProviderRegistry(List.of(provider("ACME"), provider("ACME"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACME");
    }

    @Test
    void aProviderCannotClaimGeneric() {
        assertThatThrownBy(() -> new InboundProviderRegistry(List.of(provider("GENERIC"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GENERIC");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "acme", "1ACME", "AC-ME", "A23456789012345678901234567890123456789012345678901"})
    void anIdTheColumnCannotHoldFailsStartup(String id) {
        assertThatThrownBy(() -> new InboundProviderRegistry(List.of(provider(id))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aBlankDisplayNameFailsStartup() {
        InboundProvider nameless = new TestProvider("ACME", " ");

        assertThatThrownBy(() -> new InboundProviderRegistry(List.of(nameless)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACME");
    }

    private static InboundProvider provider(String id) {
        return new TestProvider(id, id.isEmpty() ? "x" : id.charAt(0) + id.substring(1).toLowerCase());
    }

    private record TestProvider(String id, String displayName) implements InboundProvider {
        @Override
        public String signatureHeader() {
            return "X-Test-Signature";
        }

        @Override
        public VerificationResult verify(String secret, byte[] body, HttpServletRequest request) {
            return VerificationResult.success();
        }
    }
}
