# PAGW PAS - Postman Setup Guide

## Overview

This guide explains how to:
1. **Act as a Provider** - Send PA requests to PAGW
2. **Create a Mock Payer** - Simulate payer responses for testing

---

## Quick Start

### Import the Collection
1. Open Postman
2. Click **Import** → Select `test/postman/PAGW-PAS-Collection.postman_collection.json`
3. The collection includes:
   - Provider Requests (what you send TO PAGW)
   - Mock Payer Responses (what payers send BACK)
   - Test Scenarios (end-to-end flows)

---

## Part 1: Acting as a PROVIDER

As a provider, you send Prior Authorization requests to PAGW.

### Setup Environment Variables
In Postman, set these variables:

| Variable | Dev Value |
|----------|-----------|
| `baseUrl` | `https://pasorchestrator.pagwdev.awsdns.internal.das` |
| `correlationId` | (auto-generated) |
| `pagwId` | (captured from responses) |

### Provider Flow

```
Provider                          PAGW
   │                               │
   │──── POST /pas/api/v1/submit ─────►│
   │     (FHIR Bundle)             │
   │                               │
   │◄─── 200 OK ──────────────────│
   │     (ClaimResponse)           │
   │                               │
   │──── GET /pas/api/v1/status/{id} ─►│
   │                               │
   │◄─── 200 OK ──────────────────│
   │     (Current status)          │
```

### Example: Submit a PA Request

```bash
curl -X POST "https://pasorchestrator.pagwdev.awsdns.internal.das/pas/api/v1/submit?syncMode=true" \
  -H "Content-Type: application/json" \
  -H "X-Correlation-ID: DEMO-$(date +%s)" \
  -H "X-Tenant-ID: ANTHEM" \
  -d '{
    "resourceType": "Bundle",
    "type": "collection",
    "entry": [
      {
        "resource": {
          "resourceType": "Claim",
          "status": "active",
          "type": {"coding": [{"code": "professional"}]},
          "use": "preauthorization",
          "patient": {"reference": "Patient/12345"},
          "created": "2025-12-22",
          "provider": {"reference": "Organization/67890"},
          "priority": {"coding": [{"code": "normal"}]},
          "insurance": [{"sequence": 1, "focal": true, "coverage": {"reference": "Coverage/ABC123"}}],
          "item": [{"sequence": 1, "productOrService": {"coding": [{"code": "27447", "display": "Total Knee Replacement"}]}}]
        }
      }
    ]
  }'
```

---

## Part 2: Creating a MOCK PAYER

A mock payer simulates the external payer system that PAGW connects to.

### Option A: Postman Mock Server (Easiest)

1. In Postman, right-click on **Mock Payer Responses** folder
2. Click **Mock Collection**
3. Configure:
   - Name: `PAGW Mock Payer`
   - Environment: Select your environment
4. Copy the Mock Server URL (e.g., `https://abc123.mock.pstmn.io`)
5. Configure PAGW to point to this URL

### Option B: WireMock (More Control)

#### 1. Start WireMock
```bash
# Using Docker
docker run -d --name wiremock \
  -p 8081:8080 \
  wiremock/wiremock:latest

# Or download JAR
java -jar wiremock-standalone.jar --port 8081
```

#### 2. Create Mock Mappings

**Approved Response** - `mappings/approve-pa.json`:
```json
{
  "request": {
    "method": "POST",
    "urlPathPattern": "/payer/pas/submit",
    "bodyPatterns": [
      {"contains": "preauthorization"}
    ]
  },
  "response": {
    "status": 200,
    "headers": {
      "Content-Type": "application/json"
    },
    "jsonBody": {
      "resourceType": "ClaimResponse",
      "status": "active",
      "outcome": "complete",
      "disposition": "Prior Authorization Approved",
      "preAuthRef": "PA-2025-{{randomValue length=8 type='ALPHANUMERIC'}}",
      "preAuthPeriod": {
        "start": "2025-12-22",
        "end": "2026-03-22"
      }
    }
  }
}
```

**Denied Response** - `mappings/deny-pa.json`:
```json
{
  "request": {
    "method": "POST",
    "urlPathPattern": "/payer/pas/submit",
    "headers": {
      "X-Mock-Response": {"equalTo": "DENY"}
    }
  },
  "response": {
    "status": 200,
    "jsonBody": {
      "resourceType": "ClaimResponse",
      "status": "active",
      "outcome": "complete",
      "disposition": "Prior Authorization Denied",
      "error": [{"code": {"text": "Medical necessity not established"}}]
    }
  }
}
```

**Pended Response** - `mappings/pend-pa.json`:
```json
{
  "request": {
    "method": "POST",
    "urlPathPattern": "/payer/pas/submit",
    "headers": {
      "X-Mock-Response": {"equalTo": "PEND"}
    }
  },
  "response": {
    "status": 200,
    "jsonBody": {
      "resourceType": "ClaimResponse",
      "status": "active",
      "outcome": "queued",
      "disposition": "Pended - Additional documentation required"
    }
  }
}
```

### Option C: Simple Node.js Mock Server

Create `mock-payer.js`:
```javascript
const express = require('express');
const app = express();
app.use(express.json());

// Approved response (default)
app.post('/payer/pas/submit', (req, res) => {
  const mockResponse = req.headers['x-mock-response'] || 'APPROVE';
  
  const responses = {
    APPROVE: {
      resourceType: 'ClaimResponse',
      status: 'active',
      outcome: 'complete',
      disposition: 'Prior Authorization Approved',
      preAuthRef: `PA-${Date.now()}`,
      preAuthPeriod: { start: '2025-12-22', end: '2026-03-22' }
    },
    DENY: {
      resourceType: 'ClaimResponse',
      status: 'active',
      outcome: 'complete',
      disposition: 'Prior Authorization Denied',
      error: [{ code: { text: 'Medical necessity not established' } }]
    },
    PEND: {
      resourceType: 'ClaimResponse',
      status: 'active',
      outcome: 'queued',
      disposition: 'Pended - Additional documentation required'
    }
  };

  console.log(`[Mock Payer] Received PA request, responding with: ${mockResponse}`);
  res.json(responses[mockResponse] || responses.APPROVE);
});

// Health check
app.get('/health', (req, res) => res.json({ status: 'UP' }));

app.listen(8081, () => console.log('Mock Payer running on http://localhost:8081'));
```

Run with:
```bash
npm init -y && npm install express
node mock-payer.js
```

---

## Part 3: End-to-End Testing Flow

```
┌──────────┐      ┌──────────────┐      ┌────────────┐
│ PROVIDER │      │    PAGW      │      │ MOCK PAYER │
│(Postman) │      │(Orchestrator)│      │ (WireMock) │
└────┬─────┘      └──────┬───────┘      └─────┬──────┘
     │                   │                     │
     │ POST /submit      │                     │
     │──────────────────►│                     │
     │                   │ POST /payer/submit  │
     │                   │────────────────────►│
     │                   │                     │
     │                   │◄────────────────────│
     │                   │ ClaimResponse       │
     │◄──────────────────│                     │
     │ ClaimResponse     │                     │
     │                   │                     │
```

### Test Scenario Matrix

| Scenario | X-Mock-Response Header | Expected Outcome |
|----------|------------------------|------------------|
| Happy Path | (none) or `APPROVE` | PA Approved |
| Denial | `DENY` | PA Denied with reason |
| Pended | `PEND` | Pending more info |
| Timeout | (delay mock 30s) | Timeout error |
| Error | Return 500 | Error handling |

---

## Part 4: Configuring PAGW to Use Mock Payer

### For Local Development
In `application-local.yml`:
```yaml
pagw:
  payer:
    api:
      base-url: http://localhost:8081
      endpoints:
        submit: /payer/pas/submit
        inquiry: /payer/pas/inquiry
```

### For Dev Environment (via ConfigMap)
```yaml
# In Kubernetes ConfigMap
data:
  PAYER_API_BASE_URL: "http://mock-payer-service:8081"
```

---

## Postman Collection Structure

```
PAGW PAS Collection/
├── Provider Requests/
│   ├── Health Check
│   ├── PAS Health
│   ├── Submit PA Request (Sync)
│   ├── Submit PA Request (Async)
│   ├── Get Request Status
│   └── PA Inquiry
├── Mock Payer Responses/
│   ├── Payer - Approve PA
│   ├── Payer - Deny PA
│   └── Payer - Pend PA (Need More Info)
└── Test Scenarios/
    ├── Scenario 1: Happy Path
    └── Scenario 2: Async with Polling
```

---

## Tips for Demos

1. **Use unique correlation IDs** - The collection auto-generates them
2. **Show the flow visually** - Explain provider→PAGW→payer flow
3. **Demonstrate error handling** - Use mock to return errors
4. **Show async polling** - Submit async, then poll status
5. **Trace with correlation ID** - Show logs filtered by correlation ID

---

## Troubleshooting

| Issue | Solution |
|-------|----------|
| Connection refused | Check baseUrl is correct |
| 401 Unauthorized | Check API Gateway auth headers |
| 500 Internal Server Error | Check PAGW logs, may be downstream payer issue |
| Timeout | Increase sync timeout or use async mode |

---

*Last Updated: December 22, 2025*
