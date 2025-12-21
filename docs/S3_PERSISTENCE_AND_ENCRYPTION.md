# S3 Persistence and Encryption Strategy

## Overview

This document provides comprehensive documentation on S3 data persistence, folder structure, bucket configuration, and encryption strategy for the Prior Authorization Gateway (PAGW) system. All PHI (Protected Health Information) data is encrypted at rest using AWS KMS encryption to ensure HIPAA compliance.

---

## Table of Contents

1. [S3 Bucket Architecture](#s3-bucket-architecture)
2. [Folder Structure](#folder-structure)
3. [Request Lifecycle & Storage](#request-lifecycle--storage)
4. [KMS Encryption Strategy](#kms-encryption-strategy)
5. [PHI Field-Level Encryption](#phi-field-level-encryption)
6. [Configuration Reference](#configuration-reference)
7. [Local Development](#local-development)
8. [Terraform Resources](#terraform-resources)
9. [Security & Compliance](#security--compliance)

---

## S3 Bucket Architecture

### Production Buckets

| Bucket | Purpose | Data Type | Naming Convention |
|--------|---------|-----------|-------------------|
| **Request Bucket** | All request/response data for a PAGW transaction | PHI | `pagw-request-{env}` |
| **Audit Bucket** | Audit logs, metrics, compliance records | Non-PHI | `pagw-audit-{env}` |
| **Attachments Bucket** | Binary attachments (PDFs, images, documents) | PHI | `pagw-attachments-{env}` |

### Bucket Features

```
┌─────────────────────────────────────────────────────────────────────┐
│                    S3 BUCKET CONFIGURATION                          │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  pagw-request-{env}                                          │   │
│  ├─────────────────────────────────────────────────────────────┤   │
│  │  • Server-Side Encryption: SSE-KMS (aws:kms)                │   │
│  │  • KMS Key: alias/pagw-s3-{env}                             │   │
│  │  • Bucket Keys: Enabled (reduces KMS API calls)             │   │
│  │  • Versioning: Enabled                                       │   │
│  │  • Public Access: Blocked                                    │   │
│  │  • Lifecycle: 90d→Standard-IA, 365d→Glacier, 7yr→Expire    │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  pagw-audit-{env}                                            │   │
│  ├─────────────────────────────────────────────────────────────┤   │
│  │  • Server-Side Encryption: SSE-KMS                          │   │
│  │  • Versioning: Enabled                                       │   │
│  │  • Public Access: Blocked                                    │   │
│  │  • Access Logging: Enabled (for compliance)                  │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  pagw-attachments-{env}                                      │   │
│  ├─────────────────────────────────────────────────────────────┤   │
│  │  • Server-Side Encryption: SSE-KMS                          │   │
│  │  • Large Object Support: Multipart upload                    │   │
│  │  • Max Object Size: 5GB per attachment                       │   │
│  │  • Content Types: PDF, PNG, JPEG, TIFF                       │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Folder Structure

### Overview

All data for a single PAGW transaction is organized under a single folder path for easy retrieval, compliance auditing, and operational troubleshooting:

```
{YYYYMM}/{pagwId}/
├── request/
│   ├── raw.json           # Original FHIR Bundle as received
│   ├── parsed.json        # Extracted claim data from FHIR Bundle
│   ├── validated.json     # Business validated data with validation results
│   ├── enriched.json      # Enriched with provider/eligibility info
│   └── canonical.json     # Converted to target format (X12 278)
├── response/
│   ├── payer_raw.json     # Raw response from payer API
│   ├── callback.json      # Processed callback/normalized response
│   └── fhir.json          # Final FHIR ClaimResponse to client
├── attachments/
│   ├── meta.json          # Attachment metadata summary
│   └── {uuid}_{filename}  # Individual attachment files
├── callbacks/
│   └── {timestamp}.json   # Async callback events with timestamps
└── _manifest.json         # Request manifest/summary
```

### Folder Structure Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                     S3 FOLDER STRUCTURE                              │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  pagw-request-{env}/                                                │
│  │                                                                   │
│  ├── 202512/                        ← Month Partition (YYYYMM)      │
│  │   │                                                               │
│  │   ├── PAGW-20251221-00001-3DB4E734/   ← Transaction ID          │
│  │   │   │                                                           │
│  │   │   ├── request/               ← Request Processing Stages     │
│  │   │   │   ├── raw.json           [Parser]                        │
│  │   │   │   ├── parsed.json        [Parser]                        │
│  │   │   │   ├── validated.json     [Validator]                     │
│  │   │   │   ├── enriched.json      [Enricher]                      │
│  │   │   │   └── canonical.json     [Converter]                     │
│  │   │   │                                                           │
│  │   │   ├── response/              ← Response Stages               │
│  │   │   │   ├── payer_raw.json     [API Connector]                 │
│  │   │   │   ├── callback.json      [Callback Handler]              │
│  │   │   │   └── fhir.json          [Response Builder]              │
│  │   │   │                                                           │
│  │   │   ├── attachments/           ← Document Attachments          │
│  │   │   │   ├── meta.json          [Attachment Handler]            │
│  │   │   │   ├── abc123_xray.pdf                                    │
│  │   │   │   └── def456_labs.pdf                                    │
│  │   │   │                                                           │
│  │   │   └── _manifest.json         ← Request Summary               │
│  │   │                                                               │
│  │   └── PAGW-20251221-00002-A1B2C3D4/                              │
│  │       └── ...                                                     │
│  │                                                                   │
│  └── 202601/                                                         │
│      └── ...                                                         │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### Benefits of This Structure

| Benefit | Description |
|---------|-------------|
| **Single Transaction View** | All data for one PA request in one folder |
| **Easy Retrieval** | Simple prefix query by pagwId |
| **Month Partitioning** | Efficient lifecycle management and archival |
| **Compliance Auditing** | Complete data lineage for a single request |
| **Cost Optimization** | Lifecycle policies by date partition |
| **Troubleshooting** | All processing stages visible in one location |

---

## Request Lifecycle & Storage

### Processing Pipeline

Each service in the pipeline reads from and writes to specific S3 paths:

```
┌─────────────────────────────────────────────────────────────────────┐
│                    REQUEST PROCESSING PIPELINE                       │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  Client Request                                                      │
│       │                                                              │
│       ▼                                                              │
│  ┌─────────────┐     Writes: {pagwId}/request/raw.json              │
│  │ Orchestrator │────────────────────────────────────►               │
│  └─────────────┘                                                     │
│       │                                                              │
│       ▼ SQS                                                          │
│  ┌─────────────┐     Reads:  raw.json                               │
│  │   Parser    │     Writes: {pagwId}/request/parsed.json           │
│  └─────────────┘                                                     │
│       │                                                              │
│       ▼ SQS                                                          │
│  ┌─────────────┐     Reads:  parsed.json                            │
│  │  Validator  │     Writes: {pagwId}/request/validated.json        │
│  └─────────────┘                                                     │
│       │                                                              │
│       ▼ SQS                                                          │
│  ┌─────────────┐     Reads:  validated.json                         │
│  │  Enricher   │     Writes: {pagwId}/request/enriched.json         │
│  └─────────────┘                                                     │
│       │                                                              │
│       ▼ SQS                                                          │
│  ┌─────────────┐     Reads:  enriched.json                          │
│  │ Attachment  │     Writes: {pagwId}/attachments/meta.json         │
│  │   Handler   │             {pagwId}/attachments/{file}            │
│  └─────────────┘                                                     │
│       │                                                              │
│       ▼ SQS                                                          │
│  ┌─────────────┐     Reads:  enriched.json                          │
│  │  Converter  │     Writes: {pagwId}/request/canonical.json        │
│  └─────────────┘                                                     │
│       │                                                              │
│       ▼ SQS                                                          │
│  ┌─────────────┐     Reads:  canonical.json                         │
│  │    API      │     Writes: {pagwId}/response/payer_raw.json       │
│  │  Connector  │                                                     │
│  └─────────────┘                                                     │
│       │                                                              │
│       ▼ SQS                                                          │
│  ┌─────────────┐     Reads:  payer_raw.json                         │
│  │  Callback   │     Writes: {pagwId}/response/callback.json        │
│  │  Handler    │                                                     │
│  └─────────────┘                                                     │
│       │                                                              │
│       ▼ SQS                                                          │
│  ┌─────────────┐     Reads:  callback.json                          │
│  │  Response   │     Writes: {pagwId}/response/fhir.json            │
│  │   Builder   │                                                     │
│  └─────────────┘                                                     │
│       │                                                              │
│       ▼                                                              │
│  Final Response to Client                                            │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### File Descriptions

| File | Service | Description | Content Type |
|------|---------|-------------|--------------|
| `request/raw.json` | Orchestrator | Original FHIR Bundle submitted by client | `application/fhir+json` |
| `request/parsed.json` | Parser | Extracted claim data, patient info, provider | `application/json` |
| `request/validated.json` | Validator | Validation results, NPI checks, business rules | `application/json` |
| `request/enriched.json` | Enricher | Enriched with provider info, eligibility data | `application/json` |
| `request/canonical.json` | Converter | Converted to payer format (X12 278, etc.) | `application/json` |
| `response/payer_raw.json` | API Connector | Raw response from payer's API | `application/json` |
| `response/callback.json` | Callback Handler | Normalized callback response | `application/json` |
| `response/fhir.json` | Response Builder | Final FHIR ClaimResponse | `application/fhir+json` |
| `attachments/meta.json` | Attachment Handler | Attachment metadata (count, sizes, types) | `application/json` |
| `attachments/{uuid}_{name}` | Attachment Handler | Binary attachment files | Various |

---

## KMS Encryption Strategy

### Encryption Layers

```
┌─────────────────────────────────────────────────────────────────────┐
│                    PHI DATA PROTECTION LAYERS                        │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │ Layer 1: TRANSPORT ENCRYPTION (TLS 1.3)                      │   │
│  │ • All API calls over HTTPS                                   │   │
│  │ • mTLS for service-to-service communication                  │   │
│  │ • Certificate validation and pinning                         │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                              │                                       │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │ Layer 2: STORAGE ENCRYPTION (AES-256)                        │   │
│  │ • S3: SSE-KMS with Customer Managed Key (CMK)               │   │
│  │ • Aurora: Encryption at rest with KMS                        │   │
│  │ • SQS: Server-side encryption with KMS                       │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                              │                                       │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │ Layer 3: FIELD-LEVEL ENCRYPTION (Envelope Encryption)        │   │
│  │ • Sensitive PHI fields encrypted before storage              │   │
│  │ • Uses AWS KMS envelope encryption                           │   │
│  │ • Separate key per tenant (optional)                         │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### KMS Key Hierarchy

```
┌─────────────────────────────────────────────────────────────────┐
│                    AWS KMS KEY HIERARCHY                         │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  pagw-s3-kms-key                                                │
│  ├── Purpose: S3 bucket encryption                              │
│  ├── Alias: alias/pagw-s3-{env}                                │
│  ├── Rotation: Annual (automatic)                               │
│  └── Used by: All S3 buckets (request, attachments, audit)     │
│                                                                  │
│  pagw-sqs-kms-key                                               │
│  ├── Purpose: SQS queue encryption                              │
│  ├── Alias: alias/pagw-sqs-{env}                               │
│  ├── Rotation: Annual (automatic)                               │
│  └── Used by: All SQS queues and DLQs                           │
│                                                                  │
│  pagw-rds-kms-key                                               │
│  ├── Purpose: Aurora database encryption                        │
│  ├── Alias: alias/pagw-rds-{env}                               │
│  ├── Rotation: Annual (automatic)                               │
│  └── Used by: Aurora cluster (data at rest)                     │
│                                                                  │
│  pagw-phi-field-kms-key  ← PRIMARY FOR PHI FIELDS               │
│  ├── Purpose: Field-level PHI encryption                        │
│  ├── Alias: alias/pagw-phi-field-{env}                         │
│  ├── Rotation: Annual (automatic)                               │
│  └── Used by: PhiEncryptionService for sensitive fields         │
│                                                                  │
│  pagw-secrets-kms-key                                           │
│  ├── Purpose: Secrets Manager encryption                        │
│  ├── Alias: alias/pagw-secrets-{env}                           │
│  └── Used by: DB credentials, API keys                          │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### S3 Encryption Implementation

The `S3Service` in pagwcore applies encryption to all uploads:

```java
// S3Service.java - Encryption Configuration
public S3Service(
        S3Client s3Client,
        @Value("${pagw.encryption.kms-enabled:false}") boolean kmsEnabled,
        @Value("${pagw.encryption.kms-key-id:}") String kmsKeyId) {
    // KMS encryption when enabled with valid key ID
    // Falls back to AES256 otherwise
}

private void applyEncryption(PutObjectRequest.Builder requestBuilder) {
    if (kmsEnabled && kmsKeyId != null && !kmsKeyId.isBlank()) {
        requestBuilder
            .serverSideEncryption(ServerSideEncryption.AWS_KMS)
            .ssekmsKeyId(kmsKeyId);
    } else {
        requestBuilder.serverSideEncryption(ServerSideEncryption.AES256);
    }
}
```

### Envelope Encryption Flow

```
┌─────────────────────────────────────────────────────────────────────┐
│                  ENVELOPE ENCRYPTION PROCESS                         │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ENCRYPT:                                                            │
│  ┌────────┐    ┌─────────┐    ┌──────────────┐    ┌──────────────┐ │
│  │  PHI   │───►│ KMS     │───►│ Data Key     │───►│ Encrypted    │ │
│  │  Data  │    │ Generate│    │ (Plaintext)  │    │ PHI Data     │ │
│  └────────┘    │ DataKey │    │ (Encrypted)  │    └──────────────┘ │
│                └─────────┘    └──────────────┘           │          │
│                                      │                    │          │
│                                      ▼                    ▼          │
│                              ┌──────────────────────────────────┐   │
│                              │ Store: Encrypted Data +          │   │
│                              │        Encrypted Data Key        │   │
│                              └──────────────────────────────────┘   │
│                                                                      │
│  DECRYPT:                                                            │
│  ┌──────────────┐    ┌─────────┐    ┌──────────────┐    ┌────────┐ │
│  │ Encrypted    │───►│ KMS     │───►│ Data Key     │───►│  PHI   │ │
│  │ Data Key     │    │ Decrypt │    │ (Plaintext)  │    │  Data  │ │
│  └──────────────┘    └─────────┘    └──────────────┘    └────────┘ │
│         │                                    │                       │
│         ▼                                    ▼                       │
│  ┌──────────────┐                   ┌──────────────┐                │
│  │ Encrypted    │──────────────────►│ Decrypt with │                │
│  │ PHI Data     │                   │ Plaintext Key│                │
│  └──────────────┘                   └──────────────┘                │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## PHI Field-Level Encryption

### PHI Fields to Encrypt

The following PHI fields are encrypted at the field level:

| Field Path | Data Type | Encryption Required |
|------------|-----------|---------------------|
| `patient.name` | String | ✅ Yes |
| `patient.birthDate` | Date | ✅ Yes |
| `patient.identifier` (SSN, MRN) | String | ✅ Yes |
| `patient.address` | Object | ✅ Yes |
| `patient.telecom` (phone, email) | Array | ✅ Yes |
| `subscriber.name` | String | ✅ Yes |
| `subscriber.identifier` | String | ✅ Yes |
| `provider.name` | String | ✅ Yes |
| `provider.npi` | String | ⚠️ Optional |
| `diagnosis.code` | String | ❌ No (de-identified) |
| `procedure.code` | String | ❌ No (de-identified) |

### Encryption Context

Always provide encryption context for additional security:

```java
Map<String, String> context = Map.of(
    "pagwId", pagwId,
    "tenant", tenantId,
    "stage", "ORCHESTRATOR"
);
```

This ensures:
- The same ciphertext can only be decrypted with the same context
- Audit trail in CloudTrail shows which pagwId was accessed
- Prevents accidental cross-tenant decryption

---

## Configuration Reference

### Environment Variables

| Variable | Description | Example |
|----------|-------------|---------|
| `PAGW_S3_REQUEST_BUCKET` | Main request/response bucket | `pagw-request-local` |
| `PAGW_S3_AUDIT_BUCKET` | Audit logging bucket | `pagw-audit-local` |
| `PAGW_S3_ATTACHMENTS_BUCKET` | Attachments bucket | `pagw-attachments-local` |
| `PAGW_ENCRYPTION_KMS_ENABLED` | Enable KMS encryption | `true` |
| `KMS_KEY_ID` | KMS key ID for encryption | `64d8fa08-d935-...` |

### Application Properties

```yaml
pagw:
  aws:
    s3:
      request-bucket: ${PAGW_S3_REQUEST_BUCKET:pagw-request-dev}
      attachments-bucket: ${PAGW_S3_ATTACHMENTS_BUCKET:pagw-attachments-dev}
      audit-bucket: ${PAGW_S3_AUDIT_BUCKET:pagw-audit-dev}
  encryption:
    kms-enabled: ${PAGW_ENCRYPTION_KMS_ENABLED:false}
    kms-key-id: ${KMS_KEY_ID:}
```

### Docker Compose Example

```yaml
environment:
  # S3 Buckets
  PAGW_S3_REQUEST_BUCKET: pagw-request-local
  PAGW_S3_AUDIT_BUCKET: pagw-audit-local
  # KMS Encryption
  PAGW_ENCRYPTION_KMS_ENABLED: "true"
  KMS_KEY_ID: "64d8fa08-d935-4139-bc2c-1d5e615b17d5"
```

---

## Local Development

### LocalStack Setup

For local development, we use LocalStack to emulate AWS services:

```bash
# docker-compose.infra.yml
services:
  localstack:
    image: localstack/localstack:latest
    environment:
      SERVICES: s3,sqs,kms,secretsmanager
```

### LocalStack Initialization Script

The `infra/localstack-init.sh` script creates:

1. **S3 Buckets**:
   ```bash
   awslocal s3 mb s3://pagw-request-local
   awslocal s3 mb s3://pagw-audit-local
   awslocal s3 mb s3://pagw-attachments-local
   ```

2. **KMS Keys**:
   ```bash
   # Create S3 encryption key
   S3_KEY_ID=$(awslocal kms create-key --description "PAGW S3 Key" \
     --query 'KeyMetadata.KeyId' --output text)
   awslocal kms create-alias --alias-name alias/pagw-s3-key --target-key-id $S3_KEY_ID
   
   # Create SQS encryption key
   SQS_KEY_ID=$(awslocal kms create-key --description "PAGW SQS Key" \
     --query 'KeyMetadata.KeyId' --output text)
   awslocal kms create-alias --alias-name alias/pagw-sqs-key --target-key-id $SQS_KEY_ID
   ```

3. **Bucket Encryption**:
   ```bash
   awslocal s3api put-bucket-encryption --bucket pagw-request-local \
     --server-side-encryption-configuration '{
       "Rules": [{
         "ApplyServerSideEncryptionByDefault": {
           "SSEAlgorithm": "aws:kms",
           "KMSMasterKeyID": "'$S3_KEY_ID'"
         },
         "BucketKeyEnabled": true
       }]
     }'
   ```

### Verifying Encryption (LocalStack)

```bash
# List KMS keys
docker exec pagw-localstack awslocal kms list-aliases \
  --query "Aliases[?starts_with(AliasName, 'alias/pagw')]"

# Check object encryption
docker exec pagw-localstack awslocal s3api head-object \
  --bucket pagw-request-local \
  --key "202512/PAGW-20251221-00001-3DB4E734/request/raw.json" \
  --query "{Encryption:ServerSideEncryption, KMSKey:SSEKMSKeyId}"

# Expected output:
# {
#     "Encryption": "aws:kms",
#     "KMSKey": "arn:aws:kms:us-east-1:000000000000:key/64d8fa08-..."
# }
```

---

## Terraform Resources

### S3 Bucket Definition (infra/terraform/s3.tf)

```hcl
resource "aws_s3_bucket" "request" {
  bucket = "${var.app_name}-request-${var.environment}"
  
  tags = {
    Name     = "${var.app_name}-request-${var.environment}"
    Purpose  = "PAGW request/response data"
    DataType = "PHI"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "request" {
  bucket = aws_s3_bucket.request.id
  
  rule {
    apply_server_side_encryption_by_default {
      kms_master_key_id = aws_kms_key.s3_key.arn
      sse_algorithm     = "aws:kms"
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "request" {
  bucket = aws_s3_bucket.request.id
  
  rule {
    id     = "phi-retention"
    status = "Enabled"
    
    transition {
      days          = 90
      storage_class = "STANDARD_IA"
    }
    
    transition {
      days          = 365
      storage_class = "GLACIER"
    }
    
    # PHI retention - 7 years for HIPAA
    expiration {
      days = 2555
    }
  }
}
```

### KMS Key Definition (infra/terraform/kms.tf)

```hcl
resource "aws_kms_key" "s3_key" {
  description             = "KMS key for PAGW S3 bucket encryption (PHI data)"
  deletion_window_in_days = var.kms_deletion_window_days
  enable_key_rotation     = true
  
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "Enable IAM User Permissions"
        Effect = "Allow"
        Principal = {
          AWS = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:root"
        }
        Action   = "kms:*"
        Resource = "*"
      },
      {
        Sid    = "Allow S3 Service"
        Effect = "Allow"
        Principal = {
          Service = "s3.amazonaws.com"
        }
        Action = [
          "kms:Decrypt",
          "kms:GenerateDataKey*"
        ]
        Resource = "*"
      }
    ]
  })
  
  tags = {
    Name    = "${var.app_name}-s3-kms-key-${var.environment}"
    Purpose = "S3 PHI encryption"
  }
}

resource "aws_kms_alias" "s3_key_alias" {
  name          = "alias/${var.app_name}-s3-${var.environment}"
  target_key_id = aws_kms_key.s3_key.key_id
}
```

---

## Security & Compliance

### HIPAA Compliance Checklist

| Requirement | Implementation | Status |
|-------------|----------------|--------|
| Encryption at rest (S3) | SSE-KMS with CMK | ✅ |
| Encryption at rest (Aurora) | RDS KMS encryption | ✅ |
| Encryption at rest (SQS) | SQS KMS encryption | ✅ |
| Encryption in transit | TLS 1.3 | ✅ |
| Field-level encryption | KMS envelope encryption | ✅ |
| Access logging | CloudTrail, S3 access logs | ✅ |
| Key rotation | Automatic annual rotation | ✅ |
| Least privilege | IAM roles per service | ✅ |
| Backup encryption | S3 versioning + KMS | ✅ |
| Data retention | 7-year lifecycle policy | ✅ |

### Security Best Practices

1. **Key Management**
   - ✅ Enable automatic annual key rotation
   - ✅ IAM policies grant only necessary KMS permissions
   - ✅ Different keys for different purposes (S3, SQS, PHI fields)
   - ✅ CMKs are non-exportable

2. **Data Handling**
   - ✅ Clear plaintext DEK from memory after use
   - ✅ Use SecureRandom for IV generation
   - ✅ Never log decrypted PHI data
   - ✅ All KMS operations logged in CloudTrail

3. **Access Control**
   - ✅ Each service has dedicated IAM role
   - ✅ KMS accessed via VPC endpoint (no internet)
   - ✅ Explicit key policies for each KMS key
   - ✅ MFA delete on S3 buckets (production)
   - ✅ Block all public access to S3 buckets

### Audit Trail

All encryption operations are logged:
- `kms:GenerateDataKey` - when encrypting
- `kms:Decrypt` - when decrypting
- Includes encryption context (pagwId)

CloudTrail query example:
```sql
SELECT eventTime, eventName, requestParameters.keyId, 
       requestParameters.encryptionContext
FROM cloudtrail_logs
WHERE eventSource = 'kms.amazonaws.com'
  AND requestParameters.encryptionContext LIKE '%PAGW-%'
ORDER BY eventTime DESC
```

---

## S3Paths Utility Reference

The `PagwProperties.S3Paths` class in pagwcore provides centralized path generation:

```java
// Request Processing Stages
S3Paths.raw(pagwId)        // {YYYYMM}/{pagwId}/request/raw.json
S3Paths.parsed(pagwId)     // {YYYYMM}/{pagwId}/request/parsed.json
S3Paths.validated(pagwId)  // {YYYYMM}/{pagwId}/request/validated.json
S3Paths.enriched(pagwId)   // {YYYYMM}/{pagwId}/request/enriched.json
S3Paths.canonical(pagwId)  // {YYYYMM}/{pagwId}/request/canonical.json

// Response Stages
S3Paths.payerResponse(pagwId)    // {YYYYMM}/{pagwId}/response/payer_raw.json
S3Paths.callbackResponse(pagwId) // {YYYYMM}/{pagwId}/response/callback.json
S3Paths.fhirResponse(pagwId)     // {YYYYMM}/{pagwId}/response/fhir.json

// Attachments
S3Paths.attachmentMeta(pagwId)           // {YYYYMM}/{pagwId}/attachments/meta.json
S3Paths.attachment(pagwId, uuid, name)   // {YYYYMM}/{pagwId}/attachments/{uuid}_{name}

// Audit (separate bucket - no PHI)
S3Paths.audit(service, pagwId, uuid)     // {YYYYMM}/{date}/{service}/{hour}/{pagwId}_{uuid}.json
```

---

## References

- [AWS KMS Developer Guide](https://docs.aws.amazon.com/kms/latest/developerguide/)
- [AWS S3 Encryption](https://docs.aws.amazon.com/AmazonS3/latest/userguide/UsingEncryption.html)
- [HIPAA Security Rule](https://www.hhs.gov/hipaa/for-professionals/security/)
- [AWS HIPAA Compliance](https://aws.amazon.com/compliance/hipaa-compliance/)
- [Da Vinci PAS Implementation Guide](http://hl7.org/fhir/us/davinci-pas/)
