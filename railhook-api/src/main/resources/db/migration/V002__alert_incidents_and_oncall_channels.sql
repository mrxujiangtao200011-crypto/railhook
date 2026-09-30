-- An alert rule opens its own Incident and resolves it when the condition clears. The partial
-- unique index keeps it to one open Incident per rule even if two firings race.
ALTER TABLE incidents ADD COLUMN alert_rule_id uuid REFERENCES alert_rules(id) ON DELETE SET NULL;
ALTER TABLE incidents ADD COLUMN auto_resolved boolean DEFAULT false NOT NULL;

CREATE UNIQUE INDEX uq_incidents_open_per_alert_rule ON incidents (alert_rule_id)
    WHERE alert_rule_id IS NOT NULL AND status <> 'RESOLVED';

ALTER TABLE alert_rules ADD COLUMN integration_key_encrypted text;
ALTER TABLE alert_rules ADD COLUMN integration_key_iv text;
ALTER TABLE alert_rules ADD COLUMN encryption_key_version integer DEFAULT 1 NOT NULL;
ALTER TABLE alert_rules ADD COLUMN opsgenie_region character varying(2);
