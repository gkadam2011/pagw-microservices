-- ============================================================================
-- V003: Fix attachment_tracker schema to match AttachmentHandlerService code
-- ============================================================================
-- This migration aligns the attachment_tracker table with the code expectations
-- by adding missing columns and renaming file_size_bytes to size.
-- ============================================================================

-- Step 1: Add missing columns
ALTER TABLE pagw.attachment_tracker 
    ADD COLUMN IF NOT EXISTS sequence INTEGER,
    ADD COLUMN IF NOT EXISTS checksum VARCHAR(64);

-- Step 2: Rename file_size_bytes to size (matches code)
ALTER TABLE pagw.attachment_tracker 
    RENAME COLUMN file_size_bytes TO size;

-- Step 3: Make id column compatible with UUID strings (code uses UUID.randomUUID().toString())
-- V001 defined it as BIGSERIAL, but code inserts UUID strings
-- We need to change the column type

-- First, check if there's existing data and handle the conversion
DO $$
BEGIN
    -- If table is empty or has only test data, we can safely change the type
    IF (SELECT COUNT(*) FROM pagw.attachment_tracker) = 0 THEN
        -- Table is empty, safe to alter
        ALTER TABLE pagw.attachment_tracker DROP CONSTRAINT IF EXISTS attachment_tracker_pkey;
        ALTER TABLE pagw.attachment_tracker ALTER COLUMN id TYPE VARCHAR(100);
        ALTER TABLE pagw.attachment_tracker ADD PRIMARY KEY (id);
    ELSE
        -- Table has data - create new column and migrate
        ALTER TABLE pagw.attachment_tracker ADD COLUMN id_new VARCHAR(100);
        -- For existing rows, generate UUID-like identifiers
        UPDATE pagw.attachment_tracker SET id_new = gen_random_uuid()::TEXT WHERE id_new IS NULL;
        -- Drop old constraint and column
        ALTER TABLE pagw.attachment_tracker DROP CONSTRAINT IF EXISTS attachment_tracker_pkey;
        ALTER TABLE pagw.attachment_tracker DROP CONSTRAINT IF EXISTS attachment_tracker_unique;
        ALTER TABLE pagw.attachment_tracker DROP COLUMN id;
        -- Rename new column
        ALTER TABLE pagw.attachment_tracker RENAME COLUMN id_new TO id;
        ALTER TABLE pagw.attachment_tracker ADD PRIMARY KEY (id);
    END IF;
END $$;

-- Step 4: Update the unique constraint to match code expectations
-- Code uses ON CONFLICT (id), not (pagw_id, attachment_id)
DROP INDEX IF EXISTS pagw.attachment_tracker_unique;

-- Step 5: Add indexes for performance
CREATE INDEX IF NOT EXISTS idx_attachment_tracker_pagw ON pagw.attachment_tracker(pagw_id);
CREATE INDEX IF NOT EXISTS idx_attachment_tracker_status ON pagw.attachment_tracker(status);
CREATE INDEX IF NOT EXISTS idx_attachment_tracker_sequence ON pagw.attachment_tracker(pagw_id, sequence);

-- Step 6: Add comments
COMMENT ON COLUMN pagw.attachment_tracker.sequence IS 'Sequential order of attachment in the claim (1, 2, 3...)';
COMMENT ON COLUMN pagw.attachment_tracker.checksum IS 'SHA-256 checksum of attachment content for integrity verification';
COMMENT ON COLUMN pagw.attachment_tracker.size IS 'File size in bytes';

-- ============================================================================
-- Migration complete: attachment_tracker now matches AttachmentHandlerService.java
-- ============================================================================
