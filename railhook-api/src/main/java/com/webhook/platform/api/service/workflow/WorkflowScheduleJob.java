package com.webhook.platform.api.service.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.Workflow;
import com.webhook.platform.api.domain.entity.WorkflowTriggerOutbox;
import com.webhook.platform.api.domain.repository.WorkflowRepository;
import com.webhook.platform.api.domain.repository.WorkflowTriggerOutboxRepository;
import com.webhook.platform.api.tenancy.SystemTenant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class WorkflowScheduleJob {

    private static final int BATCH_SIZE = 100;

    private final WorkflowRepository workflowRepository;
    private final WorkflowTriggerOutboxRepository outboxRepository;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    @SystemTenant("scheduled workflows belong to every organization; each run enters its own")
    @Scheduled(fixedDelay = 10_000, initialDelay = 10_000)
    @SchedulerLock(name = "enqueueScheduledWorkflows", lockAtMostFor = "PT1M")
    public void poll() {
        int fired = enqueueDue(Instant.now());
        if (fired > 0) {
            log.debug("Enqueued {} scheduled workflow run(s)", fired);
        }
    }

    @SystemTenant("scheduled workflows belong to every organization; each run enters its own")
    public int enqueueDue(Instant now) {
        return transactionTemplate.execute(tx -> {
            List<Workflow> due = workflowRepository.claimDueSchedules(now, PageRequest.of(0, BATCH_SIZE));
            for (Workflow workflow : due) {
                outboxRepository.save(WorkflowTriggerOutbox.builder()
                        .projectId(workflow.getProjectId())
                        .workflowId(workflow.getId())
                        .scheduledFor(workflow.getNextRunAt())
                        .build());
                workflowRepository.setNextRunAt(workflow.getId(), nextRun(workflow, now));
            }
            return due.size();
        });
    }

    // After downtime the next run is the first tick after now, so the missed ones collapse into this one.
    private Instant nextRun(Workflow workflow, Instant now) {
        try {
            return WorkflowSchedule.of(objectMapper.readTree(workflow.getTriggerConfig())).nextAfter(now);
        } catch (Exception e) {
            log.warn("Workflow {} has an unreadable schedule and stops firing: {}", workflow.getId(), e.getMessage());
            return null;
        }
    }
}
