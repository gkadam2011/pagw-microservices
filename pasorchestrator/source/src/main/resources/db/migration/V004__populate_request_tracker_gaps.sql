-- ============================================================================
-- V004: Address DATA_MODEL_GAPS_ANALYSIS.md Recommendations
-- ============================================================================
-- Purpose: Fix critical gaps in request_tracker field population
--
-- Gaps Addressed:
--   1. Add client_id FK to oauth_provider_registry (Gap 4)
--   2. Ensure provider_npi, payer_id, patient_member_id are properly indexed (Gap 1)
--   3. Add proper constraints and defaults
--
-- See: docs/DATA_MODEL_GAPS_ANALYSIS.md for complete analysis
-- ============================================================================

-- ============================================================================
-- 1. Add client_id column to request_tracker (if not exists)
-- ============================================================================
-- This establishes FK relationship to oauth_provider_registry
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_schema = 'pagw' 
        AND table_name = 'request_tracker' 
        AND column_name = 'client_id'
    ) THEN
        -- Column doesn't exist in V001, need to add it
        ALTER TABLE pagw.request_tracker 
            ADD COLUMN client_id VARCHAR(255);
        
        COMMENT ON COLUMN pagw.request_tracker.client_id IS 
            'OAuth clientId from Lambda authorizer (FK to oauth_provider_registry)';
    END IF;
END $$;

-- ============================================================================
-- 2. Create foreign key relationship to oauth_provider_registry
-- ============================================================================
-- Note: Using ON DELETE SET NULL because we don't want to cascade delete requests
--       if a provider registration is removed
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints 
        WHERE constraint_name = 'fk_request_tracker_oauth_provider'
        AND table_schema = 'pagw'
    ) THEN
        ALTER TABLE pagw.request_tracker
            ADD CONSTRAINT fk_request_tracker_oauth_provider
            FOREIGN KEY (client_id) 
            REFERENCES pagw.oauth_provider_registry(client_id)
            ON DELETE SET NULL;
    END IF;
END $$;

-- ============================================================================
-- 3. Create index on client_id for FK lookups
-- ============================================================================
CREATE INDEX IF NOT EXISTS idx_request_tracker_client_id 
    ON pagw.request_tracker(client_id);

-- ============================================================================
-- 4. Add index on provider_npi for provider analytics (Gap 1)
-- ============================================================================
CREATE INDEX IF NOT EXISTS idx_request_tracker_provider_npi 
    ON pagw.request_tracker(provider_npi) 
    WHERE provider_npi IS NOT NULL;

-- ============================================================================
-- 5. Add index on payer_id for payer analytics (Gap 1)
-- ============================================================================
CREATE INDEX IF NOT EXISTS idx_request_tracker_payer_id 
    ON pagw.request_tracker(payer_id) 
    WHERE payer_id IS NOT NULL;

-- ============================================================================
-- 6. Add index on patient_member_id for patient inquiries (Gap 1)
-- ============================================================================
CREATE INDEX IF NOT EXISTS idx_request_tracker_patient_member_id 
    ON pagw.request_tracker(patient_member_id) 
    WHERE patient_member_id IS NOT NULL;

-- ============================================================================
-- 7. Add composite index for provider+payer analytics
-- ============================================================================
CREATE INDEX IF NOT EXISTS idx_request_tracker_provider_payer 
    ON pagw.request_tracker(provider_npi, payer_id, status)
    WHERE provider_npi IS NOT NULL AND payer_id IS NOT NULL;

-- ============================================================================
-- 8. Add index on correlation_id for distributed tracing (Gap 1)
-- ============================================================================
CREATE INDEX IF NOT EXISTS idx_request_tracker_correlation_id 
    ON pagw.request_tracker(correlation_id) 
    WHERE correlation_id IS NOT NULL;

-- ============================================================================
-- 9. Add index on expires_at for cleanup jobs (Gap 1)
-- ============================================================================
CREATE INDEX IF NOT EXISTS idx_request_tracker_expires_at 
    ON pagw.request_tracker(expires_at, status)
    WHERE expires_at IS NOT NULL;

-- ============================================================================
-- 10. Add comments for critical fields
-- ============================================================================
COMMENT ON COLUMN pagw.request_tracker.provider_npi IS 
    'Submitting provider NPI - MUST be extracted from FHIR Bundle.entry[Practitioner].identifier during parsing';

COMMENT ON COLUMN pagw.request_tracker.payer_id IS 
    'Target payer identifier - MUST be extracted from FHIR Bundle.entry[Coverage].payor during parsing (FK to payer_configuration)';

COMMENT ON COLUMN pagw.request_tracker.patient_member_id IS 
    'Patient/member identifier - MUST be extracted from FHIR Bundle.entry[Patient].identifier during parsing';

COMMENT ON COLUMN pagw.request_tracker.correlation_id IS 
    'Distributed tracing correlation ID - MUST be set from X-Correlation-Id header in orchestrator';

COMMENT ON COLUMN pagw.request_tracker.expires_at IS 
    'Request expiration timestamp for cleanup - SHOULD be set to received_at + 90 days in orchestrator';

-- ============================================================================
-- 11. Create view for complete request context (combines with provider info)
-- ============================================================================
CREATE OR REPLACE VIEW pagw.v_request_with_provider_context AS
SELECT 
    -- Request tracking fields
    rt.pagw_id,
    rt.tenant,
    rt.status,
    rt.request_type,
    rt.last_stage,
    rt.received_at,
    rt.completed_at,
    
    -- OAuth provider context (from oauth_provider_registry)
    opr.client_id,
    opr.entity_name as provider_entity_name,
    opr.entity_type as provider_entity_type,
    opr.tenant as provider_tenant,
    opr.status as provider_status,
    opr.allowed_apis as provider_allowed_apis,
    
    -- Denormalized fields (for query performance)
    rt.provider_npi,
    rt.payer_id,
    rt.patient_member_id,
    
    -- Timing
    EXTRACT(EPOCH FROM (rt.completed_at - rt.received_at)) as duration_seconds,
    rt.sync_processed,
    rt.async_queued,
    
    -- Error tracking
    rt.last_error_code,
    rt.last_error_msg,
    rt.retry_count
FROM pagw.request_tracker rt
LEFT JOIN pagw.oauth_provider_registry opr ON opr.client_id = rt.client_id;

COMMENT ON VIEW pagw.v_request_with_provider_context IS 
    'Complete request context including OAuth provider registration details. Use for analytics and reporting.';

-- ============================================================================
-- 12. Create materialized view for provider activity analytics
-- ============================================================================
CREATE MATERIALIZED VIEW IF NOT EXISTS pagw.mv_provider_activity_summary AS
SELECT 
    rt.provider_npi,
    rt.payer_id,
    opr.entity_name as provider_name,
    opr.tenant,
    DATE_TRUNC('day', rt.received_at) as activity_date,
    
    -- Volume metrics
    COUNT(*) as total_requests,
    COUNT(*) FILTER (WHERE rt.sync_processed = true) as sync_requests,
    COUNT(*) FILTER (WHERE rt.async_queued = true) as async_requests,
    
    -- Status breakdown
    COUNT(*) FILTER (WHERE rt.status = 'approved') as approved_count,
    COUNT(*) FILTER (WHERE rt.status = 'denied') as denied_count,
    COUNT(*) FILTER (WHERE rt.status = 'pended') as pended_count,
    COUNT(*) FILTER (WHERE rt.status = 'failed') as failed_count,
    
    -- Performance metrics
    AVG(EXTRACT(EPOCH FROM (rt.completed_at - rt.received_at))) 
        FILTER (WHERE rt.completed_at IS NOT NULL) as avg_duration_seconds,
    MAX(EXTRACT(EPOCH FROM (rt.completed_at - rt.received_at))) 
        FILTER (WHERE rt.completed_at IS NOT NULL) as max_duration_seconds,
    
    -- Error rate
    ROUND(
        100.0 * COUNT(*) FILTER (WHERE rt.status = 'failed') / NULLIF(COUNT(*), 0),
        2
    ) as error_rate_pct
FROM pagw.request_tracker rt
LEFT JOIN pagw.oauth_provider_registry opr ON opr.client_id = rt.client_id
WHERE rt.received_at >= CURRENT_DATE - INTERVAL '90 days'
  AND rt.provider_npi IS NOT NULL
GROUP BY 
    rt.provider_npi,
    rt.payer_id,
    opr.entity_name,
    opr.tenant,
    DATE_TRUNC('day', rt.received_at);

-- Index for fast queries
CREATE UNIQUE INDEX IF NOT EXISTS idx_mv_provider_activity_unique
    ON pagw.mv_provider_activity_summary(provider_npi, payer_id, activity_date);

CREATE INDEX IF NOT EXISTS idx_mv_provider_activity_date
    ON pagw.mv_provider_activity_summary(activity_date DESC);

COMMENT ON MATERIALIZED VIEW pagw.mv_provider_activity_summary IS 
    'Provider activity analytics - Refresh daily via scheduled job. Use for dashboards and reporting.';

-- ============================================================================
-- 13. Add helpful queries as SQL functions
-- ============================================================================

-- Function: Get complete request story (request + events + provider)
CREATE OR REPLACE FUNCTION pagw.get_request_story(p_pagw_id VARCHAR)
RETURNS JSON AS $$
DECLARE
    result JSON;
BEGIN
    SELECT json_build_object(
        'request', (
            SELECT row_to_json(rt.*) 
            FROM pagw.v_request_with_provider_context rt 
            WHERE rt.pagw_id = p_pagw_id
        ),
        'events', (
            SELECT json_agg(row_to_json(et.*) ORDER BY et.started_at)
            FROM pagw.event_tracker et
            WHERE et.pagw_id = p_pagw_id
        ),
        'attachments', (
            SELECT json_agg(row_to_json(at.*) ORDER BY at.sequence)
            FROM pagw.attachment_tracker at
            WHERE at.pagw_id = p_pagw_id
        ),
        'audit_trail', (
            SELECT json_agg(row_to_json(al.*) ORDER BY al.event_timestamp DESC)
            FROM pagw.audit_log al
            WHERE al.resource_id = p_pagw_id
            LIMIT 20
        )
    ) INTO result;
    
    RETURN result;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION pagw.get_request_story(VARCHAR) IS 
    'Returns complete request story with events, attachments, and audit trail. Usage: SELECT pagw.get_request_story(''PAGW-20251225-...'')';

-- ============================================================================
-- 14. Validation queries (run after population is implemented)
-- ============================================================================

-- Check Gap 1 field population rate
-- Uncomment and run after implementation:
/*
SELECT 
    'provider_npi' as field,
    COUNT(*) as total_requests,
    COUNT(provider_npi) as populated,
    ROUND(100.0 * COUNT(provider_npi) / NULLIF(COUNT(*), 0), 2) as population_pct
FROM pagw.request_tracker
WHERE received_at >= NOW() - INTERVAL '7 days'

UNION ALL

SELECT 
    'payer_id' as field,
    COUNT(*) as total_requests,
    COUNT(payer_id) as populated,
    ROUND(100.0 * COUNT(payer_id) / NULLIF(COUNT(*), 0), 2) as population_pct
FROM pagw.request_tracker
WHERE received_at >= NOW() - INTERVAL '7 days'

UNION ALL

SELECT 
    'patient_member_id' as field,
    COUNT(*) as total_requests,
    COUNT(patient_member_id) as populated,
    ROUND(100.0 * COUNT(patient_member_id) / NULLIF(COUNT(*), 0), 2) as population_pct
FROM pagw.request_tracker
WHERE received_at >= NOW() - INTERVAL '7 days'

UNION ALL

SELECT 
    'client_id' as field,
    COUNT(*) as total_requests,
    COUNT(client_id) as populated,
    ROUND(100.0 * COUNT(client_id) / NULLIF(COUNT(*), 0), 2) as population_pct
FROM pagw.request_tracker
WHERE received_at >= NOW() - INTERVAL '7 days';
*/
