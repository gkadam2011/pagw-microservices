# PAS Orchestrator Service

## Overview

The **PAS Orchestrator** is the **entry point service** for the Prior Authorization Gateway (PAGW) microservices system. It implements the **Da Vinci PAS (Prior Authorization Support) Implementation Guide** for healthcare prior authorization requests.

### Core Responsibilities
- **Receive** FHIR-based prior authorization requests from API Gateway/Apigee
- **Orchestrate** synchronous and asynchronous processing workflows
- **Route** requests through the processing pipeline (Parser → Validator → Enricher → Payer API)
- **Manage** request lifecycle and status tracking
- **Support** Da Vinci PAS operations: `$submit`, `$inquiry`, Update, Cancel
- **Handle** R5 Backport subscriptions for pended authorization notifications

## Technical Stack

| Technology | Version | Purpose |
|------------|---------|---------|
| Java | 17 | Runtime |
| Spring Boot | 3.3.0 | Application framework |
| Spring Cloud AWS | 3.1.1 | AWS integration |
| AWS SDK | 2.25.0 | AWS services (S3, SQS, DynamoDB, KMS) |
| PostgreSQL | 15+ | Database (Aurora) |
| HAPI FHIR | 6.8.0 | FHIR parsing and validation |

## Port & Endpoints

**Server Port:** `8080`

### REST API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/pas/api/v1/submit` | Submit new prior authorization request ($submit) |
| `POST` | `/pas/api/v1/inquiry` | Query status of pended authorization ($inquiry) |
| `POST` | `/pas/api/v1/update` | Modify existing authorization |
| `POST` | `/pas/api/v1/cancel` | Cancel existing authorization |
| `GET` | `/pas/api/v1/status/{pagwId}` | Get request status by PAGW ID |
| `POST` | `/pas/api/v1/subscription` | Create subscription for status updates |
| `GET` | `/pas/api/v1/subscription/{id}` | Get subscription details |
| `DELETE` | `/pas/api/v1/subscription/{id}` | Delete subscription |
| `GET` | `/pas/api/v1/health` | Health check endpoint |

### Actuator Endpoints
| Endpoint | Description |
|----------|-------------|
| `/actuator/health` | Health check |
| `/actuator/info` | Application info |
| `/actuator/metrics` | Metrics |
| `/actuator/prometheus` | Prometheus metrics |

## Architecture

### Data Flow

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        SYNCHRONOUS FLOW (15s timeout)                        │
└─────────────────────────────────────────────────────────────────────────────┘

Client Request (FHIR Bundle)
    ↓
API Gateway + Lambda Authorizer
    ↓
Orchestrator Controller (POST /submit)
    ↓
OrchestratorService.processRequest()
    ├─ Generate pagwId
    ├─ Check idempotency (DynamoDB)
    ├─ Encrypt PHI fields (KMS)
    ├─ Store raw bundle to S3
    ├─ Create request_tracker record
    ↓
SyncProcessingService.processSync() [13 sec timeout]
    ├─ Call Request Parser (HTTP)
    ├─ Call Business Validator (HTTP)
    ↓
IF completed in time:
    → Return ClaimResponse (approved/denied/partial)
    
IF timeout or pended:
    → Queue to Outbox → SQS → Async pipeline
    → Return "pended" response

┌─────────────────────────────────────────────────────────────────────────────┐
│                           ASYNCHRONOUS FLOW                                  │
└─────────────────────────────────────────────────────────────────────────────┘

Orchestrator → Request Parser → Business Validator → Request Enricher
    ↓
Attachment Handler (parallel if attachments exist)
    ↓
Request Converter → API Connector → Callback Handler → Response Builder
    ↓
Subscription Handler → Webhook Notification to Client
```

### Pipeline Stages

| Stage | Service | Description |
|-------|---------|-------------|
| 1 | Request Parser | Parse and validate FHIR bundle structure |
| 2 | Business Validator | Apply business rules and validation |
| 3 | Request Enricher | Enrich with eligibility and provider data |
| 4 | Attachment Handler | Process clinical attachments (parallel) |
| 5 | Request Converter | Convert FHIR to target format (X12 278) |
| 6 | API Connector | Submit to payer system |
| 7 | Callback Handler | Handle async payer responses |
| 8 | Response Builder | Build FHIR ClaimResponse |
| 9 | Subscription Handler | Send webhook notifications |

## SQS Queues

### Reads From
| Queue | Purpose |
|-------|---------|
| `${PAGW_SQS_RESPONSE_QUEUE}` | Receives async workflow messages for routing |

### Writes To (via Outbox)
| Queue | Purpose |
|-------|---------|
| `pagw-request-parser-queue.fifo` | Initial parsing of FHIR bundles |
| `pagw-business-validator-queue.fifo` | Business rule validation |
| `pagw-request-enricher-queue.fifo` | Request enrichment |
| `pagw-request-converter-queue.fifo` | Format conversion |
| `pagw-api-connectors-queue.fifo` | Payer API calls |
| `pagw-dlq.fifo` | Dead letter queue |

## Database Schema

### Key Tables

| Table | Purpose |
|-------|---------|
| `request_tracker` | Core table tracking PA request lifecycle |
| `outbox` | Transactional outbox for reliable message publishing |
| `attachment_tracker` | Track attachment processing status |
| `provider_registry` | Payer routing and configuration |
| `event_tracker` | Event tracking and audit |

### Request Tracker Key Columns

| Column | Purpose |
|--------|---------|
| `pagw_id` | Primary key - internal tracking ID |
| `status` | Processing status |
| `last_stage` / `next_stage` | Pipeline tracking |
| `raw_s3_bucket` / `raw_s3_key` | Original request storage |
| `enriched_s3_bucket` / `enriched_s3_key` | Enriched data |
| `final_s3_bucket` / `final_s3_key` | Final response |
| `external_request_id` | Payer's preAuthRef |
| `sync_processed` / `async_queued` | Race condition flags |

## S3 Storage

### Buckets
| Bucket | Purpose |
|--------|---------|
| `PAGW_S3_REQUEST_BUCKET` | Primary bucket for request lifecycle |
| `PAGW_S3_ATTACHMENTS_BUCKET` | Clinical attachments |
| `PAGW_S3_AUDIT_BUCKET` | Audit logs |

### Path Structure
```
{bucket}/{YYYYMM}/{pagwId}/request/raw.json      - Original FHIR bundle
{bucket}/{YYYYMM}/{pagwId}/request/enriched.json - Enriched request
{bucket}/{YYYYMM}/{pagwId}/response/final.json   - Final response
{bucket}/{YYYYMM}/{pagwId}/attachments/*         - Attachments
```

## Configuration

### Key Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | 8080 | HTTP server port |
| `PAGW_SYNC_ENABLED` | true | Enable synchronous processing |
| `PAGW_SYNC_TIMEOUT_SECONDS` | 13 | Sync processing timeout |
| `PAGW_REQUEST_PARSER_URL` | - | Request parser service URL |
| `PAGW_BUSINESS_VALIDATOR_URL` | - | Business validator service URL |
| `PAGW_AWS_ENDPOINT` | - | AWS endpoint (LocalStack for dev) |
| `AWS_REGION` | us-east-2 | AWS region |

### Database Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `PAGW_AURORA_WRITER_ENDPOINT` | localhost | Aurora writer endpoint |
| `PAGW_AURORA_PORT` | 5432 | Database port |
| `PAGW_AURORA_DATABASE` | pagw | Database name |
| `PAGW_AURORA_IAM_AUTH_ENABLED` | false | IAM authentication |

## Da Vinci PAS Compliance

| Feature | Implementation |
|---------|---------------|
| 15-second response | SyncProcessingService with 13s timeout |
| $submit operation | `/pas/api/v1/submit` |
| $inquiry operation | `/pas/api/v1/inquiry` |
| Pended status | Returns "pended" with tracking ID |
| Subscriptions (R5 Backport) | REST-hook subscription support |
| Bundle.identifier idempotency | DynamoDB-based idempotency |
| Provider match validation | Compares OAuth token provider vs Claim.provider |
| Certificate types | 1=Initial, 3=Cancel, 4=Update |

## Key Classes

| Class | Responsibility |
|-------|----------------|
| `OrchestratorController` | REST endpoints for PA operations |
| `OrchestratorService` | Main orchestration logic, request processing |
| `SyncProcessingService` | Synchronous pipeline orchestration (15s) |
| `InquiryService` | Handle $inquiry operations |
| `ClaimModificationService` | Handle update/cancel operations |
| `SubscriptionService` | Manage R5 Backport subscriptions |
| `OrchestratorResponseListener` | SQS listener for routing async messages |

## Error Handling

### Idempotency
- Uses Bundle.identifier or X-Idempotency-Key header
- Stored in DynamoDB `pagw-idempotency` table
- Returns "duplicate" response if already processed

### Timeout Handling
- 13-second timeout for sync processing
- Request automatically queued for async processing on timeout
- Future cancelled on timeout

### Race Condition Prevention
- `sync_processed` and `async_queued` flags
- `tryMarkAsyncQueued()` uses atomic database update

### Retry Logic
- Outbox entries have `retry_count` and `max_retries` (default 5)
- `next_retry_at` for exponential backoff
- DLQ for permanently failed messages

## Running Locally

```bash
# Start dependencies (PostgreSQL, LocalStack)
docker-compose -f docker-compose.infra.yml up -d

# Build and run
cd pasorchestrator/source
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

## Building

```bash
# Build JAR
cd pasorchestrator/source
mvn clean package -DskipTests

# Build Docker image
docker build -t pasorchestrator .
```

## Testing

```bash
# Run tests
cd pasorchestrator/source
mvn test

# Run with coverage
mvn test jacoco:report
```

## Health Check

```bash
curl http://localhost:8080/actuator/health
```

## API Example

### Submit Prior Authorization

```bash
curl -X POST http://localhost:8080/pas/api/v1/submit \
  -H "Content-Type: application/fhir+json" \
  -H "Authorization: Bearer <token>" \
  -d @fhir-bundle.json
```

### Response (Approved)
```json
{
  "resourceType": "ClaimResponse",
  "status": "active",
  "outcome": "complete",
  "disposition": "Authorization approved",
  "preAuthRef": "AUTH-12345",
  "identifier": [{
    "system": "urn:pagw:claim-response",
    "value": "PAGW-20251225-ABC123"
  }]
}
```

### Response (Pended)
```json
{
  "resourceType": "ClaimResponse",
  "status": "active",
  "outcome": "queued",
  "disposition": "Pending clinical review",
  "identifier": [{
    "system": "urn:pagw:tracking-id",
    "value": "PAGW-20251225-ABC123"
  }]
}
```

## Related Documentation

- [Architecture Overview](../docs/architecture/)
- [API Testing Guide](../docs/API_TESTING_AND_QUERIES.md)
- [Da Vinci PAS Compliance](../docs/da-vinci-pas-compliance.md)
- [Service Matrix](../docs/SERVICE_MATRIX.md)
