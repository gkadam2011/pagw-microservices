# PAS Attachment Handler Service

## Overview

The **PAS Attachment Handler** processes document attachments (clinical notes, images, lab results, etc.) associated with Prior Authorization requests. It operates as a **parallel path** in the PAGW pipeline, triggered by the Request Parser when attachments exist.

### Core Responsibilities
- **Attachment Processing** - Decode, validate, and store binary attachments
- **Content Type Validation** - Validate allowed document formats
- **Size Validation** - Enforce 50 MB attachment limit
- **Checksum Calculation** - SHA-256 hash for integrity verification
- **S3 Storage** - Store attachments in dedicated S3 bucket
- **Tracking** - Track attachment status in database

## Technical Stack

| Technology | Version | Purpose |
|------------|---------|--------|
| Java | 17 | Runtime |
| Spring Boot | 3.3.0 | Application framework |
| Spring Cloud AWS SQS | 3.1.1 | SQS integration |
| AWS SDK | 2.25.0 | AWS services (S3, SQS) |
| PostgreSQL | 15+ | Database (Aurora) |

## Port & Endpoints

**Server Port:** `8084`

### REST API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/pas/api/v1/attachments/{pagwId}/status` | Get attachment completion status |
| `GET` | `/pas/api/v1/attachments/{pagwId}/complete` | Check if all attachments processed |
| `GET` | `/pas/api/v1/attachments/{pagwId}/pending` | Get pending attachment IDs |
| `GET` | `/actuator/health` | Health check |

## Supported Content Types

| Content Type | Description |
|--------------|-------------|
| `application/pdf` | PDF documents |
| `image/jpeg` | JPEG images |
| `image/png` | PNG images |
| `image/tiff` | TIFF images |
| `application/dicom` | DICOM medical imaging |
| `text/plain` | Text files |
| `application/xml` | XML documents |
| `application/json` | JSON documents |

## Attachment Statuses

| Status | Description |
|--------|-------------|
| `PROCESSED` | Successfully stored in S3 |
| `PENDING` | Awaiting processing |
| `PENDING_RESOLUTION` | Internal reference needs resolution |
| `EXTERNAL_REFERENCE` | External URL reference |
| `REJECTED` | Invalid content type or encoding |
| `FAILED` | Storage or processing error |

## SQS Queues

### Reads From
| Queue | Purpose |
|-------|--------|
| `pagw-attachment-handler-queue.fifo` | Receives attachment processing requests |

### Note
This is a **terminal parallel path** - it does NOT write to any downstream queue.

## Database Tables

### attachment_tracker
| Column | Type | Description |
|--------|------|-------------|
| `id` | VARCHAR(100) | Primary key |
| `pagw_id` | VARCHAR(50) | FK to request_tracker |
| `sequence_number` | INTEGER | Attachment sequence |
| `attachment_id` | VARCHAR(100) | Original attachment ID |
| `content_type` | VARCHAR(100) | MIME type |
| `file_size` | BIGINT | Size in bytes |
| `checksum` | VARCHAR(64) | SHA-256 hash |
| `s3_bucket` | VARCHAR(255) | S3 bucket name |
| `s3_key` | VARCHAR(500) | S3 object key |
| `status` | VARCHAR(30) | Processing status |
| `error_message` | TEXT | Error details |

## S3 Storage

### Buckets
| Bucket | Purpose |
|--------|--------|
| `PAGW_S3_REQUEST_BUCKET` | Input: parsed claim data |
| `PAGW_S3_ATTACHMENTS_BUCKET` | Output: processed attachments |

### Key Patterns
```
pagw/attachments/{pagwId-prefix}/{pagwId}/{attachmentId}
{YYYYMM}/{pagwId}/attachments/meta.json
```

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | 8084 | HTTP server port |
| `PAGW_SQS_ATTACHMENT_HANDLER_QUEUE` | - | Input SQS queue |
| `PAGW_S3_ATTACHMENTS_BUCKET` | - | Attachments S3 bucket |
| `AWS_REGION` | us-east-2 | AWS region |

## Key Classes

| Class | Responsibility |
|-------|---------------|
| `AttachmentHandlerApplication` | Spring Boot entry point |
| `AttachmentHandlerListener` | SQS message consumer |
| `AttachmentHandlerService` | Core processing logic |
| `AttachmentController` | REST endpoints for status queries |
| `AttachmentMetadata` | Attachment metadata DTO |
| `CompletionStatus` | Completion status with counts |

## Error Handling

### Validation Errors
- Invalid content type → REJECTED
- Invalid base64 encoding → REJECTED
- Size exceeds 50MB → REJECTED
- S3 storage failure → FAILED

### Individual Attachment Errors
- Logged but don't fail entire batch
- Attachment marked with REJECTED/FAILED status
- Error message stored in `attachment_tracker.error_message`

## Running Locally

```bash
cd pasattachmenthandler/source
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

## Building

```bash
cd pasattachmenthandler/source
mvn clean package -DskipTests
docker build -t pasattachmenthandler .
```

## Related Services

- **Upstream**: pasrequestparser (port 8081) - triggers when attachments detected
- **Parallel to**: Main flow (validator → enricher → converter)
