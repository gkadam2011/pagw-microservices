# PAS Request Converter Service

## Overview

The **PAS Request Converter** transforms enriched FHIR data into per-target (canonical) payloads for downstream claims processing systems. It acts as a format converter, converting enriched healthcare data to target-specific formats like X12 837P (Professional), 837I (Institutional), 837D (Dental), NCPDP (Pharmacy), etc.

### Core Responsibilities
- **Format Conversion** - Convert FHIR to target-specific formats (X12 278, NCPDP)
- **Target System Routing** - Determine target system based on claim type
- **Header Generation** - Add target-specific headers for routing
- **S3 Storage** - Store converted payloads
- **Event Tracking** - Record conversion events

## Technical Stack

| Technology | Version | Purpose |
|------------|---------|--------|
| Java | 17 | Runtime |
| Spring Boot | 3.3.0 | Application framework |
| Spring Cloud AWS SQS | 3.1.1 | SQS integration |
| AWS SDK | 2.25.0 | AWS services (S3, SQS) |
| PostgreSQL | 15+ | Database (Aurora) |

## Port & Endpoints

**Server Port:** `8085`

> **Note**: This service is SQS-driven with no custom REST endpoints.

### Actuator Endpoints
| Endpoint | Description |
|----------|-------------|
| `/actuator/health` | Health check |
| `/actuator/info` | Application info |
| `/actuator/metrics` | Metrics |

## Claim Type to Target System Mapping

| FHIR Claim Type | Target System | X12 Format |
|-----------------|---------------|------------|
| `professional` | CLAIMS_PRO | 837P |
| `institutional` | CLAIMS_INST | 837I |
| `pharmacy` | CLAIMS_RX | NCPDP |
| `oral`/`dental` | CLAIMS_DENTAL | 837D |
| `vision` | CLAIMS_VISION | Custom |
| (other) | CLAIMS_GENERIC | Pass-through |

## Conversion Details

### Extracted Data
- Patient data (memberId, name, DOB, gender)
- Provider data (NPI, name, specialty, network status, tier)
- Insurance data (coverage reference, plan name/type, group number)
- Line items (service codes, quantities, unit prices)
- Eligibility data (if present)

### Generated Headers
- `X-Target-System` - Target payer system
- `X-PAGW-ID` - Internal tracking ID
- `X-Tenant` - Tenant identifier
- `X-Correlation-ID` - Request correlation

### Processing Metadata
```json
{
  "converterVersion": "1.0",
  "sourceFormat": "FHIR_R4",
  "targetFormat": "X12_278",
  "convertedAt": "2025-12-25T10:30:00Z"
}
```

## SQS Queues

### Reads From
| Queue | Purpose |
|-------|--------|
| `pagw-request-converter-queue.fifo` | Receives enriched requests |

### Writes To (via Outbox)
| Queue | Purpose |
|-------|--------|
| `pagw-api-connector-queue.fifo` | Sends converted payloads |
| `pagw-dlq.fifo` | Processing errors |

## Database Tables

| Table | Operation | Purpose |
|-------|-----------|--------|
| `request_tracker` | UPDATE | Status updates (CONVERTING → CONVERTED) |
| `event_tracker` | INSERT | Event logging (CONVERT_START, CONVERT_OK, CONVERT_FAIL) |
| `outbox` | INSERT | Transactional outbox |

## S3 Storage

### Key Patterns
| Operation | Path Pattern |
|-----------|-------------|
| Read enriched data | `{YYYYMM}/{pagwId}/request/enriched.json` |
| Write canonical data | `{YYYYMM}/{pagwId}/request/canonical.json` |

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | 8085 | HTTP server port |
| `PAGW_SQS_REQUEST_CONVERTER_QUEUE` | - | Input SQS queue |
| `PAGW_SQS_API_CONNECTOR_QUEUE` | - | Output queue |
| `PAGW_S3_REQUEST_BUCKET` | - | S3 bucket |
| `AWS_REGION` | us-east-2 | AWS region |

## Key Classes

| Class | Responsibility |
|-------|---------------|
| `RequestConverterApplication` | Spring Boot entry point |
| `RequestConverterListener` | SQS message consumer |
| `RequestConverterService` | Core conversion logic |
| `ConversionResult` | Conversion result wrapper |
| `ConvertedPayload` | Target payload structure |

## Error Handling

### Error Events
- `CONVERT_START` - Stage started
- `CONVERT_OK` - Success with duration and targetSystem
- `CONVERT_FAIL` - Failure with error code and message

### Request Tracker Status Updates
- `CONVERTING` - Processing started
- `CONVERTED` - Successfully converted
- `CONVERSION_ERROR` - Failed with error details

## Running Locally

```bash
cd pasrequestconverter/source
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

## Building

```bash
cd pasrequestconverter/source
mvn clean package -DskipTests
docker build -t pasrequestconverter .
```

## Related Services

- **Upstream**: pasrequestenricher (port 8083)
- **Downstream**: pasapiconnector (port 8086)
