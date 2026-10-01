package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.AlertRule;
import com.webhook.platform.api.domain.enums.IncidentStatus;
import com.webhook.platform.api.domain.repository.AlertEventRepository;
import com.webhook.platform.api.domain.repository.AlertRuleRepository;
import com.webhook.platform.api.domain.repository.IncidentRepository;
import com.webhook.platform.api.service.alert.condition.AlertCondition;
import com.webhook.platform.api.service.alert.condition.AlertCondition.Breach;
import com.webhook.platform.api.service.alert.condition.AlertConditionRegistry;
import com.webhook.platform.api.tenancy.SystemTenant;
import com.webhook.platform.api.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Fires on the crossing, not the condition: an open alert keeps its rule quiet until it clears.
 * Each rule runs in its own organization, or counts would include everyone's deliveries.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertEvaluatorService {

    private final AlertRuleRepository ruleRepository;
    private final AlertEventRepository eventRepository;
    private final IncidentRepository incidentRepository;
    private final AlertService alertService;
    private final AlertConditionRegistry conditions;

    private static final Duration RESOLVED_ALERT_RETENTION = Duration.ofDays(90);

    @SystemTenant("alert rules belong to every organization; each is evaluated inside its own")
    @Scheduled(cron = "${app.alerts.evaluation-cron:0 * * * * *}")
    @SchedulerLock(name = "alert_evaluation", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    public void evaluate() {
        List<AlertRule> rules = ruleRepository.findByEnabledTrue();
        if (rules.isEmpty()) {
            return;
        }

        int fired = 0;
        for (AlertRule rule : rules) {
            try {
                if (evaluateOne(rule)) {
                    fired++;
                }
            } catch (Exception e) {
                // WARN, not swallowed: a rule that never evaluates looks exactly like one that never fires.
                log.warn("Alert rule {} ('{}') could not be evaluated: {}",
                        rule.getId(), rule.getName(), e.toString());
            }
        }

        if (fired > 0) {
            log.info("Alert evaluation: {} rule(s) fired out of {} enabled", fired, rules.size());
        }
    }

    // Only resolved events: an open one keeps its rule quiet, and deleting it would re-fire the rule.
    @SystemTenant("alert history of every organization past its retention window")
    @Scheduled(cron = "0 30 3 * * *")
    @SchedulerLock(name = "alert_event_purge", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    @Transactional
    public void purgeResolvedAlertEvents() {
        int deleted = eventRepository.deleteResolvedBefore(Instant.now().minus(RESOLVED_ALERT_RETENTION));
        if (deleted > 0) {
            log.info("Alert history: deleted {} resolved alert event(s) older than {} days",
                    deleted, RESOLVED_ALERT_RETENTION.toDays());
        }
    }

    private boolean evaluateOne(AlertRule rule) {
        if (isSilenced(rule)) {
            return false;
        }
        return Boolean.TRUE.equals(TenantContext.callAs(rule.getOrganizationId(), () -> {
            // Checked inside the tenant, because AlertEvent is tenant-scoped too.
            boolean open = eventRepository.existsByAlertRuleIdAndResolvedFalse(rule.getId());
            Optional<Breach> breach = assess(rule);
            if (breach.isEmpty()) {
                // Otherwise only a person resolves it, and the rule stays silent through later outages.
                if (open || incidentRepository.existsByAlertRuleIdAndStatusNot(rule.getId(), IncidentStatus.RESOLVED)) {
                    alertService.resolveRecovered(rule);
                }
                return false;
            }
            if (open) {
                return false;
            }
            alertService.fireAlert(rule, breach.get().currentValue(), breach.get().message());
            return true;
        }));
    }

    private boolean isSilenced(AlertRule rule) {
        if (Boolean.TRUE.equals(rule.getMuted())) {
            return true;
        }
        return rule.getSnoozedUntil() != null && rule.getSnoozedUntil().isAfter(Instant.now());
    }

    private Optional<Breach> assess(AlertRule rule) {
        AlertCondition condition = conditions.find(rule.getAlertType()).orElseThrow(() ->
                new IllegalStateException("no alert condition is installed for type " + rule.getAlertType()));
        int minutes = rule.getWindowMinutes() == null ? 5 : rule.getWindowMinutes();
        Instant now = Instant.now();
        return condition.assess(rule, new AlertCondition.Window(now.minus(Duration.ofMinutes(minutes)), now, minutes));
    }
}
