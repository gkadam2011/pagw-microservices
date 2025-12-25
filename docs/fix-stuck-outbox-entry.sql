-- Fix stuck outbox entry with incorrect queue name
-- This query updates the old queue name to the correct AWS SQS queue name

-- First, check the current state
SELECT id, aggregate_id, destination_queue, status, retry_count, last_error
FROM pagw.outbox
WHERE destination_queue = 'pagw-request-parser-queue'
  AND status IN ('PENDING', 'FAILED');

-- Update the queue name to match AWS naming convention
UPDATE pagw.outbox
SET destination_queue = 'dev-PAGW-pagw-request-parser-queue.fifo',
    retry_count = 0,  -- Reset retry count
    last_error = NULL  -- Clear error
WHERE destination_queue = 'pagw-request-parser-queue'
  AND status IN ('PENDING', 'FAILED');

-- Verify the update
SELECT id, aggregate_id, destination_queue, status, retry_count
FROM pagw.outbox
WHERE id = '0a984579-e90d-4ca3-819d-7e3d0aef66c5';

-- Also check for any other outbox entries with old queue names that need fixing
SELECT DISTINCT destination_queue, status, COUNT(*) as count
FROM pagw.outbox
WHERE destination_queue NOT LIKE 'dev-PAGW-%'
  AND destination_queue NOT LIKE 'https://%'
  AND status IN ('PENDING', 'FAILED')
GROUP BY destination_queue, status
ORDER BY destination_queue;
