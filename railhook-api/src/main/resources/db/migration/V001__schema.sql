CREATE SEQUENCE public_bin_requests_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE alert_events (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    alert_rule_id uuid,
    project_id uuid NOT NULL,
    severity character varying(20) NOT NULL,
    title character varying(500) NOT NULL,
    message text,
    current_value double precision,
    threshold_value double precision,
    resolved boolean DEFAULT false NOT NULL,
    resolved_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    organization_id uuid NOT NULL,
    endpoint_id uuid
);

ALTER TABLE ONLY alert_events
    ADD CONSTRAINT alert_events_pkey PRIMARY KEY (id);

CREATE INDEX idx_alert_events_project ON alert_events USING btree (project_id, created_at DESC);

CREATE INDEX idx_alert_events_rule ON alert_events USING btree (alert_rule_id, created_at DESC);

CREATE INDEX idx_alert_events_unresolved ON alert_events USING btree (project_id, resolved) WHERE (resolved = false);

CREATE TABLE alert_rules (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    project_id uuid NOT NULL,
    name character varying(200) NOT NULL,
    description text,
    alert_type character varying(50) NOT NULL,
    severity character varying(20) DEFAULT 'WARNING'::character varying NOT NULL,
    channel character varying(20) DEFAULT 'IN_APP'::character varying NOT NULL,
    threshold_value double precision NOT NULL,
    window_minutes integer DEFAULT 5 NOT NULL,
    endpoint_id uuid,
    enabled boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    muted boolean DEFAULT false NOT NULL,
    snoozed_until timestamp with time zone,
    webhook_url character varying(2048),
    email_recipients text,
    organization_id uuid NOT NULL
);

ALTER TABLE ONLY alert_rules
    ADD CONSTRAINT alert_rules_pkey PRIMARY KEY (id);

CREATE INDEX idx_alert_rules_project ON alert_rules USING btree (project_id);

CREATE INDEX idx_alert_rules_project_enabled ON alert_rules USING btree (project_id, enabled) WHERE (enabled = true);

CREATE TABLE api_keys (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    project_id uuid NOT NULL,
    name character varying(255) NOT NULL,
    key_hash character varying(255) NOT NULL,
    key_prefix character varying(16) NOT NULL,
    last_used_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    revoked_at timestamp with time zone,
    expires_at timestamp with time zone,
    scope character varying(20) DEFAULT 'READ_WRITE'::character varying NOT NULL,
    organization_id uuid NOT NULL,
    rotated_at timestamp with time zone,
    replaced_by_id uuid
);

ALTER TABLE ONLY api_keys
    ADD CONSTRAINT api_keys_key_hash_key UNIQUE (key_hash);

ALTER TABLE ONLY api_keys
    ADD CONSTRAINT api_keys_pkey PRIMARY KEY (id);

CREATE INDEX idx_api_keys_key_hash ON api_keys USING btree (key_hash);

CREATE INDEX idx_api_keys_project_active ON api_keys USING btree (project_id) WHERE (revoked_at IS NULL);

CREATE INDEX idx_api_keys_project_id ON api_keys USING btree (project_id);

CREATE INDEX idx_api_keys_revoked_at ON api_keys USING btree (revoked_at) WHERE (revoked_at IS NULL);

CREATE TABLE audit_log (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    action character varying(50) NOT NULL,
    resource_type character varying(50) NOT NULL,
    resource_id uuid,
    user_id uuid,
    organization_id uuid,
    status character varying(20) NOT NULL,
    error_message text,
    duration_ms integer,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    client_ip character varying(45),
    details text
);

ALTER TABLE ONLY audit_log
    ADD CONSTRAINT audit_log_pkey PRIMARY KEY (id);

CREATE INDEX idx_audit_log_created_at ON audit_log USING btree (created_at);

CREATE INDEX idx_audit_log_org_id ON audit_log USING btree (organization_id);

CREATE INDEX idx_audit_log_resource ON audit_log USING btree (resource_type, resource_id);

CREATE INDEX idx_audit_log_user_id ON audit_log USING btree (user_id);

CREATE TABLE billing_invoices (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    organization_id uuid NOT NULL,
    subscription_id uuid,
    provider_code character varying(50) NOT NULL,
    external_invoice_id character varying(255),
    invoice_number character varying(100),
    status character varying(30) DEFAULT 'DRAFT'::character varying NOT NULL,
    subtotal_cents bigint DEFAULT 0 NOT NULL,
    tax_cents bigint DEFAULT 0 NOT NULL,
    total_cents bigint DEFAULT 0 NOT NULL,
    currency character varying(3) DEFAULT 'USD'::character varying NOT NULL,
    period_start timestamp with time zone,
    period_end timestamp with time zone,
    due_date timestamp with time zone,
    paid_at timestamp with time zone,
    voided_at timestamp with time zone,
    hosted_url character varying(2048),
    pdf_url character varying(2048),
    line_items jsonb DEFAULT '[]'::jsonb,
    metadata jsonb DEFAULT '{}'::jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY billing_invoices
    ADD CONSTRAINT billing_invoices_pkey PRIMARY KEY (id);

CREATE INDEX idx_billing_invoices_due ON billing_invoices USING btree (due_date) WHERE ((status)::text = 'OPEN'::text);

CREATE INDEX idx_billing_invoices_external ON billing_invoices USING btree (external_invoice_id) WHERE (external_invoice_id IS NOT NULL);

CREATE INDEX idx_billing_invoices_org ON billing_invoices USING btree (organization_id, created_at DESC);

CREATE INDEX idx_billing_invoices_status ON billing_invoices USING btree (status) WHERE ((status)::text = ANY ((ARRAY['OPEN'::character varying, 'PAST_DUE'::character varying])::text[]));

CREATE INDEX idx_billing_invoices_sub ON billing_invoices USING btree (subscription_id) WHERE (subscription_id IS NOT NULL);

CREATE TABLE billing_payments (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    invoice_id uuid,
    organization_id uuid NOT NULL,
    subscription_id uuid,
    provider_code character varying(50) NOT NULL,
    external_payment_id character varying(255),
    status character varying(30) DEFAULT 'PENDING'::character varying NOT NULL,
    amount_cents bigint NOT NULL,
    currency character varying(3) DEFAULT 'USD'::character varying NOT NULL,
    refunded_cents bigint DEFAULT 0 NOT NULL,
    payment_method character varying(50),
    card_last4 character varying(4),
    card_brand character varying(20),
    failure_code character varying(100),
    failure_message text,
    provider_response jsonb DEFAULT '{}'::jsonb,
    metadata jsonb DEFAULT '{}'::jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY billing_payments
    ADD CONSTRAINT billing_payments_pkey PRIMARY KEY (id);

CREATE INDEX idx_billing_payments_external ON billing_payments USING btree (external_payment_id) WHERE (external_payment_id IS NOT NULL);

CREATE INDEX idx_billing_payments_invoice ON billing_payments USING btree (invoice_id) WHERE (invoice_id IS NOT NULL);

CREATE INDEX idx_billing_payments_org ON billing_payments USING btree (organization_id, created_at DESC);

CREATE INDEX idx_billing_payments_status ON billing_payments USING btree (status, created_at DESC);

CREATE INDEX idx_billing_payments_sub ON billing_payments USING btree (subscription_id) WHERE (subscription_id IS NOT NULL);

CREATE TABLE billing_scheduled_changes (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    organization_id uuid NOT NULL,
    subscription_id uuid NOT NULL,
    from_plan_id uuid NOT NULL,
    to_plan_id uuid NOT NULL,
    change_type character varying(30) DEFAULT 'PLAN_CHANGE'::character varying NOT NULL,
    effective_at timestamp with time zone NOT NULL,
    status character varying(30) DEFAULT 'PENDING'::character varying NOT NULL,
    applied_at timestamp with time zone,
    cancelled_at timestamp with time zone,
    reason text,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY billing_scheduled_changes
    ADD CONSTRAINT billing_scheduled_changes_pkey PRIMARY KEY (id);

CREATE INDEX idx_billing_scheduled_org ON billing_scheduled_changes USING btree (organization_id);

CREATE INDEX idx_billing_scheduled_pending ON billing_scheduled_changes USING btree (effective_at) WHERE ((status)::text = 'PENDING'::text);

CREATE INDEX idx_billing_scheduled_sub ON billing_scheduled_changes USING btree (subscription_id);

CREATE TABLE billing_subscription_events (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    subscription_id uuid NOT NULL,
    event_type character varying(50) NOT NULL,
    from_status character varying(30),
    to_status character varying(30),
    from_plan_id uuid,
    to_plan_id uuid,
    reason text,
    metadata jsonb DEFAULT '{}'::jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    organization_id uuid NOT NULL
);

ALTER TABLE ONLY billing_subscription_events
    ADD CONSTRAINT billing_subscription_events_pkey PRIMARY KEY (id);

CREATE INDEX idx_billing_sub_events_sub ON billing_subscription_events USING btree (subscription_id, created_at DESC);

CREATE INDEX idx_billing_sub_events_type ON billing_subscription_events USING btree (event_type, created_at DESC);

CREATE TABLE billing_subscriptions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    organization_id uuid NOT NULL,
    plan_id uuid NOT NULL,
    provider_code character varying(50) NOT NULL,
    status character varying(30) DEFAULT 'ACTIVE'::character varying NOT NULL,
    external_subscription_id character varying(255),
    external_customer_id character varying(255),
    current_period_start timestamp with time zone,
    current_period_end timestamp with time zone,
    cancel_at_period_end boolean DEFAULT false NOT NULL,
    cancelled_at timestamp with time zone,
    trial_start timestamp with time zone,
    trial_end timestamp with time zone,
    recurring_token_encrypted text,
    card_last4 character varying(4),
    card_brand character varying(20),
    currency character varying(3) DEFAULT 'USD'::character varying NOT NULL,
    metadata jsonb DEFAULT '{}'::jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    billing_interval character varying(20) DEFAULT 'MONTHLY'::character varying NOT NULL,
    price_cents bigint
);

ALTER TABLE ONLY billing_subscriptions
    ADD CONSTRAINT billing_subscriptions_pkey PRIMARY KEY (id);

CREATE INDEX idx_billing_subs_active ON billing_subscriptions USING btree (status) WHERE ((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'PAST_DUE'::character varying, 'TRIALING'::character varying, 'GRACE_PERIOD'::character varying])::text[]));

CREATE INDEX idx_billing_subs_external_cust ON billing_subscriptions USING btree (external_customer_id) WHERE (external_customer_id IS NOT NULL);

CREATE INDEX idx_billing_subs_external_sub ON billing_subscriptions USING btree (external_subscription_id) WHERE (external_subscription_id IS NOT NULL);

CREATE INDEX idx_billing_subs_org ON billing_subscriptions USING btree (organization_id);

CREATE INDEX idx_billing_subs_period_end ON billing_subscriptions USING btree (current_period_end) WHERE ((status)::text = 'ACTIVE'::text);

CREATE INDEX idx_billing_subs_provider ON billing_subscriptions USING btree (provider_code, status);

CREATE INDEX idx_billing_subscriptions_interval ON billing_subscriptions USING btree (billing_interval);

CREATE UNIQUE INDEX uq_billing_subs_one_open_per_org ON billing_subscriptions USING btree (organization_id) WHERE ((status)::text = ANY ((ARRAY['PENDING'::character varying, 'TRIALING'::character varying, 'ACTIVE'::character varying, 'PAST_DUE'::character varying, 'GRACE_PERIOD'::character varying])::text[]));

CREATE TABLE captured_requests (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    test_endpoint_id uuid NOT NULL,
    method character varying(10) NOT NULL,
    path character varying(1024),
    query_string text,
    headers text,
    body text,
    content_type character varying(255),
    source_ip character varying(45),
    user_agent character varying(512),
    received_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    organization_id uuid NOT NULL
);

ALTER TABLE ONLY captured_requests
    ADD CONSTRAINT captured_requests_pkey PRIMARY KEY (id);

CREATE INDEX idx_captured_requests_endpoint ON captured_requests USING btree (test_endpoint_id);

CREATE INDEX idx_captured_requests_received ON captured_requests USING btree (received_at DESC);

CREATE TABLE consumers (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    external_id character varying(255) NOT NULL,
    name character varying(255) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY consumers
    ADD CONSTRAINT consumers_pkey PRIMARY KEY (id);

ALTER TABLE ONLY consumers
    ADD CONSTRAINT uq_consumers_project_external_id UNIQUE (project_id, external_id);

CREATE TABLE deliveries (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    event_id uuid NOT NULL,
    endpoint_id uuid NOT NULL,
    subscription_id uuid,
    status character varying(50) DEFAULT 'PENDING'::character varying NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    max_attempts integer DEFAULT 7 NOT NULL,
    sequence_number bigint,
    ordering_enabled boolean DEFAULT false NOT NULL,
    timeout_seconds integer DEFAULT 30,
    retry_delays text DEFAULT '60,300,900,3600,21600,86400'::text,
    payload_template text,
    custom_headers text,
    next_retry_at timestamp with time zone,
    last_attempt_at timestamp with time zone,
    succeeded_at timestamp with time zone,
    failed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    idempotency_key character varying(255),
    replay_session_id uuid,
    version bigint DEFAULT 0 NOT NULL,
    transformation_id uuid,
    delivery_origin character varying(20) DEFAULT 'SUBSCRIPTION'::character varying NOT NULL,
    ordering_first_buffered_at timestamp with time zone,
    claim_token uuid,
    organization_id uuid NOT NULL,
    ladder_resumed_at timestamp with time zone,
    retryable_statuses text DEFAULT '408,429,500-599'::text NOT NULL,
    CONSTRAINT deliveries_active_has_due_time CHECK ((((status)::text <> ALL ((ARRAY['PENDING'::character varying, 'PROCESSING'::character varying])::text[])) OR (next_retry_at IS NOT NULL)))
);

ALTER TABLE ONLY deliveries
    ADD CONSTRAINT deliveries_pkey PRIMARY KEY (id);

CREATE INDEX idx_deliveries_created_at ON deliveries USING btree (created_at);

CREATE INDEX idx_deliveries_dlq_created ON deliveries USING btree (created_at) WHERE ((status)::text = 'DLQ'::text);

CREATE INDEX idx_deliveries_due ON deliveries USING btree (endpoint_id, next_retry_at) WHERE ((status)::text = ANY ((ARRAY['PENDING'::character varying, 'PROCESSING'::character varying])::text[]));

CREATE INDEX idx_deliveries_endpoint_created_status ON deliveries USING btree (endpoint_id, created_at, status);

CREATE INDEX idx_deliveries_endpoint_seq ON deliveries USING btree (endpoint_id, sequence_number) WHERE ((status)::text = ANY ((ARRAY['PENDING'::character varying, 'PROCESSING'::character varying])::text[]));

CREATE UNIQUE INDEX idx_deliveries_endpoint_seq_unique ON deliveries USING btree (endpoint_id, sequence_number) WHERE ((ordering_enabled = true) AND (sequence_number IS NOT NULL));

CREATE INDEX idx_deliveries_event_id ON deliveries USING btree (event_id);

CREATE INDEX idx_deliveries_idempotency_key ON deliveries USING btree (idempotency_key) WHERE (idempotency_key IS NOT NULL);

CREATE INDEX idx_deliveries_pending_created ON deliveries USING btree (created_at) WHERE ((status)::text = 'PENDING'::text);

CREATE INDEX idx_deliveries_processing_created ON deliveries USING btree (created_at) WHERE ((status)::text = 'PROCESSING'::text);

CREATE INDEX idx_deliveries_replay_session_id ON deliveries USING btree (replay_session_id) WHERE (replay_session_id IS NOT NULL);

CREATE INDEX idx_deliveries_rule_origin ON deliveries USING btree (endpoint_id, created_at DESC) WHERE ((delivery_origin)::text = 'RULE'::text);

CREATE INDEX idx_deliveries_subscription_id ON deliveries USING btree (subscription_id);

CREATE INDEX idx_deliveries_transformation_id ON deliveries USING btree (transformation_id);

CREATE UNIQUE INDEX idx_deliveries_unique_rule ON deliveries USING btree (event_id, endpoint_id) WHERE ((subscription_id IS NULL) AND (replay_session_id IS NULL));

CREATE UNIQUE INDEX idx_deliveries_unique_rule_replay ON deliveries USING btree (event_id, endpoint_id, replay_session_id) WHERE ((subscription_id IS NULL) AND (replay_session_id IS NOT NULL));

CREATE UNIQUE INDEX idx_deliveries_unique_subscription ON deliveries USING btree (event_id, endpoint_id, subscription_id) WHERE ((subscription_id IS NOT NULL) AND (replay_session_id IS NULL));

CREATE UNIQUE INDEX idx_deliveries_unique_subscription_replay ON deliveries USING btree (event_id, endpoint_id, subscription_id, replay_session_id) WHERE ((subscription_id IS NOT NULL) AND (replay_session_id IS NOT NULL));

ALTER TABLE deliveries SET (autovacuum_vacuum_scale_factor = 0.02, autovacuum_analyze_scale_factor = 0.01);

CREATE TABLE delivery_attempts (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    delivery_id uuid NOT NULL,
    attempt_number integer NOT NULL,
    http_status_code integer,
    response_body text,
    error_message text,
    duration_ms integer,
    request_headers jsonb,
    request_body text,
    response_headers jsonb,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    organization_id uuid NOT NULL
)
PARTITION BY RANGE (created_at);

ALTER TABLE delivery_attempts
    ADD CONSTRAINT delivery_attempts_pkey PRIMARY KEY (id, created_at);

CREATE INDEX idx_delivery_attempts_cleanup ON delivery_attempts USING btree (created_at, http_status_code);

CREATE UNIQUE INDEX idx_delivery_attempts_unique_attempt ON delivery_attempts USING btree (delivery_id, attempt_number, created_at);

CREATE INDEX idx_delivery_attempts_delivery_id ON delivery_attempts USING btree (delivery_id);

CREATE TABLE device_auth_codes (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    device_code character varying(128) NOT NULL,
    user_code character varying(16) NOT NULL,
    user_id uuid,
    organization_id uuid,
    status character varying(32) DEFAULT 'PENDING'::character varying NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    approved_at timestamp with time zone
);

ALTER TABLE ONLY device_auth_codes
    ADD CONSTRAINT device_auth_codes_device_code_key UNIQUE (device_code);

ALTER TABLE ONLY device_auth_codes
    ADD CONSTRAINT device_auth_codes_pkey PRIMARY KEY (id);

ALTER TABLE ONLY device_auth_codes
    ADD CONSTRAINT device_auth_codes_user_code_key UNIQUE (user_code);

CREATE INDEX idx_device_auth_device_code ON device_auth_codes USING btree (device_code);

CREATE INDEX idx_device_auth_status ON device_auth_codes USING btree (status) WHERE ((status)::text = 'PENDING'::text);

CREATE INDEX idx_device_auth_user_code ON device_auth_codes USING btree (user_code);

CREATE TABLE email_change_requests (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    previous_email character varying(255) NOT NULL,
    new_email character varying(255) NOT NULL,
    status character varying(16) NOT NULL,
    token_hash character varying(64),
    cancel_token_hash character varying(64),
    expires_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    resolved_at timestamp with time zone,
    CONSTRAINT ck_email_change_requests_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'CONFIRMED'::character varying, 'CANCELLED'::character varying, 'APPLIED'::character varying])::text[])))
);

ALTER TABLE ONLY email_change_requests
    ADD CONSTRAINT email_change_requests_pkey PRIMARY KEY (id);

ALTER TABLE ONLY email_change_requests
    ADD CONSTRAINT uq_email_change_requests_cancel_token_hash UNIQUE (cancel_token_hash);

ALTER TABLE ONLY email_change_requests
    ADD CONSTRAINT uq_email_change_requests_token_hash UNIQUE (token_hash);

CREATE INDEX idx_email_change_requests_user_created ON email_change_requests USING btree (user_id, created_at);

CREATE UNIQUE INDEX uq_email_change_requests_one_pending ON email_change_requests USING btree (user_id) WHERE ((status)::text = 'PENDING'::text);

CREATE TABLE endpoints (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    project_id uuid NOT NULL,
    url character varying(2048) NOT NULL,
    description text,
    enabled boolean DEFAULT true NOT NULL,
    rate_limit_per_second integer,
    secret_encrypted text NOT NULL,
    secret_iv text NOT NULL,
    secret_previous_encrypted text,
    secret_previous_iv text,
    secret_rotated_at timestamp with time zone,
    secret_rotation_grace_period_hours integer DEFAULT 24,
    allowed_source_ips text,
    mtls_enabled boolean DEFAULT false NOT NULL,
    client_cert_encrypted text,
    client_cert_iv text,
    client_key_encrypted text,
    client_key_iv text,
    ca_cert text,
    verification_status character varying(32) DEFAULT 'SKIPPED'::character varying NOT NULL,
    verification_token character varying(64),
    verification_attempted_at timestamp with time zone,
    verification_completed_at timestamp with time zone,
    verification_skip_reason character varying(255),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted_at timestamp with time zone,
    encryption_key_version integer DEFAULT 1 NOT NULL,
    organization_id uuid NOT NULL,
    signature_scheme character varying(20) DEFAULT 'BOTH'::character varying NOT NULL,
    consumer_id uuid,
    failing_since timestamp with time zone,
    consecutive_failures integer DEFAULT 0 NOT NULL,
    auto_disabled_at timestamp with time zone,
    auto_disabled_reason text
);

ALTER TABLE ONLY endpoints
    ADD CONSTRAINT endpoints_pkey PRIMARY KEY (id);

CREATE INDEX idx_endpoints_consumer ON endpoints USING btree (consumer_id) WHERE (consumer_id IS NOT NULL);

CREATE INDEX idx_endpoints_deleted_at ON endpoints USING btree (deleted_at) WHERE (deleted_at IS NULL);

CREATE INDEX idx_endpoints_enabled ON endpoints USING btree (enabled) WHERE (enabled = true);

CREATE INDEX idx_endpoints_failing_since ON endpoints USING btree (failing_since) WHERE ((failing_since IS NOT NULL) AND (enabled = true) AND (deleted_at IS NULL));

CREATE INDEX idx_endpoints_mtls_enabled ON endpoints USING btree (mtls_enabled) WHERE (mtls_enabled = true);

CREATE INDEX idx_endpoints_project_id ON endpoints USING btree (project_id);

CREATE INDEX idx_endpoints_verification_status ON endpoints USING btree (verification_status);

CREATE TABLE event_schema_version (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    event_type_id uuid NOT NULL,
    version integer NOT NULL,
    schema_json jsonb NOT NULL,
    fingerprint character varying(64) NOT NULL,
    status character varying(20) DEFAULT 'DRAFT'::character varying NOT NULL,
    compatibility_mode character varying(20) DEFAULT 'NONE'::character varying NOT NULL,
    description text,
    created_by uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    organization_id uuid NOT NULL
);

ALTER TABLE ONLY event_schema_version
    ADD CONSTRAINT event_schema_version_pkey PRIMARY KEY (id);

ALTER TABLE ONLY event_schema_version
    ADD CONSTRAINT uq_schema_version_type_version UNIQUE (event_type_id, version);

CREATE INDEX idx_event_schema_version_status ON event_schema_version USING btree (event_type_id, status);

CREATE INDEX idx_event_schema_version_type ON event_schema_version USING btree (event_type_id);

CREATE TABLE event_type_catalog (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    project_id uuid NOT NULL,
    name character varying(255) NOT NULL,
    description text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    organization_id uuid NOT NULL
);

ALTER TABLE ONLY event_type_catalog
    ADD CONSTRAINT event_type_catalog_pkey PRIMARY KEY (id);

ALTER TABLE ONLY event_type_catalog
    ADD CONSTRAINT uq_event_type_catalog_project_name UNIQUE (project_id, name);

CREATE INDEX idx_event_type_catalog_project ON event_type_catalog USING btree (project_id);

CREATE TABLE events (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    project_id uuid NOT NULL,
    event_type character varying(255) NOT NULL,
    idempotency_key character varying(255),
    payload jsonb NOT NULL,
    sequence_number bigint,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    payload_compressed boolean DEFAULT false NOT NULL,
    organization_id uuid NOT NULL
);

ALTER TABLE ONLY events
    ADD CONSTRAINT events_pkey PRIMARY KEY (id);

CREATE INDEX idx_events_created_at ON events USING btree (created_at);

CREATE INDEX idx_events_event_type ON events USING btree (event_type);

CREATE UNIQUE INDEX idx_events_idempotency_key ON events USING btree (project_id, idempotency_key) WHERE (idempotency_key IS NOT NULL);

CREATE INDEX idx_events_payload_compressed ON events USING btree (payload_compressed) WHERE (payload_compressed = true);

CREATE INDEX idx_events_project_created_id ON events USING btree (project_id, created_at, id);

CREATE INDEX idx_events_project_id ON events USING btree (project_id);

CREATE INDEX idx_events_project_id_id ON events USING btree (project_id, id);

CREATE INDEX idx_events_project_type_created_id ON events USING btree (project_id, event_type, created_at, id);

CREATE TABLE incident_timeline (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    incident_id uuid NOT NULL,
    entry_type character varying(30) NOT NULL,
    title character varying(500) NOT NULL,
    detail text,
    delivery_id uuid,
    endpoint_id uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    organization_id uuid NOT NULL
);

ALTER TABLE ONLY incident_timeline
    ADD CONSTRAINT incident_timeline_pkey PRIMARY KEY (id);

CREATE INDEX idx_incident_timeline ON incident_timeline USING btree (incident_id, created_at);

CREATE TABLE incidents (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    project_id uuid NOT NULL,
    title character varying(500) NOT NULL,
    status character varying(20) DEFAULT 'OPEN'::character varying NOT NULL,
    severity character varying(20) DEFAULT 'WARNING'::character varying NOT NULL,
    rca_notes text,
    resolved_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    organization_id uuid NOT NULL
);

ALTER TABLE ONLY incidents
    ADD CONSTRAINT incidents_pkey PRIMARY KEY (id);

CREATE INDEX idx_incidents_open ON incidents USING btree (project_id, status) WHERE ((status)::text <> 'RESOLVED'::text);

CREATE INDEX idx_incidents_project ON incidents USING btree (project_id, created_at DESC);

CREATE TABLE incoming_destinations (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    incoming_source_id uuid NOT NULL,
    url character varying(2048) NOT NULL,
    auth_type character varying(20) DEFAULT 'NONE'::character varying NOT NULL,
    auth_config_encrypted text,
    auth_config_iv text,
    custom_headers_json text,
    enabled boolean DEFAULT true NOT NULL,
    max_attempts integer DEFAULT 5 NOT NULL,
    timeout_seconds integer DEFAULT 30 NOT NULL,
    retry_delays text DEFAULT '60,300,900,3600,21600'::text NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    payload_transform text,
    transformation_id uuid,
    encryption_key_version integer DEFAULT 1 NOT NULL,
    organization_id uuid NOT NULL,
    failing_since timestamp with time zone,
    consecutive_failures integer DEFAULT 0 NOT NULL,
    auto_disabled_at timestamp with time zone,
    auto_disabled_reason text,
    retryable_statuses text DEFAULT '408,429,500-599'::text NOT NULL
);

ALTER TABLE ONLY incoming_destinations
    ADD CONSTRAINT incoming_destinations_pkey PRIMARY KEY (id);

CREATE INDEX idx_incoming_dest_source_enabled ON incoming_destinations USING btree (incoming_source_id, enabled) WHERE (enabled = true);

CREATE INDEX idx_incoming_destinations_enabled ON incoming_destinations USING btree (enabled) WHERE (enabled = true);

CREATE INDEX idx_incoming_destinations_failing_since ON incoming_destinations USING btree (failing_since) WHERE ((failing_since IS NOT NULL) AND (enabled = true));

CREATE INDEX idx_incoming_destinations_source_id ON incoming_destinations USING btree (incoming_source_id);

CREATE INDEX idx_incoming_destinations_transformation_id ON incoming_destinations USING btree (transformation_id);

CREATE TABLE incoming_events (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    incoming_source_id uuid NOT NULL,
    request_id character varying(64) NOT NULL,
    method character varying(10) NOT NULL,
    path character varying(2048),
    query_params text,
    headers_json text,
    body_raw text,
    body_sha256 character varying(64),
    content_type character varying(255),
    client_ip character varying(45),
    user_agent character varying(512),
    verified boolean,
    verification_error text,
    received_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    provider_event_id character varying(255),
    organization_id uuid NOT NULL,
    body_bytes bytea
);

ALTER TABLE ONLY incoming_events
    ADD CONSTRAINT incoming_events_pkey PRIMARY KEY (id);

ALTER TABLE ONLY incoming_events
    ADD CONSTRAINT incoming_events_request_id_key UNIQUE (request_id);

CREATE INDEX idx_incoming_events_received_at ON incoming_events USING btree (received_at DESC);

CREATE INDEX idx_incoming_events_request_id ON incoming_events USING btree (request_id);

CREATE INDEX idx_incoming_events_source_id ON incoming_events USING btree (incoming_source_id);

CREATE INDEX idx_incoming_events_source_id_received ON incoming_events USING btree (incoming_source_id, received_at DESC);

CREATE UNIQUE INDEX idx_incoming_events_source_provider_event ON incoming_events USING btree (incoming_source_id, provider_event_id) WHERE (provider_event_id IS NOT NULL);

CREATE INDEX idx_incoming_events_verified ON incoming_events USING btree (verified);

CREATE TABLE incoming_forward_attempts (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    incoming_event_id uuid NOT NULL,
    destination_id uuid NOT NULL,
    attempt_number integer DEFAULT 1 NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    response_code integer,
    response_headers_json text,
    response_body_snippet text,
    error_message text,
    next_retry_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    organization_id uuid NOT NULL,
    claim_token uuid,
    request_headers_json text,
    request_body_snippet text,
    replay_session_id uuid,
    CONSTRAINT forward_attempts_active_has_due_time CHECK ((((status)::text <> ALL ((ARRAY['PENDING'::character varying, 'PROCESSING'::character varying])::text[])) OR (next_retry_at IS NOT NULL)))
);

ALTER TABLE ONLY incoming_forward_attempts
    ADD CONSTRAINT incoming_forward_attempts_pkey PRIMARY KEY (id);

CREATE UNIQUE INDEX idx_incoming_forward_attempts_unique_attempt ON incoming_forward_attempts USING btree (incoming_event_id, destination_id, attempt_number) WHERE (replay_session_id IS NULL);

CREATE UNIQUE INDEX idx_incoming_forward_attempts_unique_attempt_replay ON incoming_forward_attempts USING btree (incoming_event_id, destination_id, attempt_number, replay_session_id) WHERE (replay_session_id IS NOT NULL);

CREATE INDEX idx_incoming_fwd_attempts_dest_id ON incoming_forward_attempts USING btree (destination_id);

CREATE INDEX idx_incoming_fwd_attempts_due ON incoming_forward_attempts USING btree (destination_id, next_retry_at) WHERE ((status)::text = ANY ((ARRAY['PENDING'::character varying, 'PROCESSING'::character varying])::text[]));

CREATE INDEX idx_incoming_fwd_attempts_event_id ON incoming_forward_attempts USING btree (incoming_event_id);

CREATE INDEX idx_incoming_fwd_dlq_created ON incoming_forward_attempts USING btree (created_at) WHERE ((status)::text = 'DLQ'::text);

CREATE INDEX idx_incoming_fwd_event_dest_attempt ON incoming_forward_attempts USING btree (incoming_event_id, destination_id, attempt_number DESC);

CREATE INDEX idx_incoming_fwd_pending_created ON incoming_forward_attempts USING btree (created_at) WHERE ((status)::text = 'PENDING'::text);

CREATE INDEX idx_incoming_fwd_pending_retry ON incoming_forward_attempts USING btree (next_retry_at) WHERE (((status)::text = 'PENDING'::text) AND (next_retry_at IS NOT NULL));

CREATE INDEX idx_incoming_fwd_processing_created ON incoming_forward_attempts USING btree (created_at) WHERE ((status)::text = 'PROCESSING'::text);

ALTER TABLE incoming_forward_attempts SET (autovacuum_vacuum_scale_factor = 0.02, autovacuum_analyze_scale_factor = 0.01);

CREATE TABLE incoming_sources (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    project_id uuid NOT NULL,
    name character varying(255) NOT NULL,
    slug character varying(64) NOT NULL,
    provider_type character varying(50) DEFAULT 'GENERIC'::character varying NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    ingress_path_token character varying(64) NOT NULL,
    verification_mode character varying(30) DEFAULT 'NONE'::character varying NOT NULL,
    hmac_secret_encrypted text,
    hmac_secret_iv text,
    hmac_header_name character varying(255) DEFAULT 'X-Signature'::character varying,
    hmac_signature_prefix character varying(50) DEFAULT ''::character varying,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    rate_limit_per_second integer,
    encryption_key_version integer DEFAULT 1 NOT NULL,
    organization_id uuid NOT NULL
);

ALTER TABLE ONLY incoming_sources
    ADD CONSTRAINT incoming_sources_ingress_path_token_key UNIQUE (ingress_path_token);

ALTER TABLE ONLY incoming_sources
    ADD CONSTRAINT incoming_sources_pkey PRIMARY KEY (id);

CREATE INDEX idx_incoming_sources_active_token ON incoming_sources USING btree (ingress_path_token) WHERE ((status)::text = 'ACTIVE'::text);

CREATE INDEX idx_incoming_sources_ingress_token ON incoming_sources USING btree (ingress_path_token);

CREATE INDEX idx_incoming_sources_project_id ON incoming_sources USING btree (project_id);

CREATE UNIQUE INDEX idx_incoming_sources_project_slug ON incoming_sources USING btree (project_id, slug);

CREATE INDEX idx_incoming_sources_status ON incoming_sources USING btree (status) WHERE ((status)::text = 'ACTIVE'::text);

CREATE TABLE memberships (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    role character varying(50) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    invite_token_hash character varying(64),
    invite_expires_at timestamp with time zone,
    status character varying(50) DEFAULT 'ACTIVE'::character varying NOT NULL
);

ALTER TABLE ONLY memberships
    ADD CONSTRAINT memberships_pkey PRIMARY KEY (id);

ALTER TABLE ONLY memberships
    ADD CONSTRAINT memberships_user_id_organization_id_key UNIQUE (user_id, organization_id);

CREATE UNIQUE INDEX idx_memberships_invite_token_hash ON memberships USING btree (invite_token_hash) WHERE (invite_token_hash IS NOT NULL);

CREATE INDEX idx_memberships_organization_id ON memberships USING btree (organization_id);

CREATE INDEX idx_memberships_role ON memberships USING btree (role);

CREATE INDEX idx_memberships_user_id ON memberships USING btree (user_id);

CREATE TABLE oauth_authorization_requests (
    id uuid NOT NULL,
    client_id uuid NOT NULL,
    redirect_uri character varying(2000) NOT NULL,
    code_challenge character varying(128) NOT NULL,
    state character varying(1000),
    requested_scope character varying(500),
    resource character varying(2000),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    completed_at timestamp with time zone
);

ALTER TABLE ONLY oauth_authorization_requests
    ADD CONSTRAINT oauth_authorization_requests_pkey PRIMARY KEY (id);

CREATE INDEX idx_oauth_authorization_requests_expires_at ON oauth_authorization_requests USING btree (expires_at);

CREATE TABLE oauth_clients (
    id uuid NOT NULL,
    client_id character varying(64) NOT NULL,
    client_secret_hash character varying(64),
    token_endpoint_auth_method character varying(32) NOT NULL,
    client_name character varying(200) NOT NULL,
    client_uri character varying(2000),
    redirect_uris text NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);

ALTER TABLE ONLY oauth_clients
    ADD CONSTRAINT oauth_clients_client_id_key UNIQUE (client_id);

ALTER TABLE ONLY oauth_clients
    ADD CONSTRAINT oauth_clients_pkey PRIMARY KEY (id);

CREATE TABLE oauth_grants (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    client_id uuid NOT NULL,
    user_id uuid NOT NULL,
    scope character varying(20) NOT NULL,
    redirect_uri character varying(2000) NOT NULL,
    code_challenge character varying(128) NOT NULL,
    code_hash character varying(64),
    code_expires_at timestamp with time zone,
    code_used_at timestamp with time zone,
    access_token_hash character varying(64),
    access_token_expires_at timestamp with time zone,
    refresh_token_hash character varying(64),
    refresh_token_expires_at timestamp with time zone,
    previous_refresh_token_hash character varying(64),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    activated_at timestamp with time zone,
    last_used_at timestamp with time zone,
    revoked_at timestamp with time zone
);

ALTER TABLE ONLY oauth_grants
    ADD CONSTRAINT oauth_grants_access_token_hash_key UNIQUE (access_token_hash);

ALTER TABLE ONLY oauth_grants
    ADD CONSTRAINT oauth_grants_code_hash_key UNIQUE (code_hash);

ALTER TABLE ONLY oauth_grants
    ADD CONSTRAINT oauth_grants_pkey PRIMARY KEY (id);

ALTER TABLE ONLY oauth_grants
    ADD CONSTRAINT oauth_grants_refresh_token_hash_key UNIQUE (refresh_token_hash);

CREATE INDEX idx_oauth_grants_previous_refresh ON oauth_grants USING btree (previous_refresh_token_hash);

CREATE INDEX idx_oauth_grants_project ON oauth_grants USING btree (project_id);

CREATE TABLE ordering_cursors (
    endpoint_id uuid NOT NULL,
    last_delivered_sequence bigint NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);

ALTER TABLE ONLY ordering_cursors
    ADD CONSTRAINT ordering_cursors_pkey PRIMARY KEY (endpoint_id);

CREATE INDEX idx_ordering_cursors_updated_at ON ordering_cursors USING btree (updated_at);

CREATE TABLE organizations (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    name character varying(255) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    plan_id uuid NOT NULL,
    billing_email character varying(255),
    billing_status character varying(30) DEFAULT 'ACTIVE'::character varying NOT NULL,
    suspended_at timestamp with time zone,
    suspension_reason text,
    suspended_by text
);

ALTER TABLE ONLY organizations
    ADD CONSTRAINT organizations_pkey PRIMARY KEY (id);

CREATE INDEX idx_organizations_created_at ON organizations USING btree (created_at);

CREATE INDEX idx_organizations_plan_id ON organizations USING btree (plan_id);

CREATE INDEX idx_organizations_suspended ON organizations USING btree (suspended_at) WHERE (suspended_at IS NOT NULL);

CREATE TABLE pii_masking_rules (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    project_id uuid NOT NULL,
    rule_type character varying(20) NOT NULL,
    pattern_name character varying(100) NOT NULL,
    json_path character varying(500),
    mask_style character varying(20) DEFAULT 'PARTIAL'::character varying NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    organization_id uuid NOT NULL
);

ALTER TABLE ONLY pii_masking_rules
    ADD CONSTRAINT pii_masking_rules_pkey PRIMARY KEY (id);

ALTER TABLE ONLY pii_masking_rules
    ADD CONSTRAINT pii_masking_rules_project_id_pattern_name_key UNIQUE (project_id, pattern_name);

CREATE INDEX idx_pii_masking_rules_project ON pii_masking_rules USING btree (project_id);

CREATE TABLE plans (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    name character varying(50) NOT NULL,
    display_name character varying(100) NOT NULL,
    max_events_per_month bigint DEFAULT 10000 NOT NULL,
    max_endpoints_per_project integer DEFAULT 5 NOT NULL,
    max_projects integer DEFAULT 3 NOT NULL,
    max_members integer DEFAULT 5 NOT NULL,
    rate_limit_per_second integer DEFAULT 10 NOT NULL,
    max_retention_days integer DEFAULT 7 NOT NULL,
    features jsonb DEFAULT '{}'::jsonb NOT NULL,
    price_monthly_cents integer DEFAULT 0 NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    price_yearly_cents integer DEFAULT 0 NOT NULL,
    max_active_tunnels integer DEFAULT 0 NOT NULL,
    max_fanout_per_event integer DEFAULT 100 NOT NULL
);

ALTER TABLE ONLY plans
    ADD CONSTRAINT plans_name_key UNIQUE (name);

ALTER TABLE ONLY plans
    ADD CONSTRAINT plans_pkey PRIMARY KEY (id);

CREATE TABLE portal_sessions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    consumer_id uuid NOT NULL,
    token_hash character varying(64) NOT NULL,
    allowed_origin character varying(255),
    expires_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY portal_sessions
    ADD CONSTRAINT portal_sessions_pkey PRIMARY KEY (id);

ALTER TABLE ONLY portal_sessions
    ADD CONSTRAINT uq_portal_sessions_token_hash UNIQUE (token_hash);

CREATE INDEX idx_portal_sessions_consumer ON portal_sessions USING btree (consumer_id);

CREATE INDEX idx_portal_sessions_expires_at ON portal_sessions USING btree (expires_at);

CREATE TABLE projects (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    organization_id uuid NOT NULL,
    name character varying(255) NOT NULL,
    description text,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted_at timestamp with time zone,
    schema_validation_enabled boolean DEFAULT false NOT NULL,
    schema_validation_policy character varying(10) DEFAULT 'WARN'::character varying NOT NULL,
    idempotency_policy character varying(10) DEFAULT 'NONE'::character varying NOT NULL
);

ALTER TABLE ONLY projects
    ADD CONSTRAINT projects_pkey PRIMARY KEY (id);

CREATE INDEX idx_projects_created_at ON projects USING btree (created_at);

CREATE INDEX idx_projects_deleted_at ON projects USING btree (deleted_at) WHERE (deleted_at IS NOT NULL);

CREATE INDEX idx_projects_organization_id ON projects USING btree (organization_id);

CREATE TABLE public_bin_requests (
    id bigint NOT NULL,
    bin_id uuid NOT NULL,
    method character varying(10) NOT NULL,
    query_string text,
    headers text,
    body text,
    body_truncated boolean DEFAULT false NOT NULL,
    size_bytes bigint NOT NULL,
    content_type character varying(255),
    source_ip character varying(45),
    received_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);

ALTER TABLE ONLY public_bin_requests ALTER COLUMN id SET DEFAULT nextval('public_bin_requests_id_seq'::regclass);

ALTER TABLE ONLY public_bin_requests
    ADD CONSTRAINT public_bin_requests_pkey PRIMARY KEY (id);

CREATE INDEX idx_public_bin_requests_bin ON public_bin_requests USING btree (bin_id, id DESC);

CREATE TABLE public_bins (
    id uuid NOT NULL,
    slug character varying(32) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    request_count bigint DEFAULT 0 NOT NULL,
    creator_ip character varying(45)
);

ALTER TABLE ONLY public_bins
    ADD CONSTRAINT public_bins_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public_bins
    ADD CONSTRAINT public_bins_slug_key UNIQUE (slug);

CREATE INDEX idx_public_bins_creator_ip ON public_bins USING btree (creator_ip, expires_at);

CREATE INDEX idx_public_bins_expires_at ON public_bins USING btree (expires_at);

CREATE TABLE replay_sessions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    project_id uuid NOT NULL,
    created_by uuid,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    from_date timestamp with time zone NOT NULL,
    to_date timestamp with time zone NOT NULL,
    event_type character varying(255),
    endpoint_id uuid,
    source_status character varying(20),
    total_events integer DEFAULT 0 NOT NULL,
    processed_events integer DEFAULT 0 NOT NULL,
    deliveries_created integer DEFAULT 0 NOT NULL,
    errors integer DEFAULT 0 NOT NULL,
    last_processed_event_id uuid,
    error_message text,
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    cancelled_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    version integer DEFAULT 0 NOT NULL,
    organization_id uuid NOT NULL,
    CONSTRAINT chk_replay_dates CHECK ((from_date < to_date)),
    CONSTRAINT chk_replay_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'ESTIMATING'::character varying, 'RUNNING'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying, 'CANCELLING'::character varying])::text[])))
);

ALTER TABLE ONLY replay_sessions
    ADD CONSTRAINT replay_sessions_pkey PRIMARY KEY (id);

CREATE INDEX idx_replay_sessions_created_at ON replay_sessions USING btree (created_at DESC);

CREATE INDEX idx_replay_sessions_project_id ON replay_sessions USING btree (project_id);

CREATE INDEX idx_replay_sessions_project_status ON replay_sessions USING btree (project_id, status);

CREATE INDEX idx_replay_sessions_status ON replay_sessions USING btree (status);

CREATE TABLE rule_actions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    rule_id uuid NOT NULL,
    type character varying(50) NOT NULL,
    endpoint_id uuid,
    transformation_id uuid,
    config jsonb DEFAULT '{}'::jsonb NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    organization_id uuid NOT NULL,
    CONSTRAINT chk_action_type CHECK (((type)::text = ANY ((ARRAY['ROUTE'::character varying, 'TRANSFORM'::character varying, 'DROP'::character varying, 'TAG'::character varying])::text[])))
);

ALTER TABLE ONLY rule_actions
    ADD CONSTRAINT rule_actions_pkey PRIMARY KEY (id);

CREATE INDEX idx_rule_actions_endpoint_id ON rule_actions USING btree (endpoint_id);

CREATE INDEX idx_rule_actions_rule_id ON rule_actions USING btree (rule_id);

CREATE TABLE rule_execution_log (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    rule_id uuid NOT NULL,
    project_id uuid NOT NULL,
    event_id uuid NOT NULL,
    matched boolean NOT NULL,
    actions_executed integer DEFAULT 0 NOT NULL,
    evaluation_time_ms integer,
    executed_at timestamp with time zone DEFAULT now() NOT NULL,
    organization_id uuid NOT NULL
);

ALTER TABLE ONLY rule_execution_log
    ADD CONSTRAINT rule_execution_log_pkey PRIMARY KEY (id);

CREATE INDEX idx_rule_exec_log_project_time ON rule_execution_log USING btree (project_id, executed_at DESC);

CREATE INDEX idx_rule_exec_log_rule_id ON rule_execution_log USING btree (rule_id, executed_at DESC);

CREATE TABLE rules (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    project_id uuid NOT NULL,
    name character varying(255) NOT NULL,
    description text,
    enabled boolean DEFAULT true NOT NULL,
    priority integer DEFAULT 0 NOT NULL,
    event_type_pattern character varying(255),
    conditions jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    organization_id uuid NOT NULL
);

ALTER TABLE ONLY rules
    ADD CONSTRAINT rules_pkey PRIMARY KEY (id);

ALTER TABLE ONLY rules
    ADD CONSTRAINT uq_rule_name_project UNIQUE (project_id, name);

CREATE INDEX idx_rules_event_type_pattern ON rules USING btree (project_id, event_type_pattern) WHERE (enabled = true);

CREATE INDEX idx_rules_project_enabled ON rules USING btree (project_id, enabled) WHERE (enabled = true);

CREATE INDEX idx_rules_project_id ON rules USING btree (project_id);

CREATE TABLE schema_change (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    event_type_id uuid NOT NULL,
    from_version_id uuid,
    to_version_id uuid NOT NULL,
    change_summary jsonb NOT NULL,
    breaking boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    organization_id uuid NOT NULL
);

ALTER TABLE ONLY schema_change
    ADD CONSTRAINT schema_change_pkey PRIMARY KEY (id);

CREATE INDEX idx_schema_change_type ON schema_change USING btree (event_type_id);

CREATE TABLE shared_debug_links (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    project_id uuid NOT NULL,
    event_id uuid NOT NULL,
    token character varying(64) NOT NULL,
    created_by uuid,
    expires_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    view_count integer DEFAULT 0 NOT NULL,
    organization_id uuid NOT NULL
);

ALTER TABLE ONLY shared_debug_links
    ADD CONSTRAINT shared_debug_links_pkey PRIMARY KEY (id);

ALTER TABLE ONLY shared_debug_links
    ADD CONSTRAINT shared_debug_links_token_key UNIQUE (token);

CREATE INDEX idx_shared_debug_links_event ON shared_debug_links USING btree (event_id);

CREATE INDEX idx_shared_debug_links_project ON shared_debug_links USING btree (project_id);

CREATE INDEX idx_shared_debug_links_token ON shared_debug_links USING btree (token);

CREATE TABLE shedlock (
    name character varying(64) NOT NULL,
    lock_until timestamp with time zone NOT NULL,
    locked_at timestamp with time zone NOT NULL,
    locked_by character varying(255) NOT NULL
);

ALTER TABLE ONLY shedlock
    ADD CONSTRAINT shedlock_pkey PRIMARY KEY (name);

CREATE INDEX idx_shedlock_lock_until ON shedlock USING btree (lock_until);

CREATE TABLE sign_in_handoffs (
    code_hash character varying(64) NOT NULL,
    user_id uuid NOT NULL,
    account_created boolean DEFAULT false NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    consumed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY sign_in_handoffs
    ADD CONSTRAINT sign_in_handoffs_pkey PRIMARY KEY (code_hash);

CREATE INDEX idx_sign_in_handoffs_expires_at ON sign_in_handoffs USING btree (expires_at);

CREATE TABLE subscriptions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    project_id uuid NOT NULL,
    endpoint_id uuid NOT NULL,
    event_type character varying(255) NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    ordering_enabled boolean DEFAULT false NOT NULL,
    max_attempts integer DEFAULT 7,
    timeout_seconds integer DEFAULT 30,
    retry_delays text DEFAULT '60,300,900,3600,21600,86400'::text,
    payload_template text,
    custom_headers text,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    transformation_id uuid,
    organization_id uuid NOT NULL,
    retryable_statuses text DEFAULT '408,429,500-599'::text NOT NULL
);

ALTER TABLE ONLY subscriptions
    ADD CONSTRAINT subscriptions_pkey PRIMARY KEY (id);

CREATE INDEX idx_subscriptions_enabled ON subscriptions USING btree (enabled) WHERE (enabled = true);

CREATE INDEX idx_subscriptions_endpoint_id ON subscriptions USING btree (endpoint_id);

CREATE INDEX idx_subscriptions_event_type ON subscriptions USING btree (event_type);

CREATE INDEX idx_subscriptions_project_exists ON subscriptions USING btree (project_id) WHERE (project_id IS NOT NULL);

CREATE INDEX idx_subscriptions_project_id ON subscriptions USING btree (project_id);

CREATE INDEX idx_subscriptions_transformation_id ON subscriptions USING btree (transformation_id);

CREATE UNIQUE INDEX idx_subscriptions_unique ON subscriptions USING btree (endpoint_id, event_type);

CREATE TABLE test_endpoints (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    project_id uuid NOT NULL,
    slug character varying(12) NOT NULL,
    name character varying(255),
    description text,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    request_count integer DEFAULT 0 NOT NULL,
    organization_id uuid NOT NULL
);

ALTER TABLE ONLY test_endpoints
    ADD CONSTRAINT test_endpoints_pkey PRIMARY KEY (id);

ALTER TABLE ONLY test_endpoints
    ADD CONSTRAINT test_endpoints_slug_key UNIQUE (slug);

CREATE INDEX idx_test_endpoints_expires ON test_endpoints USING btree (expires_at);

CREATE INDEX idx_test_endpoints_project ON test_endpoints USING btree (project_id);

CREATE INDEX idx_test_endpoints_slug ON test_endpoints USING btree (slug);

CREATE TABLE transformation_versions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    organization_id uuid NOT NULL,
    transformation_id uuid NOT NULL,
    version integer NOT NULL,
    template text NOT NULL,
    restored_from_version integer,
    created_by uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    kind character varying(16) DEFAULT 'TEMPLATE'::character varying NOT NULL
);

ALTER TABLE ONLY transformation_versions
    ADD CONSTRAINT transformation_versions_pkey PRIMARY KEY (id);

ALTER TABLE ONLY transformation_versions
    ADD CONSTRAINT uq_transformation_version UNIQUE (transformation_id, version);

CREATE INDEX idx_transformation_versions_history ON transformation_versions USING btree (transformation_id, version DESC);

CREATE TABLE transformations (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    project_id uuid NOT NULL,
    name character varying(255) NOT NULL,
    description text,
    template text NOT NULL,
    version integer DEFAULT 1 NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    organization_id uuid NOT NULL,
    kind character varying(16) DEFAULT 'TEMPLATE'::character varying NOT NULL
);

ALTER TABLE ONLY transformations
    ADD CONSTRAINT transformations_pkey PRIMARY KEY (id);

ALTER TABLE ONLY transformations
    ADD CONSTRAINT uq_transformation_name_project UNIQUE (project_id, name);

CREATE INDEX idx_transformations_project_id ON transformations USING btree (project_id);

CREATE TABLE tunnel_request_log (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tunnel_session_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    slug character varying(64) NOT NULL,
    request_id character varying(64) NOT NULL,
    method character varying(10) NOT NULL,
    path character varying(2048),
    query_string character varying(2048),
    request_headers jsonb,
    request_body_size integer DEFAULT 0,
    response_status integer,
    response_headers jsonb,
    response_body_size integer DEFAULT 0,
    duration_ms integer,
    error character varying(512),
    created_at timestamp with time zone DEFAULT now() NOT NULL
)
PARTITION BY RANGE (created_at);

ALTER TABLE tunnel_request_log
    ADD CONSTRAINT tunnel_request_log_pkey PRIMARY KEY (id, created_at);

CREATE INDEX idx_tunnel_req_log_created ON tunnel_request_log USING btree (created_at);

CREATE INDEX idx_tunnel_req_log_errors ON tunnel_request_log USING btree (slug, created_at) WHERE (error IS NOT NULL);

CREATE INDEX idx_tunnel_req_log_org ON tunnel_request_log USING btree (organization_id);

CREATE INDEX idx_tunnel_req_log_session ON tunnel_request_log USING btree (tunnel_session_id);

CREATE INDEX idx_tunnel_req_log_slug ON tunnel_request_log USING btree (slug);

CREATE TABLE tunnel_sessions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    organization_id uuid NOT NULL,
    user_id uuid NOT NULL,
    project_id uuid,
    tunnel_token character varying(128) NOT NULL,
    public_slug character varying(64) NOT NULL,
    local_port integer NOT NULL,
    status character varying(32) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    last_heartbeat timestamp with time zone,
    closed_at timestamp with time zone,
    client_info character varying(255)
);

ALTER TABLE ONLY tunnel_sessions
    ADD CONSTRAINT tunnel_sessions_pkey PRIMARY KEY (id);

ALTER TABLE ONLY tunnel_sessions
    ADD CONSTRAINT tunnel_sessions_public_slug_key UNIQUE (public_slug);

ALTER TABLE ONLY tunnel_sessions
    ADD CONSTRAINT tunnel_sessions_tunnel_token_key UNIQUE (tunnel_token);

CREATE INDEX idx_tunnel_sessions_org ON tunnel_sessions USING btree (organization_id);

CREATE INDEX idx_tunnel_sessions_org_status ON tunnel_sessions USING btree (organization_id, status);

CREATE INDEX idx_tunnel_sessions_slug ON tunnel_sessions USING btree (public_slug);

CREATE INDEX idx_tunnel_sessions_status ON tunnel_sessions USING btree (status) WHERE ((status)::text = 'ACTIVE'::text);

CREATE INDEX idx_tunnel_sessions_token ON tunnel_sessions USING btree (tunnel_token);

CREATE INDEX idx_tunnel_sessions_user ON tunnel_sessions USING btree (user_id);

CREATE TABLE usage_daily (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    project_id uuid NOT NULL,
    date date NOT NULL,
    events_count bigint DEFAULT 0 NOT NULL,
    deliveries_count bigint DEFAULT 0 NOT NULL,
    successful_deliveries bigint DEFAULT 0 NOT NULL,
    failed_deliveries bigint DEFAULT 0 NOT NULL,
    dlq_count bigint DEFAULT 0 NOT NULL,
    incoming_events_count bigint DEFAULT 0 NOT NULL,
    incoming_forwards_count bigint DEFAULT 0 NOT NULL,
    avg_latency_ms double precision,
    p95_latency_ms double precision,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    organization_id uuid NOT NULL
);

ALTER TABLE ONLY usage_daily
    ADD CONSTRAINT usage_daily_pkey PRIMARY KEY (id);

ALTER TABLE ONLY usage_daily
    ADD CONSTRAINT usage_daily_project_id_date_key UNIQUE (project_id, date);

CREATE INDEX idx_usage_daily_project_date ON usage_daily USING btree (project_id, date DESC);

CREATE TABLE user_identities (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    provider character varying(32) NOT NULL,
    subject character varying(255) NOT NULL,
    email character varying(255) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY user_identities
    ADD CONSTRAINT uq_user_identities_provider_subject UNIQUE (provider, subject);

ALTER TABLE ONLY user_identities
    ADD CONSTRAINT user_identities_pkey PRIMARY KEY (id);

CREATE INDEX idx_user_identities_user_id ON user_identities USING btree (user_id);

CREATE TABLE user_sessions (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    refresh_token_jti character varying(64) NOT NULL,
    client character varying(16) NOT NULL,
    user_agent character varying(512),
    ip_address character varying(45),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    last_seen_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    revoked_at timestamp with time zone
);

ALTER TABLE ONLY user_sessions
    ADD CONSTRAINT user_sessions_pkey PRIMARY KEY (id);

ALTER TABLE ONLY user_sessions
    ADD CONSTRAINT user_sessions_refresh_token_jti_key UNIQUE (refresh_token_jti);

CREATE INDEX idx_user_sessions_expires_at ON user_sessions USING btree (expires_at);

CREATE INDEX idx_user_sessions_user_active ON user_sessions USING btree (user_id, last_seen_at DESC) WHERE (revoked_at IS NULL);

CREATE TABLE users (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    email character varying(255) NOT NULL,
    password_hash character varying(255),
    status character varying(50) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    email_verified boolean DEFAULT false NOT NULL,
    verification_token character varying(64),
    verification_token_expires_at timestamp with time zone,
    password_reset_token character varying(64),
    password_reset_token_expires_at timestamp with time zone,
    full_name character varying(255),
    failed_login_attempts integer DEFAULT 0 NOT NULL,
    last_failed_login_at timestamp with time zone,
    lockout_expires_at timestamp with time zone,
    onboarding_welcome_sent_at timestamp with time zone,
    onboarding_nudge_sent_at timestamp with time zone
);

ALTER TABLE ONLY users
    ADD CONSTRAINT users_email_key UNIQUE (email);

ALTER TABLE ONLY users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);

CREATE INDEX idx_users_email ON users USING btree (email);

CREATE INDEX idx_users_onboarding_nudge_due ON users USING btree (onboarding_welcome_sent_at) WHERE (onboarding_nudge_sent_at IS NULL);

CREATE INDEX idx_users_password_reset_token ON users USING btree (password_reset_token);

CREATE INDEX idx_users_status ON users USING btree (status);

CREATE INDEX idx_users_verification_token ON users USING btree (verification_token);

CREATE UNIQUE INDEX uq_users_email_lower ON users USING btree (lower((email)::text));

CREATE TABLE verification_email_sends (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    reason character varying(32) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY verification_email_sends
    ADD CONSTRAINT verification_email_sends_pkey PRIMARY KEY (id);

CREATE INDEX idx_verification_email_sends_user_created ON verification_email_sends USING btree (user_id, created_at);

CREATE TABLE workflow_executions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    workflow_id uuid NOT NULL,
    trigger_event_id uuid,
    status character varying(50) DEFAULT 'RUNNING'::character varying NOT NULL,
    trigger_data jsonb,
    started_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    completed_at timestamp with time zone,
    error_message text,
    duration_ms integer,
    depth integer DEFAULT 0 NOT NULL,
    organization_id uuid NOT NULL,
    resume_at timestamp with time zone,
    resume_state jsonb,
    working_ms bigint
);

ALTER TABLE ONLY workflow_executions
    ADD CONSTRAINT workflow_executions_pkey PRIMARY KEY (id);

CREATE UNIQUE INDEX idx_wf_exec_idempotent ON workflow_executions USING btree (workflow_id, trigger_event_id) WHERE (trigger_event_id IS NOT NULL);

CREATE INDEX idx_wf_exec_resume_due ON workflow_executions USING btree (resume_at) WHERE ((status)::text = 'WAITING'::text);

CREATE INDEX idx_wf_exec_started ON workflow_executions USING btree (started_at DESC);

CREATE INDEX idx_wf_exec_status ON workflow_executions USING btree (status);

CREATE INDEX idx_wf_exec_stuck ON workflow_executions USING btree (status, started_at) WHERE ((status)::text = 'RUNNING'::text);

CREATE INDEX idx_wf_exec_workflow ON workflow_executions USING btree (workflow_id);

CREATE TABLE workflow_step_executions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    execution_id uuid NOT NULL,
    node_id character varying(100) NOT NULL,
    node_type character varying(50) NOT NULL,
    status character varying(50) DEFAULT 'PENDING'::character varying NOT NULL,
    input_data jsonb,
    output_data jsonb,
    error_message text,
    attempt_count integer DEFAULT 0 NOT NULL,
    duration_ms integer,
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    organization_id uuid NOT NULL
);

ALTER TABLE ONLY workflow_step_executions
    ADD CONSTRAINT workflow_step_executions_pkey PRIMARY KEY (id);

CREATE INDEX idx_wf_step_execution ON workflow_step_executions USING btree (execution_id);

CREATE INDEX idx_wf_step_node ON workflow_step_executions USING btree (execution_id, node_id);

CREATE TABLE workflow_trigger_outbox (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    project_id uuid NOT NULL,
    event_id uuid NOT NULL,
    event_type character varying(255) NOT NULL,
    event_payload text,
    depth integer DEFAULT 0 NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    error text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    processed_at timestamp with time zone,
    claimed_at timestamp with time zone
);

ALTER TABLE ONLY workflow_trigger_outbox
    ADD CONSTRAINT workflow_trigger_outbox_pkey PRIMARY KEY (id);

CREATE INDEX idx_wf_trigger_outbox_event ON workflow_trigger_outbox USING btree (event_id);

CREATE INDEX idx_wf_trigger_outbox_pending ON workflow_trigger_outbox USING btree (status, created_at) WHERE ((status)::text = 'PENDING'::text);

CREATE TABLE workflows (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    project_id uuid NOT NULL,
    name character varying(255) NOT NULL,
    description text,
    enabled boolean DEFAULT false NOT NULL,
    definition jsonb DEFAULT '{"edges": [], "nodes": []}'::jsonb NOT NULL,
    trigger_type character varying(50) DEFAULT 'WEBHOOK_EVENT'::character varying NOT NULL,
    trigger_config jsonb DEFAULT '{}'::jsonb NOT NULL,
    version integer DEFAULT 1 NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    organization_id uuid NOT NULL
);

ALTER TABLE ONLY workflows
    ADD CONSTRAINT workflows_pkey PRIMARY KEY (id);

ALTER TABLE ONLY workflows
    ADD CONSTRAINT workflows_project_id_name_key UNIQUE (project_id, name);

CREATE INDEX idx_workflows_enabled ON workflows USING btree (project_id, enabled) WHERE (enabled = true);

CREATE INDEX idx_workflows_project ON workflows USING btree (project_id);

CREATE INDEX idx_workflows_trigger ON workflows USING btree (trigger_type, enabled) WHERE (enabled = true);

ALTER SEQUENCE public_bin_requests_id_seq OWNED BY public_bin_requests.id;

ALTER TABLE ONLY alert_events
    ADD CONSTRAINT alert_events_alert_rule_id_fkey FOREIGN KEY (alert_rule_id) REFERENCES alert_rules(id) ON DELETE CASCADE;

ALTER TABLE ONLY alert_events
    ADD CONSTRAINT alert_events_project_id_fkey FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE;

ALTER TABLE ONLY alert_rules
    ADD CONSTRAINT alert_rules_endpoint_id_fkey FOREIGN KEY (endpoint_id) REFERENCES endpoints(id) ON DELETE CASCADE;

ALTER TABLE ONLY alert_rules
    ADD CONSTRAINT alert_rules_project_id_fkey FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE;

ALTER TABLE ONLY api_keys
    ADD CONSTRAINT api_keys_project_id_fkey FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE;

ALTER TABLE ONLY api_keys
    ADD CONSTRAINT api_keys_replaced_by_id_fkey FOREIGN KEY (replaced_by_id) REFERENCES api_keys(id) ON DELETE SET NULL;

ALTER TABLE ONLY billing_invoices
    ADD CONSTRAINT billing_invoices_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE;

ALTER TABLE ONLY billing_invoices
    ADD CONSTRAINT billing_invoices_subscription_id_fkey FOREIGN KEY (subscription_id) REFERENCES billing_subscriptions(id) ON DELETE SET NULL;

ALTER TABLE ONLY billing_payments
    ADD CONSTRAINT billing_payments_invoice_id_fkey FOREIGN KEY (invoice_id) REFERENCES billing_invoices(id) ON DELETE SET NULL;

ALTER TABLE ONLY billing_payments
    ADD CONSTRAINT billing_payments_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE;

ALTER TABLE ONLY billing_payments
    ADD CONSTRAINT billing_payments_subscription_id_fkey FOREIGN KEY (subscription_id) REFERENCES billing_subscriptions(id) ON DELETE SET NULL;

ALTER TABLE ONLY billing_scheduled_changes
    ADD CONSTRAINT billing_scheduled_changes_from_plan_id_fkey FOREIGN KEY (from_plan_id) REFERENCES plans(id);

ALTER TABLE ONLY billing_scheduled_changes
    ADD CONSTRAINT billing_scheduled_changes_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE;

ALTER TABLE ONLY billing_scheduled_changes
    ADD CONSTRAINT billing_scheduled_changes_subscription_id_fkey FOREIGN KEY (subscription_id) REFERENCES billing_subscriptions(id) ON DELETE CASCADE;

ALTER TABLE ONLY billing_scheduled_changes
    ADD CONSTRAINT billing_scheduled_changes_to_plan_id_fkey FOREIGN KEY (to_plan_id) REFERENCES plans(id);

ALTER TABLE ONLY billing_subscription_events
    ADD CONSTRAINT billing_subscription_events_from_plan_id_fkey FOREIGN KEY (from_plan_id) REFERENCES plans(id);

ALTER TABLE ONLY billing_subscription_events
    ADD CONSTRAINT billing_subscription_events_subscription_id_fkey FOREIGN KEY (subscription_id) REFERENCES billing_subscriptions(id) ON DELETE CASCADE;

ALTER TABLE ONLY billing_subscription_events
    ADD CONSTRAINT billing_subscription_events_to_plan_id_fkey FOREIGN KEY (to_plan_id) REFERENCES plans(id);

ALTER TABLE ONLY billing_subscriptions
    ADD CONSTRAINT billing_subscriptions_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE;

ALTER TABLE ONLY billing_subscriptions
    ADD CONSTRAINT billing_subscriptions_plan_id_fkey FOREIGN KEY (plan_id) REFERENCES plans(id);

ALTER TABLE ONLY captured_requests
    ADD CONSTRAINT captured_requests_test_endpoint_id_fkey FOREIGN KEY (test_endpoint_id) REFERENCES test_endpoints(id) ON DELETE CASCADE;

ALTER TABLE ONLY consumers
    ADD CONSTRAINT consumers_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE;

ALTER TABLE ONLY consumers
    ADD CONSTRAINT consumers_project_id_fkey FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE;

ALTER TABLE ONLY deliveries
    ADD CONSTRAINT deliveries_endpoint_id_fkey FOREIGN KEY (endpoint_id) REFERENCES endpoints(id) ON DELETE CASCADE;

ALTER TABLE ONLY deliveries
    ADD CONSTRAINT deliveries_event_id_fkey FOREIGN KEY (event_id) REFERENCES events(id) ON DELETE CASCADE;

ALTER TABLE ONLY deliveries
    ADD CONSTRAINT deliveries_replay_session_id_fkey FOREIGN KEY (replay_session_id) REFERENCES replay_sessions(id);

ALTER TABLE ONLY deliveries
    ADD CONSTRAINT deliveries_subscription_id_fkey FOREIGN KEY (subscription_id) REFERENCES subscriptions(id) ON DELETE CASCADE;

ALTER TABLE ONLY email_change_requests
    ADD CONSTRAINT email_change_requests_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE ONLY endpoints
    ADD CONSTRAINT endpoints_consumer_id_fkey FOREIGN KEY (consumer_id) REFERENCES consumers(id) ON DELETE SET NULL;

ALTER TABLE ONLY endpoints
    ADD CONSTRAINT endpoints_project_id_fkey FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE;

ALTER TABLE ONLY event_schema_version
    ADD CONSTRAINT event_schema_version_event_type_id_fkey FOREIGN KEY (event_type_id) REFERENCES event_type_catalog(id) ON DELETE CASCADE;

ALTER TABLE ONLY event_type_catalog
    ADD CONSTRAINT event_type_catalog_project_id_fkey FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE;

ALTER TABLE ONLY events
    ADD CONSTRAINT events_project_id_fkey FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE;

ALTER TABLE delivery_attempts
    ADD CONSTRAINT fk_delivery_attempts_delivery FOREIGN KEY (delivery_id) REFERENCES deliveries(id) ON DELETE CASCADE;

ALTER TABLE ONLY workflow_trigger_outbox
    ADD CONSTRAINT fk_wf_trigger_event FOREIGN KEY (event_id) REFERENCES events(id) ON DELETE CASCADE;

ALTER TABLE ONLY incident_timeline
    ADD CONSTRAINT incident_timeline_delivery_id_fkey FOREIGN KEY (delivery_id) REFERENCES deliveries(id) ON DELETE SET NULL;

ALTER TABLE ONLY incident_timeline
    ADD CONSTRAINT incident_timeline_endpoint_id_fkey FOREIGN KEY (endpoint_id) REFERENCES endpoints(id) ON DELETE SET NULL;

ALTER TABLE ONLY incident_timeline
    ADD CONSTRAINT incident_timeline_incident_id_fkey FOREIGN KEY (incident_id) REFERENCES incidents(id) ON DELETE CASCADE;

ALTER TABLE ONLY incidents
    ADD CONSTRAINT incidents_project_id_fkey FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE;

ALTER TABLE ONLY incoming_destinations
    ADD CONSTRAINT incoming_destinations_incoming_source_id_fkey FOREIGN KEY (incoming_source_id) REFERENCES incoming_sources(id) ON DELETE CASCADE;

ALTER TABLE ONLY incoming_destinations
    ADD CONSTRAINT incoming_destinations_transformation_id_fkey FOREIGN KEY (transformation_id) REFERENCES transformations(id) ON DELETE SET NULL;

ALTER TABLE ONLY incoming_events
    ADD CONSTRAINT incoming_events_incoming_source_id_fkey FOREIGN KEY (incoming_source_id) REFERENCES incoming_sources(id) ON DELETE CASCADE;

ALTER TABLE ONLY incoming_forward_attempts
    ADD CONSTRAINT incoming_forward_attempts_destination_id_fkey FOREIGN KEY (destination_id) REFERENCES incoming_destinations(id) ON DELETE CASCADE;

ALTER TABLE ONLY incoming_forward_attempts
    ADD CONSTRAINT incoming_forward_attempts_incoming_event_id_fkey FOREIGN KEY (incoming_event_id) REFERENCES incoming_events(id) ON DELETE CASCADE;

ALTER TABLE ONLY incoming_sources
    ADD CONSTRAINT incoming_sources_project_id_fkey FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE;

ALTER TABLE ONLY memberships
    ADD CONSTRAINT memberships_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE;

ALTER TABLE ONLY memberships
    ADD CONSTRAINT memberships_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE ONLY oauth_authorization_requests
    ADD CONSTRAINT oauth_authorization_requests_client_id_fkey FOREIGN KEY (client_id) REFERENCES oauth_clients(id) ON DELETE CASCADE;

ALTER TABLE ONLY oauth_grants
    ADD CONSTRAINT oauth_grants_client_id_fkey FOREIGN KEY (client_id) REFERENCES oauth_clients(id) ON DELETE CASCADE;

ALTER TABLE ONLY oauth_grants
    ADD CONSTRAINT oauth_grants_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE;

ALTER TABLE ONLY oauth_grants
    ADD CONSTRAINT oauth_grants_project_id_fkey FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE;

ALTER TABLE ONLY oauth_grants
    ADD CONSTRAINT oauth_grants_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE ONLY ordering_cursors
    ADD CONSTRAINT ordering_cursors_endpoint_id_fkey FOREIGN KEY (endpoint_id) REFERENCES endpoints(id) ON DELETE CASCADE;

ALTER TABLE ONLY organizations
    ADD CONSTRAINT organizations_plan_id_fkey FOREIGN KEY (plan_id) REFERENCES plans(id);

ALTER TABLE ONLY pii_masking_rules
    ADD CONSTRAINT pii_masking_rules_project_id_fkey FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE;

ALTER TABLE ONLY portal_sessions
    ADD CONSTRAINT portal_sessions_consumer_id_fkey FOREIGN KEY (consumer_id) REFERENCES consumers(id) ON DELETE CASCADE;

ALTER TABLE ONLY portal_sessions
    ADD CONSTRAINT portal_sessions_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE;

ALTER TABLE ONLY portal_sessions
    ADD CONSTRAINT portal_sessions_project_id_fkey FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE;

ALTER TABLE ONLY projects
    ADD CONSTRAINT projects_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE;

ALTER TABLE ONLY public_bin_requests
    ADD CONSTRAINT public_bin_requests_bin_id_fkey FOREIGN KEY (bin_id) REFERENCES public_bins(id) ON DELETE CASCADE;

ALTER TABLE ONLY replay_sessions
    ADD CONSTRAINT replay_sessions_endpoint_id_fkey FOREIGN KEY (endpoint_id) REFERENCES endpoints(id);

ALTER TABLE ONLY replay_sessions
    ADD CONSTRAINT replay_sessions_project_id_fkey FOREIGN KEY (project_id) REFERENCES projects(id);

ALTER TABLE ONLY rule_actions
    ADD CONSTRAINT rule_actions_endpoint_id_fkey FOREIGN KEY (endpoint_id) REFERENCES endpoints(id) ON DELETE CASCADE;

ALTER TABLE ONLY rule_actions
    ADD CONSTRAINT rule_actions_rule_id_fkey FOREIGN KEY (rule_id) REFERENCES rules(id) ON DELETE CASCADE;

ALTER TABLE ONLY rule_actions
    ADD CONSTRAINT rule_actions_transformation_id_fkey FOREIGN KEY (transformation_id) REFERENCES transformations(id) ON DELETE SET NULL;

ALTER TABLE ONLY rules
    ADD CONSTRAINT rules_project_id_fkey FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE;

ALTER TABLE ONLY schema_change
    ADD CONSTRAINT schema_change_event_type_id_fkey FOREIGN KEY (event_type_id) REFERENCES event_type_catalog(id) ON DELETE CASCADE;

ALTER TABLE ONLY schema_change
    ADD CONSTRAINT schema_change_from_version_id_fkey FOREIGN KEY (from_version_id) REFERENCES event_schema_version(id) ON DELETE SET NULL;

ALTER TABLE ONLY schema_change
    ADD CONSTRAINT schema_change_to_version_id_fkey FOREIGN KEY (to_version_id) REFERENCES event_schema_version(id) ON DELETE CASCADE;

ALTER TABLE ONLY shared_debug_links
    ADD CONSTRAINT shared_debug_links_created_by_fkey FOREIGN KEY (created_by) REFERENCES users(id);

ALTER TABLE ONLY shared_debug_links
    ADD CONSTRAINT shared_debug_links_event_id_fkey FOREIGN KEY (event_id) REFERENCES events(id) ON DELETE CASCADE;

ALTER TABLE ONLY shared_debug_links
    ADD CONSTRAINT shared_debug_links_project_id_fkey FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE;

ALTER TABLE ONLY sign_in_handoffs
    ADD CONSTRAINT sign_in_handoffs_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE ONLY subscriptions
    ADD CONSTRAINT subscriptions_endpoint_id_fkey FOREIGN KEY (endpoint_id) REFERENCES endpoints(id) ON DELETE CASCADE;

ALTER TABLE ONLY subscriptions
    ADD CONSTRAINT subscriptions_project_id_fkey FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE;

ALTER TABLE ONLY subscriptions
    ADD CONSTRAINT subscriptions_transformation_id_fkey FOREIGN KEY (transformation_id) REFERENCES transformations(id) ON DELETE SET NULL;

ALTER TABLE ONLY test_endpoints
    ADD CONSTRAINT test_endpoints_project_id_fkey FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE;

ALTER TABLE ONLY transformation_versions
    ADD CONSTRAINT transformation_versions_created_by_fkey FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE ONLY transformation_versions
    ADD CONSTRAINT transformation_versions_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE;

ALTER TABLE ONLY transformation_versions
    ADD CONSTRAINT transformation_versions_transformation_id_fkey FOREIGN KEY (transformation_id) REFERENCES transformations(id) ON DELETE CASCADE;

ALTER TABLE ONLY transformations
    ADD CONSTRAINT transformations_project_id_fkey FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE;

ALTER TABLE ONLY usage_daily
    ADD CONSTRAINT usage_daily_project_id_fkey FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE;

ALTER TABLE ONLY user_identities
    ADD CONSTRAINT user_identities_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE ONLY user_sessions
    ADD CONSTRAINT user_sessions_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE;

ALTER TABLE ONLY user_sessions
    ADD CONSTRAINT user_sessions_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE ONLY verification_email_sends
    ADD CONSTRAINT verification_email_sends_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE ONLY workflow_executions
    ADD CONSTRAINT workflow_executions_workflow_id_fkey FOREIGN KEY (workflow_id) REFERENCES workflows(id) ON DELETE CASCADE;

ALTER TABLE ONLY workflow_step_executions
    ADD CONSTRAINT workflow_step_executions_execution_id_fkey FOREIGN KEY (execution_id) REFERENCES workflow_executions(id) ON DELETE CASCADE;

ALTER TABLE ONLY workflows
    ADD CONSTRAINT workflows_project_id_fkey FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE;

CREATE MATERIALIZED VIEW mv_delivery_stats AS
 SELECT e.organization_id,
    e.project_id,
    (d.status)::text AS status,
    count(*) AS cnt,
    date_trunc('day'::text, d.created_at, 'UTC'::text) AS day
   FROM (deliveries d
     JOIN events e ON ((d.event_id = e.id)))
  WHERE (d.created_at > (now() - '30 days'::interval))
  GROUP BY e.organization_id, e.project_id, d.status, (date_trunc('day'::text, d.created_at, 'UTC'::text))
  WITH DATA;

CREATE MATERIALIZED VIEW mv_incoming_stats AS
 SELECT s.organization_id,
    s.project_id,
    count(e.id) AS event_count,
    date_trunc('day'::text, e.received_at, 'UTC'::text) AS day
   FROM (incoming_events e
     JOIN incoming_sources s ON ((e.incoming_source_id = s.id)))
  WHERE (e.received_at > (now() - '30 days'::interval))
  GROUP BY s.organization_id, s.project_id, (date_trunc('day'::text, e.received_at, 'UTC'::text))
  WITH DATA;

CREATE UNIQUE INDEX idx_mv_delivery_stats ON mv_delivery_stats USING btree (project_id, status, day);

CREATE INDEX idx_mv_delivery_stats_day ON mv_delivery_stats USING btree (day DESC);

CREATE INDEX idx_mv_delivery_stats_org ON mv_delivery_stats USING btree (organization_id, project_id);

CREATE UNIQUE INDEX idx_mv_incoming_stats ON mv_incoming_stats USING btree (project_id, day);

CREATE INDEX idx_mv_incoming_stats_day ON mv_incoming_stats USING btree (day DESC);

CREATE INDEX idx_mv_incoming_stats_org ON mv_incoming_stats USING btree (organization_id, project_id);

DO $$
DECLARE
    month_start timestamp := date_trunc('month', now() AT TIME ZONE 'UTC');
    week_start timestamp := date_trunc('week', now() AT TIME ZONE 'UTC');
    m timestamp;
    w timestamp;
BEGIN
    FOR i IN 0..3 LOOP
        m := month_start + make_interval(months => i);
        EXECUTE format('CREATE TABLE delivery_attempts_y%s_m%s PARTITION OF delivery_attempts FOR VALUES FROM (%L) TO (%L)',
            to_char(m, 'YYYY'), to_char(m, 'MM'), m AT TIME ZONE 'UTC', (m + interval '1 month') AT TIME ZONE 'UTC');
    END LOOP;
    FOR i IN 0..3 LOOP
        w := week_start + make_interval(weeks => i);
        EXECUTE format('CREATE TABLE tunnel_request_log_y%s_w%s PARTITION OF tunnel_request_log FOR VALUES FROM (%L) TO (%L)',
            to_char(w, 'IYYY'), to_char(w, 'IW'), w AT TIME ZONE 'UTC', (w + interval '1 week') AT TIME ZONE 'UTC');
    END LOOP;
END $$;

CREATE TABLE delivery_attempts_default PARTITION OF delivery_attempts DEFAULT;
CREATE TABLE tunnel_request_log_default PARTITION OF tunnel_request_log DEFAULT;

INSERT INTO plans (id, name, display_name, max_events_per_month, max_endpoints_per_project, max_projects, max_members, rate_limit_per_second, max_retention_days, features, price_monthly_cents, is_active, created_at, price_yearly_cents, max_active_tunnels, max_fanout_per_event) VALUES ('130ec8ea-7b78-4bcc-821c-d67455329969', 'starter', 'Starter', 100000, 20, 10, 10, 50, 30, '{"mTLS": false, "rules": true, "replay": true, "tunnels": true, "workflows": true}', 2900, true, '2026-09-30 11:28:37.167857', 29000, 3, 100);
INSERT INTO plans (id, name, display_name, max_events_per_month, max_endpoints_per_project, max_projects, max_members, rate_limit_per_second, max_retention_days, features, price_monthly_cents, is_active, created_at, price_yearly_cents, max_active_tunnels, max_fanout_per_event) VALUES ('5e9d49c4-a780-44b9-a90b-5a26306c7da3', 'self_hosted', 'Self-Hosted', -1, -1, -1, -1, 10000, -1, '{"mTLS": true, "rules": true, "replay": true, "tunnels": true, "workflows": true}', 0, true, '2026-09-30 11:28:37.167857', 0, -1, 100);
INSERT INTO plans (id, name, display_name, max_events_per_month, max_endpoints_per_project, max_projects, max_members, rate_limit_per_second, max_retention_days, features, price_monthly_cents, is_active, created_at, price_yearly_cents, max_active_tunnels, max_fanout_per_event) VALUES ('02e2cceb-9f28-4342-9d6b-cfc9686cb495', 'enterprise', 'Enterprise', -1, -1, -1, -1, 1000, 365, '{"mTLS": true, "rules": true, "replay": true, "tunnels": true, "workflows": true}', -1, true, '2026-09-30 11:28:37.167857', -1, -1, 500);
INSERT INTO plans (id, name, display_name, max_events_per_month, max_endpoints_per_project, max_projects, max_members, rate_limit_per_second, max_retention_days, features, price_monthly_cents, is_active, created_at, price_yearly_cents, max_active_tunnels, max_fanout_per_event) VALUES ('33ec3644-758d-4d08-b64f-31e09f61c8bd', 'pro', 'Pro', 1000000, 100, 50, 50, 200, 90, '{"mTLS": true, "rules": true, "replay": true, "tunnels": true, "workflows": true}', 9900, true, '2026-09-30 11:28:37.167857', 99000, 10, 250);
INSERT INTO plans (id, name, display_name, max_events_per_month, max_endpoints_per_project, max_projects, max_members, rate_limit_per_second, max_retention_days, features, price_monthly_cents, is_active, created_at, price_yearly_cents, max_active_tunnels, max_fanout_per_event) VALUES ('0da37227-5980-4554-ab02-0d471813155f', 'free', 'Free', 10000, 5, 3, 5, 10, 7, '{"mTLS": true, "rules": true, "replay": true, "tunnels": true, "workflows": true}', 0, true, '2026-09-30 11:28:37.167857', 0, 1, 100);
