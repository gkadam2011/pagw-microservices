# PAS Request Parser Service

## Overview

The **PAS Request Parser** is a microservice responsible for **parsing and validating FHIR R4 bundles** in the Prior Authorization Gateway (PAGW) pipeline. It extracts structured data from incoming FHIR bundles and routes requests to downstream services.

### Core Responsibilities
- **Parse FHIR R4 Bundles** - Extract claim, patient, practitioner, organization, and attachment data
- **FHIR Structure Validation** - Validate bundle structure (resourceType, entry array)
- **Data Extraction** - Extract structured FHIR data using `FhirExtractionService`
- **Attachment Detection** - Identify `DocumentReference` and `Binary` resources
- **S3 Storage** - Store parsed data for downstream services
- **Event Tracking** - Record parsing events (PARSE_START, PARSE_OK, PARSE_FAIL)
- **Request Routing** - Route to Business Validator (main flow) and Attachment Handler (if attachments exist)

## Technical Stack

| Technology | Version | Purpose |
|------------|---------|---------|
| Java | 17 | Runtime |
| Spring Boot | 3.3.0 | Application framework |
| Spring Cloud AWS SQS | 3.1.1 | SQS integration |
| AWS SDK | 2.25.0 | AWS services (S3, SQS) |
| PostgreSQL | 15+ | Database (Aurora) |
| HAPI FHIR | 6.8.0 | FHIR parsing |

## Port & Endpoints

**Server Port:** `8081`

### REST API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/pas/api/v1/parse` | Synchronous FHIR bundle parsing |
| `GET` | `/pas/api/v1/health` | Health check endpoint |
| `GET` | `/actuator/health` | Spring Actuator health |
| `GET` | `/actuator/info` | Application info |
| `GET` | `/actuator/metrics` | Metrics |

### Parse Request Format
```json
{
  "pagwId": "PAGW-xxx",
  "tenant": "tenant-id",
  "fhirBundle": "{ ... FHIR JSON ... }"
}
```

### Parse Response Format
```json
{
  "success": true,
  "pagwId": "PAGW-xxx",
  "parsedBundle": { ... },
  "hasAttachments": true,
  "attachmentCount": 2,
  "errors": []
}
```

## Architecture

### Data Flow

```
┌─────────────────┐    SQS    ┌──────────────────┐
│  Orchestrator   │──────────▶│  Request Parser  │
└─────────────────┘           └────────┬─────────┘
                                       │
                                       ▼
                              ┌────────────────┐
                              │  1. Get bundle │
                              │     from S3    │
                              └────────┬───────┘
                                       │
                                       ▼
                              ┌────────────────┐
                              │ 2. Parse FHIR  │
                              │    Bundle      │
                              └────────┬───────┘
                                       │
                                       ▼
                              ┌────────────────┐
                              │ 3. Extract     │
                              │    FHIR Data   │
                              └────────┬───────┘
                                       │
                                       ▼
                              ┌────────────────┐
                              │ 4. Store to S3 │
                              └────────┬───────┘
                                       │
              ┌────────────────────────┴────────────────────────┐
              │                                                  │
              ▼                                                  ▼
┌─────────────────────┐                          ┌─────────────────────┐
│ Business Validator  │                          │ Attachment Handler  │
│   (Main Flow)       │                          │   (If attachments)  │
└─────────────────────┘                          └─────────────────────┘
```

## SQS Queues

### Reads From
| Queue | Purpose |
|-------|---------|
| `pagw-request-parser-queue.fifo` | Receives messages from orchestrator |

### Writes To (via Outbox)
| Queue | Purpose |
|-------|---------|
| `pagw-business-validator-queue.fifo` | Main flow - sends parsed data |
| `pagw-attachment-handler-queue.fifo` | Parallel flow - if attachments exist |
| `pagw-dlq.fifo` | Parse failures |

## Database Tables

| Table | Operation | Purpose |
|-------|-----------|---------|
| `request_tracker` | UPDATE | Status updates (PARSING → PARSED), FHIR metadata |
| `event_tracker` | INSERT | Event logging (PARSE_START, PARSE_OK, PARSE_FAIL) |
| `outbox` | INSERT | Transactional outbox pattern for SQS messages |

## S3 Storage

### Buckets
| Bucket | Usage |
|--------|-------|
| `PAGW_S3_REQUEST_BUCKET` | Read raw bundles, store parsed data |
| `PAGW_S3_AUDIT_BUCKET` | Audit logs |

### S3 Key Patterns
| Operation | Path Pattern |
|-----------|--------------|
| Read raw bundle | `{YYYYMM}/{pagwId}/request/raw.json` |
| Write parsed data | `{YYYYMM}/{pagwId}/request/parsed.json` |
| Write extracted FHIR | `{YYYYMM}/{pagwId}/request/fhir_extracted.json` |

## FHIR Resource Extraction

### Extracted Resources

| Resource Type | Extracted Fields |
|---------------|------------------|
| **Claim** | id, type, use, status, created, priority, total, provider, patient, insurance, line items |
| **Patient** | id, identifiers (member ID), name, birthDate, gender, address |
| **Practitioner** | id, identifiers (NPI), name, qualifications |
| **Organization** | id, identifiers, name, type, address |
| **DocumentReference** | id, status, contentType, url, title |
| **Binary** | id, contentType, base64 data |

### ParsedFhirData Output
- Patient with member ID
- Practitioner with NPI
- Diagnosis codes (stored in request_tracker for fast queries)
- Procedure codes
- Urgent indicator

## Configuration

### Key Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | 8081 | HTTP server port |
| `PAGW_SQS_REQUEST_PARSER_QUEUE` | - | Input SQS queue |
| `PAGW_SQS_BUSINESS_VALIDATOR_QUEUE` | - | Output queue for validator |
| `PAGW_S3_REQUEST_BUCKET` | - | S3 bucket for requests |
| `PAGW_AWS_ENDPOINT` | - | AWS endpoint |
| `AWS_REGION` | us-east-2 | AWS region |

### Database Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `PAGW_AURORA_WRITER_ENDPOINT` | localhost | Aurora writer endpoint |
| `PAGW_AURORA_PORT` | 5432 | Database port |
| `PAGW_AURORA_DATABASE` | pagw | Database name |
| `PAGW_AURORA_MAX_POOL_SIZE` | 10 | Max connection pool |
| `PAGW_AURORA_MIN_IDLE` | 2 | Min idle connections |

## Key Classes

| Class | Responsibility |
|-------|----------------|
| `RequestParserApplication` | Spring Boot entry point |
| `ParserController` | REST API for synchronous parse requests |
| `RequestParserListener` | SQS listener for async parsing |
| `RequestParserService` | Core parsing logic, FHIR bundle extraction |
| `ParseResult` | Parse result DTO (valid, errors, data, attachments) |
| `ParsedClaim` | Extracted claim data model |

## Error Handling

### Error Types
| Error Type | Handling |
|------------|----------|
| Invalid Bundle Structure | Return validation errors, status PARSE_FAIL |
| Missing resourceType | Validation error |
| Empty entries | Validation error |
| FHIR Extraction Failure | Log warning, continue (non-blocking) |
| S3 Failure | Exception propagated |

### Error Events
- `PARSE_VALIDATION_ERROR` - Bundle validation failed
- `PARSE_EXCEPTION` - Unexpected exception with retry scheduled

### Request Tracker Status Updates
- `PARSING` - Processing started
- `PARSED` - Successfully parsed
- `PARSE_FAILED` - Parsing failed (with error message)

## Running Locally

```bash
# Start dependencies (PostgreSQL, LocalStack)
docker-compose -f docker-compose.infra.yml up -d

# Build and run
cd pasrequestparser/source
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

## Building

```bash
# Build JAR
cd pasrequestparser/source
mvn clean package -DskipTests

# Build Docker image
docker build -t pasrequestparser .
```

## Testing

```bash
# Run tests
cd pasrequestparser/source
mvn test
```

## Health Check

```bash
curl http://localhost:8081/actuator/health
```

## Resource Limits

| Resource | Request | Limit |
|----------|---------|-------|
| CPU | 200m | 500m |
| Memory | 512Mi | 1Gi |

## Related Services

- **Upstream**: pasorchestrator (port 8080)
- **Downstream**: pasbusinessvalidator (port 8082), pasattachmenthandler (port 8084)
