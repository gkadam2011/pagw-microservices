# PAS API Connector Service

## Overview

The **PAS API Connector** is the payer API integration component of the PAGW system. It serves as the bridge between PAGW and external claims processing systems (payers), submitting converted X12 278 prior authorization requests to payer systems.

### Core Responsibilities
- **Payer API Integration** - Connect to external claims processing APIs
- **Request Submission** - Submit converted X12 278 requests to payer systems
- **Payer Routing** - Route requests based on target system configuration
- **Response Handling** - Handle both sync and async payer responses
- **Retry Logic** - Implement retry with exponential backoff
- **Event Tracking** - Record API call events

## Technical Stack

| Technology | Version | Purpose |
|------------|---------|--------|
| Java | 17 | Runtime |
| Spring Boot | 3.3.0 | Application framework |
| Spring Cloud AWS SQS | 3.1.1 | SQS integration |
| Spring Retry | 3.3.0 | Retry logic |
| AWS SDK | 2.25.0 | AWS services (S3, SQS) |
| PostgreSQL | 15+ | Database (Aurora) |

## Port & Endpoints

**Server Port:** `8086`

> **Note**: This service is SQS-driven with no custom REST endpoints.

### Actuator Endpoints
| Endpoint | Description |
|----------|-------------|
| `/actuator/health` | Health check |
| `/actuator/info` | Application info |
| `/actuator/metrics` | Metrics |

## Payer System Integration

### Supported Target Systems

| Target System | Config Property | Description |
|---------------|-----------------|-------------|
| `CLAIMS_PRO` | `CLAIMS_PRO_URL` | Professional claims |
| `CLAIMS_INST` | `CLAIMS_INST_URL` | Institutional claims |
| `CLAIMS_RX` | `CLAIMS_RX_URL` | Pharmacy claims |
| `CLAIMS_DENTAL` | Falls back to CLAIMS_PRO | Dental claims |
| `CLAIMS_VISION` | Falls back to CLAIMS_PRO | Vision claims |
| `CARELON` | `CARELON_URL` | Carelon payer |
| `EAPI` | `EAPI_URL` | Enterprise API |

### API Endpoint Pattern
`POST {payerUrl}/api/v1/claims/submit`

### Request Headers
- `Content-Type: application/json`
- `X-PAGW-ID` - Tracking ID
- `X-Tenant` - Organization identifier
- `X-Correlation-ID` - Correlation tracking
- `X-Target-System` - Target payer system

## Retry Configuration

| Setting | Value |
|---------|-------|
| Max Attempts | 3 |
| Initial Delay | 1000ms |
| Multiplier | 2 (exponential backoff) |
| Retry On | `RestClientException` |

### HTTP Client Timeouts
- Connect Timeout: 10 seconds
- Read Timeout: 60 seconds

## SQS Queues

### Reads From
| Queue | Purpose |
|-------|--------|
| `pagw-api-connector-queue.fifo` | Receives converted X12 requests |

### Writes To (via Outbox)
| Queue | Purpose |
|-------|--------|
| `pagw-callback-handler-queue.fifo` | Sends API responses |
| `pagw-dlq.fifo` | Failed messages |

## Database Tables

| Table | Operation | Purpose |
|-------|-----------|--------|
| `request_tracker` | UPDATE | Status (SUBMITTING → SUBMITTED/AWAITING_CALLBACK), external_reference_id |
| `event_tracker` | INSERT | Event logging (API_CON_START, API_CON_OK, API_CON_ERROR) |
| `outbox` | INSERT | Transactional outbox |

## S3 Storage

### Key Patterns
| Operation | Path Pattern |
|-----------|-------------|
| Read converted payload | `{YYYYMM}/{pagwId}/request/canonical.json` |
| Write payer response | `{YYYYMM}/{pagwId}/payer_raw.json` |

## Response Handling

| Response Type | isAsync | Tracker Status | Next Step |
|--------------|---------|----------------|------------|
| Synchronous | false | SUBMITTED | → callback-handler |
| Asynchronous | true | AWAITING_CALLBACK | → callback-handler |

> **Note**: All responses (sync/async) are routed to callback-handler for normalization.

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | 8086 | HTTP server port |
| `PAGW_SQS_API_CONNECTORS_QUEUE` | - | Input SQS queue |
| `PAGW_SQS_CALLBACK_HANDLER_QUEUE` | - | Output queue |
| `CLAIMS_PRO_URL` | http://pasmockpayer:8095 | Claims Pro URL |
| `CLAIMS_INST_URL` | http://pasmockpayer:8095 | Claims Inst URL |
| `CLAIMS_RX_URL` | http://pasmockpayer:8095 | Claims RX URL |
| `CARELON_URL` | http://pasmockpayer:8095 | Carelon URL |
| `EAPI_URL` | http://pasmockpayer:8095 | EAPI URL |

## Key Classes

| Class | Responsibility |
|-------|---------------|
| `ApiConnectorApplication` | Spring Boot entry point |
| `ApiConnectorListener` | SQS message consumer |
| `ExternalApiClient` | HTTP client with retry |
| `ApiResponse` | Payer response DTO |
| `ApiConnectorConfig` | RestTemplate configuration |

## Error Handling

### Retry Logic
- 3 attempts with exponential backoff (1s → 2s → 4s)
- Automatic retry on `RestClientException`
- After max retries, message goes to DLQ

### Mock Response Fallback
In dev/test, if external API fails, a mock response is generated:
```java
{
  "externalId": "EXT-<random>",
  "status": "ACCEPTED",
  "async": false
}
```

## Running Locally

```bash
cd pasapiconnector/source
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

## Building

```bash
cd pasapiconnector/source
mvn clean package -DskipTests
docker build -t pasapiconnector .
```

## Related Services

- **Upstream**: pasrequestconverter (port 8085)
- **Downstream**: pascallbackhandler (port 8088)
- **External**: pasmockpayer (port 8095) for testing
