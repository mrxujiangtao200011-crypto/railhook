package com.webhook.platform.api.service.alert.channel;

import com.webhook.platform.api.exception.DomainException;
import com.webhook.platform.api.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Component
public class AlertChannelRegistry {

    public static final String IN_APP = "IN_APP";

    // alert_rules.channel is varchar(20).
    private static final Pattern ID = Pattern.compile("[A-Z][A-Z0-9_]{0,19}");

    private final Map<String, AlertChannelProvider> byId = new LinkedHashMap<>();

    public AlertChannelRegistry(List<AlertChannelProvider> channels) {
        channels.forEach(this::register);
        if (!byId.containsKey(IN_APP)) {
            throw new IllegalStateException("No alert channel claims " + IN_APP + ", the channel a rule gets by default");
        }
    }

    private void register(AlertChannelProvider channel) {
        String id = channel.id();
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalStateException("Alert channel id '" + id + "' of " + channel.getClass().getName()
                    + " must match " + ID.pattern());
        }
        if (channel.displayName() == null || channel.displayName().isBlank()) {
            throw new IllegalStateException("Alert channel " + id + " has no display name");
        }
        if (channel.configSchema() == null) {
            throw new IllegalStateException("Alert channel " + id + " has no config schema");
        }
        AlertChannelProvider previous = byId.putIfAbsent(id, channel);
        if (previous != null) {
            throw new IllegalStateException("Alert channel id " + id + " is claimed by both "
                    + previous.getClass().getName() + " and " + channel.getClass().getName());
        }
    }

    public Optional<AlertChannelProvider> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    public AlertChannelProvider require(String id) {
        return find(id).orElseThrow(() -> new DomainException(ErrorCode.INVALID_REQUEST,
                "Unknown alert channel '" + id + "'. Use an id from GET /api/v1/alert-channels."));
    }

    public List<AlertChannelProvider> all() {
        return List.copyOf(byId.values());
    }
}
