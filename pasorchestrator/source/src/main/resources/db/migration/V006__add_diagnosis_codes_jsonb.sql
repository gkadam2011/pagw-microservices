-- ============================================================================
-- V006: Add diagnosis_codes JSONB column to request_tracker
-- Description: Store extracted diagnosis codes from FHIR extraction for fast queries
-- Author: Round 3 FHIR Extraction
-- Date: 2025-12-25
-- ============================================================================

-- Add diagnosis_codes JSONB column
ALTER TABLE pagw.request_tracker 
    ADD COLUMN IF NOT EXISTS diagnosis_codes JSONB;

-- Add GIN index for efficient JSONB queries
CREATE INDEX IF NOT EXISTS idx_request_tracker_diagnosis_codes 
    ON pagw.request_tracker USING GIN (diagnosis_codes);

-- Add comment
COMMENT ON COLUMN pagw.request_tracker.diagnosis_codes IS 
    'Array of diagnosis codes extracted from FHIR Claim resource. Format: [{"code":"E11.9","display":"Type 2 diabetes mellitus without complications","type":"principal","sequence":1}]';

-- Sample queries for diagnosis_codes:

-- 1. Find all requests with specific ICD-10 code
-- SELECT pagw_id, status, diagnosis_codes 
-- FROM pagw.request_tracker 
-- WHERE diagnosis_codes @> '[{"code": "E11.9"}]';

-- 2. Find requests with diabetes-related codes (wildcard)
-- SELECT pagw_id, status, 
--        jsonb_array_length(diagnosis_codes) as dx_count
-- FROM pagw.request_tracker 
-- WHERE diagnosis_codes::text LIKE '%E11%';

-- 3. Find requests with specific diagnosis type (principal)
-- SELECT pagw_id, status, diagnosis_codes 
-- FROM pagw.request_tracker 
-- WHERE diagnosis_codes @> '[{"type": "principal"}]';

-- 4. Count requests by diagnosis code
-- SELECT 
--     dx->>'code' as diagnosis_code,
--     dx->>'display' as diagnosis_name,
--     COUNT(*) as request_count
-- FROM pagw.request_tracker, 
--      jsonb_array_elements(diagnosis_codes) as dx
-- WHERE diagnosis_codes IS NOT NULL
-- GROUP BY dx->>'code', dx->>'display'
-- ORDER BY request_count DESC;

-- 5. Population statistics
-- SELECT 
--     'diagnosis_codes' as column_name,
--     COUNT(*) as total_rows,
--     COUNT(diagnosis_codes) as populated,
--     COUNT(*) - COUNT(diagnosis_codes) as null_count,
--     ROUND(100.0 * COUNT(diagnosis_codes) / NULLIF(COUNT(*), 0), 2) as population_pct,
--     ROUND(AVG(jsonb_array_length(diagnosis_codes)), 2) as avg_codes_per_request
-- FROM pagw.request_tracker;
