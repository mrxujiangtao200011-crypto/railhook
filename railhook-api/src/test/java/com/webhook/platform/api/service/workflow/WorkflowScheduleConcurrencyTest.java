package com.webhook.platform.api.service.workflow;

import com.webhook.platform.api.AbstractIntegrationTest;
import com.webhook.platform.api.domain.entity.WorkflowExecution;
import com.webhook.platform.api.domain.repository.WorkflowExecutionRepository;
import com.webhook.platform.api.domain.repository.WorkflowRepository;
import com.webhook.platform.api.tenancy.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// The job is driven with a clock a day ahead, so the app's own poll, on the real clock, never finds these rows due.
@TestPropertySource(properties = "workflow.trigger-outbox.poll-interval-ms=3600000")
class WorkflowScheduleConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private WorkflowScheduleJob scheduleJob;

    @Autowired
    private WorkflowTriggerService triggerService;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowExecutionRepository executionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private final Instant now = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MINUTES);
    private UUID orgId;
    private UUID projectId;

    @BeforeEach
    void seedProject() {
        jdbcTemplate.update("UPDATE workflows SET next_run_at = NULL");
        orgId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO organizations (id, name, plan_id) VALUES (?, ?, (SELECT id FROM plans WHERE name = 'free'))",
                orgId, "Scheduled Org");
        jdbcTemplate.update("INSERT INTO projects (id, organization_id, name) VALUES (?, ?, ?)",
                projectId, orgId, "Scheduled Project");
    }

    private UUID scheduledWorkflow(boolean enabled, Instant nextRunAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO workflows (id, project_id, organization_id, name, enabled, definition, trigger_type, "
                        + "trigger_config, next_run_at) VALUES (?, ?, ?, ?, ?, '{\"nodes\":[],\"edges\":[]}', "
                        + "'SCHEDULE', '{\"cron\":\"0 * * * *\",\"timezone\":\"UTC\"}', ?)",
                id, projectId, orgId, "wf-" + id, enabled, Timestamp.from(nextRunAt));
        return id;
    }

    private int outboxRows(UUID workflowId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM workflow_trigger_outbox WHERE workflow_id = ?", Integer.class, workflowId);
    }

    private Instant nextRunAt(UUID workflowId) {
        return jdbcTemplate.queryForObject(
                "SELECT next_run_at FROM workflows WHERE id = ?", Timestamp.class, workflowId).toInstant();
    }

    @Test
    @DisplayName("a tick one instance is firing is skipped, not fired again, by another")
    void aClaimedTickIsFiredOnce() throws Exception {
        UUID workflowId = scheduledWorkflow(true, now.minus(1, ChronoUnit.MINUTES));
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<Void> firstInstance = CompletableFuture.runAsync(() -> TenantContext.runAsSystem(() ->
                transactionTemplate.executeWithoutResult(tx -> {
                    workflowRepository.claimDueSchedules(now, PageRequest.of(0, 10));
                    claimed.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    tx.setRollbackOnly();
                })));
        assertThat(claimed.await(10, TimeUnit.SECONDS)).isTrue();

        int firedWhileLocked = CompletableFuture.supplyAsync(() -> scheduleJob.enqueueDue(now))
                .get(5, TimeUnit.SECONDS);
        release.countDown();
        firstInstance.get(10, TimeUnit.SECONDS);

        assertThat(firedWhileLocked).isZero();
        assertThat(scheduleJob.enqueueDue(now)).isEqualTo(1);
        assertThat(scheduleJob.enqueueDue(now)).isZero();
        assertThat(outboxRows(workflowId)).isEqualTo(1);
    }

    @Test
    @DisplayName("after downtime the missed ticks fire once, and the next run is in the future")
    void missedTicksFireOnce() {
        UUID workflowId = scheduledWorkflow(true, now.minus(5, ChronoUnit.HOURS));

        scheduleJob.enqueueDue(now);
        scheduleJob.enqueueDue(now);

        assertThat(outboxRows(workflowId)).isEqualTo(1);
        assertThat(nextRunAt(workflowId)).isEqualTo(now.plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS));
    }

    @Test
    @DisplayName("a disabled workflow does not fire")
    void disabledWorkflowDoesNotFire() {
        UUID workflowId = scheduledWorkflow(false, now.minus(1, ChronoUnit.MINUTES));

        scheduleJob.enqueueDue(now);

        assertThat(outboxRows(workflowId)).isZero();
    }

    @Test
    @DisplayName("a redelivered tick runs the workflow once, recorded as scheduled")
    void redeliveredTickRunsOnce() {
        UUID workflowId = scheduledWorkflow(true, now.plus(1, ChronoUnit.HOURS));
        Instant tick = now.minus(1, ChronoUnit.HOURS);

        triggerService.triggerScheduledSync(workflowId, tick);
        triggerService.triggerScheduledSync(workflowId, tick);

        List<WorkflowExecution> executions = executionRepository.findAll().stream()
                .filter(e -> e.getWorkflowId().equals(workflowId))
                .toList();
        assertThat(executions).singleElement()
                .extracting(WorkflowExecution::getScheduledFor)
                .isEqualTo(tick);
    }
}
