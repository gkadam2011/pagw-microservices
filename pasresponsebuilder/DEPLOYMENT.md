# PAS Response Builder - Deployment Guide

## Service Overview

**Service Name:** pasresponsebuilder  
**Purpose:** Builds FHIR ClaimResponse resources from API responses and routes to subscription handler  
**Type:** Event-driven microservice (SQS consumer)  
**Port:** 8087  
**Technology Stack:** Java 17, Spring Boot 3.3.0, AWS SDK, PostgreSQL  

### Architecture Role

The PAS Response Builder is a critical component in the PAGW pipeline that:
1. Consumes messages from `pagw-response-builder-queue`
2. Fetches API response data from S3
3. Transforms responses into FHIR ClaimResponse format
4. Stores final FHIR responses in S3
5. Updates request tracker with completion status
6. Routes to subscription handler for webhook notifications

### Stage in Pipeline

```
Orchestrator → ... → Response Converter → **Response Builder** → Subscription Handler
```

---

## Environment Variables

### Core Application Settings

| Variable | Description | Required | Default | Example |
|----------|-------------|----------|---------|---------|
| `SPRING_PROFILES_ACTIVE` | Active Spring profile | Yes | - | `dev`, `prod` |
| `SERVER_PORT` | HTTP server port | No | `8087` | `8080` |
| `PAGW_APPLICATION_ID` | Application identifier | No | `pasresponsebuilder` | `pasresponsebuilder` |

### AWS Configuration

| Variable | Description | Required | Default |
|----------|-------------|----------|---------|
| `AWS_REGION` | AWS region | Yes | `us-east-2` |
| `AWS_ACCOUNT_ID` | AWS account ID | Yes | - |
| `PAGW_AWS_ENDPOINT` | AWS endpoint (LocalStack for local) | No | `http://localhost:4566` |

### Aurora PostgreSQL Database

| Variable | Description | Required | Example |
|----------|-------------|----------|---------|
| `PAGW_AURORA_WRITER_ENDPOINT` | Writer endpoint | Yes | `pagw-cluster.cluster-xxx.us-east-2.rds.amazonaws.com` |
| `PAGW_AURORA_READER_ENDPOINT` | Reader endpoint | No | `pagw-cluster.cluster-ro-xxx.us-east-2.rds.amazonaws.com` |
| `PAGW_AURORA_DATABASE` | Database name | Yes | `pagwdb` |
| `PAGW_AURORA_PORT` | Database port | No | `5432` |
| `PAGW_AURORA_USERNAME` | Database username | Yes | `pagwuser` |
| `PAGW_AURORA_PASSWORD` | Database password | Yes | `***` |
| `PAGW_AURORA_SECRET_ARN` | Secrets Manager ARN | Recommended | `arn:aws:secretsmanager:...` |
| `PAGW_AURORA_MAX_POOL_SIZE` | Max connection pool size | No | `10` |
| `PAGW_AURORA_MIN_IDLE` | Min idle connections | No | `2` |

### SQS Queue Configuration

| Variable | Description | Required | Example |
|----------|-------------|----------|---------|
| `PAGW_SQS_RESPONSE_BUILDER_QUEUE` | Input queue URL | Yes | `https://sqs.us-east-2.amazonaws.com/.../dev-PAGW-pagw-response-builder-queue.fifo` |
| `PAGW_SQS_RESPONSE_QUEUE` | Output queue URL (subscription handler) | Yes | `https://sqs.us-east-2.amazonaws.com/.../dev-PAGW-pagw-subscription-handler-queue.fifo` |
| `PAGW_SQS_DLQ` | Dead letter queue URL | Yes | `https://sqs.us-east-2.amazonaws.com/.../dev-PAGW-pagw-dlq.fifo` |

### S3 Buckets

| Variable | Description | Required | Example |
|----------|-------------|----------|---------|
| `PAGW_S3_REQUEST_BUCKET` | Request/response bucket | Yes | `crln-pagw-dev-dataz-gbd-phi-useast2` |
| `PAGW_S3_ATTACHMENTS_BUCKET` | Attachments bucket | Yes | `crln-pagw-dev-dataz-gbd-phi-useast2` |
| `PAGW_S3_AUDIT_BUCKET` | Audit logs bucket | Yes | `crln-pagw-dev-logz-nogbd-nophi-useast2` |

### KMS Encryption

| Variable | Description | Required | Default |
|----------|-------------|----------|---------|
| `PAGW_ENCRYPTION_KMS_ENABLED` | Enable KMS encryption | No | `false` |
| `KMS_KEY_ID` | KMS key ID/ARN | Conditional | - |

---

## Dependencies

### Runtime Dependencies

#### Spring Boot & Core
- `spring-boot-starter-web` (3.3.0) - REST API support
- `spring-boot-starter-validation` - Input validation
- `spring-boot-starter-actuator` - Health checks and metrics
- `spring-boot-starter-jdbc` - Database connectivity

#### AWS Integration
- `spring-cloud-aws-starter-sqs` (3.1.1) - SQS messaging
- `spring-cloud-aws-sqs` (3.1.1) - SQS listener support
- `software.amazon.awssdk:sqs` (2.25.0) - AWS SDK for SQS

#### Database
- `postgresql` (runtime) - PostgreSQL JDBC driver

#### Internal
- `pagwcore` (1.0.0-SNAPSHOT) - Shared PAGW utilities

### Test Dependencies
- `spring-boot-starter-test` - Testing framework
- JUnit 5 - Unit testing
- Mockito - Mocking framework

---

## Database Schema

### Required Tables

#### request_tracker
Tracks request processing status across all stages.

```sql
CREATE TABLE pagw.request_tracker (
    pagw_id VARCHAR(50) PRIMARY KEY,
    correlation_id VARCHAR(100),
    tenant_id VARCHAR(50),
    current_stage VARCHAR(50),
    current_status VARCHAR(50),
    service_name VARCHAR(100),
    status_code VARCHAR(20),
    status_message TEXT,
    final_payload_bucket VARCHAR(255),
    final_payload_key VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

#### outbox
Outbox pattern for reliable message delivery.

```sql
CREATE TABLE pagw.outbox (
    id BIGSERIAL PRIMARY KEY,
    message_id VARCHAR(100) UNIQUE NOT NULL,
    destination_queue VARCHAR(500) NOT NULL,
    message_body TEXT NOT NULL,
    message_group_id VARCHAR(128),
    deduplication_id VARCHAR(128),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP,
    status VARCHAR(20) DEFAULT 'PENDING',
    retry_count INT DEFAULT 0,
    error_message TEXT
);
```

---

## API Endpoints

### Health Check
```
GET /actuator/health
```
**Response:**
```json
{
  "status": "UP",
  "components": {
    "db": {"status": "UP"},
    "diskSpace": {"status": "UP"},
    "ping": {"status": "UP"}
  }
}
```

### Metrics
```
GET /actuator/metrics
```
Lists available metrics for monitoring.

### Info
```
GET /actuator/info
```
Returns application information.

---

## SQS Message Contract

### Input Message Format

The service listens on `pagw-response-builder-queue` for messages with this structure:

```json
{
  "pagwId": "PAGW-20241223-123456-ABC",
  "correlationId": "CORR-123",
  "tenantId": "ELEVANCE",
  "stage": "RESPONSE_BUILDING",
  "payloadBucket": "crln-pagw-dev-dataz-gbd-phi-useast2",
  "payloadKey": "PAGW-20241223-123456-ABC/api-response.json",
  "errorCode": null,
  "errorMessage": null,
  "apiResponseStatus": "SUCCESS",
  "metadata": {
    "patientReference": "Patient/12345",
    "providerNpi": "1234567890"
  }
}
```

### Output Message Format

Routes to `pagw-subscription-handler-queue`:

```json
{
  "pagwId": "PAGW-20241223-123456-ABC",
  "correlationId": "CORR-123",
  "tenantId": "ELEVANCE",
  "stage": "SUBSCRIPTION_HANDLER",
  "payloadBucket": "crln-pagw-dev-dataz-gbd-phi-useast2",
  "payloadKey": "PAGW-20241223-123456-ABC/fhir-response.json",
  "apiResponseStatus": "complete",
  "metadata": {
    "patientReference": "Patient/12345"
  }
}
```

---

## S3 Data Structures

### Input: API Response Data
**Path:** `{pagwId}/api-response.json`

```json
{
  "externalId": "PAYER-REF-12345",
  "status": "ACCEPTED",
  "timestamp": "2024-12-23T10:30:00Z",
  "authorizationNumber": "AUTH-67890"
}
```

### Output: FHIR ClaimResponse
**Path:** `{pagwId}/fhir-response.json`

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

## Building the Service

### Prerequisites
- Java 17 or higher
- Maven 3.6+
- Access to pagwcore library (locally built or from registry)

### Build Commands

#### Local Development Build
```bash
cd pasresponsebuilder/source
mvn clean install -DskipTests
```

#### Full Build with Tests
```bash
cd pasresponsebuilder/source
mvn clean package
```

#### Docker Build
```bash
cd pasresponsebuilder
docker build -t pasresponsebuilder:latest .
```

#### Multi-architecture Docker Build
```bash
docker buildx build --platform linux/amd64,linux/arm64 \
  --build-arg JAVA_VERSION=17 \
  --build-arg SPRING_BOOT_VERSION=3.3.0 \
  -t quay-nonprod.elevancehealth.com/pagw/crln-shared-dev-img-pasresponsebuilder:latest .
```

---

## Testing

### Run Unit Tests
```bash
cd pasresponsebuilder/source
mvn test
```

### Run Integration Tests
```bash
mvn verify -P integration-test
```

### Test Coverage Report
```bash
mvn clean test jacoco:report
# Report: target/site/jacoco/index.html
```

### Manual Testing with LocalStack

#### 1. Start LocalStack
```bash
docker-compose -f docker-compose.infra.yml up -d
```

#### 2. Create Test Queue
```bash
aws --endpoint-url=http://localhost:4566 sqs create-queue \
  --queue-name pagw-response-builder-queue \
  --attributes FifoQueue=true,ContentBasedDeduplication=true
```

#### 3. Send Test Message
```bash
aws --endpoint-url=http://localhost:4566 sqs send-message \
  --queue-url http://localhost:4566/000000000000/pagw-response-builder-queue \
  --message-body '{
    "pagwId": "TEST-001",
    "stage": "RESPONSE_BUILDING",
    "payloadBucket": "pagw-request-local",
    "payloadKey": "TEST-001/api-response.json"
  }' \
  --message-group-id "test-group"
```

#### 4. Run Service
```bash
export SPRING_PROFILES_ACTIVE=local
export PAGW_DATABASE_HOST=localhost
export PAGW_DATABASE_PASSWORD=pagw123
mvn spring-boot:run
```

---

## Deployment Checklist

### Pre-Deployment

- [ ] **Code Review:** All changes peer-reviewed
- [ ] **Unit Tests:** All tests passing (`mvn test`)
- [ ] **Integration Tests:** Verified against LocalStack
- [ ] **Build Verification:** `mvn clean package` succeeds
- [ ] **Docker Build:** Image builds successfully
- [ ] **Environment Variables:** All required vars documented
- [ ] **Database Schema:** Migrations applied (if any)
- [ ] **Dependencies:** pagwcore version compatible

### Configuration Review

- [ ] **SQS Queues:** Response builder queue exists and accessible
- [ ] **Aurora Database:** Connection details verified
- [ ] **S3 Buckets:** All required buckets exist with proper permissions
- [ ] **IAM Roles:** Service role has required permissions:
  - SQS: `ReceiveMessage`, `DeleteMessage`, `SendMessage`
  - S3: `GetObject`, `PutObject`
  - Aurora: Database connection via Secrets Manager
  - Secrets Manager: `GetSecretValue`
- [ ] **KMS Keys:** Encryption keys available if enabled

### Helm Deployment

#### Dev Environment
```bash
cd pagwk8s/pasresponsebuilder
helm upgrade --install pasresponsebuilder . \
  -f values-dev.yaml \
  -f tekton-values-dev.yaml \
  --namespace pagw-srv-dev
```

#### Verify Deployment
```bash
kubectl get pods -n pagw-srv-dev -l app=pasresponsebuilder
kubectl logs -n pagw-srv-dev -l app=pasresponsebuilder --tail=100
kubectl describe pod -n pagw-srv-dev <pod-name>
```

#### Check Health
```bash
kubectl port-forward -n pagw-srv-dev svc/pasresponsebuilder 8087:443
curl http://localhost:8087/actuator/health
```

### Post-Deployment Verification

- [ ] **Pod Status:** Pod running and healthy
- [ ] **Health Endpoint:** Returns `{"status":"UP"}`
- [ ] **Database Connection:** No connection errors in logs
- [ ] **SQS Connectivity:** Listening on correct queue
- [ ] **Logs:** No ERROR or WARN messages
- [ ] **Metrics:** Prometheus metrics available
- [ ] **End-to-End Test:** Process a test message successfully
- [ ] **Error Handling:** DLQ routing works for failures

### Monitoring Setup

- [ ] **CloudWatch Logs:** Log group created and streaming
- [ ] **CloudWatch Alarms:**
  - SQS: AgeOfOldestMessage > 300s
  - SQS: ApproximateNumberOfMessagesVisible > 100
  - Pod: CrashLoopBackOff
  - Database: Connection pool exhaustion
- [ ] **Grafana Dashboards:** Service dashboard configured
- [ ] **PagerDuty:** Alerts routing to correct team

---

## Troubleshooting

### Common Issues

#### 1. Service Won't Start

**Symptom:** Pod in CrashLoopBackOff
**Check:**
```bash
kubectl logs -n pagw-srv-dev <pod-name> --previous
```
**Solutions:**
- Verify database credentials in Secrets Manager
- Check environment variable syntax
- Ensure pagwcore dependency is available

#### 2. Not Receiving SQS Messages

**Symptom:** No log entries for message processing
**Check:**
```bash
aws sqs get-queue-attributes \
  --queue-url <queue-url> \
  --attribute-names All
```
**Solutions:**
- Verify queue URL in configuration
- Check IAM permissions for SQS
- Ensure message visibility timeout is adequate
- Verify queue is FIFO and service expects FIFO

#### 3. Database Connection Failures

**Symptom:** `Connection refused` or `timeout` errors
**Check:**
```bash
kubectl exec -it -n pagw-srv-dev <pod-name> -- \
  nc -zv $PAGW_AURORA_WRITER_ENDPOINT 5432
```
**Solutions:**
- Verify security group allows pod CIDR
- Check Aurora cluster status
- Validate database credentials
- Verify DNS resolution

#### 4. S3 Access Denied

**Symptom:** `AccessDenied` when getting/putting objects
**Solutions:**
- Check IAM role attached to service account
- Verify bucket policy allows service role
- Ensure KMS key policy allows service role (if encrypted)
- Check bucket name matches configuration

#### 5. Messages Going to DLQ

**Symptom:** All messages ending up in dead letter queue
**Check DLQ messages:**
```bash
aws sqs receive-message --queue-url <dlq-url> --max-number-of-messages 10
```
**Solutions:**
- Check application logs for exceptions
- Verify message format matches expected schema
- Ensure all referenced S3 objects exist
- Check database schema matches application expectations

---

## Performance Tuning

### Database Connection Pool
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20  # Increase for high throughput
      minimum-idle: 5
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
```

### SQS Listener Configuration
```java
@SqsListener(
    value = "${pagw.aws.sqs.response-builder-queue}",
    maxConcurrentMessages = "10",  // Process up to 10 messages concurrently
    maxMessagesPerPoll = "10"      // Fetch 10 messages per poll
)
```

### JVM Tuning
```yaml
env:
  JAVA_OPTS: >-
    -XX:+UseG1GC
    -XX:MaxRAMPercentage=75.0
    -XX:InitialRAMPercentage=50.0
    -XX:+UseStringDeduplication
    -XX:+ParallelRefProcEnabled
```

---

## Security Considerations

### Data Classification
- **PHI Data:** Patient references, claim details (encrypted in S3)
- **PCI Data:** None
- **Credentials:** Database passwords, AWS credentials (Secrets Manager)

### Security Controls
1. **Encryption at Rest:** S3 bucket encryption with KMS
2. **Encryption in Transit:** HTTPS/TLS for all API calls
3. **IAM Least Privilege:** Service role with minimal permissions
4. **Network Isolation:** Deployed in private subnets
5. **Secrets Management:** AWS Secrets Manager for credentials
6. **Audit Logging:** CloudWatch Logs with 90-day retention

---

## Rollback Procedure

### Helm Rollback
```bash
# List revisions
helm history pasresponsebuilder -n pagw-srv-dev

# Rollback to previous
helm rollback pasresponsebuilder -n pagw-srv-dev

# Rollback to specific revision
helm rollback pasresponsebuilder <revision> -n pagw-srv-dev
```

### Emergency Stop
```bash
# Scale to zero
kubectl scale deployment pasresponsebuilder-app -n pagw-srv-dev --replicas=0

# Resume
kubectl scale deployment pasresponsebuilder-app -n pagw-srv-dev --replicas=2
```

---

## Support

### Contacts
- **Team:** PAGW Engineering
- **Slack:** #pagw-support
- **Email:** pagw-team@elevancehealth.com
- **On-Call:** PagerDuty - PAGW Team

### Documentation
- [PAGW Architecture](../docs/ARCHITECTURE.md)
- [PAGW Demo Guide](../PAGW-Demo-Guide.md)
- [pagwcore Library](../pagwcore/README.md)

---

## Change Log

### v1.0.0-SNAPSHOT (Current)
- Initial deployment-ready version
- FHIR ClaimResponse building
- Success and error response handling
- S3 integration for response storage
- SQS listener for message consumption
- Comprehensive test coverage
- Deployment documentation
