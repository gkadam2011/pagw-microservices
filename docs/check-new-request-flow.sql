-- Check the full workflow history for the new request
SELECT pagw_id, status, service_name, updated_at
FROM pagw.request_tracker
WHERE pagw_id = 'PAGW-20251225-00001-3E6D0FD7'
ORDER BY updated_at ASC;

{
"SELECT *\n--pagw_id, status, service_name, updated_at\nFROM pagw.request_tracker\nWHERE pagw_id = 'PAGW-20251225-00001-3E6D0FD7'\nORDER BY updated_at ASC": [
	{
		"pagw_id" : "PAGW-20251225-00001-3E6D0FD7",
		"tenant" : "ANTHEM",
		"source_system" : "APIGEE",
		"request_type" : "SUBMIT",
		"idempotency_key" : "PAGW-20251225-00001-3E6D0FD7",
		"correlation_id" : null,
		"status" : "COMPLETED",
		"last_stage" : "response-builder",
		"next_stage" : null,
		"workflow_id" : null,
		"last_error_code" : null,
		"last_error_msg" : null,
		"retry_count" : 0,
		"raw_s3_bucket" : "crln-pagw-dev-dataz-gbd-phi-useast2",
		"raw_s3_key" : "202512\/PAGW-20251225-00001-3E6D0FD7\/request\/raw.json",
		"enriched_s3_bucket" : null,
		"enriched_s3_key" : null,
		"final_s3_bucket" : "crln-pagw-dev-dataz-gbd-phi-useast2",
		"final_s3_key" : "202512\/PAGW-20251225-00001-3E6D0FD7\/response\/fhir.json",
		"external_request_id" : null,
		"external_reference_id" : "EXT-46B0AC0A",
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
		"async_queued_at" : "2025-12-25T02:42:13.555Z",
		"received_at" : "2025-12-25T02:42:12.957Z",
		"completed_at" : "2025-12-25T02:43:17.746Z",
		"callback_sent_at" : null,
		"expires_at" : null,
		"created_at" : "2025-12-25T02:42:13.555Z",
		"updated_at" : "2025-12-25T02:43:17.746Z"
	}
]}




-- Check all outbox entries for this request
SELECT id, aggregate_id, event_type, destination_queue, status, retry_count, created_at, processed_at
FROM pagw.outbox
WHERE aggregate_id = 'PAGW-20251225-00001-3E6D0FD7'
ORDER BY created_at ASC;
"id","aggregate_id","event_type","destination_queue","status","retry_count","created_at","processed_at"
"2abfa5d9-b5c1-4915-88b1-f3379d0ee856",PAGW-20251225-00001-3E6D0FD7,REQUEST_PARSER,https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-orchestrator-complete-queue.fifo,COMPLETED,0,2025-12-24 21:42:13.555 -0500,2025-12-24 21:42:16.265 -0500
"433a3cc8-5547-4e71-b0a6-09961f3cac86",PAGW-20251225-00001-3E6D0FD7,REQUEST_PARSER,dev-PAGW-pagw-request-parser-queue.fifo,COMPLETED,0,2025-12-24 21:42:16.346 -0500,2025-12-24 21:42:26.356 -0500
e6249ac5-9182-4c75-ab98-be0beb2f70fb,PAGW-20251225-00001-3E6D0FD7,BUSINESS_VALIDATOR,dev-PAGW-pagw-business-validator-queue.fifo,COMPLETED,0,2025-12-24 21:42:26.555 -0500,2025-12-24 21:42:36.412 -0500
"72eb3ac1-64c8-4005-b863-50b0ec1e2465",PAGW-20251225-00001-3E6D0FD7,REQUEST_ENRICHER,dev-PAGW-pagw-request-enricher-queue.fifo,COMPLETED,0,2025-12-24 21:42:37.323 -0500,2025-12-24 21:42:46.470 -0500
"66828e20-0eb4-40d1-aea5-ea88348b2452",PAGW-20251225-00001-3E6D0FD7,CANONICAL_MAPPER,https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-request-converter-queue.fifo,COMPLETED,0,2025-12-24 21:42:46.958 -0500,2025-12-24 21:42:56.523 -0500
"803899da-6351-4dae-9af8-4fdeee3680ec",PAGW-20251225-00001-3E6D0FD7,API_CONNECTOR,https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-api-connector-queue.fifo,COMPLETED,0,2025-12-24 21:42:56.730 -0500,2025-12-24 21:43:06.573 -0500
af6fb87c-39b5-4925-b4db-b855d24dabce,PAGW-20251225-00001-3E6D0FD7,CALLBACK_HANDLER,https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-response-builder-queue.fifo,COMPLETED,0,2025-12-24 21:43:06.776 -0500,2025-12-24 21:43:16.622 -0500
"9c580e1d-43ab-4064-a024-ffb367fd5208",PAGW-20251225-00001-3E6D0FD7,SUBSCRIPTION_HANDLER,dev-PAGW-pagw-subscription-handler-queue.fifo,COMPLETED,0,2025-12-24 21:43:17.746 -0500,2025-12-24 21:43:26.685 -0500



-- Check the initial orchestrator processing
SELECT *
--pagw_id, status, service_name, updated_at
FROM pagw.request_tracker
WHERE pagw_id = 'PAGW-20251225-00001-3E6D0FD7'
  AND service_name = 'orchestrator'
ORDER BY updated_at ASC;
"pagw_id","tenant","source_system","request_type","idempotency_key","correlation_id","status","last_stage","next_stage","workflow_id","last_error_code","last_error_msg","retry_count","raw_s3_bucket","raw_s3_key","enriched_s3_bucket","enriched_s3_key","final_s3_bucket","final_s3_key","external_request_id","external_reference_id","payer_id","provider_npi","patient_member_id","client_id","member_id","provider_id","contains_phi","phi_encrypted","sync_processed","sync_processed_at","async_queued","async_queued_at","received_at","completed_at","callback_sent_at","expires_at","created_at","updated_at"
PAGW-20251225-00001-3E6D0FD7,ANTHEM,APIGEE,SUBMIT,PAGW-20251225-00001-3E6D0FD7,,COMPLETED,response-builder,,,,,0,crln-pagw-dev-dataz-gbd-phi-useast2,"202512/PAGW-20251225-00001-3E6D0FD7/request/raw.json",,,crln-pagw-dev-dataz-gbd-phi-useast2,"202512/PAGW-20251225-00001-3E6D0FD7/response/fhir.json",,EXT-46B0AC0A,,,,,,,true,false,false,,true,2025-12-24 21:42:13.555 -0500,2025-12-24 21:42:12.957 -0500,2025-12-24 21:43:17.746 -0500,,,2025-12-24 21:42:13.555 -0500,2025-12-24 21:43:17.746 -0500
