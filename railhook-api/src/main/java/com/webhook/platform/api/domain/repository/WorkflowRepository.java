package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.Workflow;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkflowRepository extends JpaRepository<Workflow, UUID> {

    Optional<Workflow> findByIdAndProjectId(UUID id, UUID projectId);

    List<Workflow> findByProjectIdOrderByCreatedAtDesc(UUID projectId);

    boolean existsByProjectIdAndName(UUID projectId, String name);

    @Query("SELECT w FROM Workflow w WHERE w.projectId = :projectId AND w.enabled = true " +
           "AND w.triggerType = 'WEBHOOK_EVENT'")
    List<Workflow> findEnabledWebhookWorkflows(UUID projectId);

    // A lock timeout of -2 is SKIP LOCKED: another instance's claimed ticks are passed over, not waited on.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("SELECT w FROM Workflow w WHERE w.nextRunAt <= :now AND w.enabled = true " +
           "AND w.triggerType = 'SCHEDULE' ORDER BY w.nextRunAt")
    List<Workflow> claimDueSchedules(Instant now, Pageable page);

    // A bulk update, so updated_at keeps meaning the last edit rather than the last run.
    @Modifying
    @Query("UPDATE Workflow w SET w.nextRunAt = :nextRunAt WHERE w.id = :id")
    void setNextRunAt(UUID id, Instant nextRunAt);
}
