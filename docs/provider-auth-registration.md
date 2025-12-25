# Provider Auth & Registration (HealthOS PriorAuthGateWay / PAGW)

This document defines the **recommended, end-to-end provider onboarding + authentication + authorization flow** for **PAGW PAS** requests (starting with **PAS `$submit`**), based on your current HealthOS TotalView introspection response shape and your target architecture (Apigee → Orchestrator → event-driven services).

---

## Goals

- Allow external provider systems/entities to securely call PAGW PAS APIs.
- Enforce **who** is calling (authentication) and **what** they can do (authorization).
- Resolve provider attributes needed for routing and policy decisions:
  - **NPI(s)**, **TIN**, **entity name**, **entity type**
  - **tenant/brand**: `carelon` vs `elevance`
  - **allowed capabilities/APIs**: submit/inquire/attachments/subscription
- Support **stateless** runtime with **Redis caching** to reduce network calls.
- Produce consistent **audit events** and **OperationOutcome** errors.

---

## Terminology

- **Provider**: external organization/system calling PAS APIs (EHR, clearinghouse, provider group, facility, etc.).
- **ClientId**: stable identifier returned by token introspection (example: `bdda3ee5-df8f-4c86-8d89-4a160e90764d`).
- **Orchestrator**: PAS ingress service that receives `/pas/v1/submit`, generates `trace_id`, stores raw payload, and drives workflow.
- **Registration Service**: system of record for onboarding and entitlements (NPI/TIN/name/tenant/allowed APIs/callback URLs).
- **Introspection**: token verification endpoint returning `active`, `iss`, `clientId`, `scope`, etc.

---

## Recommended End-to-End Flow (happy path)

### 0) Provider Registration (one-time onboarding)

Provider (or vendor on behalf of provider) registers via **HealthOS TotalView Registration Portal** and supplies:

- Legal brand selection (Carelon/Elevance)
- Organization: legal name / DBA / address
- Identifiers: **NPI(s)**, **TIN**, optionally NAIC
- Org type/role: provider, facility, clearinghouse, EHR, payer, third-party app, vendor
- Use cases: submit PA requests, inquire status, send/receive attachments, receive decisions/reasons
- Contacts: business owner, technical lead, 24×7 incident, security
- Callback endpoints (optional initially):
  - CDex `$submit-attachment` return URL
  - Subscription notifications / webhooks

**Output** (what provider receives):
- **client credentials** (or client assertion setup)
- Allowed capability list
- Production/non-prod base URLs (Apigee)

---

### 1) Provider Obtains Access Token (runtime)

Provider uses `client_credentials` grant (or equivalent backend-service pattern) to obtain an **access token**.

- Token endpoint: TotalView OAuth token URL
- Provider stores token client-side; token is sent as:
  - `Authorization: Bearer <access_token>` on PAS API calls

---

### 2) Provider Calls PAS `$submit`

Provider posts the PAS Request Bundle (FHIR R4) to Apigee, which forwards to Orchestrator:

- Endpoint (example): `POST /pas/v1/submit`
- Content-Type: `application/fhir+json`

---

### 3) Orchestrator AuthN/AuthZ + Provider Context

Orchestrator performs:

1. **Extract bearer token** from header.
2. **Introspect token** (or verify JWT via JWKS if available).
3. Validate token:
   - `active == true`
   - `iss` matches expected environment
   - token not expired (`expires_in > 0`)
4. Extract **`clientId`** (best lookup key).
5. Fetch **Provider Registration Profile** using `clientId`:
   - From Redis cache (preferred), else Registration Service / Aurora table.
6. Validate authorization:
   - Provider is active/not suspended
   - Provider is entitled for operation:
     - `/pas/v1/submit` requires capability `PAS_SUBMIT` (or scope `pas.submit`)
7. Create **ProviderContext** (used across pipeline):
   - `tenant`, `entityName`, `npis[]`, `tins[]`, `allowedApis[]`, callback URLs, etc.
8. Generate `trace_id` (UUID) / `pagwId`
9. Persist raw request to S3:
   - `/pagw/pas/<trace_id>/raw.json`
10. Emit SQS event to start pipeline:
   - `SUBMIT_RECEIVED` with `{trace_id, clientId, tenant, s3KeyRaw, ...}`

---

## Flow Diagram (block / architecture)

```mermaid
flowchart LR
  A[Provider System<br/>EHR / Clearinghouse] -->|1) Register (one-time)| R[HealthOS Registration Portal]
  R -->|clientId + credentials + entitlements| A

  A -->|2) POST /pas/v1/submit<br/>Bearer access_token| G[Apigee API Gateway]
  G --> O[Pas Orchestrator]

  O -->|3a) Introspect token| I[Token Introspection API]
  I -->|active, iss, clientId, scope| O

  O -->|3b) Lookup registration by clientId| C{Redis Cache?}
  C -->|hit| RC[Provider Profile]
  C -->|miss| RS[Registration Service / Provider Directory]
  RS -->|provider profile| O

  O -->|4) Generate trace_id| T[trace_id / pagwId]
  O -->|5) Store raw bundle| S3[(S3<br/>/pagw/pas/<trace_id>/raw.json)]
  O -->|6) Emit SUBMIT_RECEIVED| Q1[(SQS: pas-orchestrator)]
  Q1 --> PV[PasRequestValidator Service/Lambda]
  PV -->|VAL_OK / VAL_FAIL| Q2[(SQS: validator results)]
  Q2 --> BV[PasBusinessValidator Service]
  BV --> RB[PasResponseBuilder Service]
  RB -->|Persist response| S3
  RB -->|Return ClaimResponse bundle| O
```

---

## What the Registration Lookup MUST return (minimum contract)

Because introspection response **does not include NPI/TIN/name/tenant**, you must fetch them from Registration/Directory.

**Recommended fields:**
- `clientId` (same as introspection response)
- `status`: `ACTIVE | SUSPENDED | REVOKED`
- `tenant`: `carelon | elevance`
- `entityName` (legal name)
- `entityType`: provider/facility/clearinghouse/EHR/vendor
- `npis`: array
- `tins`: array
- `allowedApis`: array  
  Example: `["PAS_SUBMIT","PAS_INQUIRE","CDEX_SUBMIT_ATTACHMENT","PAS_SUBSCRIBE"]`
- `callbackEndpoints` (optional initially):
  - cdex submit-attachment URL
  - subscription webhook URL(s)

---

## Caching Strategy (recommended)

### A) Cache introspection results (short TTL)
Key:
- `token:introspect:<sha256(access_token)>`

TTL:
- `min(300s, expires_in - 60s)` (safety buffer)

### B) Cache provider registration profile (medium TTL)
Key:
- `reg:client:<clientId>`

TTL:
- 15 minutes to 24 hours (choose based on how quickly entitlements may change)
- If you need near-real-time revocation, prefer shorter TTL (e.g., 15–60 minutes)

---

## Authorization Rules (PAS submit)

### Required checks
- Token valid (active, not expired)
- Registration record exists for `clientId`
- Registration status is ACTIVE
- Registration entitles `PAS_SUBMIT` for `/pas/v1/submit`
- Tenant resolved (carelon vs elevance) from registration (source of truth)

### Status codes
- `401 Unauthorized`
  - missing/invalid token, inactive token, wrong issuer, expired token
- `403 Forbidden`
  - token valid but provider not registered, suspended, or lacks entitlement for operation

---

## Error Responses (FHIR-friendly)

Even for auth failures, many integrations prefer a FHIR `OperationOutcome` body.

### Suggested mapping
- HTTP 401/403 with `OperationOutcome`:
  - `issue[0].severity = "error"`
  - `issue[0].code = "security"`
  - `issue[0].diagnostics` includes a non-sensitive reason
  - avoid leaking internal identifiers

---

## Implementation Checklist

### Orchestrator
- [ ] Extract bearer token
- [ ] Introspection call
- [ ] Validate `active`, `iss`, expiry
- [ ] Extract `clientId`
- [ ] Load provider profile from Redis or registration service
- [ ] Validate `PAS_SUBMIT` entitlement
- [ ] Create `ProviderContext`
- [ ] Generate `trace_id`
- [ ] Write raw bundle to S3
- [ ] Emit SQS event (`SUBMIT_RECEIVED`)
- [ ] Return immediate ACK/PEND (if async), or wait for response (if sync)

### Registration/Directory
- [ ] API supports lookup by `clientId`
- [ ] Returns tenant + NPI/TIN + allowed APIs
- [ ] Status field drives allow/deny

### Redis
- [ ] Key patterns defined
- [ ] TTL policy aligned to expiry/entitlement churn
- [ ] Cache invalidation plan (optional)

---

## Notes / Decisions

- **Source of truth for tenant**: Registration Service / Provider Directory (not request payload).
- If issuer scopes are not PAS-specific, enforce PAS access via **registration entitlements** until PAS scopes are added.
- Prefer JWKS JWT validation for low latency if supported; use introspection when revocation/extra metadata is required.
