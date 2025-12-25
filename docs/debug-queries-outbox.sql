-- Debug queries for outbox and request tracking
-- Date: 2025-12-24

-- 1. Check outbox entries for specific request
SELECT id, aggregate_id, event_type, destination_queue, status, retry_count, last_error, created_at, processed_at
FROM pagw.outbox
WHERE aggregate_id = 'PAGW-20251224-00001-DBCC6FD5'
ORDER BY created_at DESC;

-- 2. Check request tracker status
SELECT pagw_id, status, stage, created_at, updated_at
FROM pagw.request_tracker
WHERE pagw_id = 'PAGW-20251224-00001-DBCC6FD5';

-- 3. Find pending entries with empty destination queues (these cause errors in outboxpublisher)
SELECT id, aggregate_id, destination_queue, status, retry_count, last_error, created_at
FROM pagw.outbox
WHERE status = 'PENDING' AND (destination_queue IS NULL OR destination_queue = '')
ORDER BY created_at DESC
LIMIT 10;

-- 4. Check all pending outbox entries (to see what's waiting to be published)
SELECT id, aggregate_id, event_type, destination_queue, status, retry_count, created_at
FROM pagw.outbox
WHERE status = 'PENDING'
ORDER BY created_at DESC
LIMIT 20;

-- 5. Clean up entries with empty destination queues (run after confirming they're invalid)
-- UPDATE pagw.outbox SET status = 'FAILED', last_error = 'Empty destination queue' WHERE status = 'PENDING' AND (destination_queue IS NULL OR destination_queue = '');
