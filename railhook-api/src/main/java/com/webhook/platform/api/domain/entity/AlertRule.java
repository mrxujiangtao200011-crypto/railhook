package com.webhook.platform.api.domain.entity;

import com.webhook.platform.api.domain.enums.AlertSeverity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "alert_rules")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AlertRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;


    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "alert_type", nullable = false, length = 50)
    private String alertType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private AlertSeverity severity = AlertSeverity.WARNING;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String channel = "IN_APP";

    // Only the settings a channel's schema does not mark secret; those are in channelConfigEncrypted.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "channel_config", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private Map<String, String> channelConfig = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "channel_config_encrypted", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private Map<String, Map<String, String>> channelConfigEncrypted = new LinkedHashMap<>();

    @Column(name = "threshold_value")
    private Double thresholdValue;

    @Column(name = "window_minutes", nullable = false)
    @Builder.Default
    private Integer windowMinutes = 5;

    @Column(name = "endpoint_id")
    private UUID endpointId;

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @Column(nullable = false)
    @Builder.Default
    private Boolean muted = false;

    @Column(name = "snoozed_until")
    private Instant snoozedUntil;

    @Column(name = "encryption_key_version", nullable = false)
    @Builder.Default
    private Integer encryptionKeyVersion = 1;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
