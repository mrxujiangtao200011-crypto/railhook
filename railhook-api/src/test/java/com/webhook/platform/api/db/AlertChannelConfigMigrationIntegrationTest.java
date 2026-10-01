package com.webhook.platform.api.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.common.security.SecretEncryption;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// V004 moves each channel's columns into channel_config; a rule that lost a setting would page nobody.
@Testcontainers
class AlertChannelConfigMigrationIntegrationTest {

    private static final String KEY = "migration-test-key-0123456789abcdef";
    private static final String SALT = "migration-test-salt";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("alerts")
            .withUsername("alerts")
            .withPassword("alerts");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void everyChannelKeepsItsSettings_andSecretsStayEncryptedUnderTheRulesKeyVersion() throws Exception {
        migrateTo("3");
        UUID projectId = seedProject();
        SecretEncryption.EncryptedData routingKey = SecretEncryption.encrypt("pd-routing-key", KEY, SALT, 2);
        SecretEncryption.EncryptedData apiKey = SecretEncryption.encrypt("og-api-key", KEY, SALT, 2);

        UUID inApp = insertRule(projectId, "IN_APP", null, null, null, null);
        UUID email = insertRule(projectId, "EMAIL", null, "ops@company.com,dev@company.com", null, null);
        UUID webhook = insertRule(projectId, "WEBHOOK", "https://example.com/alerts", null, null, null);
        UUID slack = insertRule(projectId, "SLACK", "https://hooks.slack.com/services/T0/B0/X", null, null, null);
        UUID pagerDuty = insertRule(projectId, "PAGERDUTY", null, null, routingKey, null);
        UUID opsgenieEu = insertRule(projectId, "OPSGENIE", null, null, apiKey, "EU");
        UUID opsgenieDefault = insertRule(projectId, "OPSGENIE", null, null, apiKey, null);

        migrateTo("latest");

        assertThat(config(inApp)).isEmpty();
        assertThat(secrets(inApp)).isEmpty();
        assertThat(config(email)).isEqualTo(Map.of("recipients", "ops@company.com,dev@company.com"));
        assertThat(config(webhook)).isEqualTo(Map.of("url", "https://example.com/alerts"));
        assertThat(config(slack)).isEqualTo(Map.of("url", "https://hooks.slack.com/services/T0/B0/X"));
        assertThat(config(pagerDuty)).isEmpty();
        assertThat(decrypted(pagerDuty, "routingKey")).isEqualTo("pd-routing-key");
        assertThat(config(opsgenieEu)).isEqualTo(Map.of("region", "EU"));
        assertThat(decrypted(opsgenieEu, "apiKey")).isEqualTo("og-api-key");
        assertThat(config(opsgenieDefault)).as("no region meant the US one").isEqualTo(Map.of("region", "US"));

        assertThat(secrets(pagerDuty).toString()).doesNotContain("pd-routing-key");
        assertThat(keyVersion(pagerDuty)).isEqualTo(2);
        assertThat(columns()).as("the previous API still reads these during a rolling deploy").contains(
                "webhook_url", "email_recipients", "integration_key_encrypted", "integration_key_iv", "opsgenie_region");
    }

    private void migrateTo(String target) {
        Flyway.configure()
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
    }

    private UUID seedProject() throws SQLException {
        UUID organizationId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        update("INSERT INTO organizations (id, name, plan_id) VALUES (?, 'o', (SELECT id FROM plans WHERE name = 'free'))",
                organizationId);
        update("INSERT INTO projects (id, organization_id, name) VALUES (?, ?, 'p')", projectId, organizationId);
        return projectId;
    }

    private UUID insertRule(UUID projectId, String channel, String webhookUrl, String recipients,
            SecretEncryption.EncryptedData key, String region) throws SQLException {
        UUID id = UUID.randomUUID();
        update("INSERT INTO alert_rules (id, organization_id, project_id, name, alert_type, channel, threshold_value, "
                        + "webhook_url, email_recipients, integration_key_encrypted, integration_key_iv, "
                        + "encryption_key_version, opsgenie_region) "
                        + "SELECT ?, organization_id, id, 'r', 'FAILURE_RATE', ?, 10, ?, ?, ?, ?, ?, ? FROM projects WHERE id = ?",
                id, channel, webhookUrl, recipients,
                key == null ? null : key.getCiphertext(), key == null ? null : key.getIv(),
                key == null ? 1 : key.getKeyVersion(), region, projectId);
        return id;
    }

    private Map<String, String> config(UUID ruleId) throws Exception {
        return objectMapper.convertValue(json("channel_config", ruleId),
                objectMapper.getTypeFactory().constructMapType(Map.class, String.class, String.class));
    }

    private JsonNode secrets(UUID ruleId) throws Exception {
        return json("channel_config_encrypted", ruleId);
    }

    private String decrypted(UUID ruleId, String name) throws Exception {
        JsonNode sealed = secrets(ruleId).get(name);
        return SecretEncryption.decrypt(sealed.get("ciphertext").asText(), sealed.get("iv").asText(), KEY, SALT);
    }

    private int keyVersion(UUID ruleId) throws SQLException {
        return Integer.parseInt(scalar("SELECT encryption_key_version::text FROM alert_rules WHERE id = ?", ruleId));
    }

    private JsonNode json(String column, UUID ruleId) throws Exception {
        return objectMapper.readTree(scalar("SELECT " + column + "::text FROM alert_rules WHERE id = ?", ruleId));
    }

    private List<String> columns() throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection connection = connect();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT column_name FROM information_schema.columns WHERE table_name = 'alert_rules'");
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                names.add(rows.getString(1));
            }
        }
        return names;
    }

    private String scalar(String sql, Object... args) throws SQLException {
        try (Connection connection = connect(); PreparedStatement query = prepare(connection, sql, args);
             ResultSet rows = query.executeQuery()) {
            rows.next();
            return rows.getString(1);
        }
    }

    private void update(String sql, Object... args) throws SQLException {
        try (Connection connection = connect(); PreparedStatement statement = prepare(connection, sql, args)) {
            statement.executeUpdate();
        }
    }

    private static PreparedStatement prepare(Connection connection, String sql, Object... args) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) {
            statement.setObject(i + 1, args[i]);
        }
        return statement;
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
