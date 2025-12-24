# PAS Response Builder

## Overview

The **PAS Response Builder** microservice is a critical component in the Prior Authorization Gateway (PAGW) pipeline. It transforms API responses from payer systems into standardized FHIR ClaimResponse resources and routes them to the subscription handler for webhook notifications.

### Key Features

- **FHIR Transformation:** Converts payer API responses into FHIR R4 ClaimResponse format
- **Error Handling:** Builds structured error responses for failed requests
- **S3 Integration:** Fetches API response data and stores final FHIR responses
- **Status Tracking:** Updates request tracker with completion status
- **Message Routing:** Routes completed responses to subscription handler
- **Resilient Processing:** DLQ routing for failed messages

---

## Architecture

### Pipeline Position

```
Orchestrator → Response Converter → **Response Builder** → Subscription Handler → External Webhooks
```

### Component Interaction

```
┌─────────────────┐
│  SQS: Response  │
│  Builder Queue  │
└────────┬────────┘
         │ Consumes
         ▼
┌────────────────────────┐        ┌──────────┐
│ Response Builder       │◄──────►│ Aurora   │
│ Listener               │        │ DB       │
└────────┬───────────────┘        └──────────┘
         │
         ├──► Fetch API Response from S3
         │
         ├──► Build FHIR ClaimResponse
         │
         ├──► Store Final Response in S3
         │
         ├──► Update Request Tracker
         │
         ▼
┌────────────────────────┐
│  SQS: Subscription     │
│  Handler Queue         │
└────────────────────────┘
```

---

## Technical Stack

- **Java:** 17
- **Spring Boot:** 3.3.0
- **AWS SDK:** 2.25.0 (SQS, S3)
- **Database:** PostgreSQL (Aurora)
- **Build Tool:** Maven
- **Container:** Docker multi-stage build

---

## Configuration

### Environment Variables

See [DEPLOYMENT.md](DEPLOYMENT.md) for comprehensive configuration details.

**Critical Variables:**
- `PAGW_SQS_RESPONSE_BUILDER_QUEUE` - Input queue URL
- `PAGW_SQS_RESPONSE_QUEUE` - Output queue URL (subscription handler)
- `PAGW_S3_REQUEST_BUCKET` - S3 bucket for payloads
- `PAGW_AURORA_WRITER_ENDPOINT` - Database writer endpoint

### Application Profiles

- `local` - LocalStack integration for local development
- `docker` - Docker Compose environment
- `dev` - AWS DEV environment
- `preprod` - Pre-production
- `prod` - Production

---

## Building & Running

### Build JAR
```bash
cd pasresponsebuilder/source
mvn clean package
```

### Run Tests
```bash
mvn test
# 31 tests: ResponseBuilderService (10), ResponseBuilderListener (9), ClaimResponse (12)
```

### Run Locally
```bash
export SPRING_PROFILES_ACTIVE=local
export PAGW_DATABASE_HOST=localhost
export PAGW_DATABASE_PASSWORD=pagw123
mvn spring-boot:run
```

### Docker Build
```bash
cd pasresponsebuilder
docker build -t pasresponsebuilder:latest .
```

---

## API Endpoints

### Health Check
```bash
curl http://localhost:8087/actuator/health
```

### Metrics
```bash
curl http://localhost:8087/actuator/metrics
```

---

## Message Processing

### Input Message (SQS)

Consumes from `pagw-response-builder-queue`:

```json
{
  "pagwId": "PAGW-20241223-123456-ABC",
  "correlationId": "CORR-123",
  "tenant": "ELEVANCE",
  "stage": "RESPONSE_BUILDING",
  "payloadBucket": "crln-pagw-dev-dataz-gbd-phi-useast2",
  "payloadKey": "PAGW-20241223-123456-ABC/api-response.json",
  "errorCode": null,
  "errorMessage": null
}
```

### Output Message (SQS)

Routes to `pagw-subscription-handler-queue`:

```json
{
  "pagwId": "PAGW-20241223-123456-ABC",
  "stage": "SUBSCRIPTION_HANDLER",
  "payloadKey": "202512/PAGW-20241223-123456-ABC/response/fhir.json",
  "apiResponseStatus": "complete"
}
```

### FHIR ClaimResponse (S3)

Stored at `{yyyyMM}/{pagwId}/response/fhir.json`:

```json
{
  "resourceType": "ClaimResponse",
  "id": "a1b2c3d4-e5f6-g7h8-i9j0-k1l2m3n4o5p6",
  "identifier": [{
    "system": "urn:anthem:pagw:claim-response",
    "value": "PAGW-20241223-123456-ABC"
  }],
  "status": "active",
  "use": "claim",
  "outcome": "complete",
  "disposition": "Claim accepted for processing",
  "preAuthRef": "PAYER-REF-12345",
  "created": "2024-12-23T10:30:15Z",
  "request": {"reference": "Claim/PAGW-20241223-123456-ABC"},
  "patient": {"reference": "Patient/12345"},
  "insurer": {
    "reference": "Organization/anthem",
    "display": "Anthem Blue Cross Blue Shield"
  },
  "processNote": [
    {"number": 1, "text": "Request received and acknowledged", "type": "display"},
    {"number": 2, "text": "PAGW ID: PAGW-20241223-123456-ABC", "type": "print"}
  ]
}
```

---

## Testing

### Unit Tests Coverage

**31 Total Tests:**
1. **ResponseBuilderService (10 tests):** Success/error responses, metadata handling, timestamp formatting
2. **ResponseBuilderListener (9 tests):** Message processing, S3 integration, error handling, routing
3. **ClaimResponse Model (12 tests):** FHIR resource structure, nested classes, field validation

### Run All Tests
```bash
mvn test
```

### Test with LocalStack
```bash
# Start infrastructure
docker-compose -f ../../docker-compose.infra.yml up -d

# Create test queue
aws --endpoint-url=http://localhost:4566 sqs create-queue \
  --queue-name pagw-response-builder-queue \
  --attributes FifoQueue=true

# Send test message
aws --endpoint-url=http://localhost:4566 sqs send-message \
  --queue-url http://localhost:4566/000000000000/pagw-response-builder-queue \
  --message-body '{"pagwId":"TEST-001","stage":"RESPONSE_BUILDING"}' \
  --message-group-id "test"
```

---

## Deployment

### Kubernetes/Helm

**Chart Location:** `pagwk8s/pasresponsebuilder`

```bash
# Dev deployment
helm upgrade --install pasresponsebuilder pagwk8s/pasresponsebuilder \
  -f pagwk8s/pasresponsebuilder/values-dev.yaml \
  --namespace pagw-srv-dev

# Verify
kubectl get pods -n pagw-srv-dev -l app=pasresponsebuilder
kubectl logs -n pagw-srv-dev -l app=pasresponsebuilder --tail=50
```

### Health Checks

**Liveness Probe:**
- Initial Delay: 60s
- Period: 10s
- Endpoint: `/actuator/health`

**Readiness Probe:**
- Initial Delay: 30s
- Period: 10s

---

## Monitoring & Observability

### Metrics

Available at `/actuator/metrics`:
- JVM memory usage
- Thread counts
- HTTP request rates
- Custom application metrics

### Logging

**Log Levels:**
- `com.anthem.pagw.response` - DEBUG/INFO
- `org.springframework` - INFO
- Root - INFO

**Key Log Events:**
- `Received message for response building` - Message consumed
- `Response building complete` - Success
- `Error processing message` - Failure

### CloudWatch Integration

- **Log Group:** `/aws/eks/pagw-srv-dev/pasresponsebuilder`
- **Metrics:** Custom CloudWatch metrics via Micrometer
- **Alarms:** SQS age of oldest message, pod crashes

---

## Error Handling

### Retry Strategy

- **Automatic Retries:** SQS visibility timeout (300s)
- **Max Retries:** 3 (configured in SQS)
- **DLQ:** Messages failing all retries → `pagw-dlq`

### Common Errors

| Error | Cause | Resolution |
|-------|-------|------------|
| S3 Access Denied | IAM permissions | Verify service role has S3 read/write |
| Database Connection Timeout | Aurora unreachable | Check security groups, DNS |
| Invalid JSON | Malformed message | Inspect SQS message format |
| OutOfMemory | Memory limits too low | Increase pod memory limits |

---

## Performance Tuning

### Recommended Settings (DEV)

```yaml
resources:
  requests:
    cpu: "200m"
    memory: "512Mi"
  limits:
    cpu: "500m"
    memory: "1Gi"
```

### Database Connection Pool

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
```

### SQS Concurrency

Configure in listener annotation:
```java
@SqsListener(
    value = "${pagw.aws.sqs.response-builder-queue}",
    maxConcurrentMessages = "10",
    maxMessagesPerPoll = "10"
)
```

---

## Development

### Project Structure

```
pasresponsebuilder/
├── source/
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/com/anthem/pagw/response/
│       │   │   ├── ResponseBuilderApplication.java
│       │   │   ├── listener/
│       │   │   │   └── ResponseBuilderListener.java
│       │   │   ├── service/
│       │   │   │   └── ResponseBuilderService.java
│       │   │   ├── model/
│       │   │   │   └── ClaimResponse.java
│       │   │   └── config/
│       │   └── resources/
│       │       ├── application.yml
│       │       ├── application-local.yml
│       │       ├── application-dev.yml
│       │       └── (other profiles)
│       └── test/
│           └── java/com/anthem/pagw/response/
│               ├── service/ResponseBuilderServiceTest.java
│               ├── listener/ResponseBuilderListenerTest.java
│               └── model/ClaimResponseTest.java
├── Dockerfile
├── README.md (this file)
└── DEPLOYMENT.md (detailed deployment guide)
```

### Code Style

- **Lombok:** Used for getters/setters, builders
- **SLF4J:** Logging facade
- **Spring Annotations:** @Service, @Component, @SqsListener

---

## Documentation

- **[DEPLOYMENT.md](DEPLOYMENT.md)** - Comprehensive deployment guide
- **[PAGW Architecture](../docs/ARCHITECTURE.md)** - Overall system architecture
- **[PAGW Demo Guide](../PAGW-Demo-Guide.md)** - End-to-end testing guide

---

## Support

- **Team:** PAGW Engineering
- **Slack:** #pagw-support
- **Repository:** bitbucket.elevancehealth.com/scm/aedlk8s/pagw-microservices

---

## Change Log

### v1.0.0-SNAPSHOT (Dec 2024)
- ✅ Initial deployment-ready version
- ✅ FHIR ClaimResponse transformation
- ✅ Success and error response handling
- ✅ S3 integration for response storage
- ✅ SQS listener with DLQ support
- ✅ Comprehensive test coverage (31 tests)
- ✅ Complete deployment documentation
- ✅ Fixed SQS queue configuration

