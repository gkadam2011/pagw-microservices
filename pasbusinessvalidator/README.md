# PAS Business Validator Service

## Overview

The **PAS Business Validator** validates prior authorization claims against business rules before they are sent to payers. It ensures claim data meets structural and business requirements, checking for required fields, valid formats, proper references, and payer-specific constraints.

### Core Responsibilities
- **FHIR Bundle Validation** - Validate bundle structure and content
- **NPI Validation** - Validate National Provider Identifier using Luhn algorithm
- **Insurance Validation** - Validate coverage and insurance information
- **Business Rule Enforcement** - Apply configurable business rules
- **Event Tracking** - Record validation events (VAL_START, VAL_OK, VAL_FAIL)
- **Request Routing** - Route valid requests to Request Enricher, errors to Response Builder

## Technical Stack

| Technology | Version | Purpose |
|------------|---------|--------|
| Java | 17 | Runtime |
| Spring Boot | 3.3.0 | Application framework |
| Spring Cloud AWS SQS | 3.1.1 | SQS integration |
| AWS SDK | 2.25.0 | AWS services (S3, SQS) |
| PostgreSQL | 15+ | Database (Aurora) |

## Port & Endpoints

**Server Port:** `8082`

### REST API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/pas/api/v1/validate` | Synchronous validation |
| `GET` | `/pas/api/v1/health` | Health check |
| `GET` | `/actuator/health` | Spring Actuator health |

### Validate Request Format
```json
{
  "pagwId": "PAGW-xxx",
  "tenant": "tenant-id",
  "fhirBundle": "{ ... FHIR JSON ... }"
}
```

### Validate Response Format
```json
{
  "valid": true,
  "canDecideImmediately": false,
  "disposition": "A4",
  "pendedReason": "Validation passed",
  "errors": [],
  "warnings": []
}
```

## Validation Rules

### Built-in Validations

| Rule | Type | Description |
|------|------|-------------|
| Required Fields | ERROR | pagwId, claimId, claimType, patientReference, providerReference, patientData |
| Claim Type | ERROR | Must be: professional, institutional, oral, vision, pharmacy |
| Future Date | ERROR | Created date cannot be in the future |
| Old Claim | WARNING | Flags claims older than 1 year |
| Negative Amount | ERROR | Total and line items cannot be negative |
| High Value Claim | WARNING | Flags claims exceeding $1,000,000 |
| Total Mismatch | WARNING | Line item total must match claim total |
| Patient Reference | ERROR | Must start with `Patient/` |
| Provider Reference | ERROR | Must start with `Practitioner/` or `Organization/` |

### Pluggable Validation Rules

| Rule Class | Priority | Description |
|------------|----------|-------------|
| `NpiValidationRule` | 100 | Validates NPI using Luhn algorithm with prefix `80840` |
| `InsuranceValidationRule` | 90 | Validates coverage reference format, sequence, focal flag |

## SQS Queues

### Reads From
| Queue | Purpose |
|-------|--------|
| `pagw-business-validator-queue.fifo` | Receives parsed requests |

### Writes To (via Outbox)
| Queue | Purpose |
|-------|--------|
| `pagw-request-enricher-queue.fifo` | Valid requests - main flow |
| `pagw-response-builder-queue.fifo` | Validation failures |
| `pagw-dlq.fifo` | Processing errors |

## Database Tables

| Table | Operation | Purpose |
|-------|-----------|--------|
| `request_tracker` | UPDATE | Status updates (VALIDATING → VALIDATED) |
| `event_tracker` | INSERT | Event logging (VAL_START, VAL_OK, VAL_FAIL) |
| `outbox` | INSERT | Transactional outbox for SQS messages |

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | 8082 | HTTP server port |
| `PAGW_SQS_BUSINESS_VALIDATOR_QUEUE` | - | Input SQS queue |
| `PAGW_SQS_REQUEST_ENRICHER_QUEUE` | - | Output queue (success) |
| `PAGW_S3_REQUEST_BUCKET` | - | S3 bucket for requests |
| `AWS_REGION` | us-east-2 | AWS region |

## Key Classes

| Class | Responsibility |
|-------|---------------|
| `BusinessValidatorApplication` | Spring Boot entry point |
| `ValidatorController` | REST endpoints for synchronous validation |
| `BusinessValidatorListener` | SQS message handler |
| `BusinessValidatorService` | Core validation logic |
| `ValidationRule` | Interface for pluggable rules |
| `NpiValidationRule` | NPI validation using Luhn algorithm |
| `InsuranceValidationRule` | Insurance/coverage validation |
| `ValidationResult` | Validation outcome |

## Error Codes

| Code | Description |
|------|-------------|
| `REQUIRED_FIELD_MISSING` | Mandatory field not present |
| `INVALID_CLAIM_TYPE` | Claim type not in allowed list |
| `FUTURE_DATE` | Created date is in the future |
| `NEGATIVE_AMOUNT` | Amount is negative |
| `INVALID_REFERENCE_FORMAT` | Reference doesn't match expected pattern |
| `INVALID_NPI` | NPI fails format or Luhn check |
| `MISSING_COVERAGE` | Coverage reference missing |

## Running Locally

```bash
cd pasbusinessvalidator/source
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

## Building

```bash
cd pasbusinessvalidator/source
mvn clean package -DskipTests
docker build -t pasbusinessvalidator .
```

## Related Services

- **Upstream**: pasrequestparser (port 8081)
- **Downstream**: pasrequestenricher (port 8083), pasresponsebuilder (port 8087)
