package com.webhook.platform.api.service.workflow;

import com.webhook.platform.api.tenancy.SystemTenant;
import com.webhook.platform.api.domain.repository.WorkflowExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

// Fails executions stuck in RUNNING past the threshold, such as after a crash. WAITING is not swept.
@Component
@Slf4j
@RequiredArgsConstructor
public class WorkflowExecutionRecoveryJob {

    private final WorkflowExecutionRepository executionRepository;
    @Value("${workflow.execution.stuck-threshold-minutes:15}")
    private final long stuckThresholdMinutes;

    @SystemTenant
    @Scheduled(fixedDelayString = "${workflow.execution.recovery-interval-ms:120000}")
    @SchedulerLock(name = "recoverStuckWorkflowExecutions", lockAtMostFor = "2m", lockAtLeastFor = "30s")
    @Transactional
    public void recoverStuckExecutions() {
        try {
            Instant cutoff = Instant.now().minus(stuckThresholdMinutes, ChronoUnit.MINUTES);
            String errorMsg = "Execution timed out — recovered by cleanup job after " + stuckThresholdMinutes + " minutes";

            int recovered = executionRepository.failStuckExecutions(cutoff, errorMsg, Instant.now());
            if (recovered > 0) {
                log.warn("Recovered {} stuck workflow executions (RUNNING > {} min)", recovered, stuckThresholdMinutes);
            }
        } catch (Exception e) {
            log.error("Failed to recover stuck workflow executions: {}", e.getMessage(), e);
        }
    }
}
