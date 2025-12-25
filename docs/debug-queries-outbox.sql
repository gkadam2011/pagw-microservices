-- Debug queries for outbox and request tracking
-- Date: 2025-12-24

-- 1. Check outbox entries for specific request
SELECT id, aggregate_id, event_type, destination_queue, status, retry_count, last_error, created_at, processed_at
FROM pagw.outbox
WHERE aggregate_id = 'PAGW-20251224-00001-DBCC6FD5'
ORDER BY created_at DESC;
```id	aggregate_id	event_type	destination_queue	status	retry_count	last_error	created_at	processed_at
c3725156-ef93-4000-8fb6-04315bb7a8a4	PAGW-20251224-00001-DBCC6FD5	REQUEST_PARSER		FAILED	1	Failed to get queue URL	2025-12-24 16:11:14.604 -0500
50a96991-2ca0-4cdd-aab2-bc176276c3cc	PAGW-20251224-00001-DBCC6FD5	REQUEST_PARSER	https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-orchestrator-complete-queue.fifo	COMPLETED	0		2025-12-24 13:30:09.755 -0500	2025-12-24 14:54:01.413 -0500
```

-- 2. Check request tracker status
SELECT pagw_id, status, stage, created_at, updated_at
FROM pagw.request_tracker
WHERE pagw_id = 'PAGW-20251224-00001-DBCC6FD5';
```json
{
"SELECT *\nFROM pagw.request_tracker\nWHERE pagw_id = 'PAGW-20251224-00001-DBCC6FD5'":
[
	{
		"pagw_id" : "PAGW-20251224-00001-DBCC6FD5",
		"tenant" : "ANTHEM",
		"source_system" : "APIGEE",
		"request_type" : "SUBMIT",
		"idempotency_key" : "PAGW-20251224-00001-DBCC6FD5",
		"correlation_id" : null,
		"status" : "ROUTING_TO_REQUEST_PARSER",
		"last_stage" : "orchestrator",
		"next_stage" : null,
		"workflow_id" : null,
		"last_error_code" : null,
		"last_error_msg" : null,
		"retry_count" : 0,
		"raw_s3_bucket" : "crln-pagw-dev-dataz-gbd-phi-useast2",
		"raw_s3_key" : "202512\/PAGW-20251224-00001-DBCC6FD5\/request\/raw.json",
		"enriched_s3_bucket" : null,
		"enriched_s3_key" : null,
		"final_s3_bucket" : null,
		"final_s3_key" : null,
		"external_request_id" : null,
		"external_reference_id" : null,
		"payer_id" : null,
		"provider_npi" : null,
		"patient_member_id" : null,
		"client_id" : null,
		"member_id" : null,
		"provider_id" : null,
		"contains_phi" : true,
		"phi_encrypted" : false,
		"sync_processed" : false,
		"sync_processed_at" : null,
		"async_queued" : true,
		"async_queued_at" : "2025-12-24T18:30:09.755Z",
		"received_at" : "2025-12-24T18:30:08.471Z",
		"completed_at" : null,
		"callback_sent_at" : null,
		"expires_at" : null,
		"created_at" : "2025-12-24T18:30:09.755Z",
		"updated_at" : "2025-12-24T21:11:14.604Z"
	}
]
}
````

-- 3. Find pending entries with empty destination queues (these cause errors in outboxpublisher)
SELECT id, aggregate_id, destination_queue, status, retry_count, last_error, created_at
FROM pagw.outbox
WHERE status = 'PENDING' AND (destination_queue IS NULL OR destination_queue = '')
ORDER BY created_at DESC
LIMIT 10;
``` nor ecords found ```


-- 4. Check all pending outbox entries (to see what's waiting to be published)
SELECT id, aggregate_id, event_type, destination_queue, status, retry_count, created_at
FROM pagw.outbox
WHERE status = 'PENDING'
ORDER BY created_at DESC
LIMIT 20;
``` nor ecords found ```

-- 5. Clean up entries with empty destination queues (run after confirming they're invalid)
-- UPDATE pagw.outbox SET status = 'FAILED', last_error = 'Empty destination queue' WHERE status = 'PENDING' AND (destination_queue IS NULL OR destination_queue = '');
