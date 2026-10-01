package com.webhook.platform.api.service;

import com.webhook.platform.api.audit.Auditable;
import com.webhook.platform.api.audit.AuditAction;
import com.webhook.platform.api.domain.entity.AlertEvent;
import com.webhook.platform.api.domain.entity.AlertRule;
import com.webhook.platform.api.domain.entity.Incident;
import com.webhook.platform.api.domain.enums.AlertSeverity;
import com.webhook.platform.api.domain.repository.AlertEventRepository;
import com.webhook.platform.api.domain.repository.AlertRuleRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.dto.AlertEventResponse;
import com.webhook.platform.api.dto.AlertRuleRequest;
import com.webhook.platform.api.dto.AlertRuleResponse;
import com.webhook.platform.api.exception.DomainException;
import com.webhook.platform.api.exception.ErrorCode;
import com.webhook.platform.api.exception.NotFoundException;
import com.webhook.platform.api.service.alert.ConfigProperty;
import com.webhook.platform.api.service.alert.channel.AlertChannelConfigs;
import com.webhook.platform.api.service.alert.channel.AlertChannelProvider;
import com.webhook.platform.api.service.alert.channel.AlertChannelRegistry;
import com.webhook.platform.api.service.alert.condition.AlertCondition;
import com.webhook.platform.api.service.alert.condition.AlertConditionRegistry;
import com.webhook.platform.api.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlertService {

    private final AlertRuleRepository ruleRepository;
    private final AlertEventRepository eventRepository;
    private final ProjectRepository projectRepository;
    private final IncidentService incidentService;
    private final AlertNotificationService notificationService;
    private final AlertChannelRegistry channels;
    private final AlertChannelConfigs channelConfigs;
    private final AlertConditionRegistry conditions;

    /** Resolving the open alerts re-arms the rule for the next crossing. */
    @Transactional
    public int resolveRecovered(AlertRule rule) {
        int resolved = eventRepository.resolveOpenByAlertRuleId(rule.getId(), Instant.now());
        if (resolved > 0) {
            log.info("Alert resolved: rule='{}', project={}; the condition no longer holds",
                    rule.getName(), rule.getProjectId());
        }
        Optional<Incident> incident = incidentService.resolveClearedAlert(rule);
        incident.ifPresent(i -> afterCommit(() -> notificationService.dispatchResolved(rule, i)));
        return resolved;
    }

    @Transactional(readOnly = true)
    public List<AlertRuleResponse> listRules(UUID projectId) {
        validateProjectAccess(projectId);
        return ruleRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(this::toRuleResponse)
                .toList();
    }

    @Auditable(action = AuditAction.CREATE, resourceType = "AlertRule")
    @Transactional
    public AlertRuleResponse createRule(UUID projectId, AlertRuleRequest request) {
        validateProjectAccess(projectId);
        AlertCondition condition = conditions.require(request.getAlertType());
        AlertChannelProvider channel = channels.require(
                request.getChannel() != null ? request.getChannel() : AlertChannelRegistry.IN_APP);

        AlertRule rule = AlertRule.builder()
                .projectId(projectId)
                .name(request.getName())
                .description(request.getDescription())
                .alertType(condition.id())
                .severity(request.getSeverity() != null ? request.getSeverity() : AlertSeverity.WARNING)
                .channel(channel.id())
                .thresholdValue(request.getThresholdValue())
                .windowMinutes(request.getWindowMinutes() != null ? request.getWindowMinutes() : 5)
                .endpointId(request.getEndpointId())
                .enabled(request.getEnabled() != null ? request.getEnabled() : true)
                .muted(request.getMuted() != null ? request.getMuted() : false)
                .snoozedUntil(request.getSnoozedUntil())
                .build();
        channelConfigs.apply(rule, channel, request.getChannelConfig(), true);
        requireConditionSettings(rule, condition);
        requirePageableSeverity(rule, channel);

        rule = ruleRepository.save(rule);
        log.debug("Created alert rule '{}' ({}) for project {}", rule.getName(), rule.getAlertType(), projectId);
        return toRuleResponse(rule);
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "AlertRule")
    @Transactional
    public AlertRuleResponse updateRule(UUID projectId, UUID ruleId, AlertRuleRequest request) {
        validateProjectAccess(projectId);

        AlertRule rule = ruleRepository.findByIdAndProjectId(ruleId, projectId)
                .orElseThrow(() -> new NotFoundException("Alert rule not found"));

        if (request.getName() != null) rule.setName(request.getName());
        if (request.getDescription() != null) rule.setDescription(request.getDescription());
        if (request.getAlertType() != null) rule.setAlertType(conditions.require(request.getAlertType()).id());
        if (request.getSeverity() != null) rule.setSeverity(request.getSeverity());
        if (request.getThresholdValue() != null) rule.setThresholdValue(request.getThresholdValue());
        if (request.getWindowMinutes() != null) rule.setWindowMinutes(request.getWindowMinutes());
        if (request.getEndpointId() != null) rule.setEndpointId(request.getEndpointId());
        if (request.getEnabled() != null) rule.setEnabled(request.getEnabled());
        if (request.getMuted() != null) rule.setMuted(request.getMuted());
        if (request.getSnoozedUntil() != null) rule.setSnoozedUntil(request.getSnoozedUntil());

        // A new channel starts empty: the old channel's key must not travel to wherever the new one sends.
        boolean switching = request.getChannel() != null && !request.getChannel().equals(rule.getChannel());
        AlertChannelProvider channel = channels.require(switching ? request.getChannel() : rule.getChannel());
        if (switching || request.getChannelConfig() != null) {
            rule.setChannel(channel.id());
            channelConfigs.apply(rule, channel, request.getChannelConfig(), switching);
        }
        requireConditionSettings(rule, conditions.require(rule.getAlertType()));
        requirePageableSeverity(rule, channel);

        rule = ruleRepository.save(rule);
        log.debug("Updated alert rule '{}' for project {}", rule.getName(), projectId);
        return toRuleResponse(rule);
    }

    @Auditable(action = AuditAction.DELETE, resourceType = "AlertRule")
    @Transactional
    public void deleteRule(UUID projectId, UUID ruleId) {
        validateProjectAccess(projectId);
        AlertRule rule = ruleRepository.findByIdAndProjectId(ruleId, projectId)
                .orElseThrow(() -> new NotFoundException("Alert rule not found"));
        ruleRepository.delete(rule);
        log.debug("Deleted alert rule '{}' from project {}", rule.getName(), projectId);
    }

    @Transactional(readOnly = true)
    public Page<AlertEventResponse> listEvents(UUID projectId, int page, int size) {
        validateProjectAccess(projectId);
        return eventRepository.findByProjectIdOrderByCreatedAtDesc(projectId, PageRequest.of(page, Math.min(size, 100)))
                .map(this::toEventResponse);
    }

    @Transactional(readOnly = true)
    public long countUnresolved(UUID projectId) {
        validateProjectAccess(projectId);
        return eventRepository.countByProjectIdAndResolvedFalse(projectId);
    }

    @Transactional
    public void resolveEvent(UUID projectId, UUID eventId) {
        validateProjectAccess(projectId);
        int updated = eventRepository.resolveById(eventId, projectId, Instant.now());
        if (updated == 0) {
            throw new NotFoundException("Alert event not found");
        }
    }

    @Transactional
    public int resolveAll(UUID projectId) {
        validateProjectAccess(projectId);
        return eventRepository.resolveAllByProjectId(projectId, Instant.now());
    }

    // An alert with no rule behind it. Creates no Incident and notifies nobody; the caller decides.
    @Transactional
    public AlertEvent raiseSystemAlert(UUID projectId, UUID endpointId, AlertSeverity severity,
            String title, String message) {
        AlertEvent event = eventRepository.save(AlertEvent.builder()
                .alertRuleId(null)
                .projectId(projectId)
                .endpointId(endpointId)
                .severity(severity)
                .title(title)
                .message(message)
                .build());
        log.warn("System alert raised: project={}, endpoint={}, title='{}'", projectId, endpointId, title);
        return event;
    }

    @Transactional
    public AlertEvent fireAlert(AlertRule rule, double currentValue, String message) {
        AlertEvent event = AlertEvent.builder()
                .alertRuleId(rule.getId())
                .projectId(rule.getProjectId())
                .severity(rule.getSeverity())
                .title(rule.getName())
                .message(message)
                .currentValue(currentValue)
                .thresholdValue(rule.getThresholdValue())
                .build();

        event = eventRepository.save(event);
        log.warn("Alert fired: rule='{}', project={}, current={}, threshold={}",
                rule.getName(), rule.getProjectId(), currentValue, rule.getThresholdValue());

        Incident incident = rule.getSeverity() == AlertSeverity.INFO ? null
                : incidentService.recordAlertFiring(rule, message);

        // Only after commit: sent mid-transaction, a later failure rolled the alert back after
        // the message went out, and the next evaluation sent it again.
        AlertEvent fired = event;
        afterCommit(() -> notificationService.dispatch(rule, fired, incident));
        return event;
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        action.run();
                    }
                });
    }

    private static void requireConditionSettings(AlertRule rule, AlertCondition condition) {
        for (String name : condition.configSchema().required()) {
            Object value = switch (name) {
                case AlertCondition.THRESHOLD -> rule.getThresholdValue();
                case AlertCondition.WINDOW -> rule.getWindowMinutes();
                case AlertCondition.ENDPOINT -> rule.getEndpointId();
                default -> throw new IllegalStateException("A rule has no setting " + name);
            };
            if (value == null) {
                String title = condition.configSchema().field(name).map(ConfigProperty::title).orElse(name);
                throw new DomainException(ErrorCode.INVALID_REQUEST,
                        condition.displayName() + ": " + title + " is required");
            }
        }
    }

    private static void requirePageableSeverity(AlertRule rule, AlertChannelProvider channel) {
        if (channel.pages() && rule.getSeverity() == AlertSeverity.INFO) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, "An INFO rule cannot page anyone; use WARNING or CRITICAL");
        }
    }

    private AlertRuleResponse toRuleResponse(AlertRule rule) {
        return AlertRuleResponse.builder()
                .id(rule.getId())
                .projectId(rule.getProjectId())
                .name(rule.getName())
                .description(rule.getDescription())
                .alertType(rule.getAlertType())
                .severity(rule.getSeverity())
                .channel(rule.getChannel())
                .channelConfig(rule.getChannelConfig())
                .configuredSecrets(channelConfigs.configuredSecrets(rule))
                .thresholdValue(rule.getThresholdValue())
                .windowMinutes(rule.getWindowMinutes())
                .endpointId(rule.getEndpointId())
                .enabled(rule.getEnabled())
                .muted(rule.getMuted())
                .snoozedUntil(rule.getSnoozedUntil())
                .createdAt(rule.getCreatedAt())
                .updatedAt(rule.getUpdatedAt())
                .build();
    }

    private AlertEventResponse toEventResponse(AlertEvent event) {
        return AlertEventResponse.builder()
                .id(event.getId())
                .alertRuleId(event.getAlertRuleId())
                .projectId(event.getProjectId())
                .endpointId(event.getEndpointId())
                .severity(event.getSeverity())
                .title(event.getTitle())
                .message(event.getMessage())
                .currentValue(event.getCurrentValue())
                .thresholdValue(event.getThresholdValue())
                .resolved(event.getResolved())
                .resolvedAt(event.getResolvedAt())
                .createdAt(event.getCreatedAt())
                .build();
    }

    private void validateProjectAccess(UUID projectId) {
        UUID organizationId = TenantContext.require();
        projectRepository.findById(projectId)
                .filter(p -> p.getOrganizationId().equals(organizationId))
                .orElseThrow(() -> new NotFoundException("Project not found"));
    }
}
