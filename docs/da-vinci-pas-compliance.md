# Da Vinci PAS Implementation Guide Compliance

This document describes how the PAGW platform implements the [HL7 Da Vinci Prior Authorization Support (PAS) Implementation Guide](http://hl7.org/fhir/us/davinci-pas/).

## Overview

Da Vinci PAS defines a standardized FHIR-based API for exchanging prior authorization requests between providers and payers. The PAGW platform supports both synchronous and asynchronous processing modes to meet varying payer requirements.

## Key Requirements

| Requirement | Da Vinci PAS IG | PAGW Implementation | Status |
|------------|-----------------|---------------------|--------|
| Response Time | ≤15 seconds | Sync path with 13s timeout | ✅ |
| Pended Responses | Return "pended" status | Supported with async queue | ✅ |
| $inquiry Operation | Query pended status | InquiryService | ✅ |
| Update Claims | Claim.related.claim | ClaimModificationService | ✅ |
| Cancel Claims | certificationType=3 | ClaimModificationService | ✅ |
| OperationOutcome | Validation errors | Supported | ✅ |
| Provider Match | Claim.provider vs token | Validated in sync path | ✅ |
| Bundle.identifier | Unique per submission | Idempotency check | ✅ |
| Subscriptions | R5 Backport | SubscriptionService | ✅ |

## Processing Architecture

### Synchronous Path (Default)

```
Provider Request
       │
       ▼
┌─────────────────┐
│   API Gateway   │
│ (pasproviderauth│
│   authorizer)   │
└────────┬────────┘
         │ X-Provider-Id header
         ▼
┌─────────────────┐
│  Orchestrator   │ ◄── 15-second timer starts
│                 │
│ 1. Idempotency  │
│ 2. Store to S3  │
│ 3. Create tracker│
└────────┬────────┘
         │
         ▼
┌─────────────────┐     ┌─────────────────┐
│ SyncProcessing  │────►│ Request Parser  │
│    Service      │     │ (HTTP call)     │
└────────┬────────┘     └─────────────────┘
         │
         ▼
┌─────────────────┐
│Business Validator│
│ (HTTP call)      │
│                  │
│ • Validate FHIR  │
│ • Check rules    │
│ • Decision?      │
└────────┬────────┘
         │
    ┌────┴────┐
    │         │
    ▼         ▼
IMMEDIATE   PENDED
DECISION    (async)
    │         │
    ▼         ▼
ClaimResponse  Queue to
(approved/     SQS for
denied/        full
partial)       pipeline
```

### Response Types

#### 1. Immediate Decision (within 15 seconds)

```json
{
  "resourceType": "ClaimResponse",
  "pagwId": "PAGW-12345",
  "status": "approved",
  "disposition": "Certified in Total",
  "payerTrackingId": "AUTH-67890",
  "processingTimeMs": 2345,
  "claimResponseBundle": "{ ... full FHIR bundle ... }"
}
```

#### 2. Pended Response

```json
{
  "resourceType": "ClaimResponse",
  "pagwId": "PAGW-12345",
  "status": "pended",
  "disposition": "Pended",
  "message": "Request requires additional review. You will be notified when a decision is made. Use $inquiry operation with tracking ID: PAGW-12345",
  "payerTrackingId": "PAGW-12345",
  "processingTimeMs": 13000
}
```

#### 3. Validation Error (OperationOutcome)

```json
{
  "resourceType": "OperationOutcome",
  "pagwId": "PAGW-12345",
  "status": "error",
  "message": "Validation failed: [MISSING_MEMBER_ID] Member ID is required at Claim.patient.identifier",
  "validationErrors": [
    {
      "code": "MISSING_MEMBER_ID",
      "severity": "error",
      "location": "Claim.patient.identifier",
      "message": "Member ID is required",
      "issueType": "required"
    }
  ]
}
```

## Disposition Codes

The platform maps X12 271 HCR01 codes to FHIR ClaimResponse outcomes:

| X12 Code | FHIR Outcome | Description |
|----------|--------------|-------------|
| A1 | approved | Certified in Total |
| A2 | partial | Certified - Partial |
| A3 | denied | Not Certified |
| A4 | pended | Pended |
| A6 | modified | Modified |

## Security

### Provider Identity Validation

Per Da Vinci PAS IG Section 4.0.6, the `Claim.provider.identifier` MUST match the authenticated provider from the OAuth token.

The `SyncProcessingService` validates this by:
1. Extracting `Claim.provider.identifier` from the FHIR Bundle
2. Comparing against `X-Provider-Id` header (set by Lambda Authorizer)
3. Returning `PROVIDER_MISMATCH` error if they don't match

### Idempotency

`Bundle.identifier` must be unique per submission. The platform:
1. Extracts `Bundle.identifier.value` from the request
2. Checks against `IdempotencyService` (Redis/DynamoDB backed)
3. Returns duplicate error if already processed

## Async Pipeline (for Pended Requests)

When a request cannot be decided within 15 seconds, it's queued for the full async pipeline:

```
Orchestrator (pended)
       │
       ▼ SQS: pagw-request-parser
┌─────────────────┐
│ Request Parser  │
└────────┬────────┘
         │
         ▼ SQS: pagw-business-validator
┌─────────────────┐
│Business Validator│
└────────┬────────┘
         │
         ├────────────────────┐
         │                    │ (if attachments)
         ▼                    ▼
SQS: pagw-request-enricher   SQS: pagw-attachment-handler
┌─────────────────┐          ┌─────────────────┐
│Request Enricher │          │Attachment Handler│
└────────┬────────┘          └─────────────────┘
         │
         ▼ SQS: pagw-request-converter
┌─────────────────┐
│Request Converter│
└────────┬────────┘
         │
         ▼ SQS: pagw-api-connector
┌─────────────────┐
│  API Connector  │ ──► Downstream Payer
└────────┬────────┘
         │
         ▼ SQS: pagw-callback-handler
┌─────────────────┐
│Callback Handler │ ──► Notify Provider
└────────┬────────┘
         │
         ▼ SQS: pagw-response-builder
┌─────────────────┐
│Response Builder │ ──► Final ClaimResponse
└─────────────────┘
```

## Configuration

### Sync Processing Settings

```yaml
pagw:
  sync:
    enabled: true
    timeout-seconds: 13  # Leave 2s buffer for response serialization
  
  services:
    request-parser:
      url: http://pasrequestparser:8080
    business-validator:
      url: http://pasbusinessvalidator:8080
```

### Disabling Sync Mode

Set `X-Sync-Processing: false` header or configure:

```yaml
pagw:
  sync:
    enabled: false
```

## TODO Items

The following Da Vinci PAS features are planned but not yet implemented:

1. **WebSocket Subscriptions** - Real-time push notifications (REST-hook implemented)
2. **Email Notifications** - Email alerts for pended authorization updates
3. **$attach Operation** - Attach additional documentation to pending requests

## API Endpoints

### Submit ($submit)
```
POST /pas/api/v1/submit
Headers:
  - X-Tenant-Id: tenant identifier
  - X-Provider-Id: authenticated provider (from OAuth)
  - X-Correlation-Id: tracing ID
  - X-Sync-Processing: true/false (default: true)
Body: FHIR Bundle with Claim
```

### Inquiry ($inquiry)
```
POST /pas/api/v1/inquiry
Query Params (any one):
  - pagwId: PAGW tracking ID
  - preAuthRef: Payer authorization reference
  - bundleIdentifier: Original Bundle.identifier
  - patientId + providerId + serviceDateFrom/To
```

### Update
```
POST /pas/api/v1/update
Headers:
  - X-Related-Claim-Id: Original authorization to update (required)
Body: FHIR Bundle with updated Claim
```

### Cancel
```
POST /pas/api/v1/cancel
Headers:
  - X-Related-Claim-Id: Authorization to cancel (required)
```

### Status
```
GET /pas/api/v1/status/{pagwId}
```

### Subscriptions
```
POST /pas/api/v1/subscriptions
  - pagwId: Authorization to subscribe to
  - endpoint: Webhook callback URL
  - secret: HMAC secret for signature verification

GET /pas/api/v1/subscriptions/{subscriptionId}

DELETE /pas/api/v1/subscriptions/{subscriptionId}
```

## References

- [Da Vinci PAS Implementation Guide](http://hl7.org/fhir/us/davinci-pas/)
- [Da Vinci PAS Specification](http://hl7.org/fhir/us/davinci-pas/specification.html)
- [X12 278 Health Care Services Review](https://x12.org/)
- [R5 Backport Subscriptions](http://hl7.org/fhir/uv/subscriptions-backport/)
