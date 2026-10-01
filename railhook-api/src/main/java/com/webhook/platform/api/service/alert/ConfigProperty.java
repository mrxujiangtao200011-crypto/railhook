package com.webhook.platform.api.service.alert;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "One property of a JSON Schema object. writeOnly marks a secret: it is stored "
        + "encrypted and never returned.")
public record ConfigProperty(
        @JsonIgnore String name,
        @JsonIgnore boolean mandatory,
        String type,
        String title,
        String description,
        String format,
        @JsonProperty("enum") List<String> options,
        @JsonProperty("default") @Schema(oneOf = {String.class, Double.class}) Object defaultValue,
        Boolean writeOnly) {

    public static final String URI = "uri";
    public static final String EMAIL_LIST = "email-list";
    public static final String ENDPOINT_ID = "endpoint-id";

    public static ConfigProperty text(String name, String title) {
        return new ConfigProperty(name, false, "string", title, null, null, null, null, null);
    }

    public static ConfigProperty number(String name, String title) {
        return new ConfigProperty(name, false, "number", title, null, null, null, null, null);
    }

    public static ConfigProperty integer(String name, String title) {
        return new ConfigProperty(name, false, "integer", title, null, null, null, null, null);
    }

    public ConfigProperty required() {
        return new ConfigProperty(name, true, type, title, description, format, options, defaultValue, writeOnly);
    }

    public ConfigProperty secret() {
        return new ConfigProperty(name, mandatory, type, title, description, format, options, defaultValue, true);
    }

    public ConfigProperty describedAs(String text) {
        return new ConfigProperty(name, mandatory, type, title, text, format, options, defaultValue, writeOnly);
    }

    public ConfigProperty format(String value) {
        return new ConfigProperty(name, mandatory, type, title, description, value, options, defaultValue, writeOnly);
    }

    public ConfigProperty oneOf(List<String> values, String defaultOption) {
        return new ConfigProperty(name, mandatory, type, title, description, format, values, defaultOption, writeOnly);
    }

    public ConfigProperty defaultingTo(Object value) {
        return new ConfigProperty(name, mandatory, type, title, description, format, options, value, writeOnly);
    }

    @JsonIgnore
    public boolean isSecret() {
        return Boolean.TRUE.equals(writeOnly);
    }
}
