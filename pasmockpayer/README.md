# PAS Mock Payer Service

A Spring Boot mock payer service for testing the PAGW Prior Authorization Gateway without connecting to real payer systems.

## Overview

This service simulates payer API responses, allowing you to:
- Test the full PA submission flow
- Simulate different response scenarios (approved, denied, pended, errors)
- Run integration tests without external dependencies
- Demo the PAGW system

## Quick Start

### Run Locally

```bash
cd pasmockpayer/source
mvn spring-boot:run
```

### Run with Docker

```bash
# Build image
docker build -t pas-mock-payer:latest ./pasmockpayer

# Run container
docker run -p 8095:8095 pas-mock-payer:latest
```

### Run with Docker Compose

The service is included in `docker-compose.local.yml`:

```bash
docker-compose -f docker-compose.local.yml up mock-payer
```

## API Endpoints

### Submit Prior Authorization

**POST** `/api/x12/278`

Submit a PA request and receive a simulated payer response.

```bash
curl -X POST http://localhost:8095/api/x12/278 \
  -H "Content-Type: application/json" \
  -H "X-Correlation-Id: test-123" \
  -d '{
    "subscriberId": "SUB001",
    "providerNpi": "1234567890",
    "serviceType": "MRI"
  }'
```

### Check Status

**GET** `/api/status/{trackingId}`

Check the status of a PA request.

```bash
curl http://localhost:8095/api/status/TRK-123
```

### Health Check

**GET** `/api/health` or `/actuator/health`

```bash
curl http://localhost:8095/api/health
```

## Response Scenarios

Control the response by including trigger strings in your request body:

| Trigger | Response | HTTP Status | Description |
|---------|----------|-------------|-------------|
| *(default)* | Approved (A1) | 200 | PA approved with authorization number |
| `DENY-TEST` | Denied (A2) | 200 | PA denied with reasons and appeal info |
| `PEND-TEST` | Pended (A3) | 202 | PA pending clinical review |
| `MOREINFO-TEST` | Additional Info (A4) | 200 | Additional documentation required |
| `ERROR-TEST` | Server Error | 500 | Simulates internal server error |
| `TIMEOUT-TEST` | Delayed Response | 200 | 35-second delay to simulate timeout |
| `RATELIMIT-TEST` | Rate Limited | 429 | Too many requests error |
| `UNAUTH-TEST` | Unauthorized | 401 | Authentication failure |
| `BADREQUEST-TEST` | Bad Request | 400 | Validation errors |

### Examples

**Approved Response (default)**
```bash
curl -X POST http://localhost:8095/api/x12/278 \
  -H "Content-Type: application/json" \
  -d '{"subscriberId": "SUB001"}'
```

**Denied Response**
```bash
curl -X POST http://localhost:8095/api/x12/278 \
  -H "Content-Type: application/json" \
  -d '{"subscriberId": "DENY-TEST"}'
```

**Pended Response**
```bash
curl -X POST http://localhost:8095/api/x12/278 \
  -H "Content-Type: application/json" \
  -d '{"subscriberId": "PEND-TEST"}'
```

**Server Error**
```bash
curl -X POST http://localhost:8095/api/x12/278 \
  -H "Content-Type: application/json" \
  -d '{"subscriberId": "ERROR-TEST"}'
```

**Timeout (35 seconds)**
```bash
curl -X POST http://localhost:8095/api/x12/278 \
  -H "Content-Type: application/json" \
  -d '{"subscriberId": "TIMEOUT-TEST"}'
```

## Response Examples

### Approved (A1)
```json
{
  "responseCode": "A1",
  "responseStatus": "APPROVED",
  "message": "Prior authorization approved",
  "authorizationNumber": "AUTH-ABC123XYZ789",
  "effectiveDate": "2025-12-22",
  "expirationDate": "2026-03-22",
  "approvedServices": [
    {
      "serviceCode": "99213",
      "approvedUnits": 10,
      "approvedVisits": 5,
      "unitType": "VISIT"
    }
  ],
  "providerNpi": "1234567890",
  "payerName": "Mock Payer Inc.",
  "payerId": "MOCK001",
  "processedAt": "2025-12-22T10:30:00Z"
}
```

### Denied (A2)
```json
{
  "responseCode": "A2",
  "responseStatus": "DENIED",
  "message": "Prior authorization denied",
  "denialReasons": [
    {
      "code": "DN001",
      "reason": "SERVICE_NOT_MEDICALLY_NECESSARY",
      "description": "The requested service is not medically necessary"
    }
  ],
  "appealInstructions": {
    "deadline": "2026-01-21",
    "method": "Written appeal with supporting clinical documentation",
    "faxNumber": "1-800-555-0199",
    "address": "Mock Payer Appeals, PO Box 12345, Anytown, ST 12345"
  }
}
```

### Pended (A3)
```json
{
  "responseCode": "A3",
  "responseStatus": "PENDED",
  "message": "Request received and pending clinical review",
  "pendReason": "CLINICAL_REVIEW_REQUIRED",
  "pendDetails": {
    "reviewType": "MEDICAL_DIRECTOR",
    "estimatedCompletionHours": 24,
    "estimatedCompletionTime": "2025-12-23T10:30:00Z"
  },
  "statusCheckInfo": {
    "url": "/api/status/abc123",
    "pollingIntervalMinutes": 30
  }
}
```

## Configuration

### Application Properties

| Property | Default | Description |
|----------|---------|-------------|
| `server.port` | 8095 | HTTP port |
| `logging.level.com.elevance.pagw.mockpayer` | DEBUG | Log level |

### Environment Variables

```bash
# Change port
SERVER_PORT=9000

# Change log level
LOGGING_LEVEL_COM_ELEVANCE_PAGW_MOCKPAYER=INFO
```

## Integration with pasapiconnector

Configure `pasapiconnector` to use this mock payer:

**application-local.yml:**
```yaml
pagw:
  payer:
    url: http://mock-payer:8095/api/x12/278
```

**docker-compose.local.yml:**
```yaml
pasapiconnector:
  environment:
    PAYER_API_URL: http://mock-payer:8095/api/x12/278
```

## Deployment

### EKS Deployment

The service can be deployed to EKS alongside other PAGW services for dev/test environments.

**Kubernetes Deployment:**
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: pas-mock-payer
spec:
  replicas: 1
  selector:
    matchLabels:
      app: pas-mock-payer
  template:
    spec:
      containers:
      - name: pas-mock-payer
        image: quay-nonprod.elevancehealth.com/aedl/pas-mock-payer:latest
        ports:
        - containerPort: 8095
        livenessProbe:
          httpGet:
            path: /actuator/health
            port: 8095
          initialDelaySeconds: 30
        readinessProbe:
          httpGet:
            path: /actuator/health
            port: 8095
          initialDelaySeconds: 10
```

**Service:**
```yaml
apiVersion: v1
kind: Service
metadata:
  name: pas-mock-payer
spec:
  selector:
    app: pas-mock-payer
  ports:
  - port: 8095
    targetPort: 8095
```

## Related Documentation

- [WireMock Setup](../test/mocks/wiremock/README.md) - Alternative mock approach
- [Postman Collection](../test/postman/README.md) - API testing collection
- [PAGW Demo Guide](../docs/PAGW-Demo-Guide.md) - Demo documentation
