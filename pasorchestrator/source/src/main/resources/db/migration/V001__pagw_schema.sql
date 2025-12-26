-- ============================================================================
-- PAGW Database Schema - Consolidated Migration
-- Version: 1.0.0
-- Description: Complete schema for Prior Authorization Gateway
-- Date: 2025-12-26
-- ============================================================================

-- Create schema
CREATE SCHEMA IF NOT EXISTS pagw;

-- ============================================================================
-- SECTION 1: ENUM TYPES
-- ============================================================================

-- Request status lifecycle
CREATE TYPE pagw.request_status_enum AS ENUM (
    'RECEIVED', 'PARSING', 'PARSING_FAILED', 'VALIDATING', 'VALIDATION_FAILED',
    'ENRICHING', 'CONVERTING', 'SUBMITTING', 'PENDING', 'APPROVED', 'DENIED',
    'PENDED', 'FAILED', 'CANCELLED', 'COMPLETED'
);

-- Event types for tracking
CREATE TYPE pagw.event_type_enum AS ENUM (
    'STAGE_STARTED', 'STAGE_COMPLETED', 'STAGE_FAILED', 'STAGE_SKIPPED',
    'PARSE_STARTED', 'PARSE_OK', 'PARSE_FAIL',
    'VAL_STARTED', 'VAL_OK', 'VAL_FAIL', 'VAL_WARN',
    'AUTHN_STARTED', 'AUTHN_OK', 'AUTHN_FAIL', 'AUTHZ_OK', 'AUTHZ_FAIL',
    'ENRICH_STARTED', 'ENRICH_OK', 'ENRICH_FAIL',
    'CONVERT_STARTED', 'CONVERT_OK', 'CONVERT_FAIL',
    'SUBMIT_STARTED', 'SUBMIT_OK', 'SUBMIT_FAIL', 'SUBMIT_RETRY',
    'RESPONSE_RECEIVED', 'RESPONSE_PARSED', 'RESPONSE_BUILT',
    'ATTACH_REQUESTED', 'ATTACH_UPLOADED', 'ATTACH_VALIDATED', 'ATTACH_FAILED',
    'CALLBACK_SENT', 'CALLBACK_ACK', 'CALLBACK_FAIL',
    'SUBSCRIPTION_CREATED', 'SUBSCRIPTION_UPDATED', 'SUBSCRIPTION_DELETED', 'NOTIFICATION_SENT',
    'PROVIDER_AUTH_FAILED', 'FHIR_VALIDATION_FAILED', 'PAYER_TIMEOUT', 'PAYER_ERROR',
    'IDEMPOTENCY_HIT', 'DUPLICATE_REQUEST', 'WORKFLOW_DECISION', 'ROUTING_DECISION', 'RATE_LIMIT_EXCEEDED'
);

-- Execution status for events
CREATE TYPE pagw.execution_status_enum AS ENUM (
    'SUCCESS', 'FAILURE', 'ERROR', 'TIMEOUT', 'SKIPPED', 'IN_PROGRESS'
);

-- Attachment source types
CREATE TYPE pagw.attachment_source_enum AS ENUM (
    'INLINE', 'CDEX_PULL', 'PRESIGNED_UPLOAD', 'EXTERNAL_REFERENCE'
);

-- Attachment fetch status
CREATE TYPE pagw.fetch_status_enum AS ENUM (
    'PENDING', 'IN_PROGRESS', 'SUCCESS', 'FAILED', 'CANCELLED', 'NOT_REQUIRED'
);

-- ============================================================================
-- SECTION 2: TRIGGER FUNCTION
-- ============================================================================

CREATE OR REPLACE FUNCTION pagw.update_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ============================================================================
-- SECTION 3: REQUEST_TRACKER - Core table tracking PA request lifecycle
-- ============================================================================
CREATE TABLE IF NOT EXISTS pagw.request_tracker (
    pagw_id                 VARCHAR(50) PRIMARY KEY,
    tenant                  VARCHAR(100),
    source_system           VARCHAR(50),
    request_type            VARCHAR(20) NOT NULL DEFAULT 'submit',
    idempotency_key         VARCHAR(255),
    correlation_id          VARCHAR(100),
    
    -- Processing status
    status                  pagw.request_status_enum NOT NULL DEFAULT 'RECEIVED',
    last_stage              VARCHAR(50),
    next_stage              VARCHAR(50),
    workflow_id             VARCHAR(100),
    workflow_version        VARCHAR(50),
    scenario_id             VARCHAR(100),
    ruleset_version         VARCHAR(50),
    workflow_hash           VARCHAR(64),
    last_error_code         VARCHAR(50),
    last_error_msg          TEXT,
    retry_count             INT DEFAULT 0,
    
    -- S3 storage paths
    raw_s3_bucket           VARCHAR(255),
    raw_s3_key              VARCHAR(500),
    enriched_s3_bucket      VARCHAR(255),
    enriched_s3_key         VARCHAR(500),
    final_s3_bucket         VARCHAR(255),
    final_s3_key            VARCHAR(500),
    
    -- External references
    external_request_id     VARCHAR(100),
    external_reference_id   VARCHAR(100),
    payer_id                VARCHAR(50),
    provider_npi            VARCHAR(20),
    patient_member_id       VARCHAR(100),
    client_id               VARCHAR(255),
    member_id               VARCHAR(100),
    provider_id             VARCHAR(100),
    
    -- PHI handling
    contains_phi            BOOLEAN DEFAULT true,
    phi_encrypted           BOOLEAN DEFAULT false,
    
    -- Sync processing flags
    sync_processed          BOOLEAN DEFAULT false,
    sync_processed_at       TIMESTAMP WITH TIME ZONE,
    async_queued            BOOLEAN DEFAULT false,
    async_queued_at         TIMESTAMP WITH TIME ZONE,
    
    -- FHIR extracted data
    diagnosis_codes         JSONB,
    metadata                JSONB DEFAULT '{}'::jsonb,
    
    -- Timestamps
    received_at             TIMESTAMP WITH TIME ZONE,
    completed_at            TIMESTAMP WITH TIME ZONE,
    callback_sent_at        TIMESTAMP WITH TIME ZONE,
    expires_at              TIMESTAMP WITH TIME ZONE,
    created_at              TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Request tracker indexes
CREATE INDEX IF NOT EXISTS idx_request_tracker_status ON pagw.request_tracker(status);
CREATE INDEX IF NOT EXISTS idx_request_tracker_tenant ON pagw.request_tracker(tenant);
CREATE INDEX IF NOT EXISTS idx_request_tracker_idempotency ON pagw.request_tracker(idempotency_key);
CREATE INDEX IF NOT EXISTS idx_request_tracker_external_id ON pagw.request_tracker(external_request_id);
CREATE INDEX IF NOT EXISTS idx_request_tracker_patient ON pagw.request_tracker(patient_member_id);
CREATE INDEX IF NOT EXISTS idx_request_tracker_created ON pagw.request_tracker(created_at);
CREATE INDEX IF NOT EXISTS idx_request_tracker_received ON pagw.request_tracker(received_at);
CREATE INDEX IF NOT EXISTS idx_request_tracker_client_id ON pagw.request_tracker(client_id);
CREATE INDEX IF NOT EXISTS idx_request_tracker_provider_npi ON pagw.request_tracker(provider_npi) WHERE provider_npi IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_request_tracker_payer_id ON pagw.request_tracker(payer_id) WHERE payer_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_request_tracker_correlation_id ON pagw.request_tracker(correlation_id) WHERE correlation_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_request_tracker_expires_at ON pagw.request_tracker(expires_at, status) WHERE expires_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_request_tracker_provider_payer ON pagw.request_tracker(provider_npi, payer_id, status) WHERE provider_npi IS NOT NULL AND payer_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_request_tracker_workflow ON pagw.request_tracker(workflow_id, workflow_version, received_at DESC);
CREATE INDEX IF NOT EXISTS idx_request_tracker_scenario ON pagw.request_tracker(scenario_id, received_at DESC) WHERE scenario_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_request_tracker_tenant_status_updated ON pagw.request_tracker(tenant, status, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_request_tracker_tenant_client_created ON pagw.request_tracker(tenant, client_id, received_at DESC) WHERE client_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_request_tracker_tenant_payer_status ON pagw.request_tracker(tenant, payer_id, status) WHERE payer_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_request_tracker_diagnosis_codes ON pagw.request_tracker USING GIN (diagnosis_codes);

DROP TRIGGER IF EXISTS request_tracker_updated_at ON pagw.request_tracker;
CREATE TRIGGER request_tracker_updated_at BEFORE UPDATE ON pagw.request_tracker
    FOR EACH ROW EXECUTE FUNCTION pagw.update_updated_at();

-- ============================================================================
-- SECTION 4: OAUTH_PROVIDER_REGISTRY - OAuth client registration
-- ============================================================================
CREATE TABLE IF NOT EXISTS pagw.oauth_provider_registry (
    client_id               VARCHAR(255) PRIMARY KEY,
    tenant                  VARCHAR(50) NOT NULL,
    entity_name             VARCHAR(255) NOT NULL,
    entity_type             VARCHAR(50) NOT NULL,
    npis                    TEXT[],
    tins                    TEXT[],
    status                  VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    allowed_apis            TEXT[] NOT NULL DEFAULT '{}',
    scopes                  TEXT[],
    rate_limit_per_minute   INT DEFAULT 100,
    issuer_url              VARCHAR(500),
    jwks_url                VARCHAR(500),
    cdex_callback_url       VARCHAR(500),
    subscription_webhook_url VARCHAR(500),
    business_contact_name   VARCHAR(255),
    business_contact_email  VARCHAR(255),
    technical_contact_name  VARCHAR(255),
    technical_contact_email VARCHAR(255),
    security_contact_email  VARCHAR(255),
    environment             VARCHAR(20) DEFAULT 'dev',
    registered_at           TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    last_auth_at            TIMESTAMP WITH TIME ZONE,
    updated_at              TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    notes                   TEXT
);

CREATE INDEX IF NOT EXISTS idx_oauth_provider_tenant ON pagw.oauth_provider_registry(tenant);
CREATE INDEX IF NOT EXISTS idx_oauth_provider_status ON pagw.oauth_provider_registry(status) WHERE status = 'ACTIVE';
CREATE INDEX IF NOT EXISTS idx_oauth_provider_environment ON pagw.oauth_provider_registry(environment);
CREATE INDEX IF NOT EXISTS idx_oauth_provider_npis ON pagw.oauth_provider_registry USING GIN(npis);
CREATE INDEX IF NOT EXISTS idx_oauth_provider_tins ON pagw.oauth_provider_registry USING GIN(tins);

DROP TRIGGER IF EXISTS oauth_provider_registry_updated_at ON pagw.oauth_provider_registry;
CREATE TRIGGER oauth_provider_registry_updated_at BEFORE UPDATE ON pagw.oauth_provider_registry
    FOR EACH ROW EXECUTE FUNCTION pagw.update_updated_at();

-- Add FK from request_tracker to oauth_provider_registry
ALTER TABLE pagw.request_tracker
    ADD CONSTRAINT fk_request_tracker_oauth_provider
    FOREIGN KEY (client_id) REFERENCES pagw.oauth_provider_registry(client_id) ON DELETE SET NULL;

-- ============================================================================
-- SECTION 5: OUTBOX - Transactional outbox for reliable message publishing
-- ============================================================================
CREATE TABLE IF NOT EXISTS pagw.outbox (
    id                      UUID PRIMARY KEY,
    tenant                  VARCHAR(50) NOT NULL DEFAULT 'elevance',
    aggregate_type          VARCHAR(100) NOT NULL,
    aggregate_id            VARCHAR(100) NOT NULL,
    event_type              VARCHAR(100),
    destination_queue       VARCHAR(500) NOT NULL,
    destination             VARCHAR(100),
    destination_type        VARCHAR(20) DEFAULT 'SQS',
    payload                 JSONB NOT NULL,
    status                  VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count             INT DEFAULT 0,
    max_retries             INT DEFAULT 5,
    last_error              TEXT,
    created_at              TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    processed_at            TIMESTAMP WITH TIME ZONE,
    published_at            TIMESTAMP WITH TIME ZONE,
    next_retry_at           TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_outbox_pending ON pagw.outbox(status, created_at) WHERE status = 'PENDING';
CREATE INDEX IF NOT EXISTS idx_outbox_retry ON pagw.outbox(status, next_retry_at) WHERE status = 'PENDING' AND retry_count > 0;
CREATE INDEX IF NOT EXISTS idx_outbox_tenant_published ON pagw.outbox(tenant, published_at) WHERE published_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_outbox_destination ON pagw.outbox(destination, created_at DESC);

-- ============================================================================
-- SECTION 6: ATTACHMENT_TRACKER - Track attachment processing status
-- ============================================================================
CREATE TABLE IF NOT EXISTS pagw.attachment_tracker (
    id                      VARCHAR(100) PRIMARY KEY,
    pagw_id                 VARCHAR(50) NOT NULL REFERENCES pagw.request_tracker(pagw_id),
    tenant                  VARCHAR(50) NOT NULL DEFAULT 'elevance',
    attachment_id           VARCHAR(100) NOT NULL,
    attachment_type         VARCHAR(50),
    original_filename       VARCHAR(255),
    content_type            VARCHAR(100),
    size                    BIGINT,
    sequence                INTEGER,
    checksum                VARCHAR(64),
    s3_bucket               VARCHAR(255),
    s3_key                  VARCHAR(500),
    status                  VARCHAR(30) NOT NULL DEFAULT 'pending',
    error_message           TEXT,
    attachment_source       pagw.attachment_source_enum DEFAULT 'INLINE',
    fetch_status            pagw.fetch_status_enum DEFAULT 'NOT_REQUIRED',
    fetch_attempts          INT DEFAULT 0,
    fetch_last_error        TEXT,
    fetch_started_at        TIMESTAMP WITH TIME ZONE,
    fetch_completed_at      TIMESTAMP WITH TIME ZONE,
    created_at              TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    processed_at            TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_attachment_tracker_pagw ON pagw.attachment_tracker(pagw_id);
CREATE INDEX IF NOT EXISTS idx_attachment_tracker_status ON pagw.attachment_tracker(status);
CREATE INDEX IF NOT EXISTS idx_attachment_tracker_sequence ON pagw.attachment_tracker(pagw_id, sequence);
CREATE INDEX IF NOT EXISTS idx_attachment_tracker_tenant_pagw ON pagw.attachment_tracker(tenant, pagw_id);
CREATE INDEX IF NOT EXISTS idx_attachment_tracker_fetch_status ON pagw.attachment_tracker(fetch_status, fetch_started_at) WHERE fetch_status IN ('PENDING', 'IN_PROGRESS');
CREATE INDEX IF NOT EXISTS idx_attachment_tracker_checksum ON pagw.attachment_tracker(checksum) WHERE checksum IS NOT NULL;

-- ============================================================================
-- SECTION 7: PROVIDER_REGISTRY - Payer routing and configuration
-- ============================================================================
CREATE TABLE IF NOT EXISTS pagw.provider_registry (
    id                      BIGSERIAL PRIMARY KEY,
    provider_npi            VARCHAR(20) NOT NULL,
    provider_tax_id         VARCHAR(20),
    provider_name           VARCHAR(255),
    payer_id                VARCHAR(50) NOT NULL,
    payer_name              VARCHAR(255),
    endpoint_url            VARCHAR(500),
    api_version             VARCHAR(20),
    auth_type               VARCHAR(50),
    credentials_secret_name VARCHAR(255),
    supports_sync           BOOLEAN DEFAULT true,
    supports_attachments    BOOLEAN DEFAULT true,
    supports_subscriptions  BOOLEAN DEFAULT false,
    is_active               BOOLEAN DEFAULT true,
    created_at              TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT provider_registry_unique UNIQUE (provider_npi, payer_id)
);

CREATE INDEX IF NOT EXISTS idx_provider_registry_npi ON pagw.provider_registry(provider_npi);
CREATE INDEX IF NOT EXISTS idx_provider_registry_payer ON pagw.provider_registry(payer_id);

DROP TRIGGER IF EXISTS provider_registry_updated_at ON pagw.provider_registry;
CREATE TRIGGER provider_registry_updated_at BEFORE UPDATE ON pagw.provider_registry
    FOR EACH ROW EXECUTE FUNCTION pagw.update_updated_at();

-- ============================================================================
-- SECTION 8: PAYER_CONFIGURATION - Payer-specific settings
-- ============================================================================
CREATE TABLE IF NOT EXISTS pagw.payer_configuration (
    id                      BIGSERIAL PRIMARY KEY,
    payer_id                VARCHAR(50) NOT NULL UNIQUE,
    payer_name              VARCHAR(255),
    base_url                VARCHAR(500),
    auth_endpoint           VARCHAR(500),
    submit_endpoint         VARCHAR(500),
    inquiry_endpoint        VARCHAR(500),
    connection_timeout_ms   INT DEFAULT 5000,
    read_timeout_ms         INT DEFAULT 30000,
    max_retries             INT DEFAULT 3,
    retry_delay_ms          INT DEFAULT 1000,
    supports_x12            BOOLEAN DEFAULT true,
    supports_fhir           BOOLEAN DEFAULT true,
    requires_attachments    BOOLEAN DEFAULT false,
    is_active               BOOLEAN DEFAULT true,
    created_at              TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

DROP TRIGGER IF EXISTS payer_configuration_updated_at ON pagw.payer_configuration;
CREATE TRIGGER payer_configuration_updated_at BEFORE UPDATE ON pagw.payer_configuration
    FOR EACH ROW EXECUTE FUNCTION pagw.update_updated_at();

-- ============================================================================
-- SECTION 9: AUDIT_LOG - HIPAA-compliant audit trail (NO PHI)
-- ============================================================================
CREATE TABLE IF NOT EXISTS pagw.audit_log (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant                  VARCHAR(50) NOT NULL DEFAULT 'elevance',
    event_type              VARCHAR(50) NOT NULL,
    event_timestamp         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resource_id             VARCHAR(50),
    correlation_id          VARCHAR(100),
    event_source            VARCHAR(50) NOT NULL,
    actor_id                VARCHAR(100) NOT NULL,
    actor_type              VARCHAR(20) NOT NULL,
    actor_ip                VARCHAR(50),
    trace_id                VARCHAR(100),
    request_path            VARCHAR(500),
    action_description      TEXT,
    resource_type           VARCHAR(50) NOT NULL,
    outcome                 VARCHAR(20),
    error_code              VARCHAR(50),
    phi_accessed            BOOLEAN DEFAULT false NOT NULL,
    phi_fields_accessed     TEXT[],
    access_reason           VARCHAR(500),
    metadata                JSONB,
    created_at              TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_audit_log_timestamp ON pagw.audit_log(event_timestamp);
CREATE INDEX IF NOT EXISTS idx_audit_log_resource ON pagw.audit_log(resource_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_type ON pagw.audit_log(event_type);
CREATE INDEX IF NOT EXISTS idx_audit_log_correlation ON pagw.audit_log(correlation_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_actor ON pagw.audit_log(actor_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_phi ON pagw.audit_log(phi_accessed) WHERE phi_accessed = true;
CREATE INDEX IF NOT EXISTS idx_audit_log_tenant ON pagw.audit_log(tenant, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_tenant_actor_action ON pagw.audit_log(tenant, actor_id, action_description, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_tenant_resource ON pagw.audit_log(tenant, resource_type, resource_id, created_at DESC);

-- ============================================================================
-- SECTION 10: EVENT_TRACKER - Track processing events per stage
-- ============================================================================
CREATE TABLE IF NOT EXISTS pagw.event_tracker (
    id                      BIGSERIAL PRIMARY KEY,
    pagw_id                 VARCHAR(50) NOT NULL REFERENCES pagw.request_tracker(pagw_id),
    tenant                  VARCHAR(50) NOT NULL DEFAULT 'elevance',
    stage                   VARCHAR(50) NOT NULL,
    event_type              VARCHAR(50) NOT NULL,
    status                  VARCHAR(30) NOT NULL,
    execution_status        pagw.execution_status_enum DEFAULT 'SUCCESS',
    external_reference      VARCHAR(100),
    duration_ms             BIGINT,
    error_code              VARCHAR(50),
    error_message           TEXT,
    sequence_no             BIGINT,
    attempt                 INT NOT NULL DEFAULT 1,
    retryable               BOOLEAN NOT NULL DEFAULT true,
    next_retry_at           TIMESTAMP WITH TIME ZONE,
    worker_id               VARCHAR(100),
    pod_name                VARCHAR(100),
    metadata                JSONB DEFAULT '{}'::jsonb,
    op_outcome_s3_key       VARCHAR(500),
    started_at              TIMESTAMP WITH TIME ZONE,
    completed_at            TIMESTAMP WITH TIME ZONE,
    created_at              TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_event_tracker_sequence UNIQUE (pagw_id, sequence_no)
);

CREATE INDEX IF NOT EXISTS idx_event_tracker_pagw ON pagw.event_tracker(pagw_id);
CREATE INDEX IF NOT EXISTS idx_event_tracker_stage ON pagw.event_tracker(stage);
CREATE INDEX IF NOT EXISTS idx_event_tracker_tenant_pagw ON pagw.event_tracker(tenant, pagw_id, created_at);
CREATE INDEX IF NOT EXISTS idx_event_tracker_tenant_stage ON pagw.event_tracker(tenant, stage, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_event_tracker_tenant_event_type ON pagw.event_tracker(tenant, event_type, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_event_tracker_retry ON pagw.event_tracker(next_retry_at) WHERE retryable = true AND next_retry_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_event_tracker_worker ON pagw.event_tracker(worker_id, created_at DESC) WHERE worker_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_event_tracker_metadata ON pagw.event_tracker USING GIN (metadata);

-- ============================================================================
-- SECTION 11: IDEMPOTENCY - Prevent duplicate request processing
-- ============================================================================
CREATE TABLE IF NOT EXISTS pagw.idempotency (
    tenant                  VARCHAR(50) NOT NULL DEFAULT 'elevance',
    idempotency_key         VARCHAR(255) NOT NULL,
    pagw_id                 VARCHAR(50) NOT NULL,
    request_hash            VARCHAR(64),
    response_status         VARCHAR(30),
    response_body           JSONB,
    response_s3_key         VARCHAR(500),
    response_hash           VARCHAR(64),
    response_status_code    INT,
    response_summary        JSONB,
    created_at              TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    expires_at              TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (tenant, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_idempotency_expires ON pagw.idempotency(expires_at);
CREATE INDEX IF NOT EXISTS idx_idempotency_pagw ON pagw.idempotency(pagw_id);
CREATE INDEX IF NOT EXISTS idx_idempotency_hash ON pagw.idempotency(response_hash) WHERE response_hash IS NOT NULL;

-- ============================================================================
-- SECTION 12: SHEDLOCK - Distributed lock for scheduled tasks
-- ============================================================================
CREATE TABLE IF NOT EXISTS pagw.shedlock (
    name            VARCHAR(64) NOT NULL PRIMARY KEY,
    lock_until      TIMESTAMP NOT NULL,
    locked_at       TIMESTAMP NOT NULL,
    locked_by       VARCHAR(255) NOT NULL
);

-- ============================================================================
-- SECTION 13: SUBSCRIPTIONS - FHIR R5-style subscription registrations
-- ============================================================================
CREATE TABLE IF NOT EXISTS pagw.subscriptions (
    id                      VARCHAR(50) PRIMARY KEY,
    tenant                  VARCHAR(50) NOT NULL DEFAULT 'elevance',
    tenant_id               VARCHAR(50) NOT NULL,
    status                  VARCHAR(20) NOT NULL DEFAULT 'active',
    channel_type            VARCHAR(20) NOT NULL DEFAULT 'rest-hook',
    endpoint                VARCHAR(500) NOT NULL,
    content_type            VARCHAR(20) DEFAULT 'id-only',
    headers                 JSONB,
    filter_criteria         VARCHAR(500),
    expires_at              TIMESTAMP WITH TIME ZONE,
    created_at              TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    last_delivered_at       TIMESTAMP WITH TIME ZONE,
    failure_count           INTEGER DEFAULT 0,
    last_error              TEXT,
    CONSTRAINT chk_subscriptions_status CHECK (status IN ('requested', 'active', 'error', 'off')),
    CONSTRAINT chk_subscriptions_channel_type CHECK (channel_type IN ('rest-hook', 'websocket', 'email')),
    CONSTRAINT chk_subscriptions_content_type CHECK (content_type IN ('id-only', 'full-resource'))
);

CREATE INDEX IF NOT EXISTS idx_subscriptions_tenant_status ON pagw.subscriptions(tenant_id, status) WHERE status = 'active';
CREATE INDEX IF NOT EXISTS idx_subscriptions_expires_at ON pagw.subscriptions(expires_at) WHERE expires_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_subscriptions_tenant_status_created ON pagw.subscriptions(tenant, status, created_at DESC);

-- ============================================================================
-- SECTION 14: VIEWS
-- ============================================================================

-- View: OAuth provider routing
CREATE OR REPLACE VIEW pagw.v_oauth_provider_routing AS
SELECT 
    opr.client_id, opr.tenant, opr.entity_name, opr.entity_type,
    opr.status AS oauth_status, opr.allowed_apis,
    unnest(opr.npis) AS provider_npi,
    pr.payer_id, pr.payer_name, pr.endpoint_url,
    pr.supports_sync, pr.supports_attachments, pr.is_active AS payer_route_active
FROM pagw.oauth_provider_registry opr
CROSS JOIN LATERAL unnest(opr.npis) AS npi
LEFT JOIN pagw.provider_registry pr ON pr.provider_npi = npi
WHERE opr.status = 'ACTIVE';

-- View: Request with provider context
CREATE OR REPLACE VIEW pagw.v_request_with_provider_context AS
SELECT 
    rt.pagw_id, rt.tenant, rt.status, rt.request_type, rt.last_stage,
    rt.received_at, rt.completed_at, opr.client_id,
    opr.entity_name as provider_entity_name, opr.entity_type as provider_entity_type,
    opr.tenant as provider_tenant, opr.status as provider_status,
    opr.allowed_apis as provider_allowed_apis,
    rt.provider_npi, rt.payer_id, rt.patient_member_id,
    EXTRACT(EPOCH FROM (rt.completed_at - rt.received_at)) as duration_seconds,
    rt.sync_processed, rt.async_queued, rt.last_error_code, rt.last_error_msg, rt.retry_count
FROM pagw.request_tracker rt
LEFT JOIN pagw.oauth_provider_registry opr ON opr.client_id = rt.client_id;

-- ============================================================================
-- SECTION 15: MATERIALIZED VIEWS
-- ============================================================================

-- Provider activity summary
CREATE MATERIALIZED VIEW IF NOT EXISTS pagw.mv_provider_activity_summary AS
SELECT 
    rt.provider_npi, rt.payer_id, opr.entity_name as provider_name, opr.tenant,
    DATE_TRUNC('day', rt.received_at) as activity_date,
    COUNT(*) as total_requests,
    COUNT(*) FILTER (WHERE rt.sync_processed = true) as sync_requests,
    COUNT(*) FILTER (WHERE rt.async_queued = true) as async_requests,
    COUNT(*) FILTER (WHERE rt.status = 'APPROVED') as approved_count,
    COUNT(*) FILTER (WHERE rt.status = 'DENIED') as denied_count,
    COUNT(*) FILTER (WHERE rt.status = 'PENDED') as pended_count,
    COUNT(*) FILTER (WHERE rt.status = 'FAILED') as failed_count,
    AVG(EXTRACT(EPOCH FROM (rt.completed_at - rt.received_at))) FILTER (WHERE rt.completed_at IS NOT NULL) as avg_duration_seconds,
    MAX(EXTRACT(EPOCH FROM (rt.completed_at - rt.received_at))) FILTER (WHERE rt.completed_at IS NOT NULL) as max_duration_seconds,
    ROUND(100.0 * COUNT(*) FILTER (WHERE rt.status = 'FAILED') / NULLIF(COUNT(*), 0), 2) as error_rate_pct
FROM pagw.request_tracker rt
LEFT JOIN pagw.oauth_provider_registry opr ON opr.client_id = rt.client_id
WHERE rt.received_at >= CURRENT_DATE - INTERVAL '90 days' AND rt.provider_npi IS NOT NULL
GROUP BY rt.provider_npi, rt.payer_id, opr.entity_name, opr.tenant, DATE_TRUNC('day', rt.received_at);

CREATE UNIQUE INDEX IF NOT EXISTS idx_mv_provider_activity_unique ON pagw.mv_provider_activity_summary(provider_npi, payer_id, activity_date);
CREATE INDEX IF NOT EXISTS idx_mv_provider_activity_date ON pagw.mv_provider_activity_summary(activity_date DESC);

-- Workflow performance
CREATE MATERIALIZED VIEW IF NOT EXISTS pagw.mv_workflow_performance AS
SELECT 
    rt.tenant, rt.workflow_id, rt.workflow_version, rt.scenario_id, rt.status::TEXT as status,
    DATE_TRUNC('day', rt.received_at) as date,
    COUNT(*) as request_count,
    AVG(EXTRACT(EPOCH FROM (rt.updated_at - rt.received_at))) as avg_duration_seconds,
    PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (rt.updated_at - rt.received_at))) as p50_duration,
    PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (rt.updated_at - rt.received_at))) as p95_duration,
    COUNT(*) FILTER (WHERE rt.status = 'APPROVED') as approved_count,
    COUNT(*) FILTER (WHERE rt.status = 'DENIED') as denied_count,
    COUNT(*) FILTER (WHERE rt.status = 'FAILED') as failed_count
FROM pagw.request_tracker rt
WHERE rt.received_at > NOW() - INTERVAL '90 days'
GROUP BY rt.tenant, rt.workflow_id, rt.workflow_version, rt.scenario_id, rt.status::TEXT, DATE_TRUNC('day', rt.received_at);

CREATE UNIQUE INDEX IF NOT EXISTS idx_mv_workflow_performance ON pagw.mv_workflow_performance(tenant, workflow_id, workflow_version, scenario_id, status, date);

-- ============================================================================
-- SECTION 16: FUNCTIONS
-- ============================================================================

-- Function: Get complete request story
CREATE OR REPLACE FUNCTION pagw.get_request_story(p_pagw_id VARCHAR)
RETURNS JSON AS $$
DECLARE result JSON;
BEGIN
    SELECT json_build_object(
        'request', (SELECT row_to_json(rt.*) FROM pagw.v_request_with_provider_context rt WHERE rt.pagw_id = p_pagw_id),
        'events', (SELECT json_agg(row_to_json(et.*) ORDER BY et.started_at) FROM pagw.event_tracker et WHERE et.pagw_id = p_pagw_id),
        'attachments', (SELECT json_agg(row_to_json(at.*) ORDER BY at.sequence) FROM pagw.attachment_tracker at WHERE at.pagw_id = p_pagw_id),
        'audit_trail', (SELECT json_agg(row_to_json(al.*) ORDER BY al.event_timestamp DESC) FROM pagw.audit_log al WHERE al.resource_id = p_pagw_id LIMIT 20)
    ) INTO result;
    RETURN result;
END;
$$ LANGUAGE plpgsql;

-- Function: Get request timeline
CREATE OR REPLACE FUNCTION pagw.get_request_timeline(p_pagw_id VARCHAR)
RETURNS TABLE (sequence_no BIGINT, event_type TEXT, stage VARCHAR, attempt INT, execution_status TEXT, duration_ms BIGINT, worker_id VARCHAR, created_at TIMESTAMP WITH TIME ZONE, metadata JSONB) AS $$
BEGIN
    RETURN QUERY SELECT et.sequence_no, et.event_type::TEXT, et.stage, et.attempt, et.execution_status::TEXT, et.duration_ms, et.worker_id, et.created_at, et.metadata
    FROM pagw.event_tracker et WHERE et.pagw_id = p_pagw_id ORDER BY et.sequence_no;
END;
$$ LANGUAGE plpgsql;

-- Function: Get failed retryable events
CREATE OR REPLACE FUNCTION pagw.get_failed_retryable_events(p_tenant VARCHAR DEFAULT NULL, p_minutes_ago INT DEFAULT 60)
RETURNS TABLE (pagw_id VARCHAR, stage VARCHAR, event_type TEXT, attempt INT, next_retry_at TIMESTAMP WITH TIME ZONE, error_code VARCHAR, error_message TEXT, created_at TIMESTAMP WITH TIME ZONE) AS $$
BEGIN
    RETURN QUERY SELECT et.pagw_id, et.stage, et.event_type::TEXT, et.attempt, et.next_retry_at, et.error_code, et.error_message, et.created_at
    FROM pagw.event_tracker et
    WHERE et.retryable = true AND et.execution_status = 'FAILURE' AND et.created_at > NOW() - (p_minutes_ago || ' minutes')::INTERVAL AND (p_tenant IS NULL OR et.tenant = p_tenant)
    ORDER BY et.created_at DESC;
END;
$$ LANGUAGE plpgsql;

-- ============================================================================
-- SECTION 17: SEED DATA
-- ============================================================================

-- Payer configurations
INSERT INTO pagw.payer_configuration (payer_id, payer_name, base_url, is_active) VALUES 
    ('CARELON', 'Carelon Behavioral Health', 'http://pagw-mock-payer:1080', true),
    ('ELEVANCE', 'Elevance Health', 'http://pagw-mock-payer:1080', true),
    ('ANTHEM', 'Anthem Blue Cross', 'http://pagw-mock-payer:1080', true)
ON CONFLICT (payer_id) DO NOTHING;

-- OAuth provider registrations for testing
INSERT INTO pagw.oauth_provider_registry (client_id, tenant, entity_name, entity_type, npis, tins, status, allowed_apis, rate_limit_per_minute, issuer_url, environment, notes) VALUES 
    ('bdda3ee5-df8f-4c86-8d89-4a160e90764d', 'carelon', 'Test Provider Organization', 'PROVIDER', ARRAY['1234567890', '9876543210'], ARRAY['12-3456789'], 'ACTIVE', ARRAY['PAS_SUBMIT', 'PAS_INQUIRE', 'CDEX_SUBMIT_ATTACHMENT', 'PAS_SUBSCRIBE'], 100, 'https://dev.totalview.healthos.carelon.com', 'dev', 'Test provider from TotalView introspection response'),
    ('elevance-test-client-001', 'elevance', 'Elevance Test EHR System', 'EHR', ARRAY['1111111111', '2222222222'], ARRAY['11-1111111'], 'ACTIVE', ARRAY['PAS_SUBMIT', 'PAS_INQUIRE'], 200, 'https://dev.totalview.healthos.carelon.com', 'dev', 'Elevance test provider requiring ACMP mapping'),
    ('clearinghouse-test-001', 'carelon', 'Test Clearinghouse Inc', 'CLEARINGHOUSE', ARRAY['3333333333', '4444444444', '5555555555'], ARRAY['33-3333333'], 'ACTIVE', ARRAY['PAS_SUBMIT', 'PAS_INQUIRE', 'PAS_SUBSCRIBE'], 500, 'https://dev.totalview.healthos.carelon.com', 'dev', 'Multi-provider clearinghouse')
ON CONFLICT (client_id) DO NOTHING;

-- ============================================================================
-- SECTION 18: COMMENTS
-- ============================================================================

COMMENT ON TABLE pagw.request_tracker IS 'Core table tracking PA request lifecycle through all stages';
COMMENT ON TABLE pagw.oauth_provider_registry IS 'OAuth provider registration and entitlements for API Gateway authorization';
COMMENT ON TABLE pagw.outbox IS 'Transactional outbox for reliable SQS message publishing';
COMMENT ON TABLE pagw.attachment_tracker IS 'Track attachment upload and processing status';
COMMENT ON TABLE pagw.provider_registry IS 'Provider to payer routing configuration';
COMMENT ON TABLE pagw.payer_configuration IS 'Payer-specific API and feature configuration';
COMMENT ON TABLE pagw.audit_log IS 'HIPAA-compliant audit trail - NO PHI stored';
COMMENT ON TABLE pagw.event_tracker IS 'Track processing events per pipeline stage';
COMMENT ON TABLE pagw.idempotency IS 'Idempotency keys to prevent duplicate processing';
COMMENT ON TABLE pagw.shedlock IS 'Distributed lock table for ShedLock';
COMMENT ON TABLE pagw.subscriptions IS 'FHIR R5-style subscription registrations';

COMMENT ON VIEW pagw.v_oauth_provider_routing IS 'Shows payer routing options for each active OAuth client based on their registered NPIs';
COMMENT ON VIEW pagw.v_request_with_provider_context IS 'Complete request context including OAuth provider registration details';
COMMENT ON MATERIALIZED VIEW pagw.mv_provider_activity_summary IS 'Provider activity analytics - Refresh daily via scheduled job';
COMMENT ON MATERIALIZED VIEW pagw.mv_workflow_performance IS 'Workflow performance metrics by version and scenario';

COMMENT ON FUNCTION pagw.get_request_story IS 'Returns complete request story with events, attachments, and audit trail';
COMMENT ON FUNCTION pagw.get_request_timeline IS 'Get complete event timeline for a request with retry context';
COMMENT ON FUNCTION pagw.get_failed_retryable_events IS 'Identify failed events eligible for retry';

-- ============================================================================
-- MIGRATION COMPLETE
-- ============================================================================
