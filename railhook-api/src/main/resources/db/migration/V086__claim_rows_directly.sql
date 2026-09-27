-- The worker now claims due rows straight from these tables, so every PENDING or PROCESSING row
-- needs a due time. For a PROCESSING row it is when its claim is presumed lost.

UPDATE deliveries SET next_retry_at = created_at
WHERE status = 'PENDING' AND next_retry_at IS NULL;

UPDATE deliveries SET next_retry_at = COALESCE(last_attempt_at, updated_at) + INTERVAL '5 minutes'
WHERE status = 'PROCESSING';

UPDATE incoming_forward_attempts SET next_retry_at = created_at
WHERE status = 'PENDING' AND next_retry_at IS NULL;

UPDATE incoming_forward_attempts SET next_retry_at = COALESCE(started_at, created_at) + INTERVAL '5 minutes'
WHERE status = 'PROCESSING';

ALTER TABLE deliveries ADD CONSTRAINT deliveries_active_has_due_time
    CHECK (status NOT IN ('PENDING', 'PROCESSING') OR next_retry_at IS NOT NULL) NOT VALID;
ALTER TABLE deliveries VALIDATE CONSTRAINT deliveries_active_has_due_time;

ALTER TABLE incoming_forward_attempts ADD CONSTRAINT forward_attempts_active_has_due_time
    CHECK (status NOT IN ('PENDING', 'PROCESSING') OR next_retry_at IS NOT NULL) NOT VALID;
ALTER TABLE incoming_forward_attempts VALIDATE CONSTRAINT forward_attempts_active_has_due_time;

DROP TABLE outbox_messages;
