-- Debug queries for new request PAGW-20251225-00001-0199FDC7
-- Date: 2025-12-24

-- 1. Check outbox entries for new request
SELECT id, aggregate_id, event_type, destination_queue, status, retry_count, last_error, created_at, processed_at
FROM pagw.outbox
WHERE aggregate_id = 'PAGW-20251225-00001-0199FDC7'
ORDER BY created_at DESC;

-- 2. Check request tracker status for new request
SELECT pagw_id, status, stage, last_stage, created_at, updated_at
FROM pagw.request_tracker
WHERE pagw_id = 'PAGW-20251225-00001-0199FDC7';

-- 3. Check if the second outbox entry exists and its status
SELECT id, destination_queue, status, retry_count, last_error, created_at, processed_at
FROM pagw.outbox
WHERE aggregate_id = 'PAGW-20251225-00001-0199FDC7' 
  AND destination_queue = 'pagw-request-parser-queue';

-- 4. Check all PENDING entries for this request
SELECT id, destination_queue, status, retry_count, last_error, created_at
FROM pagw.outbox
WHERE aggregate_id = 'PAGW-20251225-00001-0199FDC7' 
  AND status = 'PENDING';

-- 5. Check if outboxpublisher is having issues with queue name resolution
SELECT id, aggregate_id, destination_queue, status, retry_count, last_error
FROM pagw.outbox
WHERE destination_queue = 'pagw-request-parser-queue'
  AND status IN ('PENDING', 'FAILED')
ORDER BY created_at DESC
LIMIT 5;
