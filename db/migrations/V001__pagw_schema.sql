-- ============================================================================
-- PAGW Database Schema - Single Migration Script
-- Version: 1.0.0
-- Description: Complete schema for Prior Authorization Gateway
-- ============================================================================

-- Create schema
CREATE SCHEMA IF NOT EXISTS pagw;

-- ============================================================================
-- 1. REQUEST_TRACKER - Core table tracking PA request lifecycle
-- ============================================================================
CREATE TABLE IF NOT EXISTS pagw.request_tracker (
    -- Primary identifier
    pagw_id                 VARCHAR(50) PRIMARY KEY,
    
    -- Request metadata
    tenant                  VARCHAR(100),
    source_system           VARCHAR(50),
    request_type            VARCHAR(20) NOT NULL DEFAULT 'submit',  -- submit, inquiry, cancel, update
    idempotency_key         VARCHAR(255),
    correlation_id          VARCHAR(100),
    
    -- Processing status
    status                  VARCHAR(30) NOT NULL DEFAULT 'received',
    last_stage              VARCHAR(50),
    next_stage              VARCHAR(50),
    workflow_id             VARCHAR(100),  -- Workflow tracking ID
    last_error_code         VARCHAR(50),
    last_error_msg          TEXT,
    retry_count             INT DEFAULT 0,
    
    -- S3 storage paths (bucket/YYYYMM/pagwId/folder/file)
    raw_s3_bucket           VARCHAR(255),
    raw_s3_key              VARCHAR(500),
    enriched_s3_bucket      VARCHAR(255),
    enriched_s3_key         VARCHAR(500),
    final_s3_bucket         VARCHAR(255),
    final_s3_key            VARCHAR(500),
    
    -- External references
    external_request_id     VARCHAR(100),  -- Payer's reference (preAuthRef)
    external_reference_id   VARCHAR(100),  -- External system reference ID
    payer_id                VARCHAR(50),
    provider_npi            VARCHAR(20),
    patient_member_id       VARCHAR(100),
    client_id               VARCHAR(100),  -- Source client ID
    member_id               VARCHAR(100),  -- Member ID for lookups
    provider_id             VARCHAR(100),  -- Provider identifier
    
    -- PHI handling
    contains_phi            BOOLEAN DEFAULT true,
    phi_encrypted           BOOLEAN DEFAULT false,
    
    -- Sync processing flags (for race condition prevention)
    sync_processed          BOOLEAN DEFAULT false,
    sync_processed_at       TIMESTAMP WITH TIME ZONE,
    async_queued            BOOLEAN DEFAULT false,
    async_queued_at         TIMESTAMP WITH TIME ZONE,
    
    -- Timestamps
    received_at             TIMESTAMP WITH TIME ZONE,
    completed_at            TIMESTAMP WITH TIME ZONE,
    callback_sent_at        TIMESTAMP WITH TIME ZONE,
    expires_at              TIMESTAMP WITH TIME ZONE,
    created_at              TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for common queries
CREATE INDEX IF NOT EXISTS idx_request_tracker_status ON pagw.request_tracker(status);
CREATE INDEX IF NOT EXISTS idx_request_tracker_tenant ON pagw.request_tracker(tenant);
CREATE INDEX IF NOT EXISTS idx_request_tracker_idempotency ON pagw.request_tracker(idempotency_key);
CREATE INDEX IF NOT EXISTS idx_request_tracker_external_id ON pagw.request_tracker(external_request_id);
CREATE INDEX IF NOT EXISTS idx_request_tracker_patient ON pagw.request_tracker(patient_member_id);
CREATE INDEX IF NOT EXISTS idx_request_tracker_created ON pagw.request_tracker(created_at);
CREATE INDEX IF NOT EXISTS idx_request_tracker_received ON pagw.request_tracker(received_at);

-- ============================================================================
-- 2. OUTBOX - Transactional outbox for reliable message publishing
-- ============================================================================
CREATE TABLE IF NOT EXISTS pagw.outbox (
    id                      UUID PRIMARY KEY,
    
    -- Aggregate info for event sourcing pattern
    aggregate_type          VARCHAR(100) NOT NULL,
    aggregate_id            VARCHAR(100) NOT NULL,
    event_type              VARCHAR(100),
    
    -- Message routing
    destination_queue       VARCHAR(500) NOT NULL,
    
    -- Message content
    payload                 JSONB NOT NULL,
    
    -- Processing status
    status                  VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING, COMPLETED, FAILED
    retry_count             INT DEFAULT 0,
    max_retries             INT DEFAULT 5,
    last_error              TEXT,
    
    -- Timestamps
    created_at              TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    processed_at            TIMESTAMP WITH TIME ZONE,
    next_retry_at           TIMESTAMP WITH TIME ZONE
);

-- Index for efficient polling of pending messages
CREATE INDEX IF NOT EXISTS idx_outbox_pending ON pagw.outbox(status, created_at) 
    WHERE status = 'PENDING';
CREATE INDEX IF NOT EXISTS idx_outbox_retry ON pagw.outbox(status, next_retry_at) 
    WHERE status = 'PENDING' AND retry_count > 0;

-- ============================================================================
-- 3. ATTACHMENT_TRACKER - Track attachment processing status
-- ============================================================================
CREATE TABLE IF NOT EXISTS pagw.attachment_tracker (
    id                      BIGSERIAL PRIMARY KEY,
    pagw_id                 VARCHAR(50) NOT NULL REFERENCES pagw.request_tracker(pagw_id),
    
    -- Attachment identification
    attachment_id           VARCHAR(100) NOT NULL,
    attachment_type         VARCHAR(50),           -- clinical_note, lab_result, image, etc.
    original_filename       VARCHAR(255),
    content_type            VARCHAR(100),
    file_size_bytes         BIGINT,
    
    -- S3 storage
    s3_bucket               VARCHAR(255),
    s3_key                  VARCHAR(500),
    
    -- Processing status
    status                  VARCHAR(30) NOT NULL DEFAULT 'pending',
    error_message           TEXT,
    
    -- Timestamps
    created_at              TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    processed_at            TIMESTAMP WITH TIME ZONE,
    
    CONSTRAINT attachment_tracker_unique UNIQUE (pagw_id, attachment_id)
);

CREATE INDEX IF NOT EXISTS idx_attachment_tracker_pagw ON pagw.attachment_tracker(pagw_id);
CREATE INDEX IF NOT EXISTS idx_attachment_tracker_status ON pagw.attachment_tracker(status);

-- ============================================================================
-- 4. PROVIDER_REGISTRY - Payer routing and configuration
-- ============================================================================
CREATE TABLE IF NOT EXISTS pagw.provider_registry (
    id                      BIGSERIAL PRIMARY KEY,
    
    -- Provider identification
    provider_npi            VARCHAR(20) NOT NULL,
    provider_tax_id         VARCHAR(20),
    provider_name           VARCHAR(255),
    
    -- Payer mapping
    payer_id                VARCHAR(50) NOT NULL,
    payer_name              VARCHAR(255),
    
    -- Routing configuration
    endpoint_url            VARCHAR(500),
    api_version             VARCHAR(20),
    auth_type               VARCHAR(50),           -- oauth2, api_key, mtls
    credentials_secret_name VARCHAR(255),
    
    -- Feature flags
    supports_sync           BOOLEAN DEFAULT true,
    supports_attachments    BOOLEAN DEFAULT true,
    supports_subscriptions  BOOLEAN DEFAULT false,
    
    -- Status
    is_active               BOOLEAN DEFAULT true,
    
    -- Timestamps
    created_at              TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT provider_registry_unique UNIQUE (provider_npi, payer_id)
);

CREATE INDEX IF NOT EXISTS idx_provider_registry_npi ON pagw.provider_registry(provider_npi);
CREATE INDEX IF NOT EXISTS idx_provider_registry_payer ON pagw.provider_registry(payer_id);

-- ============================================================================
-- 5. PAYER_CONFIGURATION - Payer-specific settings
-- ============================================================================
CREATE TABLE IF NOT EXISTS pagw.payer_configuration (
    id                      BIGSERIAL PRIMARY KEY,
    payer_id                VARCHAR(50) NOT NULL UNIQUE,
    payer_name              VARCHAR(255),
    
    -- API Configuration
    base_url                VARCHAR(500),
    auth_endpoint           VARCHAR(500),
    submit_endpoint         VARCHAR(500),
    inquiry_endpoint        VARCHAR(500),
    
    -- Timeouts (milliseconds)
    connection_timeout_ms   INT DEFAULT 5000,
    read_timeout_ms         INT DEFAULT 30000,
    
    -- Retry configuration
    max_retries             INT DEFAULT 3,
    retry_delay_ms          INT DEFAULT 1000,
    
    -- Feature flags
    supports_x12            BOOLEAN DEFAULT true,
    supports_fhir           BOOLEAN DEFAULT true,
    requires_attachments    BOOLEAN DEFAULT false,
    
    -- Status
    is_active               BOOLEAN DEFAULT true,
    
    -- Timestamps
    created_at              TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================================
-- 6. AUDIT_LOG - Compliance audit trail (NO PHI)
-- ============================================================================
CREATE TABLE IF NOT EXISTS pagw.audit_log (
    id                      BIGSERIAL PRIMARY KEY,
    
    -- Event identification
    event_id                VARCHAR(100) NOT NULL,
    event_type              VARCHAR(50) NOT NULL,
    event_timestamp         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    -- Context (NO PHI - IDs only)
    pagw_id                 VARCHAR(50),
    correlation_id          VARCHAR(100),
    tenant                  VARCHAR(100),
    
    -- Actor
    service_name            VARCHAR(50),
    user_id                 VARCHAR(100),
    
    -- Event details
    action                  VARCHAR(100),
    resource_type           VARCHAR(50),
    outcome                 VARCHAR(20),           -- success, failure, error
    error_code              VARCHAR(50),
    
    -- Additional context (JSON, NO PHI)
    metadata                JSONB,
    
    -- Timestamps
    created_at              TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Partitioning-friendly indexes
CREATE INDEX IF NOT EXISTS idx_audit_log_timestamp ON pagw.audit_log(event_timestamp);
CREATE INDEX IF NOT EXISTS idx_audit_log_pagw ON pagw.audit_log(pagw_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_type ON pagw.audit_log(event_type);
CREATE INDEX IF NOT EXISTS idx_audit_log_correlation ON pagw.audit_log(correlation_id);

-- ============================================================================
-- 7. EVENT_TRACKER - Track processing events per stage
-- ============================================================================
CREATE TABLE IF NOT EXISTS pagw.event_tracker (
    id                      BIGSERIAL PRIMARY KEY,
    pagw_id                 VARCHAR(50) NOT NULL REFERENCES pagw.request_tracker(pagw_id),
    
    -- Event details
    stage                   VARCHAR(50) NOT NULL,
    event_type              VARCHAR(50) NOT NULL,
    status                  VARCHAR(30) NOT NULL,
    external_reference      VARCHAR(100),
    
    -- Processing details
    duration_ms             BIGINT,
    error_code              VARCHAR(50),
    error_message           TEXT,
    
    -- Timestamps
    started_at              TIMESTAMP WITH TIME ZONE,
    completed_at            TIMESTAMP WITH TIME ZONE,
    created_at              TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_event_tracker_pagw ON pagw.event_tracker(pagw_id);
CREATE INDEX IF NOT EXISTS idx_event_tracker_stage ON pagw.event_tracker(stage);

-- ============================================================================
-- 8. IDEMPOTENCY - Prevent duplicate request processing
-- ============================================================================
CREATE TABLE IF NOT EXISTS pagw.idempotency (
    idempotency_key         VARCHAR(255) PRIMARY KEY,
    pagw_id                 VARCHAR(50) NOT NULL,
    request_hash            VARCHAR(64),
    
    -- Response caching
    response_status         VARCHAR(30),
    response_body           JSONB,
    
    -- Timestamps
    created_at              TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    expires_at              TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_idempotency_expires ON pagw.idempotency(expires_at);
CREATE INDEX IF NOT EXISTS idx_idempotency_pagw ON pagw.idempotency(pagw_id);

-- ============================================================================
-- 9. SHEDLOCK - Distributed lock for scheduled tasks
-- ============================================================================
CREATE TABLE IF NOT EXISTS pagw.shedlock (
    name            VARCHAR(64) NOT NULL PRIMARY KEY,
    lock_until      TIMESTAMP NOT NULL,
    locked_at       TIMESTAMP NOT NULL,
    locked_by       VARCHAR(255) NOT NULL
);

-- ============================================================================
-- 10. SUBSCRIPTIONS - FHIR R5-style subscription registrations
-- ============================================================================
CREATE TABLE IF NOT EXISTS pagw.subscriptions (
    id                      VARCHAR(50) PRIMARY KEY,
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

-- Index for finding active subscriptions by tenant
CREATE INDEX IF NOT EXISTS idx_subscriptions_tenant_status 
    ON pagw.subscriptions(tenant_id, status) 
    WHERE status = 'active';

-- Index for expiration cleanup
CREATE INDEX IF NOT EXISTS idx_subscriptions_expires_at 
    ON pagw.subscriptions(expires_at) 
    WHERE expires_at IS NOT NULL;

-- ============================================================================
-- TRIGGERS - Auto-update timestamps
-- ============================================================================
CREATE OR REPLACE FUNCTION pagw.update_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER request_tracker_updated_at
    BEFORE UPDATE ON pagw.request_tracker
    FOR EACH ROW
    EXECUTE FUNCTION pagw.update_updated_at();

CREATE TRIGGER provider_registry_updated_at
    BEFORE UPDATE ON pagw.provider_registry
    FOR EACH ROW
    EXECUTE FUNCTION pagw.update_updated_at();

CREATE TRIGGER payer_configuration_updated_at
    BEFORE UPDATE ON pagw.payer_configuration
    FOR EACH ROW
    EXECUTE FUNCTION pagw.update_updated_at();

-- ============================================================================
-- SEED DATA - Default payer configurations for local development
-- ============================================================================
INSERT INTO pagw.payer_configuration (payer_id, payer_name, base_url, is_active)
VALUES 
    ('CARELON', 'Carelon Behavioral Health', 'http://pagw-mock-payer:1080', true),
    ('ELEVANCE', 'Elevance Health', 'http://pagw-mock-payer:1080', true),
    ('ANTHEM', 'Anthem Blue Cross', 'http://pagw-mock-payer:1080', true)
ON CONFLICT (payer_id) DO NOTHING;

-- ============================================================================
-- COMMENTS - Documentation
-- ============================================================================
COMMENT ON TABLE pagw.request_tracker IS 'Core table tracking PA request lifecycle through all stages';
COMMENT ON TABLE pagw.outbox IS 'Transactional outbox for reliable SQS message publishing';
COMMENT ON TABLE pagw.attachment_tracker IS 'Track attachment upload and processing status';
COMMENT ON TABLE pagw.provider_registry IS 'Provider to payer routing configuration';
COMMENT ON TABLE pagw.payer_configuration IS 'Payer-specific API and feature configuration';
COMMENT ON TABLE pagw.audit_log IS 'Compliance audit trail - NO PHI stored';
COMMENT ON TABLE pagw.event_tracker IS 'Track processing events per pipeline stage';
COMMENT ON TABLE pagw.idempotency IS 'Idempotency keys to prevent duplicate processing';
COMMENT ON TABLE pagw.shedlock IS 'Distributed lock table for ShedLock';

COMMENT ON COLUMN pagw.request_tracker.sync_processed IS 'Flag set when sync processing completes - prevents race condition with async queue';
COMMENT ON COLUMN pagw.request_tracker.async_queued IS 'Flag set when request is queued for async processing';
COMMENT ON COLUMN pagw.outbox.destination_queue IS 'Full SQS queue URL for message routing';
