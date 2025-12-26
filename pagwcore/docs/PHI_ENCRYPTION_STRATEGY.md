# PHI Encryption Strategy for PAGW

## Overview

This document describes the encryption strategy for Protected Health Information (PHI) in the Prior Authorization Gateway (PAGW) system, ensuring HIPAA compliance and data security.

## Encryption Layers

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

## KMS Key Hierarchy

```
┌─────────────────────────────────────────────────────────────┐
│                    AWS KMS KEY HIERARCHY                     │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  pagw-s3-kms-key                                            │
│  ├── Purpose: S3 bucket encryption                          │
│  ├── Alias: alias/pagw-s3-{env}                            │
│  └── Used by: All S3 buckets (raw, enriched, final, attach) │
│                                                              │
│  pagw-sqs-kms-key                                           │
│  ├── Purpose: SQS queue encryption                          │
│  ├── Alias: alias/pagw-sqs-{env}                           │
│  └── Used by: All SQS queues and DLQs                       │
│                                                              │
│  pagw-rds-kms-key                                           │
│  ├── Purpose: Aurora database encryption                    │
│  ├── Alias: alias/pagw-rds-{env}                           │
│  └── Used by: Aurora cluster (data at rest)                 │
│                                                              │
│  pagw-phi-field-kms-key  ← PRIMARY FOR PHI                  │
│  ├── Purpose: Field-level PHI encryption                    │
│  ├── Alias: alias/pagw-phi-field-{env}                     │
│  └── Used by: PhiEncryptionService for sensitive fields     │
│                                                              │
│  pagw-secrets-kms-key                                       │
│  ├── Purpose: Secrets Manager encryption                    │
│  ├── Alias: alias/pagw-secrets-{env}                       │
│  └── Used by: DB credentials, API keys                      │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

## Envelope Encryption Flow

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

## PHI Fields to Encrypt

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

## Implementation

### 1. PhiEncryptionService

```java
@Service
public class PhiEncryptionService {
    
    // Uses AWS KMS envelope encryption
    // Algorithm: AES-256-GCM
    // IV: 12 bytes, random per encryption
    // Tag: 128 bits
    
    public EncryptedData encrypt(String plaintext, Map<String, String> context) {
        // 1. Generate Data Key from KMS
        // 2. Encrypt locally with DEK
        // 3. Return encrypted data + encrypted DEK
    }
    
    public String decrypt(EncryptedData data, Map<String, String> context) {
        // 1. Decrypt DEK using KMS
        // 2. Decrypt data locally with DEK
        // 3. Clear DEK from memory
    }
}
```

### 2. Encryption Context

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

### 3. Configuration

```yaml
pagw:
  encryption:
    enabled: true
    encrypt-phi-fields: true
    phi-fields:
      - patient.name
      - patient.birthDate
      - patient.identifier
      - patient.address
      - patient.telecom
  aws:
    kms:
      enabled: true
      phi-key-alias: alias/pagw-phi-field-dev
```

## Security Best Practices

### Key Management
1. **Key Rotation**: Enable automatic annual key rotation for all KMS keys
2. **Least Privilege**: IAM policies grant only necessary KMS permissions
3. **Key Separation**: Different keys for different purposes (S3, SQS, PHI fields)
4. **No Key Export**: CMKs are non-exportable

### Data Handling
1. **Memory Clearing**: Clear plaintext DEK from memory after use
2. **Secure Random**: Use SecureRandom for IV generation
3. **No Logging PHI**: Never log decrypted PHI data
4. **Audit Trail**: All KMS operations logged in CloudTrail

### Access Control
1. **IAM Roles**: Each service has dedicated IAM role
2. **VPC Endpoints**: KMS accessed via VPC endpoint (no internet)
3. **Key Policy**: Explicit key policies for each KMS key
4. **MFA Delete**: Enable MFA delete on S3 buckets

## Audit & Compliance

### CloudTrail Integration
All KMS operations are logged:
- `GenerateDataKey` - when encrypting
- `Decrypt` - when decrypting
- Includes encryption context (pagwId)

### Audit Log Table
```sql
CREATE TABLE audit_log (
    ...
    phi_accessed        BOOLEAN NOT NULL DEFAULT FALSE,
    phi_fields_accessed TEXT[] NULL,
    access_reason       TEXT NULL,
    ...
);
```

### HIPAA Compliance Checklist
- [x] Encryption at rest (S3, Aurora, SQS)
- [x] Encryption in transit (TLS 1.3)
- [x] Field-level encryption for sensitive PHI
- [x] Access logging and audit trail
- [x] Key rotation enabled
- [x] Least privilege access
- [x] Backup encryption
- [x] Data retention policies

## Terraform Resources

See `infra/terraform/kms.tf` for KMS key definitions:
- `aws_kms_key.s3_key`
- `aws_kms_key.sqs_key`
- `aws_kms_key.rds_key`
- `aws_kms_key.phi_field_key`
- `aws_kms_key.secrets_key`

## Testing

### Local Development
For local development without AWS:
```yaml
pagw:
  encryption:
    enabled: false  # Disable in local dev
```

### Integration Testing
Use LocalStack or AWS test account:
```bash
aws kms create-key --description "Test PHI key"
aws kms create-alias --alias-name alias/pagw-phi-field-test --target-key-id <key-id>
```

## References

- [AWS KMS Developer Guide](https://docs.aws.amazon.com/kms/latest/developerguide/)
- [HIPAA Security Rule](https://www.hhs.gov/hipaa/for-professionals/security/)
- [AWS HIPAA Compliance](https://aws.amazon.com/compliance/hipaa-compliance/)
