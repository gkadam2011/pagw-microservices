-- Check the full workflow history for the new request
SELECT pagw_id, status, service_name, updated_at
FROM pagw.request_tracker
WHERE pagw_id = 'PAGW-20251225-00001-3E6D0FD7'
ORDER BY updated_at ASC;

-- Check all outbox entries for this request
SELECT id, aggregate_id, event_type, destination_queue, status, retry_count, created_at, processed_at
FROM pagw.outbox
WHERE aggregate_id = 'PAGW-20251225-00001-3E6D0FD7'
ORDER BY created_at ASC;

-- Check the initial orchestrator processing
SELECT pagw_id, status, service_name, updated_at
FROM pagw.request_tracker
WHERE pagw_id = 'PAGW-20251225-00001-3E6D0FD7'
  AND service_name = 'orchestrator'
ORDER BY updated_at ASC;
