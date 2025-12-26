-- ============================================================================
-- PAGW Database Reset Script
-- WARNING: This will DELETE ALL DATA in the pagw schema!
-- ============================================================================

-- Drop all objects in pagw schema (CASCADE handles dependencies)
DROP SCHEMA IF EXISTS pagw CASCADE;

-- Recreate empty schema
CREATE SCHEMA pagw;

-- Grant permissions (adjust as needed for your environment)
-- GRANT ALL ON SCHEMA pagw TO pagw_app;

-- ============================================================================
-- After running this script:
-- 1. Restart the pasorchestrator service
-- 2. Flyway will automatically run all migrations V001-V007
-- ============================================================================
