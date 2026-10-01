package com.webhook.platform.api.service.alert.channel;

import com.webhook.platform.api.domain.entity.AlertRule;
import com.webhook.platform.api.exception.DomainException;
import com.webhook.platform.api.exception.ErrorCode;
import com.webhook.platform.api.service.alert.ConfigProperty;
import com.webhook.platform.api.service.alert.ConfigSchema;
import com.webhook.platform.common.security.EncryptionKeyRegistry;
import com.webhook.platform.common.security.SecretEncryption;
import com.webhook.platform.common.security.UrlValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

@Component
@RequiredArgsConstructor
public class AlertChannelConfigs {

    public static final String CIPHERTEXT = "ciphertext";
    public static final String IV = "iv";

    private final EncryptionKeyRegistry encryptionKeyRegistry;
    @Value("${webhook.url-validation.allow-private-ips:false}")
    private final boolean allowPrivateIps;
    @Value("${webhook.url-validation.allowed-hosts:}")
    private final List<String> allowedHosts;

    // Absent keeps a setting; blank clears a plain one but keeps a secret, so an edit need not re-enter it.
    public void apply(AlertRule rule, AlertChannelProvider channel, Map<String, String> requested, boolean fresh) {
        Map<String, String> given = requested == null ? Map.of() : requested;
        ConfigSchema schema = channel.configSchema();
        List<String> unknown = given.keySet().stream().filter(name -> schema.field(name).isEmpty()).sorted().toList();
        if (!unknown.isEmpty()) {
            throw invalid(channel.displayName() + " has no setting " + String.join(", ", unknown));
        }

        Map<String, String> plain = fresh ? new LinkedHashMap<>() : new LinkedHashMap<>(rule.getChannelConfig());
        Map<String, String> secrets = fresh ? new LinkedHashMap<>() : decryptSecrets(rule);
        boolean secretsChanged = fresh;
        for (ConfigProperty field : schema.fields()) {
            String value = given.get(field.name()) == null ? null : given.get(field.name()).trim();
            if (field.isSecret()) {
                if (value != null && !value.isEmpty()) {
                    check(channel, field, value);
                    secrets.put(field.name(), value);
                    secretsChanged = true;
                }
                continue;
            }
            if (value != null && value.isEmpty()) {
                plain.remove(field.name());
            } else if (value != null) {
                check(channel, field, value);
                plain.put(field.name(), value);
            }
            if (!plain.containsKey(field.name()) && field.defaultValue() != null) {
                plain.put(field.name(), String.valueOf(field.defaultValue()));
            }
        }
        for (String name : schema.required()) {
            if (!plain.containsKey(name) && !secrets.containsKey(name)) {
                throw invalid(channel.displayName() + ": " + schema.field(name).orElseThrow().title() + " is required");
            }
        }

        rule.setChannelConfig(new LinkedHashMap<>(channel.normalize(plain)));
        if (secretsChanged) {
            storeSecrets(rule, secrets);
        }
    }

    public ChannelConfig read(AlertRule rule) {
        Map<String, String> values = new LinkedHashMap<>(rule.getChannelConfig());
        values.putAll(decryptSecrets(rule));
        return new ChannelConfig(values);
    }

    public List<String> configuredSecrets(AlertRule rule) {
        return List.copyOf(new TreeSet<>(rule.getChannelConfigEncrypted().keySet()));
    }

    private void check(AlertChannelProvider channel, ConfigProperty field, String value) {
        if (field.options() != null && !field.options().contains(value)) {
            throw invalid(channel.displayName() + ": " + field.title() + " must be one of "
                    + String.join(", ", field.options()));
        }
        // An SSRF sink like an Endpoint URL.
        if (ConfigProperty.URI.equals(field.format())) {
            UrlValidator.validateWebhookUrl(value, allowPrivateIps, allowedHosts);
        }
    }

    private Map<String, String> decryptSecrets(AlertRule rule) {
        Map<String, String> secrets = new LinkedHashMap<>();
        rule.getChannelConfigEncrypted().forEach((name, sealed) -> secrets.put(name,
                encryptionKeyRegistry.decrypt(sealed.get(CIPHERTEXT), sealed.get(IV), rule.getEncryptionKeyVersion())));
        return secrets;
    }

    // All under one key version, the rule's, so rotation re-encrypts a rule as a whole.
    private void storeSecrets(AlertRule rule, Map<String, String> secrets) {
        Map<String, Map<String, String>> sealed = new LinkedHashMap<>();
        secrets.forEach((name, value) -> {
            SecretEncryption.EncryptedData encrypted = encryptionKeyRegistry.encrypt(value);
            sealed.put(name, Map.of(CIPHERTEXT, encrypted.getCiphertext(), IV, encrypted.getIv()));
            rule.setEncryptionKeyVersion(encrypted.getKeyVersion());
        });
        rule.setChannelConfigEncrypted(sealed);
    }

    private static DomainException invalid(String message) {
        return new DomainException(ErrorCode.INVALID_REQUEST, message);
    }
}
