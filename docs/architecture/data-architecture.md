# PAGW Data Architecture

## Overview

The Prior Authorization Gateway (PAGW) uses a polyglot persistence strategy, employing multiple specialized data stores optimized for specific access patterns and requirements.

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                           PAGW Data Architecture                                │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                 │
│  ┌───────────────┐   ┌───────────────┐   ┌───────────────┐   ┌───────────────┐ │
│  │   DynamoDB    │   │    Aurora     │   │      S3       │   │  ElastiCache  │ │
│  │  Idempotency  │   │  PostgreSQL   │   │  File Store   │   │    Redis      │ │
│  └───────┬───────┘   └───────┬───────┘   └───────┬───────┘   └───────┬───────┘ │
│          │                   │                   │                   │         │
│  ┌───────▼───────┐   ┌───────▼───────┐   ┌───────▼───────┐   ┌───────▼───────┐ │
│  │ • Deduplication│  │ • Request     │   │ • FHIR Bundles│   │ • Session     │ │
│  │ • Fast lookup │   │   Tracking    │   │ • Attachments │   │   Cache       │ │
│  │ • TTL expiry  │   │ • Outbox      │   │ • Responses   │   │ • Rate Limits │ │
│  │ • Distributed │   │ • Audit Log   │   │ • Audit Files │   │ • Token Cache │ │
│  └───────────────┘   │ • Config      │   └───────────────┘   └───────────────┘ │
│                      └───────────────┘                                         │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## Table of Contents

1. [Storage Components](#1-storage-components)
2. [DynamoDB - Idempotency](#2-dynamodb---idempotency)
3. [Aurora PostgreSQL - Business Data](#3-aurora-postgresql---business-data)
4. [Outbox Pattern](#4-outbox-pattern)
5. [S3 - File Storage](#5-s3---file-storage)
6. [ElastiCache - Caching](#6-elasticache---caching)
7. [Data Flow](#7-data-flow)
8. [Comparison Matrix](#8-comparison-matrix)
9. [Environment Configuration](#9-environment-configuration)

---

## 1. Storage Components

### Why Polyglot Persistence?

Different data access patterns require different storage solutions:

| Access Pattern | Best Solution | Why |
|----------------|---------------|-----|
| Key-value lookup (< 1ms) | DynamoDB | Optimized for single-key access |
| Complex queries, JOINs | Aurora PostgreSQL | Full SQL capabilities |
| Large file storage | S3 | Cost-effective, scalable |
| Ephemeral caching | ElastiCache Redis | In-memory speed |
| Transactional messaging | Aurora (Outbox) | ACID guarantees |

### Component Summary

```
┌────────────────┬─────────────────────┬──────────────────┬─────────────────┐
│   Component    │      Purpose        │   Data Lifetime  │   Access Type   │
├────────────────┼─────────────────────┼──────────────────┼─────────────────┤
│ DynamoDB       │ Idempotency keys    │ 24-48 hours (TTL)│ Key lookup      │
│ Aurora         │ Business state      │ 7 years (HIPAA)  │ SQL queries     │
│ S3             │ FHIR bundles, files │ 7 years (HIPAA)  │ Object key      │
│ ElastiCache    │ Session, rate limit │ Minutes-hours    │ Key-value       │
└────────────────┴─────────────────────┴──────────────────┴─────────────────┘
```

---

## 2. DynamoDB - Idempotency

### Purpose

Prevent duplicate request processing when clients retry failed requests or network issues cause duplicate submissions.

### Why DynamoDB?

| Requirement | DynamoDB | Aurora Alternative |
|-------------|----------|-------------------|
| Latency | < 1ms single-digit | 5-20ms (network + query) |
| Concurrency | Millions RPS | Limited by connections |
| TTL (auto-expire) | Built-in | Requires cron job |
| Scaling | Automatic | Manual/Aurora Serverless |
| Global | Multi-region native | Complex replication |

### Table Schema

```
Table: pagw-idempotency-{env}

┌─────────────────────────────────────────────────────────────────────────────┐
│ idempotencyKey (PK) │ pagwId    │ responseHash │ statusCode │ expiresAt    │
├─────────────────────┼───────────┼──────────────┼────────────┼──────────────┤
│ "tenant:abc123"     │ "PAGW-1"  │ "sha256..."  │ 202        │ 1735862400   │
│ "tenant:def456"     │ "PAGW-2"  │ "sha256..."  │ 200        │ 1735862400   │
└─────────────────────┴───────────┴──────────────┴────────────┴──────────────┘

Primary Key: idempotencyKey (String)
TTL Attribute: expiresAt (Unix timestamp)
```

### Flow Diagram

```
Client Request with X-Idempotency-Key: "abc123"
                    │
                    ▼
        ┌───────────────────────┐
        │  API Gateway/         │
        │  Orchestrator         │
        └───────────┬───────────┘
                    │
                    ▼
        ┌───────────────────────┐
        │  DynamoDB GetItem     │◄─── Sub-millisecond lookup
        │  Key: "tenant:abc123" │
        └───────────┬───────────┘
                    │
          ┌─────────┴─────────┐
          │                   │
    ┌─────▼─────┐       ┌─────▼─────┐
    │ Key Exists │       │ Key Not   │
    │ (Duplicate)│       │ Found     │
    └─────┬─────┘       └─────┬─────┘
          │                   │
          ▼                   ▼
    ┌───────────┐       ┌───────────────┐
    │ Return    │       │ Process       │
    │ Cached    │       │ Request       │
    │ Response  │       └───────┬───────┘
    └───────────┘               │
                                ▼
                        ┌───────────────┐
                        │ DynamoDB      │
                        │ PutItem with  │
                        │ TTL (24-48hr) │
                        └───────────────┘
```

### Code Example

```java
@Service
public class IdempotencyService {
    
    private final DynamoDbClient dynamoDb;
    private final String tableName;
    
    public Optional<CachedResponse> checkIdempotency(String tenant, String key) {
        String compositeKey = tenant + ":" + key;
        
        GetItemResponse response = dynamoDb.getItem(GetItemRequest.builder()
            .tableName(tableName)
            .key(Map.of("idempotencyKey", AttributeValue.fromS(compositeKey)))
            .build());
        
        if (response.hasItem()) {
            return Optional.of(mapToCachedResponse(response.item()));
        }
        return Optional.empty();
    }
    
    public void storeIdempotencyKey(String tenant, String key, String pagwId, 
                                     int statusCode, String responseHash) {
        long expiresAt = Instant.now().plus(Duration.ofHours(48)).getEpochSecond();
        
        dynamoDb.putItem(PutItemRequest.builder()
            .tableName(tableName)
            .item(Map.of(
                "idempotencyKey", AttributeValue.fromS(tenant + ":" + key),
                "pagwId", AttributeValue.fromS(pagwId),
                "statusCode", AttributeValue.fromN(String.valueOf(statusCode)),
                "responseHash", AttributeValue.fromS(responseHash),
                "expiresAt", AttributeValue.fromN(String.valueOf(expiresAt))
            ))
            .build());
    }
}
```

### Configuration

```yaml
# application-{env}.yml
pagw:
  aws:
    dynamodb:
      enabled: true
      idempotency-table: pagw-idempotency-${env}
```

---

## 3. Aurora PostgreSQL - Business Data

### Purpose

Store business-critical data requiring:
- ACID transactions
- Complex queries (JOINs, aggregations)
- Referential integrity
- Long-term retention (HIPAA: 7 years)

### Database Schema

```sql
-- Schema: pagw

┌─────────────────────────────────────────────────────────────────────────────┐
│                           Aurora PostgreSQL Schema                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐         │
│  │ request_tracker │    │     outbox      │    │   audit_log     │         │
│  ├─────────────────┤    ├─────────────────┤    ├─────────────────┤         │
│  │ pagw_id (PK)    │    │ id (PK)         │    │ id (PK)         │         │
│  │ tenant          │    │ queue_name      │    │ pagw_id (FK)    │         │
│  │ status          │    │ message_body    │    │ event_type      │         │
│  │ stage           │    │ created_at      │    │ actor           │         │
│  │ s3_request_key  │    │ processed       │    │ timestamp       │         │
│  │ s3_response_key │    │ processed_at    │    │ details (JSONB) │         │
│  │ created_at      │    │ retry_count     │    └─────────────────┘         │
│  │ updated_at      │    └─────────────────┘                                │
│  │ error_code      │                                                        │
│  │ error_message   │    ┌─────────────────┐    ┌─────────────────┐         │
│  └─────────────────┘    │provider_registry│    │payer_config     │         │
│                         ├─────────────────┤    ├─────────────────┤         │
│                         │ npi (PK)        │    │ payer_id (PK)   │         │
│                         │ name            │    │ name            │         │
│                         │ type            │    │ api_endpoint    │         │
│                         │ credentials_arn │    │ auth_type       │         │
│                         └─────────────────┘    │ credentials_arn │         │
│                                                └─────────────────┘         │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Table Definitions

#### request_tracker
Tracks the lifecycle of each prior authorization request.

```sql
CREATE TABLE pagw.request_tracker (
    pagw_id VARCHAR(50) PRIMARY KEY,
    tenant VARCHAR(50) NOT NULL,
    correlation_id VARCHAR(100),
    
    -- Status tracking
    status VARCHAR(50) NOT NULL,
    stage VARCHAR(50),
    
    -- S3 references (no PHI in database)
    s3_bucket VARCHAR(255),
    s3_request_key VARCHAR(500),
    s3_response_key VARCHAR(500),
    
    -- Provider/Payer info
    provider_npi VARCHAR(10),
    payer_id VARCHAR(50),
    
    -- Timing
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    completed_at TIMESTAMP WITH TIME ZONE,
    
    -- Error handling
    error_code VARCHAR(50),
    error_message TEXT,
    retry_count INTEGER DEFAULT 0,
    
    -- Metadata
    metadata JSONB
);

-- Indexes for common queries
CREATE INDEX idx_request_tracker_tenant ON pagw.request_tracker(tenant);
CREATE INDEX idx_request_tracker_status ON pagw.request_tracker(status);
CREATE INDEX idx_request_tracker_created ON pagw.request_tracker(created_at);
```

#### outbox
Implements the transactional outbox pattern for reliable messaging.

```sql
CREATE TABLE pagw.outbox (
    id BIGSERIAL PRIMARY KEY,
    
    -- Message routing
    queue_name VARCHAR(255) NOT NULL,
    message_body TEXT NOT NULL,
    
    -- Processing state
    processed BOOLEAN DEFAULT FALSE,
    processed_at TIMESTAMP WITH TIME ZONE,
    retry_count INTEGER DEFAULT 0,
    last_error TEXT,
    
    -- Timing
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    -- Ordering
    sequence_number BIGINT
);

-- Index for polling unprocessed messages
CREATE INDEX idx_outbox_unprocessed ON pagw.outbox(processed, created_at) 
    WHERE processed = FALSE;
```

#### audit_log
HIPAA-compliant audit trail of all operations.

```sql
CREATE TABLE pagw.audit_log (
    id BIGSERIAL PRIMARY KEY,
    pagw_id VARCHAR(50),
    
    -- Event info
    event_type VARCHAR(100) NOT NULL,
    event_subtype VARCHAR(100),
    
    -- Actor info
    actor_type VARCHAR(50),  -- 'service', 'user', 'system'
    actor_id VARCHAR(100),
    
    -- Resource info
    resource_type VARCHAR(100),
    resource_id VARCHAR(255),
    
    -- Details (no PHI!)
    details JSONB,
    
    -- Timing
    timestamp TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    -- Source
    service_name VARCHAR(100),
    service_version VARCHAR(20)
);

-- Indexes for audit queries
CREATE INDEX idx_audit_log_pagw_id ON pagw.audit_log(pagw_id);
CREATE INDEX idx_audit_log_timestamp ON pagw.audit_log(timestamp);
CREATE INDEX idx_audit_log_event_type ON pagw.audit_log(event_type);
```

### Configuration

```yaml
# application-{env}.yml
spring:
  datasource:
    url: jdbc:postgresql://${PAGW_AURORA_WRITER_ENDPOINT}:5432/${PAGW_AURORA_DATABASE}
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2

pagw:
  aws:
    aurora:
      writer-endpoint: ${PAGW_AURORA_WRITER_ENDPOINT}
      reader-endpoint: ${PAGW_AURORA_READER_ENDPOINT}
      database: pagwdb
      secret-arn: ${PAGW_AURORA_SECRET_ARN}
```

---

## 4. Outbox Pattern

### The Problem

When a service needs to update the database AND send a message to SQS:

```
❌ Naive Approach (Unsafe):
┌─────────────────────────────────────────────────────────────────┐
│  1. BEGIN TRANSACTION                                           │
│  2. UPDATE request_tracker SET status = 'PARSED'                │
│  3. COMMIT                                                      │
│  4. Send message to SQS  ◄── What if this fails?               │
│                              DB is updated but message is lost! │
└─────────────────────────────────────────────────────────────────┘
```

### The Solution: Outbox Pattern

```
✅ Outbox Pattern (Safe):
┌─────────────────────────────────────────────────────────────────┐
│  Service (e.g., Request Parser)                                 │
│  ┌───────────────────────────────────────────────────────────┐ │
│  │  BEGIN TRANSACTION                                         │ │
│  │    1. UPDATE request_tracker SET status = 'PARSED'         │ │
│  │    2. INSERT INTO outbox (queue, message, processed=false) │ │
│  │  COMMIT  ◄── Both succeed or both fail (atomic)           │ │
│  └───────────────────────────────────────────────────────────┘ │
│                                                                 │
│  Outbox Publisher (separate process)                            │
│  ┌───────────────────────────────────────────────────────────┐ │
│  │  LOOP:                                                     │ │
│  │    1. SELECT * FROM outbox WHERE processed = false         │ │
│  │    2. For each message:                                    │ │
│  │       a. Send to SQS                                       │ │
│  │       b. UPDATE outbox SET processed = true                │ │
│  │    3. Sleep(poll_interval)                                 │ │
│  └───────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

### Guarantees

| Scenario | Outcome |
|----------|---------|
| DB commit fails | No outbox entry, no message sent ✅ |
| DB commit succeeds, publisher crashes | Outbox entry exists, will be retried ✅ |
| SQS send fails | Retry on next poll ✅ |
| Duplicate message sent | Consumer handles idempotently ✅ |

### Sequence Diagram

```
┌────────┐     ┌──────────┐     ┌────────┐     ┌─────────────┐     ┌─────┐
│Service │     │  Aurora  │     │ Outbox │     │   Outbox    │     │ SQS │
│        │     │          │     │ Table  │     │  Publisher  │     │     │
└───┬────┘     └────┬─────┘     └───┬────┘     └──────┬──────┘     └──┬──┘
    │               │               │                 │               │
    │ BEGIN TX      │               │                 │               │
    │──────────────>│               │                 │               │
    │               │               │                 │               │
    │ UPDATE tracker│               │                 │               │
    │──────────────>│               │                 │               │
    │               │               │                 │               │
    │ INSERT outbox │               │                 │               │
    │──────────────>│──────────────>│                 │               │
    │               │               │                 │               │
    │ COMMIT        │               │                 │               │
    │──────────────>│               │                 │               │
    │               │               │                 │               │
    │               │               │  Poll (every 5s)│               │
    │               │               │<────────────────│               │
    │               │               │                 │               │
    │               │               │  Return rows    │               │
    │               │               │────────────────>│               │
    │               │               │                 │               │
    │               │               │                 │ SendMessage   │
    │               │               │                 │──────────────>│
    │               │               │                 │               │
    │               │               │                 │     ACK       │
    │               │               │                 │<──────────────│
    │               │               │                 │               │
    │               │               │ UPDATE processed│               │
    │               │               │<────────────────│               │
    └───────────────┴───────────────┴─────────────────┴───────────────┘
```

### Code Example

```java
@Service
@Transactional
public class RequestParserService {
    
    private final RequestTrackerRepository trackerRepo;
    private final OutboxRepository outboxRepo;
    
    public void processRequest(PagwMessage message) {
        // 1. Update business state
        RequestTracker tracker = trackerRepo.findByPagwId(message.getPagwId());
        tracker.setStatus("PARSED");
        tracker.setStage("business-validator");
        trackerRepo.save(tracker);
        
        // 2. Write to outbox (same transaction!)
        OutboxMessage outbox = OutboxMessage.builder()
            .queueName("pagw-business-validator-queue")
            .messageBody(JsonUtils.toJson(message))
            .processed(false)
            .build();
        outboxRepo.save(outbox);
        
        // Transaction commits both or neither
    }
}

@Service
public class OutboxPublisher {
    
    @Scheduled(fixedDelay = 5000)  // Every 5 seconds
    @Transactional
    public void publishPendingMessages() {
        List<OutboxMessage> pending = outboxRepo.findUnprocessed(25);  // Batch size
        
        for (OutboxMessage msg : pending) {
            try {
                sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(resolveQueueUrl(msg.getQueueName()))
                    .messageBody(msg.getMessageBody())
                    .build());
                
                msg.setProcessed(true);
                msg.setProcessedAt(Instant.now());
                outboxRepo.save(msg);
                
            } catch (Exception e) {
                msg.setRetryCount(msg.getRetryCount() + 1);
                msg.setLastError(e.getMessage());
                outboxRepo.save(msg);
            }
        }
    }
}
```

### Configuration

```yaml
# application.yml
pagw:
  outbox:
    poll-interval-ms: 5000
    batch-size: 25
    max-retries: 5
```

---

## 5. S3 - File Storage

### Purpose

Store large files that shouldn't be in the database:
- Original FHIR bundles (PHI)
- Attachments (medical records, images)
- Provider responses
- Audit files

### Bucket Structure

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           S3 Bucket Organization                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Request Bucket (PHI - KMS Encrypted)                                       │
│  s3://crln-pagw-{env}-dataz-gbd-phi-useast{region}/                        │
│  │                                                                          │
│  └── {YYYYMM}/                        # Monthly partition                   │
│      └── {pagwId}/                    # Request folder                      │
│          ├── request/                                                       │
│          │   ├── original_bundle.json      # Original FHIR bundle          │
│          │   ├── parsed_claim.json         # Parsed claim data             │
│          │   └── enriched_request.json     # After enrichment              │
│          ├── response/                                                      │
│          │   ├── payer_response.json       # Raw payer response            │
│          │   └── fhir_claim_response.json  # FHIR ClaimResponse            │
│          ├── attachments/                                                   │
│          │   ├── attachment_001.pdf        # Medical records               │
│          │   └── attachment_002.jpg        # Images                        │
│          └── callbacks/                                                     │
│              └── callback_001.json         # Async callbacks               │
│                                                                             │
│  Audit Bucket (No PHI - AES256 Encrypted)                                  │
│  s3://crln-pagw-{env}-logz-nogbd-nophi-useast{region}/                     │
│  │                                                                          │
│  └── {YYYYMM}/{YYYYMMDD}/{service}/{HH}/                                   │
│      └── {pagwId}_{uuid}.json              # Audit events                  │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Why Not Store Files in Aurora?

| Factor | S3 | Aurora |
|--------|-----|--------|
| Cost (1TB) | ~$23/month | ~$100+/month |
| Max object size | 5TB | Limited by row size |
| Throughput | Virtually unlimited | DB connection limited |
| Lifecycle rules | Built-in | Manual |
| Versioning | Built-in | Manual |
| Replication | Cross-region native | Complex setup |

### Encryption

```
┌─────────────────────────────────────────────────────────────────┐
│                    S3 Encryption Strategy                       │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Request Bucket (PHI Data)                                      │
│  ├── Encryption: SSE-KMS                                        │
│  ├── KMS Key: alias/pagw-s3-{env}                              │
│  ├── Bucket Key: Enabled (cost optimization)                    │
│  └── Access: IAM roles only, no public access                   │
│                                                                 │
│  Audit Bucket (No PHI)                                          │
│  ├── Encryption: SSE-S3 (AES256)                               │
│  └── Access: IAM roles only, no public access                   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Code Example

```java
@Service
public class S3Service {
    
    private final S3Client s3Client;
    private final PagwProperties properties;
    
    public void storeRequest(String pagwId, String content) {
        String key = PagwProperties.S3Paths.originalBundle(pagwId);
        
        PutObjectRequest request = PutObjectRequest.builder()
            .bucket(properties.getAws().getS3().getRequestBucket())
            .key(key)
            .serverSideEncryption(ServerSideEncryption.AWS_KMS)
            .ssekmsKeyId(properties.getEncryption().getKmsKeyId())
            .contentType("application/fhir+json")
            .build();
        
        s3Client.putObject(request, RequestBody.fromString(content));
    }
}

// Centralized path generation
public class PagwProperties {
    public static class S3Paths {
        public static String originalBundle(String pagwId) {
            return String.format("%s/%s/request/original_bundle.json",
                YearMonth.now().format(YYYYMM_FORMATTER), pagwId);
        }
        
        public static String fhirResponse(String pagwId) {
            return String.format("%s/%s/response/fhir_claim_response.json",
                YearMonth.now().format(YYYYMM_FORMATTER), pagwId);
        }
    }
}
```

### Configuration

```yaml
# application-{env}.yml
pagw:
  aws:
    s3:
      request-bucket: crln-pagw-${env}-dataz-gbd-phi-useast2
      attachments-bucket: crln-pagw-${env}-dataz-gbd-phi-useast2
      audit-bucket: crln-pagw-${env}-logz-nogbd-nophi-useast2
  encryption:
    kms-enabled: true
    kms-key-id: alias/pagw-s3-${env}
```

---

## 6. ElastiCache - Caching

### Purpose

High-speed caching for:
- OAuth tokens (avoid repeated auth calls)
- Provider configuration
- Rate limiting
- Session data

### Use Cases

```
┌─────────────────────────────────────────────────────────────────┐
│                    ElastiCache Use Cases                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  1. OAuth Token Cache                                           │
│     Key: "oauth:{payer_id}"                                     │
│     Value: { access_token, expires_at }                         │
│     TTL: Based on token expiry (usually 1 hour)                 │
│                                                                 │
│  2. Rate Limiting                                               │
│     Key: "ratelimit:{tenant}:{window}"                          │
│     Value: request count                                        │
│     TTL: Window duration (e.g., 60 seconds)                     │
│                                                                 │
│  3. Provider Config Cache                                       │
│     Key: "provider:{npi}"                                       │
│     Value: { name, endpoints, credentials_arn }                 │
│     TTL: 5 minutes                                              │
│                                                                 │
│  4. Payer Config Cache                                          │
│     Key: "payer:{payer_id}"                                     │
│     Value: { api_endpoint, auth_type, ... }                     │
│     TTL: 5 minutes                                              │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Configuration

```yaml
# application-{env}.yml
pagw:
  aws:
    elasticache:
      endpoint: ${PAGW_ELASTICACHE_ENDPOINT}
      port: 6379
      ssl-enabled: true
```

---

## 7. Data Flow

### Complete Request Lifecycle

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         SYNC FLOW (HTTP 202)                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│   Client ──► pasorchestrator ──► pasrequestparser ──► pasbusinessvalidator  │
│      ▲                                                          │           │
│      │                                                          │           │
│      └──────────────── 202 Accepted ◄───────────────────────────┘           │
│                                                                             │
│   During sync flow:                                                         │
│   • DynamoDB: Check idempotency key                                         │
│   • S3: Store original FHIR bundle                                          │
│   • Aurora: Create request_tracker record                                   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      │ (trigger async via SQS/Outbox)
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                          ASYNC FLOW (Background)                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│   ┌────────────────┐    ┌──────────────────┐    ┌────────────────────┐     │
│   │pasrequest      │───►│pasattachment     │───►│pasrequest          │     │
│   │enricher        │    │handler           │    │converter           │     │
│   └────────────────┘    └──────────────────┘    └─────────┬──────────┘     │
│                                                           │                 │
│   During async flow:                                      │                 │
│   • S3: Read/write enriched data                         ▼                 │
│   • Aurora: Update request_tracker status     ┌────────────────────┐       │
│   • Aurora: Write to outbox for next stage    │pasapiconnector     │       │
│                                               │(external API call) │       │
│                                               └─────────┬──────────┘       │
│                                                         │                   │
│   ┌────────────────┐    ┌──────────────────┐           │                   │
│   │passubscription │◄───│pascallback       │◄──────────┘                   │
│   │handler         │    │handler           │◄──┐                           │
│   └────────┬───────┘    └──────────────────┘   │                           │
│            │                                    │                           │
│            │            ┌──────────────────┐   │                           │
│            │            │pasresponse       │───┘                           │
│            ▼            │builder           │                               │
│   ┌────────────────┐    └──────────────────┘                               │
│   │ Notify Client  │                                                        │
│   │ (Webhook)      │    Post-processing:                                   │
│   └────────────────┘    • DynamoDB: Store idempotency key with TTL         │
│                         • S3: Store final FHIR ClaimResponse               │
│                         • Aurora: Update tracker = COMPLETED               │
│                         • Aurora: Write audit log entry                    │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Pipeline Queue Flow

```
SYNC PIPELINE:
  Client Request
       │
       ▼
  pasorchestrator ──SQS──► pasrequestparser ──SQS──► pasbusinessvalidator
       │                                                      │
       │◄─────────────────── 202 Response ◄───────────────────┘

ASYNC PIPELINE:
  pasbusinessvalidator
       │ (writes to outbox)
       ▼
  pagw-request-enricher-queue ──► pasrequestenricher
                                        │
                                        ▼
  pagw-attachment-handler-queue ──► pasattachmenthandler
                                        │
                                        ▼
  pagw-request-converter-queue ──► pasrequestconverter
                                        │
                                        ▼
  pagw-api-connector-queue ──► pasapiconnector (calls external payer API)
                                        │
                                        ▼
  pagw-response-builder-queue ──► pasresponsebuilder
                                        │
                                        ▼
  pagw-callback-handler-queue ──► pascallbackhandler (async callbacks)
                                        │
                                        ▼
  pagw-subscription-handler-queue ──► passubscriptionhandler (webhook notifications)
```

---

## 8. Comparison Matrix

### When to Use What

| Use Case | DynamoDB | Aurora | S3 | ElastiCache |
|----------|----------|--------|-----|-------------|
| Idempotency check | ✅ | ❌ | ❌ | ⚠️ |
| Request tracking | ❌ | ✅ | ❌ | ❌ |
| FHIR bundle storage | ❌ | ❌ | ✅ | ❌ |
| Attachment files | ❌ | ❌ | ✅ | ❌ |
| Audit log | ❌ | ✅ | ✅ | ❌ |
| Token cache | ❌ | ❌ | ❌ | ✅ |
| Rate limiting | ❌ | ❌ | ❌ | ✅ |
| Complex queries | ❌ | ✅ | ❌ | ❌ |
| Transactional writes | ⚠️ | ✅ | ❌ | ❌ |

### Performance Characteristics

| Metric | DynamoDB | Aurora | S3 | ElastiCache |
|--------|----------|--------|-----|-------------|
| Read latency | < 1ms | 5-20ms | 50-100ms | < 1ms |
| Write latency | < 5ms | 5-20ms | 50-200ms | < 1ms |
| Throughput | Millions RPS | Thousands QPS | High | Millions RPS |
| Consistency | Eventually* | Strong | Eventually | Strong |
| Cost model | Per request | Per instance | Per GB stored | Per node |

*DynamoDB supports strong consistency for reads at 2x cost

---

## 9. Environment Configuration

### Environment-Specific Settings

| Environment | Region | DynamoDB Table | Aurora Endpoint |
|-------------|--------|----------------|-----------------|
| local | us-east-1 | pagw-idempotency-local | localhost:5432 |
| dev | us-east-2 | pagw-idempotency-dev | *.us-east-2.rds.amazonaws.com |
| sit | us-east-2 | pagw-idempotency-sit | *.us-east-2.rds.amazonaws.com |
| preprod | us-east-1 | pagw-idempotency-preprod | *.us-east-1.rds.amazonaws.com |
| prod | us-east-1 | pagw-idempotency-prod | *.us-east-1.rds.amazonaws.com |

### LocalStack Configuration

For local development, all AWS services run in LocalStack:

```yaml
# docker-compose.infra.yml
services:
  localstack:
    image: localstack/localstack:3.0
    environment:
      SERVICES: sqs,s3,secretsmanager,kms,dynamodb
    ports:
      - "4566:4566"
```

### Terraform Resources

| Resource | File | Purpose |
|----------|------|---------|
| DynamoDB | dynamodb.tf | Idempotency table |
| Aurora | aurora.tf | PostgreSQL cluster |
| S3 | s3.tf | Request & audit buckets |
| KMS | kms.tf | Encryption keys |
| SQS | sqs.tf | Message queues |

---

## Appendix: Quick Reference

### Connection Strings

```bash
# Aurora (via Secrets Manager)
aws secretsmanager get-secret-value --secret-id pagw/aurora/${ENV}

# DynamoDB
aws dynamodb describe-table --table-name pagw-idempotency-${ENV}

# S3
aws s3 ls s3://crln-pagw-${ENV}-dataz-gbd-phi-useast2/

# ElastiCache
redis-cli -h ${ELASTICACHE_ENDPOINT} -p 6379 --tls
```

### Monitoring Queries

```sql
-- Active requests by status
SELECT status, COUNT(*) FROM pagw.request_tracker 
GROUP BY status ORDER BY COUNT(*) DESC;

-- Outbox backlog
SELECT COUNT(*) as pending FROM pagw.outbox WHERE processed = false;

-- Average processing time
SELECT AVG(EXTRACT(EPOCH FROM (completed_at - created_at))) as avg_seconds
FROM pagw.request_tracker WHERE completed_at IS NOT NULL;
```

### Health Checks

```bash
# DynamoDB
aws dynamodb describe-table --table-name pagw-idempotency-dev --query "Table.TableStatus"

# Aurora
psql -h $AURORA_ENDPOINT -U pagw -d pagwdb -c "SELECT 1"

# S3
aws s3api head-bucket --bucket crln-pagw-dev-dataz-gbd-phi-useast2

# ElastiCache
redis-cli -h $ELASTICACHE_ENDPOINT PING
```
