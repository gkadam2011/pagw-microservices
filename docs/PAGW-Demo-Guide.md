# PAGW Microservices - Demo Guide

## Overview
This document provides a structured approach to demonstrate the PAGW (Prior Authorization Gateway) microservices platform to your team.

---

## Pre-Demo Checklist

### 1. Verify Services Are Running
```bash
# Check all pods are healthy
kubectl get pods -n pagw-dev

# Expected: All pods should show STATUS=Running, READY=1/1
```

### 2. Verify Endpoints
| Service | Health Check URL |
|---------|------------------|
| pasorchestrator | `https://pasorchestrator.pagwdev.awsdns.internal.das/actuator/health` |
| pasrequestparser | Internal only (no ingress needed) |
| Other services | Internal SQS consumers |

### 3. Tools Needed
- Postman or curl
- AWS Console access (SQS, S3, CloudWatch)
- Kubernetes dashboard or kubectl access
- Sample FHIR PAS request payload

---

## Demo Flow

### Part 1: Architecture Overview (5 min)

**Show the architecture diagram and explain:**

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         PAGW Architecture                                │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│   External Provider                                                      │
│         │                                                                │
│         ▼                                                                │
│   ┌─────────────┐                                                        │
│   │ API Gateway │ ◄── pasproviderauth (Lambda Authorizer)               │
│   └──────┬──────┘                                                        │
│          │                                                               │
│          ▼                                                               │
│   ┌──────────────────┐     ┌─────────────┐                              │
│   │  pasorchestrator │────►│  SQS Queue  │                              │
│   │  (Entry Point)   │     └──────┬──────┘                              │
│   └──────────────────┘            │                                      │
│                                   ▼                                      │
│   ┌────────────────────────────────────────────────────────────┐        │
│   │              Internal Processing Pipeline                   │        │
│   │  ┌──────────────┐  ┌─────────────────┐  ┌───────────────┐  │        │
│   │  │requestparser │─►│businessvalidator│─►│requestenricher│  │        │
│   │  └──────────────┘  └─────────────────┘  └───────┬───────┘  │        │
│   │                                                  │          │        │
│   │  ┌──────────────┐  ┌─────────────────┐  ┌───────▼───────┐  │        │
│   │  │responsebuilder│◄─│ apiconnector   │◄─│requestconverter│ │        │
│   │  └──────┬───────┘  └─────────────────┘  └───────────────┘  │        │
│   └─────────┼──────────────────────────────────────────────────┘        │
│             │                                                            │
│             ▼                                                            │
│   ┌──────────────────┐                                                   │
│   │ pascallbackhandler│ ──► External Payer Callback                     │
│   └──────────────────┘                                                   │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

**Key Points:**
- Event-driven architecture using SQS
- Each service has a single responsibility
- Async processing with sync response option
- Shared library (pagwcore) for common functionality

---

### Part 2: Live Demo - Submit a PA Request (10 min)

#### Step 1: Health Check
```bash
curl -X GET "https://pasorchestrator.pagwdev.awsdns.internal.das/actuator/health"
```

**Expected Response:**
```json
{
  "status": "UP"
}
```

#### Step 2: Submit a Sync PA Request

**Using the correct API endpoint:**
```bash
curl -X POST "https://pasorchestrator.pagwdev.awsdns.internal.das/api/v1/pas/Claim/\$submit?syncMode=true" \
  -H "Content-Type: application/json" \
  -H "X-Correlation-ID: demo-123" \
  -H "X-Tenant-ID: ANTHEM" \
  -d @test/fixtures/pas-referral-bundle-valid.json
```

**Or using the simplified internal endpoint:**
```bash
curl -X POST "https://pasorchestrator.pagwdev.awsdns.internal.das/pas/v1/submit?syncMode=true" \
  -H "Content-Type: application/json" \
  -H "X-Correlation-ID: demo-123" \
  -H "X-Tenant-ID: ANTHEM" \
  -d @test/fixtures/pas-referral-bundle-valid.json
```

**Sample Request Payloads Available:**

1. **Valid Referral Bundle**: `test/fixtures/pas-referral-bundle-valid.json`
   - Complete FHIR Bundle following Da Vinci PAS IG
   - Includes Claim, Patient, Coverage, PractitionerRole resources
   - Ready for production testing

2. **Urgent Cardiac**: `test/fixtures/pas-request-urgent-cardiac.json`
   - Urgent priority authorization
   - Cardiac procedure example

3. **Home Care**: `test/fixtures/pas-homecare-bundle.json`
   - Home healthcare services authorization

4. **Knee Replacement**: `test/fixtures/pas-request-professional-knee-replacement.json`
   - Professional claim for surgical procedure

**Using Postman Collection:**

Import the collection from `test/postman/PAGW-PAS-Collection.postman_collection.json`:
- Pre-configured requests with valid FHIR bundles
- Automatic correlation ID generation
- Response validation tests
- Variables for baseUrl and tracking IDs
```

**Expected Response (Sync Mode):**
```json
{
  "pagwId": "PAGW-20251222-00001-XXXXX",
  "status": "ACCEPTED",
  "correlationId": "demo-123",
  "timestamp": "2025-12-22T10:30:00Z",
  "response": {
    "resourceType": "ClaimResponse",
    "status": "active",
    "outcome": "complete"
  }
}
```

**Verify in Database:**
```sql
-- Check request was received and tracked
SELECT 
    pagw_id,
    status,
    last_stage,
    correlation_id,
    tenant,
    received_at,
    created_at
FROM pagw.request_tracker 
WHERE correlation_id = 'demo-123'
ORDER BY created_at DESC
LIMIT 1;

-- Expected: status='RECEIVED' or 'PROCESSING'
```

#### Step 3: Check Request Status
```bash
# Using the status endpoint
curl -X GET "https://pasorchestrator.pagwdev.awsdns.internal.das/pas/v1/status/{pagwId}" \
  -H "X-Correlation-ID: demo-123"

# Or using the inquiry endpoint for DA Vinci PAS compliance
curl -X POST "https://pasorchestrator.pagwdev.awsdns.internal.das/pas/v1/inquiry" \
  -H "Content-Type: application/json" \
  -H "X-Correlation-ID: demo-123" \
  -d @test/fixtures/pas-inquiry-bundle.json
```
**Verify Processing Flow in Database:**
```sql
-- Get full request details
SELECT 
    pagw_id,
    status,
    last_stage,
    next_stage,
    correlation_id,
    tenant,
    external_request_id,
    payer_id,
    patient_member_id,
    sync_processed,
    async_queued,
    raw_s3_key,
    enriched_s3_key,
    received_at,
    completed_at,
    updated_at
FROM pagw.request_tracker 
WHERE pagw_id = 'PAGW-20251223-00001-XXXXX';

-- Check outbox messages (transactional outbox pattern)
SELECT 
    id,
    aggregate_id AS pagw_id,
    event_type,
    destination_queue,
    status,
    retry_count,
    created_at,
    processed_at
FROM pagw.outbox 
WHERE aggregate_id = 'PAGW-20251223-00001-XXXXX'
ORDER BY created_at DESC;

-- Expected: Messages for each processing stage
```
---

### Part 3: Show Internal Processing (5 min)

#### Service Communication Flow

**Synchronous Flow (syncMode=true):**
```
Orchestrator (port 443) 
  → Parser (port 443, /pas/v1/parse)
  → Validator (port 443, /pas/v1/validate)
  → Returns response within 13 seconds
```

**Asynchronous Flow (syncMode=false):**
```
Orchestrator 
  → SQS: pagw-orchestrator-queue
  → Parser (SQS listener)
  → SQS: pagw-business-validator-queue
  → Validator (SQS listener)
  → SQS: pagw-request-enricher-queue
  → Enricher (SQS listener)
  → SQS: pagw-request-converter-queue
  → Converter → API Connector → Payer
```

**Note:** All internal K8s service-to-service calls use port 443 (ClusterIP service port)
mapping to respective container ports (8080, 8081, 8082, 8083, etc.).

#### Database Verification Queries

**1. Monitor Request Lifecycle:**
```sql
-- Real-time status of recent requests
SELECT 
    pagw_id,
    status,
    last_stage,
    correlation_id,
    DATE_TRUNC('second', received_at) as received,
    DATE_TRUNC('second', completed_at) as completed,
    EXTRACT(EPOCH FROM (completed_at - received_at)) as processing_time_sec
FROM pagw.request_tracker 
WHERE received_at > NOW() - INTERVAL '1 hour'
ORDER BY received_at DESC
LIMIT 10;
```

**2. Check Outbox Queue Status:**
```sql
-- Pending messages in outbox
SELECT 
    COUNT(*) as pending_count,
    destination_queue,
    MIN(created_at) as oldest_message
FROM pagw.outbox
WHERE status = 'PENDING'
GROUP BY destination_queue;

-- Failed messages requiring attention
SELECT 
    id,
    aggregate_id as pagw_id,
    destination_queue,
    retry_count,
    last_error,
    created_at
FROM pagw.outbox
WHERE status = 'FAILED' OR retry_count >= max_retries
ORDER BY created_at DESC
LIMIT 5;
```

**3. Track Attachment Processing:**
```sql
-- Attachment status for a request
SELECT 
    att.pagw_id,
    att.attachment_id,
    att.attachment_type,
    att.original_filename,
    att.file_size_bytes,
    att.status,
    att.s3_bucket,
    att.s3_key,
    att.created_at,
    att.processed_at
FROM pagw.attachment_tracker att
WHERE att.pagw_id = 'PAGW-20251223-00001-XXXXX';
```

**4. Performance Metrics:**
```sql
-- Average processing time by status
SELECT 
    status,
    COUNT(*) as request_count,
    ROUND(AVG(EXTRACT(EPOCH FROM (updated_at - received_at))), 2) as avg_time_sec,
    ROUND(MIN(EXTRACT(EPOCH FROM (updated_at - received_at))), 2) as min_time_sec,
    ROUND(MAX(EXTRACT(EPOCH FROM (updated_at - received_at))), 2) as max_time_sec
FROM pagw.request_tracker
WHERE received_at > NOW() - INTERVAL '24 hours'
GROUP BY status;
```

**5. Error Analysis:**
```sql
-- Recent errors
SELECT 
    pagw_id,
    status,
    last_stage,
    last_error_code,
    last_error_msg,
    retry_count,
    created_at
FROM pagw.request_tracker
WHERE status = 'ERROR' OR last_error_code IS NOT NULL
ORDER BY created_at DESC
LIMIT 10;
```

#### AWS Console - SQS Queues
1. Navigate to AWS SQS Console
2. Show the queue flow:
   - `pagw-request-queue-dev` - Entry point
   - `pagw-response-queue-dev` - Processed responses
   - `pagw-dlq-dev` - Dead letter queue for failures

#### CloudWatch Logs
1. Show log groups for each service
2. Demonstrate correlation ID tracing across services
3. Show structured logging format

#### Database (Aurora PostgreSQL)

**Connect to database:**
```bash
# Using psql
psql -h aapsql-apm1082022-00dev01.czq1gklw7b57.us-east-2.rds.amazonaws.com \
     -U pagw_admin \
     -d pagwdb

# Set schema
SET search_path TO pagw;
```

**Key Tables:**
```sql
-- List all tables in pagw schema
\dt pagw.*

-- Table descriptions
\d+ pagw.request_tracker
\d+ pagw.outbox
\d+ pagw.attachment_tracker
```

**Quick Status Dashboard:**
```sql
-- Requests by status (last 24h)
SELECT 
    status,
    COUNT(*) as count,
    ROUND(COUNT(*) * 100.0 / SUM(COUNT(*)) OVER (), 2) as percentage
FROM pagw.request_tracker
WHERE received_at > NOW() - INTERVAL '24 hours'
GROUP BY status
ORDER BY count DESC;

-- Processing pipeline health
SELECT 
    last_stage,
    COUNT(*) as stuck_count,
    MIN(updated_at) as oldest_update
FROM pagw.request_tracker
WHERE status IN ('PROCESSING', 'PENDING')
  AND updated_at < NOW() - INTERVAL '5 minutes'
GROUP BY last_stage;
```

---

### Part 4: Key Features to Highlight (5 min)

#### 1. Idempotency
```bash
# Send the same request twice with same correlation ID
# Second request returns cached response (when DynamoDB is enabled)
```

#### 2. Sync vs Async Modes
```bash
# Sync mode - waits up to 13 seconds for response (Da Vinci PAS requirement: 15 sec)
curl -X POST "https://pasorchestrator.pagwdev.awsdns.internal.das/api/v1/pas/Claim/\$submit?syncMode=true" \
  -H "Content-Type: application/json" \
  -d @test/fixtures/pas-referral-bundle-valid.json

# Async mode - returns immediately with tracking ID (202 Accepted)
curl -X POST "https://pasorchestrator.pagwdev.awsdns.internal.das/api/v1/pas/Claim/\$submit?syncMode=false" \
  -H "Content-Type: application/json" \
  -d @test/fixtures/pas-referral-bundle-valid.json
```

#### 3. Error Handling
- Show DLQ processing
- Demonstrate retry logic
- Show error response format

#### 4. Observability
- Structured logging with correlation IDs
- Health endpoints on each service
- Metrics via Spring Actuator

---

## Demo Script Talking Points

### Opening (1 min)
> "Today I'll demonstrate our PAGW microservices platform, which processes Prior Authorization requests following the Da Vinci PAS FHIR standard."

### Architecture (3 min)
> "The system uses an event-driven architecture with 11 microservices, each handling a specific responsibility. This allows us to scale individual components and maintain loose coupling."

### Live Demo (10 min)
> "Let me show you a live PA submission. Notice the correlation ID flows through all services for end-to-end traceability."

### Technical Highlights (5 min)
> "Key technical decisions include:
> - Spring Boot 3.3 with Java 17
> - AWS-native services (SQS, S3, Aurora, Secrets Manager)
> - Transactional outbox pattern for reliability
> - Shared pagwcore library for consistency"

### Q&A (5 min)

---

## Troubleshooting During Demo

| Issue | Quick Fix |
|-------|-----------|
| 500 Error | Check CloudWatch logs for stack trace |
| Timeout | Verify SQS queues are processing |
| Auth Error | Check API Gateway / Lambda authorizer |
| DB Error | Verify Aurora connectivity |

---

## Backup Demo (If Live Environment Issues)

If the live environment has issues, use these:

1. **Show Postman Collection** with pre-recorded responses
2. **Walk through code** - Show key service implementations
3. **Show CI/CD Pipeline** - Tekton build process
4. **Architecture diagrams** in `/docs/architecture/`

---

## Post-Demo Resources

Share with team:
- GitHub repo: `pagw-microservices`
- Architecture docs: `/docs/architecture/`
- API schemas: `/docs/schemas/pagw_message.v1.json`
- DLQ Runbook: `/docs/operations/dlq-runbook.md`

---

## Appendix: Full API Reference

### External API (Da Vinci PAS Compliant)

#### POST /api/v1/pas/Claim/$submit
Submit a new PA request following FHIR PAS Operation

**Endpoint:** `https://pasorchestrator.pagwdev.awsdns.internal.das/api/v1/pas/Claim/$submit`

### Internal API (Simplified)

#### POST /pas/v1/submit
Simplified PA submission endpoint

**Headers:**
| Header | Required | Description |
|--------|----------|-------------|
| Content-Type | Yes | `application/json` |
| X-Correlation-ID | Yes | Unique request identifier |
| X-Tenant-ID | No | Tenant identifier |

**Query Parameters:**
| Param | Default | Description |
|-------|---------|-------------|
| syncMode | true | Wait for response or return immediately |

### POST /pas/v1/inquiry
Query status of existing PA

### GET /pas/v1/status/{pagwId}
Get current status of a request

### GET /pas/v1/health
Service health check

### GET /actuator/health
Spring Actuator health (includes dependencies)

---

*Last Updated: December 23, 2025*

## Quick Start Checklist

- [ ] Import Postman collection from `test/postman/PAGW-PAS-Collection.postman_collection.json`
- [ ] Set `baseUrl` variable to `https://pasorchestrator.pagwdev.awsdns.internal.das`
- [ ] Verify orchestrator health: `GET /actuator/health`
- [ ] Run "Submit PA Request (Sync)" from Postman
- [ ] Verify parser and validator logs show successful processing
- [ ] Check SQS queues for async flow (if using syncMode=false)

## Common Issues & Solutions

| Issue | Solution |
|-------|----------|
| "Missing or invalid 'entry' array" | Ensure FHIR Bundle has valid entry array with Claim resource |
| Connection timeout (port 8081/8082) | Services using wrong ports - verify latest deployment with port 443 |
| Env var not resolved | Check property names use camelCase (not hyphenated) for Spring Boot binding |
| Parser/Validator not found | Verify K8s services are running: `kubectl get svc -n pagw-srv-dev` |
