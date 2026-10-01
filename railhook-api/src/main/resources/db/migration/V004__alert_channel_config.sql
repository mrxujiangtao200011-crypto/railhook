-- A channel's settings move into one column, so a new channel is a class rather than columns.
-- Secrets keep their ciphertext and IV as they were: SQL cannot re-encrypt, and does not need to,
-- since every secret of a rule stays under the rule's encryption_key_version.
-- threshold_value loses NOT NULL: a condition such as NO_TRAFFIC has no threshold.
-- The old columns stay until the next release: during a rolling deploy the previous API still reads them.
ALTER TABLE alert_rules ADD COLUMN channel_config jsonb DEFAULT '{}'::jsonb NOT NULL;
ALTER TABLE alert_rules ADD COLUMN channel_config_encrypted jsonb DEFAULT '{}'::jsonb NOT NULL;

UPDATE alert_rules SET channel_config = jsonb_build_object('url', webhook_url)
    WHERE channel IN ('WEBHOOK', 'SLACK') AND webhook_url IS NOT NULL;

UPDATE alert_rules SET channel_config = jsonb_build_object('recipients', email_recipients)
    WHERE channel = 'EMAIL' AND email_recipients IS NOT NULL;

UPDATE alert_rules SET channel_config_encrypted = jsonb_build_object('routingKey',
        jsonb_build_object('ciphertext', integration_key_encrypted, 'iv', integration_key_iv))
    WHERE channel = 'PAGERDUTY' AND integration_key_encrypted IS NOT NULL;

UPDATE alert_rules SET channel_config = jsonb_build_object('region', COALESCE(opsgenie_region, 'US'))
    WHERE channel = 'OPSGENIE';

UPDATE alert_rules SET channel_config_encrypted = jsonb_build_object('apiKey',
        jsonb_build_object('ciphertext', integration_key_encrypted, 'iv', integration_key_iv))
    WHERE channel = 'OPSGENIE' AND integration_key_encrypted IS NOT NULL;

ALTER TABLE alert_rules ALTER COLUMN threshold_value DROP NOT NULL;
