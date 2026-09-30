package com.webhook.platform.worker.domain.repository;

import com.webhook.platform.common.demo.DemoTenant;
import com.webhook.platform.worker.domain.entity.Delivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Modifying;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface DeliveryRepository extends JpaRepository<Delivery, UUID> {

    /** An endpoint gets only its free concurrency, so nothing claimed is deferred for want of a permit. */
    @Query(value = """
            WITH RECURSIVE due_endpoints AS (
                (SELECT d.endpoint_id, 1 AS n FROM deliveries d
                 WHERE d.status IN ('PENDING', 'PROCESSING') AND d.next_retry_at <= :now
                   AND d.endpoint_id > :after
                 ORDER BY d.endpoint_id LIMIT 1)
                UNION ALL
                SELECT nxt.endpoint_id, e.n + 1 FROM due_endpoints e
                CROSS JOIN LATERAL (
                    SELECT d.endpoint_id FROM deliveries d
                    WHERE d.status IN ('PENDING', 'PROCESSING') AND d.next_retry_at <= :now
                      AND d.endpoint_id > e.endpoint_id
                    ORDER BY d.endpoint_id LIMIT 1) nxt
                WHERE e.n < :maxEndpoints
            ),
            picked AS (
                SELECT p.id FROM due_endpoints e
                CROSS JOIN LATERAL (
                    SELECT d.id FROM deliveries d
                    WHERE d.endpoint_id = e.endpoint_id AND d.status IN ('PENDING', 'PROCESSING')
                      AND d.next_retry_at <= :now
                    ORDER BY d.next_retry_at
                    LIMIT GREATEST(0, :perEndpoint - (SELECT count(*) FROM deliveries f
                        WHERE f.endpoint_id = e.endpoint_id AND f.status = 'PROCESSING'
                          AND f.next_retry_at > :now))
                    FOR UPDATE SKIP LOCKED) p
                LIMIT :limit
            )
            UPDATE deliveries d SET status = 'PROCESSING', claim_token = gen_random_uuid(),
                next_retry_at = :claimExpiresAt, last_attempt_at = :now, updated_at = :now,
                version = d.version + 1
            FROM picked WHERE d.id = picked.id
            RETURNING d.*
            """, nativeQuery = true)
    List<Delivery> claimDue(@Param("now") Instant now,
            @Param("claimExpiresAt") Instant claimExpiresAt,
            @Param("after") UUID after,
            @Param("maxEndpoints") int maxEndpoints,
            @Param("perEndpoint") int perEndpoint,
            @Param("limit") int limit);

    @Modifying
    @Query(value = "UPDATE deliveries SET status = 'PENDING', claim_token = NULL, next_retry_at = :until, " +
            "ordering_first_buffered_at = COALESCE(ordering_first_buffered_at, :now), " +
            "updated_at = :now, version = version + 1 " +
            "WHERE id = :id AND status = 'PROCESSING' AND claim_token = :claimToken", nativeQuery = true)
    int parkIfStillClaimed(@Param("id") UUID id,
            @Param("claimToken") UUID claimToken,
            @Param("until") Instant until,
            @Param("now") Instant now);

    /** Matches nothing once the row is claimed, so it cannot overtake an Attempt in flight. */
    @Modifying
    @Query(value = "UPDATE deliveries SET next_retry_at = :retryAt, updated_at = now(), version = version + 1 " +
            "WHERE id = :id AND status = 'PENDING' AND claim_token IS NULL", nativeQuery = true)
    int scheduleIfUnclaimed(@Param("id") UUID id, @Param("retryAt") Instant retryAt);

    /** Fenced, or an Attempt whose claim timed out spends its successor's rung. */
    @Modifying
    @Query(value = "UPDATE deliveries SET attempt_count = attempt_count + 1, " +
            "updated_at = now(), version = version + 1 " +
            "WHERE id = :id AND claim_token IS NOT DISTINCT FROM CAST(:fence AS uuid)", nativeQuery = true)
    int incrementAttemptCount(@Param("id") UUID id, @Param("fence") UUID fence);

    /** Null when nothing in the gap is outstanding. Not used to time the gap. */
    @Query("SELECT MIN(d.createdAt) FROM Delivery d WHERE d.endpointId = :endpointId " +
            "AND d.sequenceNumber BETWEEN :rangeStart AND :rangeEnd AND d.status IN ('PENDING', 'PROCESSING')")
    Instant findOldestPendingCreatedAt(
            @Param("endpointId") UUID endpointId,
            @Param("rangeStart") long rangeStart,
            @Param("rangeEnd") long rangeEnd
    );

    /** {@code inFlightSince} stops a PROCESSING row whose worker died from holding the gap open. */
    @Query("SELECT COUNT(d) FROM Delivery d WHERE d.endpointId = :endpointId "
            + "AND d.sequenceNumber BETWEEN :rangeStart AND :rangeEnd "
            + "AND ((d.status = 'PROCESSING' AND d.updatedAt > :inFlightSince) "
            + "OR (d.status = 'PENDING' AND (d.nextRetryAt IS NULL OR d.nextRetryAt <= :dueBy)))")
    long countGapClosingBefore(
            @Param("endpointId") UUID endpointId,
            @Param("rangeStart") long rangeStart,
            @Param("rangeEnd") long rangeEnd,
            @Param("inFlightSince") Instant inFlightSince,
            @Param("dueBy") Instant dueBy
    );

    @Query("SELECT COUNT(d) FROM Delivery d WHERE d.status = 'PENDING' AND d.createdAt > :since")
    long countPending(@Param("since") Instant since);

    @Query("SELECT COUNT(d) FROM Delivery d WHERE d.status = 'PROCESSING' AND d.createdAt > :since")
    long countProcessing(@Param("since") Instant since);

    @Query("SELECT COUNT(d) FROM Delivery d WHERE d.status = 'DLQ' AND d.createdAt > :since "
            + "AND d.organizationId <> :excluded")
    long countDlqExcluding(@Param("since") Instant since, @Param("excluded") UUID excludedOrganizationId);

    /** Excludes the public demo's seeded rows, which would otherwise page the operator. */
    default long countDlq(Instant since) {
        return countDlqExcluding(since, DemoTenant.ORGANIZATION_ID);
    }

    @Query("SELECT COUNT(d) FROM Delivery d WHERE d.status = 'DLQ' AND d.organizationId <> :excluded")
    long countDlqTotalExcluding(@Param("excluded") UUID excludedOrganizationId);

    default long countDlqTotal() {
        return countDlqTotalExcluding(DemoTenant.ORGANIZATION_ID);
    }

    @Query("SELECT MIN(d.createdAt) FROM Delivery d WHERE d.status = 'PENDING'")
    Instant findOldestPendingCreatedAtGlobal();

    /**
     * The ladder restarts at ladder_resumed_at when a person retries a Delivery; measuring from
     * created_at alone sent old retried Deliveries straight back to DLQ. The created_at predicate
     * stays so the partial index is usable.
     */
    @Query(value = """
            SELECT d.id FROM deliveries d
            WHERE d.status = 'PENDING' AND d.created_at < :cutoff
              AND (d.ladder_resumed_at IS NULL OR d.ladder_resumed_at < :cutoff)
            ORDER BY d.created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<UUID> findStaleDeliveryIds(@Param("cutoff") Instant cutoff, @Param("limit") int limit);
}
