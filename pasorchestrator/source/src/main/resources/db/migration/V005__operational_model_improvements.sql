-- ============================================================================
-- V005: Operational Data Model Improvements
-- ============================================================================
-- Purpose: Address recommendations from data model review
--   1. Add workflow versioning and scenario tracking
--   2. Enhance event_tracker with ordering, retry context, and tenant
--   3. Improve outbox with logical destinations and tenant
--   4. Optimize idempotency with S3 pointers and tenant
--   5. Add attachment lifecycle tracking
--   6. Normalize status/event types with ENUMs
--   7. Add composite indexes for common query patterns
--
-- Author: PAGW Platform Team
-- Date: 2025-12-25
-- References: data-model-review.txt, data-model-review-1.txt
-- ============================================================================

-- ============================================================================
-- SECTION 1: Create ENUMs for Status Normalization
-- ============================================================================

-- Request status lifecycle
CREATE TYPE pagw.request_status_enum AS ENUM (
    'RECEIVED',           -- Initial state after submission
    'PARSING',            -- FHIR parsing in progress
    'PARSING_FAILED',     -- FHIR parsing failed
    'VALIDATING',         -- Business validation in progress
    'VALIDATION_FAILED',  -- Business validation failed
    'ENRICHING',          -- Enrichment in progress
    'CONVERTING',         -- X12 conversion in progress
    'SUBMITTING',         -- Submitting to payer
    'PENDING',            -- Awaiting payer response
    'APPROVED',           -- Payer approved
    'DENIED',             -- Payer denied
    'PENDED',             -- Payer pended (needs more info)
    'FAILED',             -- System failure
    'CANCELLED',          -- Cancelled by provider
    'COMPLETED'           -- Successfully completed
);

-- Event types for tracking
CREATE TYPE pagw.event_type_enum AS ENUM (
    -- Stage lifecycle events
    'STAGE_STARTED',
    'STAGE_COMPLETED',
    'STAGE_FAILED',
    'STAGE_SKIPPED',
    
    -- Parsing events
    'PARSE_STARTED',
    'PARSE_OK',
    'PARSE_FAIL',
    
    -- Validation events
    'VAL_STARTED',
    'VAL_OK',
    'VAL_FAIL',
    'VAL_WARN',
    
    -- Authentication/Authorization events
    'AUTHN_STARTED',
    'AUTHN_OK',
    'AUTHN_FAIL',
    'AUTHZ_OK',
    'AUTHZ_FAIL',
    
    -- Enrichment events
    'ENRICH_STARTED',
    'ENRICH_OK',
    'ENRICH_FAIL',
    
    -- Conversion events
    'CONVERT_STARTED',
    'CONVERT_OK',
    'CONVERT_FAIL',
    
    -- Submission events
    'SUBMIT_STARTED',
    'SUBMIT_OK',
    'SUBMIT_FAIL',
    'SUBMIT_RETRY',
    
    -- Response events
    'RESPONSE_RECEIVED',
    'RESPONSE_PARSED',
    'RESPONSE_BUILT',
    
    -- Attachment events
    'ATTACH_REQUESTED',
    'ATTACH_UPLOADED',
    'ATTACH_VALIDATED',
    'ATTACH_FAILED',
    
    -- Callback events
    'CALLBACK_SENT',
    'CALLBACK_ACK',
    'CALLBACK_FAIL',
    
    -- Subscription events
    'SUBSCRIPTION_CREATED',
    'SUBSCRIPTION_UPDATED',
    'SUBSCRIPTION_DELETED',
    'NOTIFICATION_SENT',
    
    -- Business events
    'PROVIDER_AUTH_FAILED',
    'FHIR_VALIDATION_FAILED',
    'PAYER_TIMEOUT',
    'PAYER_ERROR',
    'IDEMPOTENCY_HIT',
    'DUPLICATE_REQUEST',
    'WORKFLOW_DECISION',
    'ROUTING_DECISION',
    'RATE_LIMIT_EXCEEDED'
);

-- Execution status for events
CREATE TYPE pagw.execution_status_enum AS ENUM (
    'SUCCESS',
    'FAILURE',
    'ERROR',
    'TIMEOUT',
    'SKIPPED',
    'IN_PROGRESS'
);

-- Attachment source types
CREATE TYPE pagw.attachment_source_enum AS ENUM (
    'INLINE',              -- Embedded in FHIR bundle
    'CDEX_PULL',          -- Retrieved via CDex
    'PRESIGNED_UPLOAD',   -- Uploaded via presigned URL
    'EXTERNAL_REFERENCE'  -- External URL reference
);

-- Attachment fetch status
CREATE TYPE pagw.fetch_status_enum AS ENUM (
    'PENDING',
    'IN_PROGRESS',
    'SUCCESS',
    'FAILED',
    'CANCELLED',
    'NOT_REQUIRED'
);

COMMENT ON TYPE pagw.request_status_enum IS 'Normalized request lifecycle states';
COMMENT ON TYPE pagw.event_type_enum IS 'Standardized event types for observability';
COMMENT ON TYPE pagw.execution_status_enum IS 'Execution outcome for events';
COMMENT ON TYPE pagw.attachment_source_enum IS 'Source mechanism for attachment retrieval';
COMMENT ON TYPE pagw.fetch_status_enum IS 'Attachment fetch lifecycle status';


-- ============================================================================
-- SECTION 2: Enhance REQUEST_TRACKER with Workflow/Scenario Versioning
-- ============================================================================

-- Add workflow versioning columns
ALTER TABLE pagw.request_tracker
    ADD COLUMN IF NOT EXISTS workflow_version VARCHAR(50),
    ADD COLUMN IF NOT EXISTS scenario_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS ruleset_version VARCHAR(50),
    ADD COLUMN IF NOT EXISTS workflow_hash VARCHAR(64);  -- SHA-256 of workflow.json

-- Add metadata JSONB for extensibility
ALTER TABLE pagw.request_tracker
    ADD COLUMN IF NOT EXISTS metadata JSONB DEFAULT '{}'::jsonb;

-- Update status column to use ENUM (if not already using it)
-- Note: This requires careful migration of existing data
DO $$
BEGIN
    -- Only attempt if column exists and is not already the enum type
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_schema = 'pagw' 
        AND table_name = 'request_tracker' 
        AND column_name = 'status'
        AND data_type != 'USER-DEFINED'
    ) THEN
        -- Create temporary column with enum type
        ALTER TABLE pagw.request_tracker ADD COLUMN status_new pagw.request_status_enum;
        
        -- Migrate existing data (map old values to new enum)
        UPDATE pagw.request_tracker SET status_new = 
            CASE 
                WHEN status = 'RECEIVED' THEN 'RECEIVED'::pagw.request_status_enum
                WHEN status = 'PARSING' THEN 'PARSING'::pagw.request_status_enum
                WHEN status = 'VALIDATING' THEN 'VALIDATING'::pagw.request_status_enum
                WHEN status = 'PENDING' THEN 'PENDING'::pagw.request_status_enum
                WHEN status = 'APPROVED' THEN 'APPROVED'::pagw.request_status_enum
                WHEN status = 'DENIED' THEN 'DENIED'::pagw.request_status_enum
                WHEN status = 'FAILED' THEN 'FAILED'::pagw.request_status_enum
                WHEN status = 'COMPLETED' THEN 'COMPLETED'::pagw.request_status_enum
                ELSE 'RECEIVED'::pagw.request_status_enum  -- Default for unknown
            END;
        
        -- Drop old column and rename new one
        ALTER TABLE pagw.request_tracker DROP COLUMN status;
        ALTER TABLE pagw.request_tracker RENAME COLUMN status_new TO status;
    END IF;
END $$;

-- Add indexes for workflow queries
CREATE INDEX IF NOT EXISTS idx_request_tracker_workflow 
    ON pagw.request_tracker(workflow_id, workflow_version, received_at DESC);

CREATE INDEX IF NOT EXISTS idx_request_tracker_scenario 
    ON pagw.request_tracker(scenario_id, received_at DESC) 
    WHERE scenario_id IS NOT NULL;

-- Add comments
COMMENT ON COLUMN pagw.request_tracker.workflow_version IS 'Version of workflow.json that processed this request';
COMMENT ON COLUMN pagw.request_tracker.scenario_id IS 'Custom scenario identifier (e.g., EXPEDITED, STANDARD, APPEAL)';
COMMENT ON COLUMN pagw.request_tracker.ruleset_version IS 'Validator ruleset version applied';
COMMENT ON COLUMN pagw.request_tracker.workflow_hash IS 'SHA-256 hash of workflow.json for change tracking';
COMMENT ON COLUMN pagw.request_tracker.metadata IS 'Extensible metadata storage for workflow-specific data';


-- ============================================================================
-- SECTION 3: Enhance EVENT_TRACKER with Ordering, Retry Context, and Tenant
-- ============================================================================

-- Add tenant for isolation
ALTER TABLE pagw.event_tracker
    ADD COLUMN IF NOT EXISTS tenant VARCHAR(50) NOT NULL DEFAULT 'elevance';

-- Add ordering and retry context
ALTER TABLE pagw.event_tracker
    ADD COLUMN IF NOT EXISTS sequence_no BIGINT,
    ADD COLUMN IF NOT EXISTS attempt INT NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS retryable BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN IF NOT EXISTS next_retry_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS worker_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS pod_name VARCHAR(100);

-- Add structured details storage
ALTER TABLE pagw.event_tracker
    ADD COLUMN IF NOT EXISTS details JSONB DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS op_outcome_s3_key VARCHAR(500);

-- Add execution status
ALTER TABLE pagw.event_tracker
    ADD COLUMN IF NOT EXISTS execution_status pagw.execution_status_enum DEFAULT 'SUCCESS';

-- Generate sequence numbers for existing events (ordered by created_at)
WITH numbered_events AS (
    SELECT 
        id,
        ROW_NUMBER() OVER (PARTITION BY pagw_id ORDER BY created_at, id) as seq
    FROM pagw.event_tracker
    WHERE sequence_no IS NULL
)
UPDATE pagw.event_tracker et
SET sequence_no = ne.seq
FROM numbered_events ne
WHERE et.id = ne.id;

-- Add unique constraint on sequence ordering
ALTER TABLE pagw.event_tracker
    ADD CONSTRAINT uq_event_tracker_sequence 
    UNIQUE (pagw_id, sequence_no);

-- Add indexes for common queries
CREATE INDEX IF NOT EXISTS idx_event_tracker_tenant_pagw 
    ON pagw.event_tracker(tenant, pagw_id, created_at);

CREATE INDEX IF NOT EXISTS idx_event_tracker_tenant_stage 
    ON pagw.event_tracker(tenant, stage, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_event_tracker_tenant_event_type 
    ON pagw.event_tracker(tenant, event_type, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_event_tracker_retry 
    ON pagw.event_tracker(next_retry_at) 
    WHERE retryable = true AND next_retry_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_event_tracker_worker 
    ON pagw.event_tracker(worker_id, created_at DESC) 
    WHERE worker_id IS NOT NULL;

-- GIN index for details JSONB searches
CREATE INDEX IF NOT EXISTS idx_event_tracker_details 
    ON pagw.event_tracker USING GIN (details);

-- Add comments
COMMENT ON COLUMN pagw.event_tracker.tenant IS 'Tenant isolation (elevance, carelon)';
COMMENT ON COLUMN pagw.event_tracker.sequence_no IS 'Monotonic sequence per pagw_id for guaranteed ordering';
COMMENT ON COLUMN pagw.event_tracker.attempt IS 'Retry attempt number (1 = first attempt)';
COMMENT ON COLUMN pagw.event_tracker.retryable IS 'Whether this event can be retried';
COMMENT ON COLUMN pagw.event_tracker.next_retry_at IS 'Scheduled time for next retry attempt';
COMMENT ON COLUMN pagw.event_tracker.worker_id IS 'Service instance or worker ID that processed this event';
COMMENT ON COLUMN pagw.event_tracker.pod_name IS 'Kubernetes pod name for debugging';
COMMENT ON COLUMN pagw.event_tracker.details IS 'Structured event metadata (counts, metrics, decisions)';
COMMENT ON COLUMN pagw.event_tracker.op_outcome_s3_key IS 'S3 key for large OperationOutcome bodies';
COMMENT ON COLUMN pagw.event_tracker.execution_status IS 'Execution outcome (SUCCESS, FAILURE, ERROR, TIMEOUT)';


-- ============================================================================
-- SECTION 4: Improve OUTBOX with Logical Destinations and Tenant
-- ============================================================================

-- Add tenant for isolation
ALTER TABLE pagw.outbox
    ADD COLUMN IF NOT EXISTS tenant VARCHAR(50) NOT NULL DEFAULT 'elevance';

-- Add logical destination (replace physical queue URL)
ALTER TABLE pagw.outbox
    ADD COLUMN IF NOT EXISTS destination VARCHAR(100),
    ADD COLUMN IF NOT EXISTS destination_type VARCHAR(20) DEFAULT 'SQS';

-- Migrate existing destination_queue to logical names
UPDATE pagw.outbox
SET destination = 
    CASE
        WHEN destination_queue LIKE '%pas-request-parser%' THEN 'PAS_REQUEST_PARSER'
        WHEN destination_queue LIKE '%pas-validator%' THEN 'PAS_BUSINESS_VALIDATOR'
        WHEN destination_queue LIKE '%pas-enricher%' THEN 'PAS_REQUEST_ENRICHER'
        WHEN destination_queue LIKE '%pas-converter%' THEN 'PAS_REQUEST_CONVERTER'
        WHEN destination_queue LIKE '%pas-connector%' THEN 'PAS_API_CONNECTOR'
        WHEN destination_queue LIKE '%pas-callback%' THEN 'PAS_CALLBACK_HANDLER'
        WHEN destination_queue LIKE '%pas-response%' THEN 'PAS_RESPONSE_BUILDER'
        WHEN destination_queue LIKE '%pas-subscription%' THEN 'PAS_SUBSCRIPTION_HANDLER'
        WHEN destination_queue LIKE '%pas-attachment%' THEN 'PAS_ATTACHMENT_HANDLER'
        ELSE 'UNKNOWN'
    END
WHERE destination IS NULL;

-- Add indexes
CREATE INDEX IF NOT EXISTS idx_outbox_tenant_published 
    ON pagw.outbox(tenant, published_at) 
    WHERE published_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_outbox_destination 
    ON pagw.outbox(destination, created_at DESC);

-- Add comments
COMMENT ON COLUMN pagw.outbox.tenant IS 'Tenant isolation';
COMMENT ON COLUMN pagw.outbox.destination IS 'Logical destination name (resolved to queue URL at runtime)';
COMMENT ON COLUMN pagw.outbox.destination_type IS 'Destination type (SQS, SNS, EventBridge)';


-- ============================================================================
-- SECTION 5: Optimize IDEMPOTENCY with S3 Pointers and Tenant
-- ============================================================================

-- Add tenant for isolation
ALTER TABLE pagw.idempotency
    ADD COLUMN IF NOT EXISTS tenant VARCHAR(50) NOT NULL DEFAULT 'elevance';

-- Add S3 pointer for large responses
ALTER TABLE pagw.idempotency
    ADD COLUMN IF NOT EXISTS response_s3_key VARCHAR(500),
    ADD COLUMN IF NOT EXISTS response_hash VARCHAR(64),
    ADD COLUMN IF NOT EXISTS response_status INT,
    ADD COLUMN IF NOT EXISTS response_summary JSONB;

-- Update unique constraint to include tenant
ALTER TABLE pagw.idempotency
    DROP CONSTRAINT IF EXISTS idempotency_pkey;

ALTER TABLE pagw.idempotency
    ADD CONSTRAINT idempotency_pkey 
    PRIMARY KEY (tenant, idempotency_key);

-- Add index for hash-based deduplication
CREATE INDEX IF NOT EXISTS idx_idempotency_hash 
    ON pagw.idempotency(response_hash) 
    WHERE response_hash IS NOT NULL;

-- Add comments
COMMENT ON COLUMN pagw.idempotency.tenant IS 'Tenant isolation';
COMMENT ON COLUMN pagw.idempotency.response_s3_key IS 'S3 key for large response bodies';
COMMENT ON COLUMN pagw.idempotency.response_hash IS 'SHA-256 hash of response for deduplication';
COMMENT ON COLUMN pagw.idempotency.response_status IS 'HTTP status code of cached response';
COMMENT ON COLUMN pagw.idempotency.response_summary IS 'Small cached summary for quick replies';


-- ============================================================================
-- SECTION 6: Add Attachment Lifecycle Tracking
-- ============================================================================

-- Add tenant for isolation
ALTER TABLE pagw.attachment_tracker
    ADD COLUMN IF NOT EXISTS tenant VARCHAR(50) NOT NULL DEFAULT 'elevance';

-- Add attachment source and fetch lifecycle
ALTER TABLE pagw.attachment_tracker
    ADD COLUMN IF NOT EXISTS attachment_source pagw.attachment_source_enum DEFAULT 'INLINE',
    ADD COLUMN IF NOT EXISTS fetch_status pagw.fetch_status_enum DEFAULT 'NOT_REQUIRED',
    ADD COLUMN IF NOT EXISTS fetch_attempts INT DEFAULT 0,
    ADD COLUMN IF NOT EXISTS fetch_last_error TEXT,
    ADD COLUMN IF NOT EXISTS fetch_started_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS fetch_completed_at TIMESTAMP WITH TIME ZONE;

-- Add indexes
CREATE INDEX IF NOT EXISTS idx_attachment_tracker_tenant_pagw 
    ON pagw.attachment_tracker(tenant, pagw_id);

CREATE INDEX IF NOT EXISTS idx_attachment_tracker_fetch_status 
    ON pagw.attachment_tracker(fetch_status, fetch_started_at) 
    WHERE fetch_status IN ('PENDING', 'IN_PROGRESS');

CREATE INDEX IF NOT EXISTS idx_attachment_tracker_checksum 
    ON pagw.attachment_tracker(checksum) 
    WHERE checksum IS NOT NULL;

-- Add comments
COMMENT ON COLUMN pagw.attachment_tracker.tenant IS 'Tenant isolation';
COMMENT ON COLUMN pagw.attachment_tracker.attachment_source IS 'How attachment was obtained';
COMMENT ON COLUMN pagw.attachment_tracker.fetch_status IS 'Fetch lifecycle status';
COMMENT ON COLUMN pagw.attachment_tracker.fetch_attempts IS 'Number of fetch attempts';
COMMENT ON COLUMN pagw.attachment_tracker.fetch_last_error IS 'Last fetch error message';
COMMENT ON COLUMN pagw.attachment_tracker.fetch_started_at IS 'When fetch started';
COMMENT ON COLUMN pagw.attachment_tracker.fetch_completed_at IS 'When fetch completed';


-- ============================================================================
-- SECTION 7: Add Tenant to Remaining Tables
-- ============================================================================

-- Add tenant to audit_log
ALTER TABLE pagw.audit_log
    ADD COLUMN IF NOT EXISTS tenant VARCHAR(50) NOT NULL DEFAULT 'elevance';

CREATE INDEX IF NOT EXISTS idx_audit_log_tenant 
    ON pagw.audit_log(tenant, created_at DESC);

-- Add tenant to subscriptions
ALTER TABLE pagw.subscriptions
    ADD COLUMN IF NOT EXISTS tenant VARCHAR(50) NOT NULL DEFAULT 'elevance';

CREATE INDEX IF NOT EXISTS idx_subscriptions_tenant_status 
    ON pagw.subscriptions(tenant, status, created_at DESC);

-- Add tenant to subscription_events
ALTER TABLE pagw.subscription_events
    ADD COLUMN IF NOT EXISTS tenant VARCHAR(50) NOT NULL DEFAULT 'elevance';

CREATE INDEX IF NOT EXISTS idx_subscription_events_tenant 
    ON pagw.subscription_events(tenant, created_at DESC);


-- ============================================================================
-- SECTION 8: Add Composite Indexes for Performance
-- ============================================================================

-- Request tracker composite indexes (based on review recommendations)
CREATE INDEX IF NOT EXISTS idx_request_tracker_tenant_status_updated 
    ON pagw.request_tracker(tenant, status, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_request_tracker_tenant_client_created 
    ON pagw.request_tracker(tenant, client_id, received_at DESC) 
    WHERE client_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_request_tracker_tenant_payer_status 
    ON pagw.request_tracker(tenant, payer_id, status) 
    WHERE payer_id IS NOT NULL;

-- Audit log composite indexes
CREATE INDEX IF NOT EXISTS idx_audit_log_tenant_user_action 
    ON pagw.audit_log(tenant, user_id, action, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_audit_log_tenant_resource 
    ON pagw.audit_log(tenant, resource_type, resource_id, created_at DESC);


-- ============================================================================
-- SECTION 9: Helper Functions for Observability
-- ============================================================================

-- Function to get request timeline with retry context
CREATE OR REPLACE FUNCTION pagw.get_request_timeline(p_pagw_id VARCHAR)
RETURNS TABLE (
    sequence_no BIGINT,
    event_type TEXT,
    stage VARCHAR,
    attempt INT,
    execution_status TEXT,
    duration_ms BIGINT,
    worker_id VARCHAR,
    created_at TIMESTAMP WITH TIME ZONE,
    details JSONB
) AS $$
BEGIN
    RETURN QUERY
    SELECT 
        et.sequence_no,
        et.event_type::TEXT,
        et.stage,
        et.attempt,
        et.execution_status::TEXT,
        et.duration_ms,
        et.worker_id,
        et.created_at,
        et.details
    FROM pagw.event_tracker et
    WHERE et.pagw_id = p_pagw_id
    ORDER BY et.sequence_no;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION pagw.get_request_timeline IS 'Get complete event timeline for a request with retry context';


-- Function to identify failed stages with retry potential
CREATE OR REPLACE FUNCTION pagw.get_failed_retryable_events(
    p_tenant VARCHAR DEFAULT NULL,
    p_minutes_ago INT DEFAULT 60
)
RETURNS TABLE (
    pagw_id VARCHAR,
    stage VARCHAR,
    event_type TEXT,
    attempt INT,
    next_retry_at TIMESTAMP WITH TIME ZONE,
    error_code VARCHAR,
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE
) AS $$
BEGIN
    RETURN QUERY
    SELECT 
        et.pagw_id,
        et.stage,
        et.event_type::TEXT,
        et.attempt,
        et.next_retry_at,
        et.error_code,
        et.error_message,
        et.created_at
    FROM pagw.event_tracker et
    WHERE et.retryable = true
      AND et.execution_status = 'FAILURE'
      AND et.created_at > NOW() - (p_minutes_ago || ' minutes')::INTERVAL
      AND (p_tenant IS NULL OR et.tenant = p_tenant)
    ORDER BY et.created_at DESC;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION pagw.get_failed_retryable_events IS 'Identify failed events eligible for retry';


-- ============================================================================
-- SECTION 10: Materialized View for Workflow Analytics
-- ============================================================================

CREATE MATERIALIZED VIEW IF NOT EXISTS pagw.mv_workflow_performance AS
SELECT 
    rt.tenant,
    rt.workflow_id,
    rt.workflow_version,
    rt.scenario_id,
    rt.status::TEXT as status,
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
GROUP BY 
    rt.tenant,
    rt.workflow_id,
    rt.workflow_version,
    rt.scenario_id,
    rt.status::TEXT,
    DATE_TRUNC('day', rt.received_at);

CREATE UNIQUE INDEX IF NOT EXISTS idx_mv_workflow_performance 
    ON pagw.mv_workflow_performance(tenant, workflow_id, workflow_version, scenario_id, status, date);

COMMENT ON MATERIALIZED VIEW pagw.mv_workflow_performance IS 'Workflow performance metrics by version and scenario';


-- ============================================================================
-- Migration Complete
-- ============================================================================

-- Add migration tracking
INSERT INTO pagw.migration_log (version, description, applied_at)
VALUES ('V005', 'Operational data model improvements: workflow versioning, event ordering, retry context, tenant isolation, attachment lifecycle, status normalization', NOW())
ON CONFLICT (version) DO NOTHING;
