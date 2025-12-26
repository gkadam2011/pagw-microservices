# PAS Callback Handler Service

## Overview

The **PAS Callback Handler** handles asynchronous callbacks and webhook notifications from downstream payer systems. It normalizes callback responses into a standardized PAGW format and routes them to the Response Builder for final response assembly.

### Core Responsibilities
- **Receive Callbacks** - REST endpoints and webhook ingestion
- **Correlate Requests** - Match callbacks to original PAGW requests
- **Normalize Status** - Convert payer statuses to PAGW standards
- **S3 Storage** - Persist callback results
- **Event Tracking** - Record callback events
- **Request Routing** - Forward to Response Builder

## Technical Stack

| Technology | Version | Purpose |
|------------|---------|--------|
| Java | 17 | Runtime |
| Spring Boot | 3.3.0 | Application framework |
| Spring Cloud AWS SQS | 3.1.1 | SQS integration |
| AWS SDK | 2.25.0 | AWS services (S3, SQS) |
| PostgreSQL | 15+ | Database (Aurora) |

## Port & Endpoints

**Server Port:** `8088`

### REST API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/v1/callbacks` | Receive callback from payer |
| `POST` | `/webhook/{targetSystem}` | Webhook endpoint for payer systems |
| `GET` | `/actuator/health` | Health check |

## Status Normalization

### Status Mapping

| Incoming Status | Normalized Status | Outcome | Is Error |
|-----------------|-------------------|---------|----------|
| ACCEPTED, COMPLETE, PROCESSED | COMPLETED | complete | No |
| PENDING, IN_PROGRESS | PENDING | queued | No |
| REJECTED, DENIED | REJECTED | error | Yes |
| ERROR, FAILED | ERROR | error | Yes |
| Unknown/Other | UNKNOWN | queued | No |

### Error Codes

| Condition | Error Code |
|-----------|------------|
| Rejected/Denied status | CLAIM_REJECTED |
| Error/Failed status | PROCESSING_ERROR |
| Processing exception | CALLBACK_PROCESSING_ERROR |
| Listener exception | CALLBACK_EXCEPTION |

## Dual Entry Architecture

### Path 1: Direct REST API
1. Payer sends callback to `POST /api/v1/callbacks`
2. Controller validates and looks up pagwId
3. Creates PagwMessage with stage CALLBACK_HANDLER
4. Writes to outbox
5. Returns 202 Accepted

### Path 2: Webhook Ingestion
1. Payer sends to `POST /webhook/{targetSystem}`
2. Controller parses raw JSON payload
3. Extracts pagwId from common fields
4. Delegates to callback handler

### Path 3: SQS Queue Processing
1. CallbackHandlerListener receives SQS message
2. Fetches original response from S3
3. Calls CallbackHandlerService for normalization
4. Stores CallbackResult to S3
5. Writes to outbox for Response Builder

## SQS Queues

### Reads From
| Queue | Purpose |
|-------|--------|
| `pagw-callback-handler-queue.fifo` | Receives API connector responses |

### Writes To (via Outbox)
| Queue | Purpose |
|-------|--------|
| `pagw-response-builder-queue.fifo` | Sends normalized callbacks |
| `pagw-dlq.fifo` | Failed messages |

## Database Tables

| Table | Operation | Purpose |
|-------|-----------|--------|
| `request_tracker` | UPDATE | Status (PROCESSING_CALLBACK → CALLBACK_PROCESSED) |
| `event_tracker` | INSERT | Event logging (CALLBACK_START, CALLBACK_OK, CALLBACK_FAIL) |
| `outbox` | INSERT | Transactional outbox |

## S3 Storage

### Key Patterns
| Operation | Path Pattern |
|-----------|-------------|
| Read payer response | `{YYYYMM}/{pagwId}/payer_raw.json` |
| Write callback result | `{YYYYMM}/{pagwId}/callback_result.json` |

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | 8088 | HTTP server port |
| `PAGW_SQS_REQUEST_QUEUE` | - | Input SQS queue |
| `PAGW_SQS_RESPONSE_BUILDER_QUEUE` | - | Output queue |
| `PAGW_S3_REQUEST_BUCKET` | - | S3 bucket |
| `AWS_REGION` | us-east-2 | AWS region |

## Key Classes

| Class | Responsibility |
|-------|---------------|
| `CallbackHandlerApplication` | Spring Boot entry point |
| `CallbackController` | REST endpoints for callbacks/webhooks |
| `CallbackHandlerListener` | SQS message consumer |
| `CallbackHandlerService` | Core normalization logic |
| `CallbackRequest` | Incoming callback DTO |
| `CallbackResult` | Processing result DTO |

## Error Handling

### Controller Layer
- Missing PAGW ID → 400 Bad Request
- Webhook parsing failure → 400 Bad Request

### Listener Layer
- Log CALLBACK_FAIL event
- Update tracker with error status
- Re-throw to trigger SQS retry/DLQ

### Service Layer
- Status set to ERROR
- Error code: CALLBACK_PROCESSING_ERROR
- Error message captured

## Running Locally

```bash
cd pascallbackhandler/source
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

## Building

```bash
cd pascallbackhandler/source
mvn clean package -DskipTests
docker build -t pascallbackhandler .
```

## Related Services

- **Upstream**: pasapiconnector (port 8086)
- **Downstream**: pasresponsebuilder (port 8087)
