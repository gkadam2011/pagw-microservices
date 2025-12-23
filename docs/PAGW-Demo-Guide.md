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
Show tables:
- `request_tracker` - Tracks request lifecycle
- `audit_log` - Audit trail for compliance
- `outbox` - Transactional outbox pattern

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
