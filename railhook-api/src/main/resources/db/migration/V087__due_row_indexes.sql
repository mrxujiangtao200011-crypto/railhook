CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_deliveries_due
    ON deliveries (endpoint_id, next_retry_at) WHERE status IN ('PENDING', 'PROCESSING');

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_incoming_fwd_attempts_due
    ON incoming_forward_attempts (destination_id, next_retry_at) WHERE status IN ('PENDING', 'PROCESSING');

DROP INDEX CONCURRENTLY IF EXISTS idx_deliveries_retry_query;
DROP INDEX CONCURRENTLY IF EXISTS idx_incoming_fwd_retry_query;
DROP INDEX CONCURRENTLY IF EXISTS idx_incoming_fwd_attempts_retry;
