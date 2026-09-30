package com.webhook.platform.worker.domain.repository;

import com.webhook.platform.common.demo.DemoTenant;
import com.webhook.platform.common.enums.ForwardAttemptStatus;
import com.webhook.platform.worker.domain.entity.IncomingForwardAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface IncomingForwardAttemptRepository extends JpaRepository<IncomingForwardAttempt, UUID> {

        /** Scoped to the Replay session: a Replay starts a second Ladder for the same pair. */
         @Query("SELECT a FROM IncomingForwardAttempt a WHERE a.incomingEventId = :eventId "
                        + "AND a.destinationId = :destinationId "
                        + "AND ((:replaySessionId IS NULL AND a.replaySessionId IS NULL) "
                        + "OR a.replaySessionId = :replaySessionId) "
                        + "ORDER BY a.attemptNumber DESC")
        List<IncomingForwardAttempt> findForwardAttempts(@Param("eventId") UUID eventId,
                        @Param("destinationId") UUID destinationId,
                        @Param("replaySessionId") UUID replaySessionId);

        /** Same fairness and claim timeout as {@code DeliveryRepository#claimDue}, per Destination. */
        @Query(value = """
                        WITH RECURSIVE due_destinations AS (
                            (SELECT a.destination_id, 1 AS n FROM incoming_forward_attempts a
                             WHERE a.status IN ('PENDING', 'PROCESSING') AND a.next_retry_at <= :now
                               AND a.destination_id > :after
                             ORDER BY a.destination_id LIMIT 1)
                            UNION ALL
                            SELECT nxt.destination_id, e.n + 1 FROM due_destinations e
                            CROSS JOIN LATERAL (
                                SELECT a.destination_id FROM incoming_forward_attempts a
                                WHERE a.status IN ('PENDING', 'PROCESSING') AND a.next_retry_at <= :now
                                  AND a.destination_id > e.destination_id
                                ORDER BY a.destination_id LIMIT 1) nxt
                            WHERE e.n < :maxDestinations
                        ),
                        picked AS (
                            SELECT p.id FROM due_destinations e
                            CROSS JOIN LATERAL (
                                SELECT a.id FROM incoming_forward_attempts a
                                WHERE a.destination_id = e.destination_id
                                  AND a.status IN ('PENDING', 'PROCESSING') AND a.next_retry_at <= :now
                                ORDER BY a.next_retry_at
                                LIMIT GREATEST(0, :perDestination - (SELECT count(*) FROM incoming_forward_attempts f
                                    WHERE f.destination_id = e.destination_id AND f.status = 'PROCESSING'
                                      AND f.next_retry_at > :now))
                                FOR UPDATE SKIP LOCKED) p
                            LIMIT :limit
                        )
                        UPDATE incoming_forward_attempts a SET status = 'PROCESSING',
                            claim_token = gen_random_uuid(), started_at = :now, next_retry_at = :claimExpiresAt
                        FROM picked WHERE a.id = picked.id
                        RETURNING a.*
                        """, nativeQuery = true)
        List<IncomingForwardAttempt> claimDue(@Param("now") Instant now,
                        @Param("claimExpiresAt") Instant claimExpiresAt,
                        @Param("after") UUID after,
                        @Param("maxDestinations") int maxDestinations,
                        @Param("perDestination") int perDestination,
                        @Param("limit") int limit);

        @Modifying
        @Query(value = "UPDATE incoming_forward_attempts SET status = 'FAILED', finished_at = :now, " +
                        "error_message = :reason, next_retry_at = NULL, claim_token = NULL " +
                        "WHERE id = :id AND status = 'PROCESSING' AND claim_token = :claimToken",
                        nativeQuery = true)
        int failIfStillClaimed(@Param("id") UUID id,
                        @Param("claimToken") UUID claimToken,
                        @Param("reason") String reason,
                        @Param("now") Instant now);

        /**
         * Locks the row for the caller's transaction if it is still held under {@code fence}, so
         * {@code finalise} cannot overwrite a reclaim that committed after its read. The no-op
         * UPDATE is what takes the lock and re-evaluates the predicate after a concurrent writer.
         */
        @Modifying
        @Query(value = "UPDATE incoming_forward_attempts SET claim_token = claim_token " +
                        "WHERE id = :id AND status IN (:statuses) " +
                        "AND claim_token IS NOT DISTINCT FROM CAST(:fence AS uuid)", nativeQuery = true)
        int holdIfStillClaimed(@Param("id") UUID id,
                        @Param("statuses") List<String> statuses,
                        @Param("fence") UUID fence);

        /**
         * Ages a Forward from its attempt 1 in the same Replay session. Each Attempt is a new row,
         * and the event's received_at made a retried or replayed Forward look days old.
         */
        @Query(value = """
                        SELECT MIN(COALESCE(f.created_at, a.created_at)) FROM incoming_forward_attempts a
                        LEFT JOIN incoming_forward_attempts f
                            ON f.incoming_event_id = a.incoming_event_id
                            AND f.destination_id = a.destination_id
                            AND f.attempt_number = 1
                            AND f.replay_session_id IS NOT DISTINCT FROM a.replay_session_id
                        WHERE a.status = 'PENDING'
                        """, nativeQuery = true)
        Instant findOldestPendingForwardStartedAt();

        /** SKIP LOCKED so two replicas do not escalate one row; only {@code a}, as the outer join requires. */
        @Query(value = """
                        SELECT a.id FROM incoming_forward_attempts a
                        LEFT JOIN incoming_forward_attempts f
                            ON f.incoming_event_id = a.incoming_event_id
                            AND f.destination_id = a.destination_id
                            AND f.attempt_number = 1
                            AND f.replay_session_id IS NOT DISTINCT FROM a.replay_session_id
                        WHERE a.status = 'PENDING' AND COALESCE(f.created_at, a.created_at) < :cutoff
                        ORDER BY COALESCE(f.created_at, a.created_at) ASC
                        LIMIT :limit
                        FOR UPDATE OF a SKIP LOCKED
                        """, nativeQuery = true)
        List<UUID> findStaleForwardAttemptIds(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

        @Query("SELECT COUNT(a) FROM IncomingForwardAttempt a WHERE a.status = 'PENDING' AND a.createdAt > :since")
        long countPending(@Param("since") Instant since);

        @Query("SELECT COUNT(a) FROM IncomingForwardAttempt a WHERE a.status = 'PROCESSING' AND a.createdAt > :since")
        long countProcessing(@Param("since") Instant since);

        @Query("SELECT COUNT(a) FROM IncomingForwardAttempt a WHERE a.status = 'DLQ' AND a.createdAt > :since "
                        + "AND a.organizationId <> :excluded")
        long countDlqExcluding(@Param("since") Instant since, @Param("excluded") UUID excludedOrganizationId);

        /** Excludes the public demo's seeded rows, which would otherwise page the operator. */
        default long countDlq(Instant since) {
                return countDlqExcluding(since, DemoTenant.ORGANIZATION_ID);
        }

        /** Not windowed: a Forward abandoned a week ago still needs a decision. */
        @Query("SELECT COUNT(a) FROM IncomingForwardAttempt a WHERE a.status = 'DLQ' AND a.organizationId <> :excluded")
        long countDlqTotalExcluding(@Param("excluded") UUID excludedOrganizationId);

        default long countDlqTotal() {
                return countDlqTotalExcluding(DemoTenant.ORGANIZATION_ID);
        }
}
