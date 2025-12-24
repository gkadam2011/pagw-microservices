# Outbox Publisher

A Spring Boot scheduled service that implements the **Transactional Outbox Pattern** for reliable event publishing to AWS SQS. It ensures at-least-once delivery semantics by polling the outbox table for unpublished entries and publishing them to their target SQS queues.

## 🎯 Overview

The Outbox Publisher is a critical component in the PAGW microservices architecture, providing reliable asynchronous communication between services. It solves the dual-write problem by decoupling database writes from message queue publishing.

### Key Features
- ✅ **Reliable Event Publishing**: Guarantees eventual delivery of all outbox entries
- ✅ **High Availability**: Uses ShedLock for distributed locking across multiple pods
- ✅ **Batch Processing**: Configurable batch size for optimal throughput (default: 50)
- ✅ **Automatic Retry**: Incremental retry with configurable max retries (default: 5)
- ✅ **Manual Trigger**: REST API endpoint for on-demand publishing
- ✅ **Monitoring**: Built-in statistics and health check endpoints
- ✅ **FOR UPDATE SKIP LOCKED**: Prevents row-level lock contention

---

## 📐 Architecture

### Outbox Pattern
```
┌─────────────────┐
│  Service A      │  1. Insert business data + outbox entry (same transaction)
│  Transaction    │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  Outbox Table   │  2. Outbox Publisher polls for PENDING entries
│  (PENDING)      │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Outbox Publisher│  3. Publishes to SQS
│ (Scheduled)     │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  AWS SQS Queue  │  4. Downstream services consume
└─────────────────┘
```

**Benefits:**
- Ensures transactional consistency (business logic + event publishing)
- Decouples services through asynchronous messaging
- Provides retry mechanism for failed deliveries
- Survives temporary SQS outages

### High Availability with ShedLock
```
┌────────────────┐     ┌────────────────┐     ┌────────────────┐
│  Publisher     │     │  Publisher     │     │  Publisher     │
│  Pod 1         │     │  Pod 2         │     │  Pod 3         │
│  🔒 ACTIVE     │     │  ⏸️  Waiting   │     │  ⏸️  Waiting   │
└───────┬────────┘     └───────┬────────┘     └───────┬────────┘
        │                      │                      │
        └──────────────────────┼──────────────────────┘
                               ▼
                       ┌────────────────┐
                       │  ShedLock      │
                       │  Database      │
                       │  (JDBC-based)  │
                       └────────────────┘
```

Only ONE pod processes outbox entries at any given time, preventing duplicate message publishing.

### Position in PAGW Pipeline
```
[API Connector] → [Validator] → [Enricher] → [Orchestrator]
                                                    │
                                                    ▼
                                             [Outbox Table]
                                                    │
                                                    ▼
                                          [Outbox Publisher] ──→ [SQS Queues]
                                                                        │
                                                                        ▼
                                                              [Downstream Services]
```

---

## 🛠️ Technical Stack

| Technology | Version | Purpose |
|-----------|---------|---------|
| **Java** | 17 | Runtime |
| **Spring Boot** | 3.3.0 | Application framework |
| **Spring Scheduling** | 3.3.0 | Scheduled task execution |
| **ShedLock** | 5.10.0 | Distributed locking for HA |
| **AWS SDK** | 2.25.0 | SQS integration |
| **PostgreSQL** | 12+ | Outbox and lock storage |
| **pagwcore** | 1.0.0-SNAPSHOT | Shared OutboxService and SqsService |
| **Docker** | Multi-stage | Containerization |
| **Kubernetes/Helm** | - | Orchestration |

---

## 🚀 Quick Start

### Prerequisites
- Java 17+
- Maven 3.8+
- PostgreSQL 12+ with outbox and shedlock tables
- AWS account with SQS access

### Local Development

**1. Setup Database**
```bash
# Run PostgreSQL
docker run -d --name postgres \
  -e POSTGRES_DB=pagw \
  -e POSTGRES_USER=pagw_user \
  -e POSTGRES_PASSWORD=pagw_pass \
  -p 5432:5432 postgres:14

# Create tables
psql -h localhost -U pagw_user -d pagw -f schema.sql
```

**2. Configure Application**
```bash
# Copy and edit application-local.yml
cp src/main/resources/application.yml src/main/resources/application-local.yml

# Edit database connection
vim src/main/resources/application-local.yml
```

**3. Run Service**
```bash
cd source
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

**4. Verify**
```bash
# Health check
curl http://localhost:8089/actuator/health

# Check statistics
curl http://localhost:8089/outbox/stats

# Manual trigger
curl -X POST http://localhost:8089/outbox/publish
```

---

## ⚙️ Configuration

### Application Properties

**Outbox Polling Configuration**
```yaml
pagw:
  outbox:
    publish-interval-ms: 10000  # Poll every 10 seconds
    batch-size: 50              # Process 50 entries per batch
    max-retries: 5              # Max retry attempts before marking as stuck
```

**Database Configuration**
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/pagw?currentSchema=pagw
    username: ${PAGW_AURORA_USERNAME}
    password: ${PAGW_AURORA_PASSWORD}
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
```

**AWS Configuration**
```yaml
pagw:
  aws:
    region: us-east-2
    endpoint: http://localhost:4566  # LocalStack for testing
```

### Environment Variables
See [DEPLOYMENT.md](DEPLOYMENT.md#environment-variables) for complete list.

Key variables:
- `OUTBOX_PUBLISH_INTERVAL_MS`: Polling frequency (default: 10000)
- `OUTBOX_BATCH_SIZE`: Entries per batch (default: 50)
- `OUTBOX_MAX_RETRIES`: Max retry attempts (default: 5)
- `PAGW_AURORA_WRITER_ENDPOINT`: Database writer endpoint
- `AWS_REGION`: AWS region

---

## 🧪 Testing

### Run Unit Tests
```bash
cd source
mvn test
```

**Test Coverage:**
- **OutboxPublisherServiceTest** (20 tests): Scheduled publishing, batch processing, retry logic, error handling, statistics
- **OutboxControllerTest** (13 tests): REST endpoints, manual trigger, health checks
- **ShedLockConfigTest** (5 tests): Distributed locking configuration

### Run Integration Tests
```bash
mvn verify -Pintegration-tests
```

### Manual Testing with LocalStack
```bash
# Start LocalStack
docker-compose -f docker-compose.local.yml up -d

# Create test SQS queue
aws --endpoint-url=http://localhost:4566 sqs create-queue \
  --queue-name test-queue.fifo \
  --attributes FifoQueue=true,ContentBasedDeduplication=true

# Insert test outbox entry
psql -h localhost -U pagw_user -d pagw -c \
  "INSERT INTO outbox (queue_name, payload, status) 
   VALUES ('test-queue.fifo', '{\"test\":\"data\"}', 'PENDING');"

# Trigger publishing
curl -X POST http://localhost:8089/outbox/publish

# Verify message in SQS
aws --endpoint-url=http://localhost:4566 sqs receive-message \
  --queue-url http://localhost:4566/000000000000/test-queue.fifo
```

---

## 📡 API Endpoints

### GET /outbox/stats
Returns current outbox statistics.

**Response:**
```json
{
  "unpublished": 150,  // Entries with status=PENDING
  "stuck": 3           // Entries with retry_count >= max_retries
}
```

### POST /outbox/publish
Manually triggers outbox publishing (bypasses scheduled task).

**Response:**
```json
{
  "published": 45,  // Successfully published entries
  "failed": 5       // Failed entries (retry_count incremented)
}
```

### GET /outbox/health
Simple health check endpoint.

**Response:**
```json
{
  "status": "UP"
}
```

### GET /actuator/health
Spring Boot actuator health endpoint with detailed component status.

**Response:**
```json
{
  "status": "UP",
  "components": {
    "db": {"status": "UP"},
    "diskSpace": {"status": "UP"}
  }
}
```

---

## 🔄 How It Works

### Scheduled Publishing Flow

```
┌─────────────────────────────────────────────────────────────┐
│  @Scheduled(fixedDelayString = "${pagw.outbox.publish-...") │
│  Every 10 seconds (configurable)                             │
└───────────────────────┬─────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────────┐
│  @SchedulerLock(name = "outbox-publisher")                  │
│  Acquire distributed lock (prevents duplicate processing)   │
└───────────────────────┬─────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────────┐
│  outboxService.fetchPendingEntries(batchSize)               │
│  SELECT ... WHERE status='PENDING' FOR UPDATE SKIP LOCKED   │
└───────────────────────┬─────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────────┐
│  For each entry:                                            │
│    sqsService.sendMessage(queueName, payload)               │
│    if (success) markAsPublished(id)                         │
│    else incrementRetryCount(id)                             │
└───────────────────────┬─────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────────┐
│  Release lock                                               │
│  (Next scheduled run begins after fixedDelay)               │
└─────────────────────────────────────────────────────────────┘
```

### Retry Logic
1. Entry starts with `retry_count=0`
2. On publish failure: `retry_count++`
3. Entry continues to be retried until `retry_count >= max_retries`
4. Stuck entries (exceeding max_retries) are not picked up by fetchPendingEntries
5. Monitor stuck entries via `/outbox/stats` endpoint

### ShedLock Behavior
```java
@SchedulerLock(
    name = "outbox-publisher",
    lockAtMostFor = "PT30S",   // Lock expires after 30 seconds (failover)
    lockAtLeastFor = "PT1S"    // Lock held for at least 1 second (stability)
)
```

- **Lock Acquisition**: First pod to acquire lock processes batch
- **Lock Expiration**: If pod crashes, lock expires after 30 seconds (another pod takes over)
- **Lock Stability**: Minimum 1-second hold prevents rapid lock flipping between pods

---

## 📊 Monitoring

### Key Metrics

**Outbox Lag** (Critical)
```sql
SELECT COUNT(*) FROM outbox WHERE status = 'PENDING';
```
- **Healthy**: < 1000
- **Warning**: 1000-5000
- **Critical**: > 5000

**Stuck Entries** (Important)
```sql
SELECT COUNT(*) FROM outbox WHERE retry_count >= 5;
```
- **Healthy**: 0
- **Warning**: 1-10
- **Critical**: > 10

**Publishing Rate**
```sql
SELECT COUNT(*) FROM outbox 
WHERE published_at > NOW() - INTERVAL '1 minute';
```
- **Expected**: ~300/minute (50 batch size × 6 runs/minute)

### Logs
```bash
# View real-time logs
kubectl logs -f deployment/outboxpublisher-app -n pagw-srv-dev

# Search for publishing activity
kubectl logs deployment/outboxpublisher-app -n pagw-srv-dev | grep "Publishing"

# Check for errors
kubectl logs deployment/outboxpublisher-app -n pagw-srv-dev | grep ERROR
```

### Alerts (Recommended)
- **High Outbox Lag**: unpublished_count > 5000 for 5 minutes
- **Stuck Entries**: stuck_count > 10 for 10 minutes
- **No Publishing**: published_count = 0 for 5 minutes
- **Lock Contention**: Multiple pods holding lock simultaneously
- **Database Errors**: Connection failures, query timeouts

---

## 🐛 Troubleshooting

### Problem: No messages being published
**Symptoms:** Outbox table has PENDING entries but nothing is published

**Diagnostic:**
```bash
# Check scheduled task is running
kubectl logs deployment/outboxpublisher-app | grep "Publishing pending"

# Check ShedLock status
psql -h $DB_HOST -U $DB_USER -d $DB_NAME \
  -c "SELECT * FROM shedlock WHERE name='outbox-publisher';"

# Manually trigger
curl -X POST http://localhost:8089/outbox/publish
```

**Solutions:**
- Verify @EnableScheduling annotation
- Check OUTBOX_PUBLISH_INTERVAL_MS is set
- Ensure only one pod holds lock
- Check database connectivity

### Problem: High number of stuck entries
**Symptoms:** `/outbox/stats` shows high stuck count

**Diagnostic:**
```sql
SELECT id, queue_name, retry_count, error_message 
FROM outbox 
WHERE retry_count >= 5 
LIMIT 10;
```

**Solutions:**
- Verify queue names are valid
- Check IAM permissions for SQS
- Validate message payload format
- Reset retry count: `UPDATE outbox SET retry_count=0 WHERE id=...`

See [DEPLOYMENT.md](DEPLOYMENT.md#troubleshooting) for more troubleshooting scenarios.

---

## 📚 Documentation

- **[DEPLOYMENT.md](DEPLOYMENT.md)**: Comprehensive deployment guide with environment variables, database schema, HA configuration, monitoring, and troubleshooting
- **[Architecture Diagrams](docs/)**: Detailed system architecture
- **[API Documentation](https://wiki.elevancehealth.com/pagw/outboxpublisher)**: Complete API reference

---

## 🏗️ Building and Deployment

### Local Build
```bash
cd source
mvn clean package
```

### Docker Build
```bash
cd pagw-microservices/outboxpublisher
docker build -t outboxpublisher:1.0.0 .
```

### Kubernetes Deployment
```bash
cd pagwk8s/outboxpublisher
helm install outboxpublisher . \
  --namespace pagw-srv-dev \
  --values values-dev.yaml
```

See [DEPLOYMENT.md](DEPLOYMENT.md#deployment) for detailed deployment instructions.

---

## 🔐 Security

- **Database Credentials**: Stored in Kubernetes Secrets (never hardcoded)
- **IAM Role**: Uses IRSA (IAM Roles for Service Accounts) for SQS access
- **Network Policies**: Restricts egress to Aurora and SQS only
- **Secret Rotation**: Automated via AWS Secrets Manager

---

## 📖 Related Services

- **pasapiconnector**: Inserts initial outbox entries when receiving PA requests
- **pasorchestrator**: Inserts outbox entries for workflow state transitions
- **pasresponsebuilder**: Inserts outbox entries for completed responses
- **All microservices**: Can use outbox pattern for reliable event publishing

---

## 🤝 Contributing

1. Create feature branch: `git checkout -b feature/your-feature`
2. Write tests (maintain >80% coverage)
3. Run tests: `mvn clean test`
4. Update documentation
5. Submit pull request

---

## 📞 Support

- **Team**: PAGW Platform Team
- **Slack**: #pagw-support
- **Email**: pagw-support@elevancehealth.com
- **Runbook**: https://wiki.elevancehealth.com/pagw/runbooks/outboxpublisher

---

## 📄 License

Copyright © 2024 Elevance Health. All rights reserved.
