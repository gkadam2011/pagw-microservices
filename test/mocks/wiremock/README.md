# WireMock Mock Payer Setup Guide

This guide explains how to set up WireMock as a mock payer for testing the PAGW Prior Authorization Gateway.

## Table of Contents

1. [Overview](#overview)
2. [Quick Start](#quick-start)
3. [Docker Compose Setup](#docker-compose-setup)
4. [Mapping Files Structure](#mapping-files-structure)
5. [Request Matching](#request-matching)
6. [Response Templates](#response-templates)
7. [Simulating Real-World Scenarios](#simulating-real-world-scenarios)
8. [Admin API](#admin-api)
9. [Troubleshooting](#troubleshooting)
10. [Best Practices](#best-practices)

---

## Overview

### What is WireMock?

WireMock is a flexible HTTP mock server that allows you to stub API responses. For PAGW, we use it to simulate payer endpoints without connecting to real payer systems.

### Why WireMock for PAGW?

| Feature | Benefit for PAGW |
|---------|------------------|
| **JSON-based mappings** | Easy to version control in Git |
| **Request matching** | Match by URL, headers, body content |
| **Response templating** | Dynamic responses with request data |
| **Delay simulation** | Test async flows and timeouts |
| **Fault injection** | Test error handling paths |
| **Docker support** | Runs alongside your services |
| **No external dependencies** | Works offline, no accounts |

### Architecture

```
┌─────────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│   pasapiconnector   │────▶│   WireMock       │────▶│  Mock Response  │
│   (makes outbound   │     │   (port 8090)    │     │  (configurable) │
│    payer calls)     │     │                  │     │                 │
└─────────────────────┘     └──────────────────┘     └─────────────────┘
                                    │
                                    ▼
                            ┌──────────────────┐
                            │  mappings/*.json │
                            │  (response rules)│
                            └──────────────────┘
```

---

## Quick Start

### 1. Start WireMock with Docker

```bash
# From repository root
docker-compose -f docker-compose.local.yml up mock-payer
```

### 2. Verify it's running

```bash
curl http://localhost:8090/__admin/mappings
```

### 3. Test a mock response

```bash
curl -X POST http://localhost:8090/api/x12/278 \
  -H "Content-Type: application/json" \
  -d '{"subscriberId": "TEST123"}'
```

---

## Docker Compose Setup

Add this service to `docker-compose.local.yml`:

```yaml
services:
  mock-payer:
    image: wiremock/wiremock:3.3.1
    container_name: pagw-mock-payer
    ports:
      - "8090:8080"
    volumes:
      - ./test/mocks/wiremock:/home/wiremock
    command: 
      - "--verbose"
      - "--global-response-templating"
    networks:
      - pagw-network
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/__admin/health"]
      interval: 10s
      timeout: 5s
      retries: 3
```

### Command Options Explained

| Option | Purpose |
|--------|---------|
| `--verbose` | Log all requests for debugging |
| `--global-response-templating` | Enable Handlebars templating in all responses |
| `--port 8080` | Internal port (default) |
| `--disable-gzip` | Disable gzip (useful for debugging) |
| `--record-mappings` | Auto-create mappings from proxied requests |

---

## Mapping Files Structure

WireMock reads configuration from these directories:

```
test/mocks/wiremock/
├── mappings/                    # Request-response mappings
│   ├── payer-approved.json      # Approved PA response
│   ├── payer-denied.json        # Denied PA response
│   ├── payer-pended.json        # Pended (needs review) response
│   ├── payer-error-500.json     # Server error simulation
│   ├── payer-timeout.json       # Timeout simulation
│   └── payer-callback.json      # Async callback trigger
├── __files/                     # Static response files
│   ├── approved-response.json
│   ├── denied-response.json
│   └── x12-278-response.edi
└── README.md                    # This file
```

---

## Request Matching

### Basic URL Matching

```json
{
  "request": {
    "method": "POST",
    "url": "/api/x12/278"
  },
  "response": {
    "status": 200,
    "body": "{\"status\": \"approved\"}"
  }
}
```

### URL Pattern Matching (Regex)

```json
{
  "request": {
    "method": "POST",
    "urlPattern": "/api/x12/278.*"
  },
  "response": {
    "status": 200
  }
}
```

### Path Parameter Matching

```json
{
  "request": {
    "method": "GET",
    "urlPathPattern": "/api/payers/([a-zA-Z0-9]+)/status"
  },
  "response": {
    "status": 200
  }
}
```

### Header Matching

```json
{
  "request": {
    "method": "POST",
    "url": "/api/x12/278",
    "headers": {
      "Content-Type": {
        "equalTo": "application/json"
      },
      "X-Payer-Id": {
        "matches": "PAYER-[0-9]+"
      },
      "Authorization": {
        "contains": "Bearer"
      }
    }
  },
  "response": {
    "status": 200
  }
}
```

### Body Matching

#### Exact Match
```json
{
  "request": {
    "method": "POST",
    "bodyPatterns": [
      { "equalToJson": {"subscriberId": "TEST123"} }
    ]
  }
}
```

#### Contains Match
```json
{
  "request": {
    "method": "POST",
    "bodyPatterns": [
      { "contains": "subscriberId" }
    ]
  }
}
```

#### JSONPath Match
```json
{
  "request": {
    "method": "POST",
    "bodyPatterns": [
      { "matchesJsonPath": "$.patient.subscriberId" },
      { "matchesJsonPath": "$.serviceType[?(@.code == 'MRI')]" }
    ]
  }
}
```

#### XPath Match (for X12/XML)
```json
{
  "request": {
    "method": "POST",
    "bodyPatterns": [
      { "matchesXPath": "//SubscriberId" }
    ]
  }
}
```

### Priority

When multiple mappings could match, use priority (lower = higher priority):

```json
{
  "priority": 1,
  "request": {
    "method": "POST",
    "url": "/api/x12/278",
    "bodyPatterns": [
      { "contains": "DENY-TEST" }
    ]
  },
  "response": {
    "status": 200,
    "body": "{\"status\": \"denied\"}"
  }
}
```

---

## Response Templates

WireMock supports Handlebars templating for dynamic responses.

### Basic Templating

```json
{
  "request": {
    "method": "POST",
    "url": "/api/x12/278"
  },
  "response": {
    "status": 200,
    "headers": {
      "Content-Type": "application/json"
    },
    "body": "{ \"trackingNumber\": \"TRK-{{randomValue type='UUID'}}\", \"timestamp\": \"{{now}}\" }",
    "transformers": ["response-template"]
  }
}
```

### Using Request Data in Response

```json
{
  "request": {
    "method": "POST",
    "url": "/api/x12/278"
  },
  "response": {
    "status": 200,
    "body": "{ \"echo\": { \"method\": \"{{request.method}}\", \"path\": \"{{request.path}}\", \"subscriberId\": \"{{jsonPath request.body '$.subscriberId'}}\" }}",
    "transformers": ["response-template"]
  }
}
```

### Available Template Helpers

| Helper | Example | Output |
|--------|---------|--------|
| `{{randomValue type='UUID'}}` | Random UUID | `a1b2c3d4-...` |
| `{{randomValue length=10}}` | Random string | `aBcDeFgHiJ` |
| `{{now}}` | Current timestamp | `2025-12-22T10:30:00Z` |
| `{{now offset='3 days'}}` | Future date | `2025-12-25T10:30:00Z` |
| `{{now format='yyyy-MM-dd'}}` | Formatted date | `2025-12-22` |
| `{{request.headers.X-Request-Id}}` | Header value | `req-123` |
| `{{jsonPath request.body '$.field'}}` | JSON field | Field value |

### Response from File

```json
{
  "request": {
    "method": "POST",
    "url": "/api/x12/278"
  },
  "response": {
    "status": 200,
    "headers": {
      "Content-Type": "application/json"
    },
    "bodyFileName": "approved-response.json",
    "transformers": ["response-template"]
  }
}
```

---

## Simulating Real-World Scenarios

### Scenario 1: Approved Response

**File: `mappings/payer-approved.json`**
```json
{
  "name": "PA Approved Response",
  "request": {
    "method": "POST",
    "urlPattern": "/api/x12/278.*",
    "bodyPatterns": [
      { "matchesJsonPath": "$[?(@.subscriberId != 'DENY-TEST')]" }
    ]
  },
  "response": {
    "status": 200,
    "headers": {
      "Content-Type": "application/json",
      "X-Correlation-Id": "{{request.headers.X-Correlation-Id}}"
    },
    "jsonBody": {
      "responseCode": "A1",
      "responseStatus": "APPROVED",
      "message": "Prior authorization approved",
      "authorizationNumber": "AUTH-{{randomValue type='UUID'}}",
      "effectiveDate": "{{now format='yyyy-MM-dd'}}",
      "expirationDate": "{{now offset='90 days' format='yyyy-MM-dd'}}",
      "approvedUnits": 10,
      "approvedVisits": 5,
      "trackingNumber": "{{jsonPath request.body '$.trackingNumber'}}",
      "processedAt": "{{now}}"
    },
    "transformers": ["response-template"],
    "fixedDelayMilliseconds": 200
  }
}
```

### Scenario 2: Denied Response

**File: `mappings/payer-denied.json`**
```json
{
  "name": "PA Denied Response",
  "priority": 1,
  "request": {
    "method": "POST",
    "urlPattern": "/api/x12/278.*",
    "bodyPatterns": [
      { "contains": "DENY-TEST" }
    ]
  },
  "response": {
    "status": 200,
    "headers": {
      "Content-Type": "application/json"
    },
    "jsonBody": {
      "responseCode": "A2",
      "responseStatus": "DENIED",
      "message": "Prior authorization denied - not medically necessary",
      "denialReason": "SERVICE_NOT_COVERED",
      "denialCode": "DN001",
      "appealDeadline": "{{now offset='30 days' format='yyyy-MM-dd'}}",
      "trackingNumber": "{{jsonPath request.body '$.trackingNumber'}}",
      "processedAt": "{{now}}"
    },
    "transformers": ["response-template"]
  }
}
```

### Scenario 3: Pended for Review

**File: `mappings/payer-pended.json`**
```json
{
  "name": "PA Pended Response",
  "priority": 1,
  "request": {
    "method": "POST",
    "urlPattern": "/api/x12/278.*",
    "bodyPatterns": [
      { "contains": "PEND-TEST" }
    ]
  },
  "response": {
    "status": 202,
    "headers": {
      "Content-Type": "application/json",
      "X-Callback-URL": "http://pascallbackhandler:8080/api/v1/callback"
    },
    "jsonBody": {
      "responseCode": "A3",
      "responseStatus": "PENDED",
      "message": "Request received and pending clinical review",
      "estimatedCompletionTime": "{{now offset='2 hours'}}",
      "trackingNumber": "{{jsonPath request.body '$.trackingNumber'}}",
      "statusCheckUrl": "/api/status/{{randomValue type='UUID'}}",
      "processedAt": "{{now}}"
    },
    "transformers": ["response-template"]
  }
}
```

### Scenario 4: Server Error (500)

**File: `mappings/payer-error-500.json`**
```json
{
  "name": "PA Server Error",
  "priority": 1,
  "request": {
    "method": "POST",
    "urlPattern": "/api/x12/278.*",
    "bodyPatterns": [
      { "contains": "ERROR-TEST" }
    ]
  },
  "response": {
    "status": 500,
    "headers": {
      "Content-Type": "application/json"
    },
    "jsonBody": {
      "error": "INTERNAL_SERVER_ERROR",
      "message": "An unexpected error occurred processing your request",
      "errorId": "ERR-{{randomValue type='UUID'}}",
      "timestamp": "{{now}}"
    },
    "transformers": ["response-template"]
  }
}
```

### Scenario 5: Timeout Simulation

**File: `mappings/payer-timeout.json`**
```json
{
  "name": "PA Timeout Simulation",
  "priority": 1,
  "request": {
    "method": "POST",
    "urlPattern": "/api/x12/278.*",
    "bodyPatterns": [
      { "contains": "TIMEOUT-TEST" }
    ]
  },
  "response": {
    "status": 200,
    "fixedDelayMilliseconds": 35000,
    "jsonBody": {
      "status": "This response is delayed to simulate timeout"
    }
  }
}
```

### Scenario 6: Rate Limiting (429)

**File: `mappings/payer-rate-limit.json`**
```json
{
  "name": "PA Rate Limited",
  "priority": 1,
  "request": {
    "method": "POST",
    "urlPattern": "/api/x12/278.*",
    "bodyPatterns": [
      { "contains": "RATELIMIT-TEST" }
    ]
  },
  "response": {
    "status": 429,
    "headers": {
      "Content-Type": "application/json",
      "Retry-After": "60",
      "X-RateLimit-Limit": "100",
      "X-RateLimit-Remaining": "0",
      "X-RateLimit-Reset": "{{now offset='1 minute'}}"
    },
    "jsonBody": {
      "error": "RATE_LIMIT_EXCEEDED",
      "message": "Too many requests. Please retry after 60 seconds.",
      "retryAfter": 60
    },
    "transformers": ["response-template"]
  }
}
```

### Scenario 7: Additional Information Required

**File: `mappings/payer-more-info.json`**
```json
{
  "name": "PA Additional Info Required",
  "priority": 1,
  "request": {
    "method": "POST",
    "urlPattern": "/api/x12/278.*",
    "bodyPatterns": [
      { "contains": "MOREINFO-TEST" }
    ]
  },
  "response": {
    "status": 200,
    "headers": {
      "Content-Type": "application/json"
    },
    "jsonBody": {
      "responseCode": "A4",
      "responseStatus": "ADDITIONAL_INFO_REQUIRED",
      "message": "Additional clinical documentation required",
      "requiredDocuments": [
        {
          "type": "CLINICAL_NOTES",
          "description": "Most recent clinical notes from treating physician"
        },
        {
          "type": "LAB_RESULTS",
          "description": "Lab results from past 30 days"
        },
        {
          "type": "IMAGING",
          "description": "Previous imaging studies if available"
        }
      ],
      "submissionDeadline": "{{now offset='14 days' format='yyyy-MM-dd'}}",
      "trackingNumber": "{{jsonPath request.body '$.trackingNumber'}}",
      "processedAt": "{{now}}"
    },
    "transformers": ["response-template"]
  }
}
```

---

## Admin API

WireMock provides a REST API for runtime management.

### View All Mappings

```bash
curl http://localhost:8090/__admin/mappings
```

### Add Mapping at Runtime

```bash
curl -X POST http://localhost:8090/__admin/mappings \
  -H "Content-Type: application/json" \
  -d '{
    "request": {
      "method": "GET",
      "url": "/test"
    },
    "response": {
      "status": 200,
      "body": "Hello from runtime mapping"
    }
  }'
```

### Delete All Mappings

```bash
curl -X DELETE http://localhost:8090/__admin/mappings
```

### Reset to File-Based Mappings

```bash
curl -X POST http://localhost:8090/__admin/mappings/reset
```

### View Request Journal (Recent Requests)

```bash
curl http://localhost:8090/__admin/requests
```

### Find Unmatched Requests

```bash
curl http://localhost:8090/__admin/requests/unmatched
```

### Get Near Misses (Almost Matched)

```bash
curl http://localhost:8090/__admin/near-misses/request
```

### Health Check

```bash
curl http://localhost:8090/__admin/health
```

### Shutdown WireMock

```bash
curl -X POST http://localhost:8090/__admin/shutdown
```

---

## Troubleshooting

### Issue: Requests Not Matching

1. **Check the request journal:**
   ```bash
   curl http://localhost:8090/__admin/requests
   ```

2. **Check near misses:**
   ```bash
   curl http://localhost:8090/__admin/near-misses/request
   ```

3. **Enable verbose logging:**
   ```yaml
   command: ["--verbose"]
   ```

4. **Common causes:**
   - URL doesn't match (check trailing slashes)
   - Content-Type header mismatch
   - Body pattern doesn't match actual request
   - Priority issues (another mapping matched first)

### Issue: Templates Not Working

1. **Ensure transformer is enabled:**
   ```json
   "transformers": ["response-template"]
   ```

2. **Or enable globally:**
   ```yaml
   command: ["--global-response-templating"]
   ```

3. **Check template syntax** - Handlebars uses `{{` not `${`

### Issue: Mappings Not Loading

1. **Check file permissions**
2. **Verify JSON syntax:**
   ```bash
   cat mappings/payer-approved.json | jq .
   ```
3. **Check container logs:**
   ```bash
   docker logs pagw-mock-payer
   ```

### Issue: Container Won't Start

1. **Check volume mount path exists**
2. **Check port conflicts:**
   ```bash
   netstat -an | findstr 8090
   ```
3. **Try without volumes first to verify image works**

---

## Best Practices

### 1. Naming Conventions

- Use descriptive file names: `payer-{scenario}.json`
- Add `name` field to each mapping for clarity
- Comment complex JSONPath expressions

### 2. Use Priority Wisely

```
Priority 1: Specific test scenarios (DENY-TEST, ERROR-TEST)
Priority 5: Default behavior (fallback responses)
Priority 10: Catch-all error handler
```

### 3. Version Control

- Keep all mappings in `test/mocks/wiremock/mappings/`
- Use `.gitignore` for `__files/` if responses are large
- Document scenarios in this README

### 4. Environment-Specific Configuration

Create separate mapping sets:

```
test/mocks/wiremock/
├── mappings/           # Default mappings
├── mappings-demo/      # Demo-specific mappings
└── mappings-load/      # Load testing mappings
```

Switch at startup:
```bash
docker run -v ./mappings-demo:/home/wiremock/mappings wiremock/wiremock
```

### 5. Correlation IDs

Always echo correlation IDs for tracing:

```json
"headers": {
  "X-Correlation-Id": "{{request.headers.X-Correlation-Id}}",
  "X-Request-Id": "{{request.headers.X-Request-Id}}"
}
```

### 6. Realistic Delays

Add realistic delays to simulate real payer response times:

```json
"fixedDelayMilliseconds": 200,         // Fast response
"fixedDelayMilliseconds": 2000,        // Normal response  
"fixedDelayMilliseconds": 30000,       // Near timeout
```

Or use random delays:
```json
"delayDistribution": {
  "type": "lognormal",
  "median": 1000,
  "sigma": 0.4
}
```

---

## Quick Reference

### Test Triggers

Include these strings in your request body to trigger specific responses:

| Trigger | Response |
|---------|----------|
| (default) | Approved (A1) |
| `DENY-TEST` | Denied (A2) |
| `PEND-TEST` | Pended (A3) |
| `MOREINFO-TEST` | Additional Info Required (A4) |
| `ERROR-TEST` | 500 Server Error |
| `TIMEOUT-TEST` | 35 second delay |
| `RATELIMIT-TEST` | 429 Rate Limited |

### Example Test Request

```bash
# Approved
curl -X POST http://localhost:8090/api/x12/278 \
  -H "Content-Type: application/json" \
  -H "X-Correlation-Id: test-123" \
  -d '{"subscriberId": "SUB001", "trackingNumber": "TRK-001"}'

# Denied
curl -X POST http://localhost:8090/api/x12/278 \
  -H "Content-Type: application/json" \
  -d '{"subscriberId": "DENY-TEST", "trackingNumber": "TRK-002"}'

# Pended
curl -X POST http://localhost:8090/api/x12/278 \
  -H "Content-Type: application/json" \
  -d '{"subscriberId": "PEND-TEST", "trackingNumber": "TRK-003"}'
```

---

## Related Documentation

- [WireMock Official Docs](https://wiremock.org/docs/)
- [WireMock Docker Hub](https://hub.docker.com/r/wiremock/wiremock)
- [Handlebars Templating](https://wiremock.org/docs/response-templating/)
- [PAGW Demo Guide](../../docs/PAGW-Demo-Guide.md)
- [Postman Collection](../postman/PAGW-PAS-Collection.postman_collection.json)
