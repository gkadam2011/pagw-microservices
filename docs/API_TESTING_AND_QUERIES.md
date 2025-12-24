# PAGW API Testing & Database Queries

Quick reference for testing PAGW APIs and querying database tables.

---

## 🌐 API Testing with curl

All commands use `-k` flag to bypass SSL certificate verification for DEV/local environments.

### Health Checks

```bash
# Orchestrator health
curl -k https://pasorchestrator.pagwdev.awsdns.internal.das/actuator/health

# Business Validator health
curl -k https://pasbusinessvalidator.pagwdev.awsdns.internal.das/actuator/health

# Request Parser health
curl -k https://pasrequestparser.pagwdev.awsdns.internal.das/actuator/health

# All services health check
for service in pasorchestrator pasbusinessvalidator pasrequestparser pasrequestconverter pasrequestenricher pasattachmenthandler pasapiconnector; do
  echo "Checking $service..."
  curl -k -s https://$service.pagwdev.awsdns.internal.das/actuator/health | jq '.status'
done
```

### Submit Requests

```bash
# Submit valid bundle (should succeed)
curl -k -X POST https://pasorchestrator.pagwdev.awsdns.internal.das/submit \
  -H "Content-Type: application/json" \
  -H "X-Tenant-ID: ANTHEM" \
  -d @docs/demo/01-success-valid-bundle.json

# Submit with invalid NPI (should fail validation)
curl -k -X POST https://pasorchestrator.pagwdev.awsdns.internal.das/submit \
  -H "Content-Type: application/json" \
  -H "X-Tenant-ID: ANTHEM" \
  -d @docs/demo/07-error-invalid-practitioner-npi.json

# Submit with missing claimId (should fail)
curl -k -X POST https://pasorchestrator.pagwdev.awsdns.internal.das/submit \
  -H "Content-Type: application/json" \
  -H "X-Tenant-ID: ANTHEM" \
  -d @docs/demo/02-error-missing-claimid.json

# Submit with multiple errors
curl -k -X POST https://pasorchestrator.pagwdev.awsdns.internal.das/submit \
  -H "Content-Type: application/json" \
  -H "X-Tenant-ID: ANTHEM" \
  -d @docs/demo/20-multiple-errors.json

# Submit with correlation ID for tracking
curl -k -X POST https://pasorchestrator.pagwdev.awsdns.internal.das/submit \
  -H "Content-Type: application/json" \
  -H "X-Tenant-ID: ANTHEM" \
  -H "X-Correlation-ID: test-$(date +%s)" \
  -d @docs/demo/01-success-valid-bundle.json

# Synchronous submit (wait for response)
curl -k -X POST https://pasorchestrator.pagwdev.awsdns.internal.das/submit?syncMode=true \
  -H "Content-Type: application/json" \
  -H "X-Tenant-ID: ANTHEM" \
  -d @docs/demo/01-success-valid-bundle.json
```

### Status Inquiries

```bash
# Get request status by pagwId
curl -k https://pasorchestrator.pagwdev.awsdns.internal.das/status/PAGW-20251224-00001-44FEAF35

# Inquire status (full details)
curl -k -X POST https://pasorchestrator.pagwdev.awsdns.internal.das/inquire \
  -H "Content-Type: application/json" \
  -d '{
    "resourceType": "Bundle",
    "type": "collection",
    "entry": [{
      "resource": {
        "resourceType": "Claim",
        "id": "PAGW-20251224-00001-44FEAF35"
      }
    }]
  }'

# Get all recent requests
curl -k https://pasorchestrator.pagwdev.awsdns.internal.das/requests?limit=10
```

### Mock Payer Testing

```bash
# Test approved scenario
curl -k -X POST https://pasmockpayer.pagwdev.awsdns.internal.das/authorize \
  -H "Content-Type: application/json" \
  -d '{
    "claimId": "CLAIM-TEST-001",
    "subscriberId": "APPROVED-TEST",
    "providerNpi": "1234567893"
  }'

# Test denied scenario
curl -k -X POST https://pasmockpayer.pagwdev.awsdns.internal.das/authorize \
  -H "Content-Type: application/json" \
  -d '{
    "claimId": "CLAIM-TEST-002",
    "subscriberId": "DENIED-TEST",
    "providerNpi": "1234567893"
  }'

# Test pended/more info scenario
curl -k -X POST https://pasmockpayer.pagwdev.awsdns.internal.das/authorize \
  -H "Content-Type: application/json" \
  -d '{
    "claimId": "CLAIM-TEST-003",
    "subscriberId": "MOREINFO-TEST",
    "providerNpi": "1234567893"
  }'

# Test timeout scenario
curl -k -X POST https://pasmockpayer.pagwdev.awsdns.internal.das/authorize \
  -H "Content-Type: application/json" \
  -d '{
    "claimId": "CLAIM-TEST-004",
    "subscriberId": "TIMEOUT-TEST",
    "providerNpi": "1234567893"
  }'
```

### Parser Service

```bash
# Parse claim synchronously
curl -k -X POST https://pasrequestparser.pagwdev.awsdns.internal.das/parse \
  -H "Content-Type: application/json" \
  -d @docs/demo/01-success-valid-bundle.json
```

### Validator Service

```bash
# Validate claim synchronously
curl -k -X POST https://pasbusinessvalidator.pagwdev.awsdns.internal.das/validate \
  -H "Content-Type: application/json" \
  -d '{
    "pagwId": "PAGW-TEST-001",
    "claimId": "CLAIM-001",
    "claimType": "professional",
    "patientReference": "Patient/patient-001",
    "providerReference": "Practitioner/practitioner-001"
  }'
```

### Pretty Print JSON Response

```bash
# Use jq for formatted output
curl -k -X POST https://pasorchestrator.pagwdev.awsdns.internal.das/submit \
  -H "Content-Type: application/json" \
  -H "X-Tenant-ID: ANTHEM" \
  -d @docs/demo/01-success-valid-bundle.json | jq '.'

# Save response to file
curl -k -X POST https://pasorchestrator.pagwdev.awsdns.internal.das/submit \
  -H "Content-Type: application/json" \
  -H "X-Tenant-ID: ANTHEM" \
  -d @docs/demo/01-success-valid-bundle.json \
  -o response.json
```

---

## 🗄️ Database Queries

Connect to PostgreSQL database:
```bash
# Via psql
psql -h aapsql-apm1082022-00dev01.czq1gklw7b57.us-east-2.rds.amazonaws.com \
     -p 5432 \
     -U pagwuser \
     -d pagwdb

# Set schema
SET search_path TO pagw;
```

### Request Tracker Queries

```sql
-- View all recent requests
SELECT 
    pagw_id,
    status,
    stage,
    tenant,
    correlation_id,
    created_at,
    updated_at
FROM pagw.request_tracker
ORDER BY created_at DESC
LIMIT 20;

-- Find request by pagwId
SELECT * FROM pagw.request_tracker 
WHERE pagw_id = 'PAGW-20251224-00001-44FEAF35';

-- Count requests by status
SELECT 
    status,
    COUNT(*) as count
FROM pagw.request_tracker
GROUP BY status
ORDER BY count DESC;

-- Find failed requests
SELECT 
    pagw_id,
    status,
    stage,
    error_code,
    error_message,
    created_at
FROM pagw.request_tracker
WHERE status IN ('ERROR', 'VALIDATION_FAILED', 'TIMEOUT')
ORDER BY created_at DESC
LIMIT 20;

-- Requests by tenant
SELECT 
    tenant,
    status,
    COUNT(*) as count
FROM pagw.request_tracker
GROUP BY tenant, status
ORDER BY tenant, count DESC;

-- Recent requests with correlation ID
SELECT 
    pagw_id,
    correlation_id,
    status,
    stage,
    created_at
FROM pagw.request_tracker
WHERE correlation_id IS NOT NULL
ORDER BY created_at DESC
LIMIT 20;

-- Average processing time by stage
SELECT 
    stage,
    AVG(EXTRACT(EPOCH FROM (updated_at - created_at))) as avg_seconds,
    COUNT(*) as count
FROM pagw.request_tracker
WHERE stage IS NOT NULL
GROUP BY stage
ORDER BY avg_seconds DESC;

-- Requests in last hour
SELECT 
    pagw_id,
    status,
    stage,
    created_at
FROM pagw.request_tracker
WHERE created_at > NOW() - INTERVAL '1 hour'
ORDER BY created_at DESC;

-- Stuck requests (in progress > 10 minutes)
SELECT 
    pagw_id,
    status,
    stage,
    created_at,
    updated_at,
    EXTRACT(EPOCH FROM (NOW() - updated_at))/60 as minutes_stuck
FROM pagw.request_tracker
WHERE status IN ('PROCESSING', 'SUBMITTED', 'VALIDATING')
  AND updated_at < NOW() - INTERVAL '10 minutes'
ORDER BY created_at DESC;
```

### Event Tracker Queries

```sql
-- View all recent events
SELECT 
    event_id,
    pagw_id,
    event_type,
    service_name,
    status,
    created_at
FROM pagw.event_tracker
ORDER BY created_at DESC
LIMIT 50;

-- Events for specific request
SELECT 
    event_id,
    event_type,
    service_name,
    status,
    duration_ms,
    created_at
FROM pagw.event_tracker
WHERE pagw_id = 'PAGW-20251224-00001-44FEAF35'
ORDER BY created_at ASC;

-- Event timeline for a request
SELECT 
    event_type,
    service_name,
    status,
    TO_CHAR(created_at, 'YYYY-MM-DD HH24:MI:SS.MS') as timestamp,
    duration_ms
FROM pagw.event_tracker
WHERE pagw_id = 'PAGW-20251224-00001-44FEAF35'
ORDER BY created_at ASC;

-- Count events by service
SELECT 
    service_name,
    event_type,
    COUNT(*) as count
FROM pagw.event_tracker
GROUP BY service_name, event_type
ORDER BY service_name, count DESC;

-- Failed events
SELECT 
    event_id,
    pagw_id,
    event_type,
    service_name,
    error_message,
    created_at
FROM pagw.event_tracker
WHERE status = 'FAILED'
ORDER BY created_at DESC
LIMIT 20;

-- Average duration by service
SELECT 
    service_name,
    event_type,
    AVG(duration_ms) as avg_duration_ms,
    MIN(duration_ms) as min_duration_ms,
    MAX(duration_ms) as max_duration_ms,
    COUNT(*) as count
FROM pagw.event_tracker
WHERE duration_ms IS NOT NULL
GROUP BY service_name, event_type
ORDER BY avg_duration_ms DESC;

-- Events with errors
SELECT 
    pagw_id,
    service_name,
    event_type,
    error_code,
    error_message,
    created_at
FROM pagw.event_tracker
WHERE error_code IS NOT NULL
ORDER BY created_at DESC
LIMIT 20;
```

### Outbox Table Queries

```sql
-- View pending outbox messages
SELECT 
    id,
    aggregate_type,
    aggregate_id,
    event_type,
    created_at,
    processed_at,
    retry_count
FROM pagw.outbox
WHERE processed_at IS NULL
ORDER BY created_at ASC
LIMIT 20;

-- Outbox statistics
SELECT 
    event_type,
    COUNT(*) as total,
    SUM(CASE WHEN processed_at IS NULL THEN 1 ELSE 0 END) as pending,
    SUM(CASE WHEN processed_at IS NOT NULL THEN 1 ELSE 0 END) as processed,
    AVG(retry_count) as avg_retries
FROM pagw.outbox
GROUP BY event_type
ORDER BY total DESC;

-- Failed outbox messages (high retry count)
SELECT 
    id,
    aggregate_id,
    event_type,
    retry_count,
    created_at,
    last_attempted_at
FROM pagw.outbox
WHERE retry_count >= 3
  AND processed_at IS NULL
ORDER BY retry_count DESC, created_at ASC
LIMIT 20;

-- Outbox processing rate
SELECT 
    DATE_TRUNC('hour', created_at) as hour,
    COUNT(*) as total_messages,
    SUM(CASE WHEN processed_at IS NOT NULL THEN 1 ELSE 0 END) as processed,
    AVG(EXTRACT(EPOCH FROM (processed_at - created_at))) as avg_processing_seconds
FROM pagw.outbox
WHERE created_at > NOW() - INTERVAL '24 hours'
GROUP BY hour
ORDER BY hour DESC;
```

### Claim Data Queries

```sql
-- View recent claims
SELECT 
    pagw_id,
    claim_id,
    claim_type,
    status,
    tenant,
    total_amount,
    created_at
FROM pagw.claim_data
ORDER BY created_at DESC
LIMIT 20;

-- Claims by type and status
SELECT 
    claim_type,
    status,
    COUNT(*) as count,
    SUM(total_amount) as total_amount
FROM pagw.claim_data
GROUP BY claim_type, status
ORDER BY claim_type, count DESC;

-- High value claims
SELECT 
    pagw_id,
    claim_id,
    claim_type,
    total_amount,
    provider_npi,
    created_at
FROM pagw.claim_data
WHERE total_amount > 100000
ORDER BY total_amount DESC
LIMIT 20;

-- Claims with attachments
SELECT 
    c.pagw_id,
    c.claim_id,
    COUNT(a.attachment_id) as attachment_count,
    c.created_at
FROM pagw.claim_data c
LEFT JOIN pagw.claim_attachments a ON c.pagw_id = a.pagw_id
GROUP BY c.pagw_id, c.claim_id, c.created_at
HAVING COUNT(a.attachment_id) > 0
ORDER BY attachment_count DESC
LIMIT 20;
```

### Validation Results Queries

```sql
-- Recent validation failures
SELECT 
    pagw_id,
    validation_errors,
    validation_warnings,
    created_at
FROM pagw.validation_results
WHERE is_valid = false
ORDER BY created_at DESC
LIMIT 20;

-- Validation error summary
SELECT 
    jsonb_array_elements(validation_errors)->>'code' as error_code,
    COUNT(*) as count
FROM pagw.validation_results
WHERE validation_errors IS NOT NULL
GROUP BY error_code
ORDER BY count DESC;

-- Claims with warnings
SELECT 
    pagw_id,
    validation_warnings,
    created_at
FROM pagw.validation_results
WHERE is_valid = true
  AND jsonb_array_length(validation_warnings) > 0
ORDER BY created_at DESC
LIMIT 20;
```

### Payer Response Queries

```sql
-- Recent payer responses
SELECT 
    pagw_id,
    payer_id,
    response_status,
    authorization_number,
    created_at
FROM pagw.payer_responses
ORDER BY created_at DESC
LIMIT 20;

-- Response status summary
SELECT 
    response_status,
    COUNT(*) as count,
    AVG(response_time_ms) as avg_response_time_ms
FROM pagw.payer_responses
GROUP BY response_status
ORDER BY count DESC;

-- Approved authorizations
SELECT 
    pagw_id,
    authorization_number,
    approved_amount,
    valid_from,
    valid_to,
    created_at
FROM pagw.payer_responses
WHERE response_status = 'APPROVED'
ORDER BY created_at DESC
LIMIT 20;

-- Denied claims
SELECT 
    pagw_id,
    payer_id,
    denial_reason,
    created_at
FROM pagw.payer_responses
WHERE response_status = 'DENIED'
ORDER BY created_at DESC
LIMIT 20;
```

### ShedLock Queries

```sql
-- View active locks (for outbox publisher)
SELECT 
    name,
    locked_at,
    locked_by,
    lock_until
FROM pagw.shedlock
WHERE lock_until > NOW()
ORDER BY locked_at DESC;

-- Lock history
SELECT 
    name,
    locked_at,
    locked_by,
    lock_until,
    EXTRACT(EPOCH FROM (lock_until - locked_at)) as lock_duration_seconds
FROM pagw.shedlock
ORDER BY locked_at DESC
LIMIT 20;

-- Check if outbox publisher is running
SELECT 
    name,
    locked_by,
    locked_at,
    lock_until,
    CASE 
        WHEN lock_until > NOW() THEN 'LOCKED'
        ELSE 'UNLOCKED'
    END as status
FROM pagw.shedlock
WHERE name = 'outboxPublisher';
```

### Database Statistics

```sql
-- Table sizes
SELECT 
    schemaname,
    tablename,
    pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) AS size
FROM pg_tables
WHERE schemaname = 'pagw'
ORDER BY pg_total_relation_size(schemaname||'.'||tablename) DESC;

-- Row counts
SELECT 
    'request_tracker' as table_name, COUNT(*) as row_count FROM pagw.request_tracker
UNION ALL
SELECT 'event_tracker', COUNT(*) FROM pagw.event_tracker
UNION ALL
SELECT 'outbox', COUNT(*) FROM pagw.outbox
UNION ALL
SELECT 'claim_data', COUNT(*) FROM pagw.claim_data
UNION ALL
SELECT 'validation_results', COUNT(*) FROM pagw.validation_results
UNION ALL
SELECT 'payer_responses', COUNT(*) FROM pagw.payer_responses;

-- Today's activity
SELECT 
    'Requests Today' as metric, COUNT(*) as count FROM pagw.request_tracker WHERE created_at > CURRENT_DATE
UNION ALL
SELECT 'Events Today', COUNT(*) FROM pagw.event_tracker WHERE created_at > CURRENT_DATE
UNION ALL
SELECT 'Validations Today', COUNT(*) FROM pagw.validation_results WHERE created_at > CURRENT_DATE;
```

### Useful Aggregations

```sql
-- Request flow analysis (join request_tracker + event_tracker)
SELECT 
    r.pagw_id,
    r.status,
    r.stage,
    COUNT(e.event_id) as event_count,
    MIN(e.created_at) as first_event,
    MAX(e.created_at) as last_event,
    EXTRACT(EPOCH FROM (MAX(e.created_at) - MIN(e.created_at))) as total_seconds
FROM pagw.request_tracker r
LEFT JOIN pagw.event_tracker e ON r.pagw_id = e.pagw_id
WHERE r.created_at > NOW() - INTERVAL '1 hour'
GROUP BY r.pagw_id, r.status, r.stage
ORDER BY r.created_at DESC;

-- Complete request details
SELECT 
    r.pagw_id,
    r.status,
    r.stage,
    c.claim_id,
    c.claim_type,
    v.is_valid,
    jsonb_array_length(v.validation_errors) as error_count,
    p.response_status,
    p.authorization_number,
    r.created_at
FROM pagw.request_tracker r
LEFT JOIN pagw.claim_data c ON r.pagw_id = c.pagw_id
LEFT JOIN pagw.validation_results v ON r.pagw_id = v.pagw_id
LEFT JOIN pagw.payer_responses p ON r.pagw_id = p.pagw_id
WHERE r.created_at > NOW() - INTERVAL '1 hour'
ORDER BY r.created_at DESC;
```

### Clean-up Queries (Use with caution!)

```sql
-- Delete old processed outbox messages (older than 7 days)
DELETE FROM pagw.outbox
WHERE processed_at IS NOT NULL
  AND processed_at < NOW() - INTERVAL '7 days';

-- Delete old event tracker entries (older than 30 days)
DELETE FROM pagw.event_tracker
WHERE created_at < NOW() - INTERVAL '30 days';

-- Archive old completed requests (older than 90 days)
-- First create archive table, then:
INSERT INTO pagw.request_tracker_archive
SELECT * FROM pagw.request_tracker
WHERE created_at < NOW() - INTERVAL '90 days'
  AND status IN ('COMPLETED', 'APPROVED', 'DENIED');
  
DELETE FROM pagw.request_tracker
WHERE created_at < NOW() - INTERVAL '90 days'
  AND status IN ('COMPLETED', 'APPROVED', 'DENIED');
```

---

## 📊 Quick Monitoring Queries

### System Health Check

```sql
-- One-stop health check
SELECT 
    (SELECT COUNT(*) FROM pagw.request_tracker WHERE status IN ('PROCESSING', 'SUBMITTED')) as active_requests,
    (SELECT COUNT(*) FROM pagw.request_tracker WHERE created_at > NOW() - INTERVAL '1 hour') as requests_last_hour,
    (SELECT COUNT(*) FROM pagw.outbox WHERE processed_at IS NULL) as pending_outbox,
    (SELECT COUNT(*) FROM pagw.request_tracker WHERE status = 'ERROR' AND created_at > NOW() - INTERVAL '1 hour') as errors_last_hour,
    (SELECT AVG(duration_ms) FROM pagw.event_tracker WHERE created_at > NOW() - INTERVAL '1 hour') as avg_duration_ms;
```

### Find Specific Request

```sql
-- Search by any identifier
SELECT 
    r.*,
    c.claim_id,
    v.is_valid,
    p.response_status
FROM pagw.request_tracker r
LEFT JOIN pagw.claim_data c ON r.pagw_id = c.pagw_id
LEFT JOIN pagw.validation_results v ON r.pagw_id = v.pagw_id
LEFT JOIN pagw.payer_responses p ON r.pagw_id = p.pagw_id
WHERE r.pagw_id LIKE '%00007%'
   OR r.correlation_id LIKE '%00007%'
   OR c.claim_id LIKE '%00007%';
```

---

## 🔧 Troubleshooting Tips

### SSL Certificate Issues
Always use `-k` flag for DEV environment:
```bash
curl -k https://...
```

### Connection Timeouts
Increase timeout for slow operations:
```bash
curl -k --max-time 60 https://...
```

### Verbose Output
Debug connection issues:
```bash
curl -k -v https://pasorchestrator.pagwdev.awsdns.internal.das/actuator/health
```

### Save Headers
Capture response headers:
```bash
curl -k -i https://pasorchestrator.pagwdev.awsdns.internal.das/actuator/health
```

---

## 📝 Notes

- Replace `pasorchestrator.pagwdev.awsdns.internal.das` with actual DEV environment URL
- For PROD, remove `-k` flag and use proper SSL certificates
- Always use correlation IDs for tracking in production
- Database credentials should be stored in secrets manager
- Run clean-up queries during maintenance windows only
