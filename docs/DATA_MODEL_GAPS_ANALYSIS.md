# PAGW Data Model Gaps Analysis

## Executive Summary

The PAGW data model has **3 critical gaps** that prevent comprehensive request tracking and observability:

1. ❌ **EVENT_TRACKER not populated** - Table exists but only CallbackHandler writes to it
2. ❌ **Sync flow not tracked** - No differentiation between sync and async processing in audit trail
3. ❌ **Provider auth not integrated** - Provider authentication happens in Lambda, disconnected from request tracking

---

## Gap 1: EVENT_TRACKER Table Not Populated

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

## Gap 2: Sync Flow Not Tracked in Event Trail

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

## Gap 3: Provider Auth Not Integrated in Request Tracking

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

### Priority 1: Implement EventTrackerService

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

### Priority 2: Add Provider Tracking Fields

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

### Priority 3: Sync Flow Event Tracking

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

---

## Implementation Plan

1. **Week 1**: Create EventTrackerService in pagwcore
2. **Week 2**: Update 3 high-volume services (orchestrator, parser, validator)
3. **Week 3**: Update remaining services + add provider tracking
4. **Week 4**: Testing and validation queries

**Effort**: ~3-4 days of development + testing

**Value**: Complete observability and compliance audit trail
