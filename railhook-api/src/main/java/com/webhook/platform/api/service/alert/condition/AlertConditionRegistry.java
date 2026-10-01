package com.webhook.platform.api.service.alert.condition;

import com.webhook.platform.api.exception.DomainException;
import com.webhook.platform.api.exception.ErrorCode;
import com.webhook.platform.api.service.alert.ConfigProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class AlertConditionRegistry {

    // alert_rules.alert_type is varchar(50).
    private static final Pattern ID = Pattern.compile("[A-Z][A-Z0-9_]{0,49}");

    private static final Set<String> RULE_FIELDS = Set.of(
            AlertCondition.THRESHOLD, AlertCondition.WINDOW, AlertCondition.ENDPOINT);

    private final Map<String, AlertCondition> byId = new LinkedHashMap<>();

    public AlertConditionRegistry(List<AlertCondition> conditions) {
        conditions.forEach(this::register);
    }

    private void register(AlertCondition condition) {
        String id = condition.id();
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalStateException("Alert condition id '" + id + "' of " + condition.getClass().getName()
                    + " must match " + ID.pattern());
        }
        if (condition.displayName() == null || condition.displayName().isBlank()) {
            throw new IllegalStateException("Alert condition " + id + " has no display name");
        }
        List<String> strangers = condition.configSchema().fields().stream()
                .map(ConfigProperty::name).filter(name -> !RULE_FIELDS.contains(name)).toList();
        if (!strangers.isEmpty()) {
            throw new IllegalStateException("Alert condition " + id + " reads " + strangers
                    + ", which a rule does not have; it has " + RULE_FIELDS);
        }
        AlertCondition previous = byId.putIfAbsent(id, condition);
        if (previous != null) {
            throw new IllegalStateException("Alert condition id " + id + " is claimed by both "
                    + previous.getClass().getName() + " and " + condition.getClass().getName());
        }
    }

    public Optional<AlertCondition> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    public AlertCondition require(String id) {
        return find(id).orElseThrow(() -> new DomainException(ErrorCode.INVALID_REQUEST,
                "Unknown alert type '" + id + "'. Use an id from GET /api/v1/alert-conditions."));
    }

    public List<AlertCondition> all() {
        return List.copyOf(byId.values());
    }
}
