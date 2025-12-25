# PAGW Data Model Gaps Analysis

## Executive Summary

The PAGW data model has **4 critical gaps** that prevent comprehensive request tracking and observability:

1. ❌ **REQUEST_TRACKER fields not populated** - 15 out of 34 columns are NEVER set, 6 partially used
2. ❌ **EVENT_TRACKER not populated** - Table exists but only CallbackHandler writes to it
3. ❌ **Sync flow not tracked** - No differentiation between sync and async processing in audit trail
4. ❌ **Provider auth not integrated** - Provider authentication happens in Lambda, disconnected from request tracking

---

## Gap 1: REQUEST_TRACKER Fields Not Populated

### Schema vs Reality

**request_tracker has 34 columns** but only **13 are actively used**. Here's the breakdown:

#### ✅ Fields Actually Used (13)

| Field | Usage | Set By |
|-------|-------|--------|
| `pagw_id` | ✅ Primary key | OrchestratorService (create) |
| `status` | ✅ Workflow state | All services (updateStatus) |
| `tenant` | ✅ Tenant ID | OrchestratorService (create) |
| `source_system` | ✅ Origin system | OrchestratorService (create) |
| `request_type` | ✅ submit/inquiry/cancel | OrchestratorService (create) |
| `last_stage` | ✅ Current stage | All services (updateStatus) |
| `workflow_id` | ✅ Workflow tracking | OrchestratorService (create) |
| `raw_s3_bucket` | ✅ Raw request location | OrchestratorService (create) |
| `raw_s3_key` | ✅ Raw request key | OrchestratorService (create) |
| `enriched_s3_bucket` | ✅ Enriched location | RequestEnricherService |
| `enriched_s3_key` | ✅ Enriched key | RequestEnricherService |
| `final_s3_bucket` | ✅ Response location | ResponseBuilderService |
| `final_s3_key` | ✅ Response key | ResponseBuilderService |

#### ❌ Fields NEVER Set (15)

| Field | Purpose | Why Not Set | Impact |
|-------|---------|-------------|--------|
| `payer_id` | Which payer (ANTHEM, UHC, etc.) | No extraction from bundle | ❌ Can't query by payer |
| `provider_npi` | Submitting provider NPI | Not extracted from FHIR | ❌ Can't track provider activity |
| `patient_member_id` | Patient member ID | Not extracted | ❌ Can't find patient's requests |
| `client_id` | API client identifier | Auth in Lambda, not passed | ❌ Can't track client usage |
| `member_id` | Alternate member ID | Not used | ❌ Duplicate of patient_member_id? |
| `provider_id` | Alternate provider ID | Not used | ❌ Duplicate of provider_npi? |
| `phi_encrypted` | PHI encryption flag | Always false | ❌ Compliance gap |
| `expires_at` | Request expiration | Not calculated | ❌ Can't cleanup old requests |
| `correlation_id` | Request correlation | Not passed through | ❌ Can't trace distributed calls |
| `next_stage` | Next processing stage | Not used | ❌ Workflow visibility gap |
| `idempotency_key` | Duplicate prevention | Set but not used for checks | ⚠️ Partial implementation |
| `external_request_id` | Payer's request ID | Only read, never set | ❌ Can't correlate with payer |
| `callback_sent_at` | Callback timestamp | Set but unused | ⚠️ Tracked but not queried |
| `sync_processed_at` | Sync completion time | Flag set, timestamp not | ⚠️ Missing timing data |
| `async_queued_at` | Queue time | Flag set, timestamp not | ⚠️ Missing timing data |

#### ⚠️ Fields Partially Used (6)

| Field | Usage | Gap |
|-------|-------|-----|
| `contains_phi` | Set to true by default | Never set to false - always assumes PHI |
| `idempotency_key` | Stored on create | Not used to check duplicates |
| `last_error_code` | Set on errors | Not always set consistently |
| `last_error_msg` | Set on errors | Not always set consistently |
| `retry_count` | Incremented on errors | Not used for retry logic |
| `external_reference_id` | Set by CallbackHandler | Only for callbacks, not payer references |

### Impact of Missing Fields

**Query Limitations**:
```sql
-- ❌ CANNOT DO: Find all requests for a provider
SELECT * FROM request_tracker WHERE provider_npi = '1234567890';
-- Result: provider_npi is always NULL

-- ❌ CANNOT DO: Find all requests for a payer
SELECT * FROM request_tracker WHERE payer_id = 'ANTHEM';
-- Result: payer_id is always NULL

-- ❌ CANNOT DO: Find all requests for a patient
SELECT * FROM request_tracker WHERE patient_member_id = 'MEM123';
-- Result: patient_member_id is always NULL

-- ❌ CANNOT DO: Find expired requests to cleanup
SELECT * FROM request_tracker WHERE expires_at < NOW();
-- Result: expires_at is always NULL

-- ❌ CANNOT DO: Analyze by correlation
SELECT * FROM request_tracker WHERE correlation_id = 'trace-123';
-- Result: correlation_id is always NULL
```

**Compliance Gaps**:
- No tracking of which provider submitted request
- No tracking of which patient the request is for
- PHI encryption status not tracked
- Can't generate provider-specific audit reports

**Operational Gaps**:
- Can't identify hot providers (high volume)
- Can't identify problematic providers (high errors)
- Can't cleanup expired requests
- Can't correlate requests across systems

---

## Gap 2: EVENT_TRACKER Table Not Populated

### Current State

**Table Schema** (V001_pagw_schema.sql):
```sql
CREATE TABLE pagw.event_tracker (
    id                      BIGSERIAL PRIMARY KEY,
    pagw_id                 VARCHAR(50) NOT NULL REFERENCES request_tracker(pagw_id),
    stage                   VARCHAR(50) NOT NULL,
    event_type              VARCHAR(50) NOT NULL,
    status                  VARCHAR(30) NOT NULL,
    external_reference      VARCHAR(100),
    duration_ms             BIGINT,
    error_code              VARCHAR(50),
    error_message           TEXT,
    started_at              TIMESTAMP WITH TIME ZONE,
    completed_at            TIMESTAMP WITH TIME ZONE,
    created_at              TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
```

**Purpose**: Track detailed event timeline for each request (per-stage audit trail)

**Who Actually Writes**: Only **CallbackHandlerService.java** (1 service out of 10!)

```java
// pascallbackhandler/CallbackHandlerService.java:142
INSERT INTO pagw.event_tracker 
(pagw_id, stage, event_type, status, external_reference, completed_at)
VALUES (?, 'callback-handler', 'CALLBACK', ?, ?, ?)
```

**Who Should Write**: All processing services:
- ❌ pasorchestrator - initial submission, routing decisions
- ❌ pasrequestparser - parsing start/end, validation results
- ❌ pasbusinessvalidator - validation checks, errors
- ❌ pasrequestenricher - enrichment sources, data added
- ❌ pasrequestconverter - mapping transformations
- ❌ pasapiconnector - payer API calls, retries
- ✅ pascallbackhandler - **ONLY ONE WRITING!**
- ❌ pasresponsebuilder - response assembly

### Impact

**Current Visibility**:
```sql
SELECT * FROM pagw.event_tracker WHERE pagw_id = 'PAGW-20251225-00001-3E6D0FD7';
```
**Result**: Only 1 row (callback event) instead of 8+ rows showing complete workflow

**Missing Data**:
- Stage-by-stage timing (duration_ms)
- Errors at specific stages
- Processing milestones
- External system interactions
- Retry attempts
- Parallel paths (attachments)

### Root Cause

**Design Intent**: event_tracker was designed to provide granular audit trail
**Implementation Reality**: Only CallbackHandler was implemented with event tracking
**Why**: Early implementation focused on getting workflow functional, observability was deferred

---

## Gap 3: Sync Flow Not Tracked in Event Trail

### Current State

**request_tracker Fields**:
```sql
sync_processed         BOOLEAN DEFAULT false
sync_processed_at      TIMESTAMP WITH TIME ZONE
async_queued           BOOLEAN DEFAULT false  
async_queued_at        TIMESTAMP WITH TIME ZONE
```

**Race Condition Prevention** (OrchestratorService.java:217):
```java
// Atomic check-and-set: Only queue if not already sync_processed or async_queued
boolean canQueue = requestTrackerService.tryMarkAsyncQueued(pagwId);
```

**Problem**: These flags prevent race conditions BUT:
- ❌ No event_tracker entries for sync processing stages
- ❌ No audit trail showing which path was taken
- ❌ No timing data for sync vs async comparison
- ❌ Can't answer: "Did this request go through sync or async path?"

### Impact

**Query for Sync Requests**:
```sql
SELECT pagw_id, sync_processed, async_queued, completed_at - received_at as duration
FROM pagw.request_tracker
WHERE sync_processed = true;
```
**Result**: Know it was sync processed, but NO details on what happened during sync processing

**Missing Observability**:
- Which stages executed synchronously?
- How long did each sync stage take?
- Which external calls were made?
- Were there retries in sync path?
- Why did sync path succeed/fail?

### Solution Needed

Every service should write event_tracker entries for BOTH paths:
```java
// Start of processing
eventTrackerService.logStageStart(pagwId, "REQUEST_PARSER", "SYNC");

// Processing...

// End of processing
eventTrackerService.logStageComplete(pagwId, "REQUEST_PARSER", "SYNC", "SUCCESS", durationMs);
```

**Differentiation**: Use `event_type` column:
- `SYNC_PARSE` vs `ASYNC_PARSE`
- `SYNC_VALIDATE` vs `ASYNC_VALIDATE`
- etc.

---

## Gap 4: Provider Auth Not Integrated in Request Tracking

### Current State

**pasproviderauth Service**:
- **Type**: AWS Lambda Authorizer (Python)
- **Location**: `c:\Users\gkada\pagw-microservices\pasproviderauth`
- **Purpose**: API Gateway authorization
- **Database**: Reads from `provider_registry` table

**Flow**:
```
Provider Request
    ↓
API Gateway
    ↓
Lambda Authorizer (pasproviderauth) ← Validates token
    ↓                                  Looks up provider_registry
IAM Policy (Allow/Deny)                Checks permissions
    ↓
Orchestrator (if Allowed)
    ↓
request_tracker created
```

**Problem**: **NO CONNECTION** between authorization and request tracking!

**Missing Data in request_tracker**:
- ❌ Which provider made the request? (provider_name, client_id)
- ❌ Which NPI submitted? (npi field in provider_registry)
- ❌ What permissions were checked?
- ❌ Was request rate-limited?
- ❌ Authentication method (JWT vs API Key)

### Impact

**Current State**:
```sql
SELECT pagw_id, tenant, source_system FROM request_tracker;
```
**Result**: 
- `tenant = "ANTHEM"` (generic)
- `source_system = "APIGEE"` (just the gateway)
- **No provider identification!**

**Questions We Can't Answer**:
- Which provider has the most requests?
- Which provider has the highest error rate?
- Are we blocking providers correctly?
- Compliance audit: "Show all requests from Provider X"

### Solution Needed

**Option 1**: Lambda passes provider context in request headers
```python
# pasproviderauth Lambda
context = {
    'provider_id': client_id,
    'provider_name': provider_name,
    'provider_npi': npi,
    'tenant': tenant
}
# Forward to Orchestrator as X-Provider-* headers
```

**Option 2**: Orchestrator looks up provider info
```java
// OrchestratorService - before creating request_tracker
ProviderInfo provider = providerService.getProviderByClientId(clientId);
tracker.setProviderId(provider.getClientId());
tracker.setProviderName(provider.getProviderName());
tracker.setProviderNpi(provider.getNpi());
```

**Recommended**: **Option 1** - Lambda already has the info, just pass it through

---

## Recommended Fixes

### Priority 1: Populate Critical request_tracker Fields

**Extract from FHIR Bundle during parsing**:

```java
// RequestParserService.java - during bundle parsing
public ParseResult parse(String fhirBundle, PagwMessage message) {
    Bundle bundle = fhirContext.newJsonParser().parseResource(Bundle.class, fhirBundle);
    
    // Extract key identifiers
    String payerId = extractPayerId(bundle);  // From Coverage.payor
    String providerNpi = extractProviderNpi(bundle);  // From Practitioner.identifier
    String patientMemberId = extractPatientMemberId(bundle);  // From Patient.identifier
    
    // Update request_tracker with extracted data
    requestTrackerService.updateIdentifiers(
        message.getPagwId(),
        payerId,
        providerNpi,
        patientMemberId
    );
    
    // Continue with parsing...
}
```

**Add RequestTrackerService method**:
```java
@Transactional
public void updateIdentifiers(String pagwId, String payerId, 
                              String providerNpi, String patientMemberId) {
    String sql = """
        UPDATE request_tracker
        SET payer_id = ?, provider_npi = ?, patient_member_id = ?, updated_at = NOW()
        WHERE pagw_id = ?
        """;
    
    jdbcTemplate.update(sql, payerId, providerNpi, patientMemberId, pagwId);
    log.info("Identifiers updated: pagwId={}, payer={}, npi={}", 
             pagwId, payerId, providerNpi);
}
```

**Set correlation_id from request headers**:
```java
// OrchestratorService - on create
tracker.setCorrelationId(request.getHeader("X-Correlation-ID"));
```

**Calculate expires_at based on business rules**:
```java
// OrchestratorService - on create
tracker.setExpiresAt(Instant.now().plus(90, ChronoUnit.DAYS));  // 90 day retention
```

**Set timestamps properly**:
```java
// RequestTrackerService.tryMarkAsyncQueued()
UPDATE request_tracker 
SET async_queued = true, async_queued_at = NOW()
WHERE pagw_id = ? AND sync_processed = false AND async_queued = false

// RequestTrackerService.markSyncProcessed() - NEW METHOD
UPDATE request_tracker
SET sync_processed = true, sync_processed_at = NOW()
WHERE pagw_id = ?
```

### Priority 2: Implement EventTrackerService

**Create EventTrackerService in pagwcore**:
```java
@Service
public class EventTrackerService {
    
    public void logStageStart(String pagwId, String stage, String eventType) {
        String sql = """
            INSERT INTO pagw.event_tracker 
            (pagw_id, stage, event_type, status, started_at)
            VALUES (?, ?, ?, 'STARTED', CURRENT_TIMESTAMP)
            """;
        jdbcTemplate.update(sql, pagwId, stage, eventType);
    }
    
    public void logStageComplete(String pagwId, String stage, String eventType, 
                                  String status, Long durationMs) {
        String sql = """
            INSERT INTO pagw.event_tracker 
            (pagw_id, stage, event_type, status, duration_ms, completed_at)
            VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            """;
        jdbcTemplate.update(sql, pagwId, stage, eventType, status, durationMs);
    }
    
    public void logStageError(String pagwId, String stage, String eventType,
                              String errorCode, String errorMsg) {
        String sql = """
            INSERT INTO pagw.event_tracker 
            (pagw_id, stage, event_type, status, error_code, error_message, completed_at)
            VALUES (?, ?, ?, 'FAILED', ?, ?, CURRENT_TIMESTAMP)
            """;
        jdbcTemplate.update(sql, pagwId, stage, eventType, errorCode, errorMsg);
    }
}
```

**Update All Services**:
```java
// Example: RequestParserListener.java
@SqsListener(value = "${pagw.aws.sqs.request-parser-queue}")
@Transactional
public void handleMessage(String messageBody, @Header String pagwIdHeader) {
    String pagwId = null;
    long startTime = System.currentTimeMillis();
    
    try {
        PagwMessage message = JsonUtils.fromJson(messageBody, PagwMessage.class);
        pagwId = message.getPagwId();
        
        eventTrackerService.logStageStart(pagwId, "REQUEST_PARSER", "ASYNC_PARSE");
        
        // ... parsing logic ...
        
        long duration = System.currentTimeMillis() - startTime;
        eventTrackerService.logStageComplete(pagwId, "REQUEST_PARSER", "ASYNC_PARSE", 
                                             "SUCCESS", duration);
    } catch (Exception e) {
        eventTrackerService.logStageError(pagwId, "REQUEST_PARSER", "ASYNC_PARSE",
                                         "PARSE_ERROR", e.getMessage());
        throw e;
    }
}
```

### Priority 3: Add Provider Tracking Fields

**Migration**: V004__add_provider_tracking.sql
```sql
ALTER TABLE pagw.request_tracker 
    ADD COLUMN provider_id VARCHAR(100),
    ADD COLUMN provider_name VARCHAR(255),
    ADD COLUMN provider_npi VARCHAR(20),
    ADD COLUMN auth_method VARCHAR(20);  -- JWT, API_KEY, etc.

CREATE INDEX idx_request_tracker_provider ON pagw.request_tracker(provider_id);
```

**Update Lambda Authorizer**:
```python
# pasproviderauth/handler.py
def lambda_handler(event, context):
    provider = validate_and_get_provider(token)
    
    # Pass provider context to Orchestrator
    return {
        'principalId': provider['client_id'],
        'policyDocument': generate_policy('Allow', event['methodArn']),
        'context': {
            'providerId': provider['client_id'],
            'providerName': provider['provider_name'],
            'providerNpi': provider['npi'],
            'authMethod': 'JWT'
        }
    }
```

**Update Orchestrator**:
```java
// OrchestratorService - extract from API Gateway context
String providerId = request.getHeader("X-Provider-Id");
String providerName = request.getHeader("X-Provider-Name");
String providerNpi = request.getHeader("X-Provider-Npi");

tracker.setProviderId(providerId);
tracker.setProviderName(providerName);
tracker.setProviderNpi(providerNpi);
tracker.setAuthMethod(request.getHeader("X-Auth-Method"));
```

### Priority 4: Sync Flow Event Tracking

**Update Sync Processing** to log events:
```java
// OrchestratorService - sync processing path
if (shouldProcessSync(request)) {
    long startTime = System.currentTimeMillis();
    
    eventTrackerService.logStageStart(pagwId, "ORCHESTRATOR", "SYNC_PROCESS");
    
    // ... sync processing stages ...
    eventTrackerService.logStageStart(pagwId, "PARSER", "SYNC_PARSE");
    parserService.parse(bundle);
    eventTrackerService.logStageComplete(pagwId, "PARSER", "SYNC_PARSE", "SUCCESS", 
                                         System.currentTimeMillis() - startTime);
    
    eventTrackerService.logStageStart(pagwId, "VALIDATOR", "SYNC_VALIDATE");
    validatorService.validate(parsedData);
    eventTrackerService.logStageComplete(pagwId, "VALIDATOR", "SYNC_VALIDATE", "SUCCESS",
                                         System.currentTimeMillis() - startTime);
    // ... etc
}
```

---

## Benefits After Fixes

### Complete Request Story

**Single Query Shows Everything**:
```sql
SELECT 
    r.pagw_id,
    r.provider_name,
    r.status,
    r.sync_processed,
    e.stage,
    e.event_type,
    e.status as stage_status,
    e.duration_ms,
    e.started_at,
    e.completed_at
FROM pagw.request_tracker r
LEFT JOIN pagw.event_tracker e ON r.pagw_id = e.pagw_id
WHERE r.pagw_id = 'PAGW-20251225-00001-3E6D0FD7'
ORDER BY e.started_at;
```

**Result Shows**:
- ✅ Which provider submitted
- ✅ Sync or async path taken
- ✅ Every stage executed
- ✅ Timing for each stage
- ✅ Errors at any stage
- ✅ Complete audit trail

### Analytics Queries

**Provider Performance**:
```sql
SELECT 
    provider_name,
    COUNT(*) as total_requests,
    AVG(EXTRACT(EPOCH FROM (completed_at - received_at))) as avg_duration_sec,
    SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END)::FLOAT / COUNT(*) as success_rate
FROM pagw.request_tracker
WHERE created_at > NOW() - INTERVAL '7 days'
GROUP BY provider_name
ORDER BY total_requests DESC;
```

**Stage Performance**:
```sql
SELECT 
    stage,
    event_type,
    COUNT(*) as executions,
    AVG(duration_ms) as avg_ms,
    MAX(duration_ms) as max_ms,
    SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) as failures
FROM pagw.event_tracker
WHERE created_at > NOW() - INTERVAL '1 day'
GROUP BY stage, event_type
ORDER BY avg_ms DESC;
```

**Sync vs Async Comparison**:
```sql
SELECT 
    CASE WHEN sync_processed THEN 'SYNC' ELSE 'ASYNC' END as path,
    COUNT(*) as requests,
    AVG(EXTRACT(EPOCH FROM (completed_at - received_at))) as avg_seconds
FROM pagw.request_tracker
WHERE completed_at IS NOT NULL
GROUP BY sync_processed;
```

**NEW: Provider Activity Report**:
```sql
SELECT 
    provider_npi,
    payer_id,
    COUNT(*) as total_requests,
    SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END) as completed,
    SUM(CASE WHEN status = 'ERROR' THEN 1 ELSE 0 END) as errors,
    AVG(EXTRACT(EPOCH FROM (completed_at - received_at))) as avg_duration_sec
FROM pagw.request_tracker
WHERE created_at > NOW() - INTERVAL '30 days'
  AND provider_npi IS NOT NULL
GROUP BY provider_npi, payer_id
ORDER BY total_requests DESC;
```

**NEW: Patient Request History**:
```sql
SELECT 
    pagw_id,
    status,
    provider_npi,
    payer_id,
    received_at,
    completed_at,
    EXTRACT(EPOCH FROM (completed_at - received_at)) as duration_sec
FROM pagw.request_tracker
WHERE patient_member_id = ?
ORDER BY received_at DESC;
```

**NEW: Expiring Requests Cleanup**:
```sql
-- Find requests ready for archival
SELECT 
    pagw_id,
    status,
    received_at,
    expires_at
FROM pagw.request_tracker
WHERE expires_at < NOW()
  AND status IN ('COMPLETED', 'ERROR')
ORDER BY expires_at
LIMIT 1000;
```

**NEW: Correlation Tracing**:
```sql
-- Trace distributed requests by correlation_id
SELECT 
    pagw_id,
    status,
    last_stage,
    correlation_id,
    received_at
FROM pagw.request_tracker
WHERE correlation_id = ?
ORDER BY received_at;
```

---

## Implementation Plan

1. **Week 1**: 
   - Populate critical request_tracker fields (payer_id, provider_npi, patient_member_id)
   - Add extraction logic in RequestParserService
   - Set correlation_id and expires_at in OrchestratorService

2. **Week 2**: 
   - Create EventTrackerService in pagwcore
   - Update 3 high-volume services (orchestrator, parser, validator)

3. **Week 3**: 
   - Update remaining services with event tracking
   - Add provider auth integration (Lambda → Orchestrator)

4. **Week 4**: 
   - Add sync flow event tracking
   - Testing and validation queries
   - Performance testing

**Effort**: ~4-5 days of development + testing

**Value**: 
- ✅ Complete request traceability (provider, patient, payer)
- ✅ Compliance-ready audit trail
- ✅ Performance analytics by provider/payer
- ✅ Proper request lifecycle management (expiration)
- ✅ Full observability for troubleshooting
