-- The unique index on (workflow_id, scheduled_for) makes a redelivered scheduled row run once.
ALTER TABLE workflows ADD COLUMN next_run_at timestamp with time zone;

CREATE INDEX idx_workflows_next_run ON workflows USING btree (next_run_at) WHERE (next_run_at IS NOT NULL);

ALTER TABLE workflow_trigger_outbox
    ALTER COLUMN event_id DROP NOT NULL,
    ALTER COLUMN event_type DROP NOT NULL,
    ADD COLUMN workflow_id uuid REFERENCES workflows(id) ON DELETE CASCADE,
    ADD COLUMN scheduled_for timestamp with time zone,
    ADD CONSTRAINT workflow_trigger_outbox_source_check
        CHECK (event_id IS NOT NULL OR (workflow_id IS NOT NULL AND scheduled_for IS NOT NULL));

ALTER TABLE workflow_executions ADD COLUMN scheduled_for timestamp with time zone;

CREATE UNIQUE INDEX idx_wf_exec_scheduled ON workflow_executions USING btree (workflow_id, scheduled_for) WHERE (scheduled_for IS NOT NULL);
