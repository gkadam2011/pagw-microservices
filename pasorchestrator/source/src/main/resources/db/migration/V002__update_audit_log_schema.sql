-- ============================================================================
-- V002: Update audit_log schema for HIPAA compliance
-- ============================================================================
-- This migration aligns the database schema with AuditService.java requirements
-- and adds necessary columns for HIPAA-compliant PHI access tracking.
-- ============================================================================

-- Step 1: Add new columns (nullable initially for existing data)
ALTER TABLE pagw.audit_log 
    ADD COLUMN IF NOT EXISTS actor_type VARCHAR(20),
    ADD COLUMN IF NOT EXISTS actor_ip VARCHAR(50),
    ADD COLUMN IF NOT EXISTS trace_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS request_path VARCHAR(500),
    ADD COLUMN IF NOT EXISTS phi_accessed BOOLEAN DEFAULT false NOT NULL,
    ADD COLUMN IF NOT EXISTS phi_fields_accessed TEXT[],
    ADD COLUMN IF NOT EXISTS access_reason VARCHAR(500);

-- Step 2: Rename columns to match code expectations
ALTER TABLE pagw.audit_log 
    RENAME COLUMN service_name TO event_source;

ALTER TABLE pagw.audit_log 
    RENAME COLUMN user_id TO actor_id;

ALTER TABLE pagw.audit_log 
    RENAME COLUMN pagw_id TO resource_id;

ALTER TABLE pagw.audit_log 
    RENAME COLUMN action TO action_description;

-- Step 3: Change id column from BIGSERIAL to UUID
-- First, create a new UUID column
ALTER TABLE pagw.audit_log 
    ADD COLUMN id_uuid UUID DEFAULT gen_random_uuid();

-- Update existing rows to have UUID values
UPDATE pagw.audit_log SET id_uuid = gen_random_uuid() WHERE id_uuid IS NULL;

-- Drop old primary key constraint and id column
ALTER TABLE pagw.audit_log DROP CONSTRAINT audit_log_pkey;
ALTER TABLE pagw.audit_log DROP COLUMN id;

-- Rename new column and add primary key
ALTER TABLE pagw.audit_log RENAME COLUMN id_uuid TO id;
ALTER TABLE pagw.audit_log ADD PRIMARY KEY (id);

-- Step 4: Drop redundant event_id column
ALTER TABLE pagw.audit_log DROP COLUMN IF EXISTS event_id;

-- Step 5: Make critical columns NOT NULL (after data backfill if needed)
-- Set default values for existing rows
UPDATE pagw.audit_log 
SET 
    actor_type = 'SERVICE',
    event_source = COALESCE(event_source, 'pasorchestrator')
WHERE actor_type IS NULL;

-- Now make them NOT NULL
ALTER TABLE pagw.audit_log 
    ALTER COLUMN event_source SET NOT NULL,
    ALTER COLUMN actor_id SET NOT NULL,
    ALTER COLUMN actor_type SET NOT NULL,
    ALTER COLUMN resource_type SET NOT NULL,
    ALTER COLUMN resource_id SET NOT NULL;

-- Step 6: Modify action_description to TEXT for longer descriptions
ALTER TABLE pagw.audit_log 
    ALTER COLUMN action_description TYPE TEXT;

-- Step 7: Drop old indexes and create new ones
DROP INDEX IF EXISTS idx_audit_log_pagw;
DROP INDEX IF EXISTS idx_audit_log_type;
DROP INDEX IF EXISTS idx_audit_log_correlation;
DROP INDEX IF EXISTS idx_audit_log_timestamp;

-- Create optimized indexes
CREATE INDEX idx_audit_log_timestamp ON pagw.audit_log(event_timestamp);
CREATE INDEX idx_audit_log_resource ON pagw.audit_log(resource_id);
CREATE INDEX idx_audit_log_type ON pagw.audit_log(event_type);
CREATE INDEX idx_audit_log_correlation ON pagw.audit_log(correlation_id);
CREATE INDEX idx_audit_log_actor ON pagw.audit_log(actor_id);
CREATE INDEX idx_audit_log_phi ON pagw.audit_log(phi_accessed) WHERE phi_accessed = true;

-- Step 8: Add comments for documentation
COMMENT ON TABLE pagw.audit_log IS 'HIPAA-compliant audit trail for tracking PHI access and system events';
COMMENT ON COLUMN pagw.audit_log.phi_accessed IS 'Boolean flag indicating if PHI was accessed in this event';
COMMENT ON COLUMN pagw.audit_log.phi_fields_accessed IS 'Array of PHI field names accessed (for HIPAA audit trail)';
COMMENT ON COLUMN pagw.audit_log.access_reason IS 'Business justification for PHI access (required for HIPAA compliance)';
COMMENT ON COLUMN pagw.audit_log.actor_type IS 'Type of actor: USER (end user), SERVICE (microservice), SYSTEM (automated process)';
COMMENT ON COLUMN pagw.audit_log.actor_ip IS 'IP address of the actor (required for HIPAA access tracking)';

-- ============================================================================
-- Migration complete: audit_log now matches AuditService.java expectations
-- ============================================================================
