package com.webhook.platform.api.service.alert;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Schema(description = "A JSON Schema object describing the settings a form asks for")
public record ConfigSchema(String type, Map<String, ConfigProperty> properties, List<String> required) {

    public static ConfigSchema of(ConfigProperty... fields) {
        Map<String, ConfigProperty> properties = new LinkedHashMap<>();
        for (ConfigProperty field : fields) {
            if (properties.putIfAbsent(field.name(), field) != null) {
                throw new IllegalStateException("Property " + field.name() + " is declared twice");
            }
        }
        List<String> required = Arrays.stream(fields).filter(ConfigProperty::mandatory).map(ConfigProperty::name).toList();
        return new ConfigSchema("object", properties, required);
    }

    @JsonIgnore
    public Collection<ConfigProperty> fields() {
        return properties.values();
    }

    public Optional<ConfigProperty> field(String name) {
        return Optional.ofNullable(properties.get(name));
    }
}
