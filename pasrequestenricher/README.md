# PAS Request Enricher Service

## Overview

The **PAS Request Enricher** enriches validated prior authorization requests with external data. It performs member eligibility lookups and provider directory lookups to augment the claim data before it proceeds to the canonical mapping stage.

### Core Responsibilities
- **Eligibility Enrichment** - Lookup member eligibility data (status, plan, coverage)
- **Provider Enrichment** - Lookup provider directory data (NPI, specialty, network status)
- **S3 Storage** - Store enriched data for downstream services
- **Event Tracking** - Record enrichment events (ENRICH_START, ENRICH_OK, ENRICH_FAIL)
- **Graceful Degradation** - Continue processing if external lookups fail

## Technical Stack

| Technology | Version | Purpose |
|------------|---------|--------|
| Java | 17 | Runtime |
| Spring Boot | 3.3.0 | Application framework |
| Spring Cloud AWS SQS | 3.1.1 | SQS integration |
| AWS SDK | 2.25.0 | AWS services (S3, SQS) |
| PostgreSQL | 15+ | Database (Aurora) |

## Port & Endpoints

**Server Port:** `8083`

> **Note**: This service is SQS-driven with no custom REST endpoints.

### Actuator Endpoints
| Endpoint | Description |
|----------|-------------|
| `/actuator/health` | Health check |
| `/actuator/info` | Application info |
| `/actuator/metrics` | Metrics |

## Enrichment Data

### Eligibility Enrichment
| Field | Description |
|-------|-------------|
| `status` | ACTIVE/INACTIVE |
| `effectiveDate` | Coverage start date |
| `terminationDate` | Coverage end date |
| `planName` | Insurance plan name |
| `planType` | HMO, PPO, etc. |
| `networkStatus` | In/out of network |
| `deductibleMet` | Deductible status |
| `copay` | Copay amount |
| `coinsurance` | Coinsurance percentage |

### Provider Enrichment
| Field | Description |
|-------|-------------|
| `npi` | National Provider Identifier |
| `name` | Provider name |
| `type` | Provider type |
| `specialty` | Medical specialty |
| `networkStatus` | Network tier |
| `tier` | Provider tier |
| `address` | Practice address |
| `acceptingPatients` | Accepting new patients |
| `languages` | Languages spoken |

## External Service Integration

| Service | URL Config | Purpose |
|---------|-----------|--------|
| Eligibility Service | `PAGW_ELIGIBILITY_SERVICE_URL` | Member eligibility lookup |
| Provider Directory | `PAGW_PROVIDER_DIRECTORY_URL` | Provider data lookup |

### API Endpoints Called
- `GET /eligibility/{memberId}` - Eligibility lookup
- `GET /providers/npi/{npi}` - Provider lookup

## SQS Queues

### Reads From
| Queue | Purpose |
|-------|--------|
| `pagw-request-enricher-queue.fifo` | Receives validated requests |

### Writes To (via Outbox)
| Queue | Purpose |
|-------|--------|
| `pagw-request-converter-queue.fifo` | Sends enriched requests |
| `pagw-dlq.fifo` | Processing errors |

## Database Tables

| Table | Operation | Purpose |
|-------|-----------|--------|
| `request_tracker` | UPDATE | Status updates (ENRICHING → ENRICHED) |
| `event_tracker` | INSERT | Event logging |
| `outbox` | INSERT | Transactional outbox |

## S3 Storage

### Key Patterns
| Operation | Path Pattern |
|-----------|-------------|
| Read validated data | `{YYYYMM}/{pagwId}/request/validated.json` |
| Write enriched data | `{YYYYMM}/{pagwId}/request/enriched.json` |

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | 8083 | HTTP server port |
| `PAGW_SQS_REQUEST_ENRICHER_QUEUE` | - | Input SQS queue |
| `PAGW_SQS_REQUEST_CONVERTER_QUEUE` | - | Output queue |
| `PAGW_ELIGIBILITY_SERVICE_URL` | http://eligibility-service:8080 | Eligibility service |
| `PAGW_PROVIDER_DIRECTORY_URL` | http://provider-directory:8080 | Provider directory |

## Key Classes

| Class | Responsibility |
|-------|---------------|
| `RequestEnricherApplication` | Spring Boot entry point |
| `RequestEnricherListener` | SQS message consumer |
| `RequestEnricherService` | Core enrichment logic |
| `EligibilityClient` | REST client for eligibility service |
| `ProviderDirectoryClient` | REST client for provider directory |
| `EnrichmentResult` | Enriched data container |

## Error Handling

### Graceful Degradation
- **Eligibility lookup failure**: Logs warning, continues without eligibility data
- **Provider lookup failure**: Logs warning, continues without provider data
- **Mock data fallback**: Returns mock data when external services unavailable

### Request Tracker Status Updates
- `ENRICHING` - Processing started
- `ENRICHED` - Successfully enriched
- `ENRICHMENT_ERROR` - Failed with error details

## Running Locally

```bash
cd pasrequestenricher/source
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

## Building

```bash
cd pasrequestenricher/source
mvn clean package -DskipTests
docker build -t pasrequestenricher .
```

## Related Services

- **Upstream**: pasbusinessvalidator (port 8082)
- **Downstream**: pasrequestconverter (port 8085)
