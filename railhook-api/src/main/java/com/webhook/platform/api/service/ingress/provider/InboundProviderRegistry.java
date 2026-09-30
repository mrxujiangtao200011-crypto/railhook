package com.webhook.platform.api.service.ingress.provider;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Component
public class InboundProviderRegistry {

    public static final String GENERIC = "GENERIC";

    // provider_type is varchar(50).
    private static final Pattern ID = Pattern.compile("[A-Z][A-Z0-9_]{0,49}");

    private final Map<String, InboundProvider> byId = new LinkedHashMap<>();

    public InboundProviderRegistry(List<InboundProvider> providers) {
        providers.stream()
                .sorted(Comparator.comparing(InboundProvider::displayName, String.CASE_INSENSITIVE_ORDER))
                .forEach(this::register);
    }

    private void register(InboundProvider provider) {
        String id = provider.id();
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalStateException("Provider id '" + id + "' of " + provider.getClass().getName()
                    + " must match " + ID.pattern());
        }
        if (GENERIC.equals(id)) {
            throw new IllegalStateException(GENERIC + " means no preset; " + provider.getClass().getName()
                    + " cannot claim it");
        }
        if (provider.displayName() == null || provider.displayName().isBlank()) {
            throw new IllegalStateException("Provider " + id + " has no display name");
        }
        InboundProvider previous = byId.putIfAbsent(id, provider);
        if (previous != null) {
            throw new IllegalStateException("Provider id " + id + " is claimed by both "
                    + previous.getClass().getName() + " and " + provider.getClass().getName());
        }
    }

    public Optional<InboundProvider> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    public boolean isKnown(String id) {
        return GENERIC.equals(id) || byId.containsKey(id);
    }

    public List<InboundProvider> all() {
        return List.copyOf(byId.values());
    }
}
