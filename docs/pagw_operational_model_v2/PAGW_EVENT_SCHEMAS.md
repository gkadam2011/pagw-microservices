# PAGW Event Schemas (SQS payloads) — v1 starter

These payloads should be **PHI-safe**: include references (S3 keys, ids), not full bundles.

## Envelope (common for all events)

```json
{
  "tenant": "elevance",
  "pagwId": "PAGW-TEST-12345",
  "requestType": "PAS",
  "workflowId": "pas-default",
  "workflowVersion": "1.0.0",
  "scenarioId": "pas-submit-inline-attachments",
  "stage": "STRUCTURE_VALIDATION",
  "eventType": "VAL_REQUEST",
  "sequenceNo": 3,
  "attempt": 0,
  "createdAt": "2025-12-25T17:21:50Z",
  "source": "pas-orchestrator",
  "correlationId": "apigee-x-correlation-id",
  "idempotencyKey": "optional",
  "s3": {
    "bucket": "dev-pagw-fhir-bundles",
    "rawKey": "pagw/pas/PAGW-TEST-12345/raw.json"
  },
  "metadata": {
    "operation": "$submit",
    "payerHint": "EAPI"
  }
}
```

## Orchestrator → Request Validator

**Queue:** `PAS_REQUEST_VALIDATOR`

- `eventType=VAL_REQUEST`
- Required: `tenant`, `pagwId`, `s3.bucket`, `s3.rawKey`, `sequenceNo`

## Request Validator → Orchestrator (result)

**Queue:** `PAS_ORCHESTRATOR` (or a dedicated result queue)

### Success

```json
{
  "tenant": "elevance",
  "pagwId": "PAGW-TEST-12345",
  "stage": "STRUCTURE_VALIDATION",
  "eventType": "VAL_OK",
  "sequenceNo": 4,
  "attempt": 0,
  "validation": {
    "isValid": true,
    "issuesCount": 0
  },
  "createdAt": "2025-12-25T17:22:10Z",
  "source": "pas-request-parser-validator"
}
```

### Failure (OperationOutcome in S3, summary in message)

```json
{
  "tenant": "elevance",
  "pagwId": "PAGW-TEST-12345",
  "stage": "STRUCTURE_VALIDATION",
  "eventType": "VAL_FAIL",
  "sequenceNo": 4,
  "attempt": 0,
  "validation": {
    "isValid": false,
    "issuesCount": 3,
    "outcomeS3": {
      "bucket": "dev-pagw-fhir-bundles",
      "key": "pagw/pas/PAGW-TEST-12345/operation-outcome.json"
    }
  },
  "error": {
    "code": "FHIR_VALIDATION_FAILED",
    "message": "Bundle.entry.fullUrl must be present and unique"
  },
  "createdAt": "2025-12-25T17:22:10Z",
  "source": "pas-request-parser-validator"
}
```

## Business Validator request/response (pattern)

- `eventType=AUTHZ_REQUEST` / `AUTHZ_OK` / `AUTHZ_FAIL`
- Include `provider` block (NPI, TIN, clientId) only if **non-PHI** per your policy; otherwise store in DB and only reference `providerId`.

## Response Builder result

- `eventType=RESP_BUILT`
- Include outcome summary + final response S3 key.
