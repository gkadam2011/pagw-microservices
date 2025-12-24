# Outbox Publisher Deployment Guide

## Table of Contents
1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Prerequisites](#prerequisites)
4. [Environment Variables](#environment-variables)
5. [Database Schema](#database-schema)
6. [High Availability Configuration](#high-availability-configuration)
7. [Building the Service](#building-the-service)
8. [Deployment](#deployment)
9. [Monitoring and Health Checks](#monitoring-and-health-checks)
10. [Troubleshooting](#troubleshooting)
11. [Performance Tuning](#performance-tuning)
12. [Security Considerations](#security-considerations)
13. [Rollback Procedures](#rollback-procedures)

---

## Overview

The **Outbox Publisher** is a scheduled service that implements the Transactional Outbox pattern for reliable event publishing to AWS SQS. It polls the `outbox` table for unpublished entries and publishes them to their target SQS queues, ensuring at-least-once delivery semantics.

### Key Features
- **Reliable Event Publishing**: Guarantees eventual delivery of all outbox entries
- **High Availability**: Uses ShedLock for distributed locking across multiple pods
- **Batch Processing**: Configurable batch size for optimal throughput
- **Automatic Retry**: Incremental retry with configurable max retries
- **Manual Trigger**: REST API endpoint for on-demand publishing
- **Monitoring**: Built-in statistics and health check endpoints

### Technology Stack
- **Spring Boot**: 3.3.0
- **ShedLock**: 5.10.0 for distributed locking
- **AWS SDK**: 2.25.0 for SQS integration
- **PostgreSQL/Aurora**: For outbox table and lock storage
- **pagwcore**: Shared library for OutboxService and SqsService

---

## Architecture

### Outbox Pattern
```
┌─────────────┐     ┌──────────┐     ┌──────────┐     ┌─────┐
│  Service A  │────▶│  Outbox  │◀────│ Outbox   │────▶│ SQS │
│ (Insert)    │     │  Table   │     │Publisher │     │Queue│
└─────────────┘     └──────────┘     └──────────┘     └─────┘
                         ▲                 │
                         │                 ▼
                    ┌────────────────────────────┐
                    │   Transactional Boundary   │
                    └────────────────────────────┘
```

### High Availability with ShedLock
```
┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│  Publisher   │     │  Publisher   │     │  Publisher   │
│   Pod 1      │     │   Pod 2      │     │   Pod 3      │
│ (🔒 Active)  │     │  (Waiting)   │     │  (Waiting)   │
└──────────────┘     └──────────────┘     └──────────────┘
       │                    │                    │
       └────────────────────┼────────────────────┘
                            ▼
                    ┌──────────────┐
                    │  ShedLock    │
                    │  Table       │
                    └──────────────┘
```

Only ONE pod holds the lock at any time, preventing duplicate message publishing.

### Processing Flow
1. **Scheduled Task**: Runs every 10 seconds (configurable)
2. **Acquire Lock**: Uses ShedLock to ensure single-pod execution
3. **Fetch Batch**: Retrieves up to 50 unpublished entries (configurable)
4. **Publish**: Sends each entry to its target SQS queue
5. **Update Status**: Marks successful entries as published, increments retry count for failures
6. **Release Lock**: Allows next scheduled run

---

## Prerequisites

### Required Services
- **AWS Account**: With SQS access
- **PostgreSQL/Aurora Database**: Version 12 or higher
- **Kubernetes Cluster**: For deployment (EKS recommended)
- **Container Registry**: For Docker images

### Required IAM Permissions
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "sqs:SendMessage",
        "sqs:GetQueueUrl"
      ],
      "Resource": "arn:aws:sqs:*:*:*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "secretsmanager:GetSecretValue"
      ],
      "Resource": "arn:aws:secretsmanager:*:*:secret:*/rds/aurora/*"
    }
  ]
}
```

### Database Setup
Run the following SQL scripts before deploying:

**Outbox Table:**
```sql
CREATE TABLE IF NOT EXISTS outbox (
    id BIGSERIAL PRIMARY KEY,
    queue_name VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP,
    retry_count INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    error_message TEXT
);

CREATE INDEX idx_outbox_status ON outbox(status);
CREATE INDEX idx_outbox_retry_count ON outbox(retry_count);
CREATE INDEX idx_outbox_created_at ON outbox(created_at);
```

**ShedLock Table:**
```sql
CREATE TABLE IF NOT EXISTS shedlock (
    name VARCHAR(64) NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP NOT NULL,
    locked_at TIMESTAMP NOT NULL,
    locked_by VARCHAR(255) NOT NULL
);
```

---

## Environment Variables

### Server Configuration
| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `SERVER_PORT` | No | 8089 | HTTP server port |

### AWS Configuration
| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `AWS_REGION` | Yes | - | AWS region (e.g., us-east-2) |
| `AWS_ACCOUNT_ID` | Yes | - | AWS account ID |
| `PAGW_AWS_ENDPOINT` | No | - | AWS endpoint (for LocalStack testing) |

### Database Configuration
| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `PAGW_AURORA_WRITER_ENDPOINT` | Yes | - | Aurora writer endpoint |
| `PAGW_AURORA_READER_ENDPOINT` | No | - | Aurora reader endpoint (optional) |
| `PAGW_AURORA_DATABASE` | Yes | pagw | Database name |
| `PAGW_AURORA_PORT` | No | 5432 | Database port |
| `PAGW_AURORA_USERNAME` | Yes | - | Database username |
| `PAGW_AURORA_PASSWORD` | Yes | - | Database password |
| `PAGW_AURORA_SECRET_ARN` | No | - | Secrets Manager ARN for credentials |
| `PAGW_AURORA_MAX_POOL_SIZE` | No | 10 | Maximum connection pool size |
| `PAGW_AURORA_MIN_IDLE` | No | 2 | Minimum idle connections |

### Outbox Configuration
| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `OUTBOX_PUBLISH_INTERVAL_MS` | No | 10000 | Polling interval in milliseconds |
| `OUTBOX_BATCH_SIZE` | No | 50 | Number of entries to process per batch |
| `OUTBOX_MAX_RETRIES` | No | 5 | Maximum retry attempts before marking as stuck |

### Application Configuration
| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `PAGW_APPLICATION_ID` | No | outboxpublisher | Application identifier |
| `LOGGING_LEVEL` | No | INFO | Log level (DEBUG, INFO, WARN, ERROR) |

### Example Configuration (DEV)
```yaml
env:
  - name: AWS_REGION
    value: "us-east-2"
  - name: AWS_ACCOUNT_ID
    value: "482754295601"
  - name: PAGW_AURORA_WRITER_ENDPOINT
    value: "aapsql-apm1082022-00dev01.czq1gklw7b57.us-east-2.rds.amazonaws.com"
  - name: PAGW_AURORA_DATABASE
    value: "pagwdb"
  - name: PAGW_AURORA_PORT
    value: "5432"
  - name: PAGW_AURORA_USERNAME
    valueFrom:
      secretKeyRef:
        name: pagw-aurora-credentials
        key: username
  - name: PAGW_AURORA_PASSWORD
    valueFrom:
      secretKeyRef:
        name: pagw-aurora-credentials
        key: password
  - name: OUTBOX_PUBLISH_INTERVAL_MS
    value: "10000"
  - name: OUTBOX_BATCH_SIZE
    value: "50"
  - name: OUTBOX_MAX_RETRIES
    value: "5"
```

---

## Database Schema

### Outbox Table Structure
```sql
TABLE outbox (
    id              BIGSERIAL PRIMARY KEY,
    queue_name      VARCHAR(255) NOT NULL,  -- Target SQS queue name or URL
    payload         TEXT NOT NULL,          -- JSON message payload
    created_at      TIMESTAMP NOT NULL,     -- Entry creation time
    published_at    TIMESTAMP,              -- Successful publish time
    retry_count     INTEGER NOT NULL,       -- Current retry attempt
    status          VARCHAR(50) NOT NULL,   -- PENDING, PUBLISHED, FAILED
    error_message   TEXT                    -- Last error message
);
```

### Status Values
- **PENDING**: Ready for publishing
- **PUBLISHED**: Successfully sent to SQS
- **FAILED**: Exceeded max retries

### ShedLock Table Structure
```sql
TABLE shedlock (
    name        VARCHAR(64) PRIMARY KEY,   -- Lock name (e.g., 'outbox-publisher')
    lock_until  TIMESTAMP NOT NULL,        -- Lock expiration time
    locked_at   TIMESTAMP NOT NULL,        -- Lock acquisition time
    locked_by   VARCHAR(255) NOT NULL      -- Pod/host identifier
);
```

### Data Flow Example
```sql
-- 1. Service inserts outbox entry (same transaction as business logic)
INSERT INTO outbox (queue_name, payload, status)
VALUES ('my-queue.fifo', '{"requestId":"123"}', 'PENDING');

-- 2. Outbox Publisher fetches pending entries
SELECT * FROM outbox
WHERE status = 'PENDING' AND retry_count < 5
ORDER BY created_at
LIMIT 50
FOR UPDATE SKIP LOCKED;

-- 3. On successful publish
UPDATE outbox
SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP
WHERE id = 1;

-- 4. On failure
UPDATE outbox
SET retry_count = retry_count + 1, error_message = 'SQS timeout'
WHERE id = 2;
```

---

## High Availability Configuration

### ShedLock Setup

**Purpose**: Prevents duplicate message publishing when running multiple replicas.

**Lock Parameters:**
```java
@SchedulerLock(
    name = "outbox-publisher",           // Unique lock identifier
    lockAtMostFor = "PT30S",             // Maximum lock duration (30 seconds)
    lockAtLeastFor = "PT1S"              // Minimum lock duration (1 second)
)
```

**How It Works:**
1. Pod 1 acquires lock at 10:00:00, lock_until = 10:00:30
2. Pods 2 and 3 skip execution (lock held by Pod 1)
3. If Pod 1 crashes, lock expires at 10:00:30
4. Pod 2 or 3 acquires lock and continues processing

**Lock Duration Guidelines:**
- `lockAtMostFor`: Should be > average batch processing time
- `lockAtLeastFor`: Prevents rapid lock flipping between pods
- Polling interval: Should be < lockAtMostFor to avoid gaps

**Recommended Settings:**
```yaml
# For small batches (< 1000 entries)
OUTBOX_PUBLISH_INTERVAL_MS: 10000   # 10 seconds
lockAtMostFor: PT30S                 # 30 seconds
lockAtLeastFor: PT1S                 # 1 second

# For large batches (> 5000 entries)
OUTBOX_PUBLISH_INTERVAL_MS: 30000   # 30 seconds
lockAtMostFor: PT2M                  # 2 minutes
lockAtLeastFor: PT5S                 # 5 seconds
```

### Deployment Replicas
```yaml
# Kubernetes Deployment
spec:
  replicas: 3  # Recommended: 3 for HA
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 1
      maxSurge: 1
```

---

## Building the Service

### Local Build
```bash
cd pagw-microservices/outboxpublisher/source
mvn clean package -DskipTests
```

### With Tests
```bash
mvn clean test
mvn clean package
```

### Docker Build
```bash
cd pagw-microservices/outboxpublisher
docker build -t outboxpublisher:latest .
```

### Multi-Architecture Build
```bash
docker buildx build --platform linux/amd64,linux/arm64 \
  -t quay-nonprod.elevancehealth.com/pagw/outboxpublisher:1.0.0 \
  --push .
```

---

## Deployment

### Kubernetes Deployment

**1. Create Namespace**
```bash
kubectl create namespace pagw-srv-dev
```

**2. Create Database Secret**
```bash
kubectl create secret generic pagw-aurora-credentials \
  --from-literal=username=pagw_user \
  --from-literal=password=secure_password \
  -n pagw-srv-dev
```

**3. Install Helm Chart**
```bash
cd pagwk8s/outboxpublisher
helm install outboxpublisher . \
  --namespace pagw-srv-dev \
  --values values-dev.yaml
```

**4. Verify Deployment**
```bash
kubectl get pods -n pagw-srv-dev -l app=outboxpublisher
kubectl logs -f deployment/outboxpublisher-app -n pagw-srv-dev
```

### Configuration Validation
```bash
# Check environment variables
kubectl exec -n pagw-srv-dev deployment/outboxpublisher-app -- env | grep PAGW

# Test database connectivity
kubectl exec -n pagw-srv-dev deployment/outboxpublisher-app -- \
  curl -s http://localhost:8089/actuator/health

# Check outbox statistics
kubectl exec -n pagw-srv-dev deployment/outboxpublisher-app -- \
  curl -s http://localhost:8089/outbox/stats
```

---

## Monitoring and Health Checks

### Health Check Endpoint
```bash
GET http://localhost:8089/actuator/health
```

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

### Statistics Endpoint
```bash
GET http://localhost:8089/outbox/stats
```

**Response:**
```json
{
  "unpublished": 150,  // Pending entries in outbox
  "stuck": 3           // Entries exceeding max retries
}
```

### Manual Trigger Endpoint
```bash
POST http://localhost:8089/outbox/publish
```

**Response:**
```json
{
  "published": 45,  // Successfully published
  "failed": 5       // Failed to publish
}
```

### Key Metrics to Monitor

**1. Outbox Lag**
```sql
SELECT COUNT(*) FROM outbox WHERE status = 'PENDING';
-- Should stay < 1000 under normal load
```

**2. Stuck Entries**
```sql
SELECT COUNT(*) FROM outbox WHERE retry_count >= 5;
-- Should stay close to 0
```

**3. Publishing Rate**
```sql
SELECT COUNT(*) FROM outbox 
WHERE published_at > NOW() - INTERVAL '1 minute';
-- Expected: ~300 per minute (50 batch size * 6 runs/min)
```

**4. Lock Contention**
```sql
SELECT * FROM shedlock WHERE name = 'outbox-publisher';
-- Check locked_by to see which pod holds the lock
```

### Prometheus Metrics (if enabled)
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  metrics:
    export:
      prometheus:
        enabled: true
```

**Sample Queries:**
- `rate(outbox_published_total[5m])`: Publishing rate
- `outbox_pending_count`: Pending entries
- `outbox_stuck_count`: Stuck entries
- `shedlock_acquisition_failures_total`: Lock contention

---

## Troubleshooting

### Issue 1: No Messages Being Published

**Symptoms:**
- Outbox table has PENDING entries
- No logs showing publishing activity

**Diagnostic Steps:**
```bash
# Check if scheduled task is running
kubectl logs -n pagw-srv-dev deployment/outboxpublisher-app | grep "Publishing pending"

# Check ShedLock status
kubectl exec -n pagw-srv-dev deployment/outboxpublisher-app -- \
  psql -h $PAGW_AURORA_WRITER_ENDPOINT -U $PAGW_AURORA_USERNAME -d $PAGW_AURORA_DATABASE \
  -c "SELECT * FROM shedlock WHERE name = 'outbox-publisher';"

# Verify @EnableScheduling is present
kubectl exec -n pagw-srv-dev deployment/outboxpublisher-app -- \
  cat /app/app.jar | grep EnableScheduling
```

**Solutions:**
- Verify `@EnableScheduling` annotation on OutboxPublisherApplication
- Check OUTBOX_PUBLISH_INTERVAL_MS is set correctly
- Ensure only one pod holds the lock (check shedlock table)
- Manually trigger: `curl -X POST http://localhost:8089/outbox/publish`

---

### Issue 2: High Number of Stuck Entries

**Symptoms:**
- `/outbox/stats` shows high `stuck` count
- Entries have retry_count >= max_retries

**Diagnostic Steps:**
```sql
-- Find stuck entries
SELECT id, queue_name, retry_count, error_message, created_at
FROM outbox
WHERE retry_count >= 5
ORDER BY created_at DESC
LIMIT 10;

-- Check error patterns
SELECT error_message, COUNT(*)
FROM outbox
WHERE retry_count >= 5
GROUP BY error_message;
```

**Common Causes:**
- **Invalid Queue Name**: Check queue_name format
- **SQS Permissions**: Verify IAM role has sqs:SendMessage
- **Network Issues**: Check VPC/security group configuration
- **Malformed Payload**: Validate JSON structure

**Solutions:**
```bash
# Reset stuck entries for retry
UPDATE outbox
SET retry_count = 0, error_message = NULL
WHERE retry_count >= 5 AND queue_name = 'valid-queue';

# Delete invalid entries
DELETE FROM outbox
WHERE retry_count >= 5 AND error_message LIKE '%Invalid queue%';
```

---

### Issue 3: Lock Contention / Multiple Pods Publishing

**Symptoms:**
- Duplicate messages in SQS
- Multiple pods showing publishing logs simultaneously

**Diagnostic Steps:**
```sql
-- Check current lock holder
SELECT * FROM shedlock WHERE name = 'outbox-publisher';

-- Check lock history (if auditing enabled)
SELECT locked_by, locked_at, lock_until
FROM shedlock_history
WHERE name = 'outbox-publisher'
ORDER BY locked_at DESC
LIMIT 20;
```

**Solutions:**
- Verify shedlock table exists and has correct schema
- Increase `lockAtLeastFor` to prevent rapid flipping
- Check database connectivity (network latency can cause lock failures)
- Ensure all pods use the same database connection

---

### Issue 4: Slow Publishing Rate

**Symptoms:**
- Outbox lag growing (unpublished count increasing)
- Publishing takes > 30 seconds per batch

**Diagnostic Steps:**
```sql
-- Check batch processing time
SELECT
    EXTRACT(EPOCH FROM (MAX(published_at) - MIN(created_at))) as batch_time_seconds,
    COUNT(*) as entries_in_batch
FROM outbox
WHERE published_at > NOW() - INTERVAL '1 minute'
GROUP BY DATE_TRUNC('minute', published_at);
```

**Solutions:**
- **Increase Batch Size**: Set OUTBOX_BATCH_SIZE=100
- **Reduce Polling Interval**: Set OUTBOX_PUBLISH_INTERVAL_MS=5000
- **Optimize Database**: Add indexes on status and retry_count
- **Increase Replicas**: Scale to 5 pods (with ShedLock, only one publishes at a time, but faster failover)
- **Optimize SQS Calls**: Use batch send if supported

---

### Issue 5: Database Connection Errors

**Symptoms:**
- Logs show "Connection refused" or "Timeout"
- Health check fails

**Diagnostic Steps:**
```bash
# Test database connectivity
kubectl exec -n pagw-srv-dev deployment/outboxpublisher-app -- \
  nc -zv $PAGW_AURORA_WRITER_ENDPOINT 5432

# Check connection pool
kubectl exec -n pagw-srv-dev deployment/outboxpublisher-app -- \
  curl -s http://localhost:8089/actuator/metrics/hikaricp.connections.active
```

**Solutions:**
- Verify security group allows inbound on port 5432
- Check Aurora endpoint is correct (writer, not reader)
- Increase connection pool: PAGW_AURORA_MAX_POOL_SIZE=20
- Verify credentials in Kubernetes secret

---

## Performance Tuning

### Batch Size Optimization

**Small Batches (< 50 entries/batch):**
```yaml
OUTBOX_BATCH_SIZE: 20
OUTBOX_PUBLISH_INTERVAL_MS: 5000  # More frequent polls
```
- Lower latency (messages published sooner)
- More database queries
- Good for: Low-traffic environments

**Large Batches (> 100 entries/batch):**
```yaml
OUTBOX_BATCH_SIZE: 200
OUTBOX_PUBLISH_INTERVAL_MS: 30000  # Less frequent polls
```
- Higher throughput
- Longer lock duration needed
- Good for: High-traffic environments

### Database Connection Tuning
```yaml
PAGW_AURORA_MAX_POOL_SIZE: 20    # Increase for high concurrency
PAGW_AURORA_MIN_IDLE: 5          # Keep connections warm
```

### ShedLock Tuning
```yaml
# High-traffic (many entries)
lockAtMostFor: PT2M              # 2 minutes for large batches
lockAtLeastFor: PT5S             # Prevent rapid flipping

# Low-traffic (few entries)
lockAtMostFor: PT30S             # 30 seconds (faster failover)
lockAtLeastFor: PT1S             # Allow quicker re-acquisition
```

### JVM Tuning
```yaml
env:
  - name: JAVA_OPTS
    value: |
      -XX:+UseContainerSupport
      -XX:MaxRAMPercentage=75.0
      -XX:+UseG1GC
      -XX:MaxGCPauseMillis=200
      -Xlog:gc*:file=/tmp/gc.log
```

---

## Security Considerations

### Database Credentials
- **DO NOT** hardcode passwords in application.yml
- Use Kubernetes Secrets or AWS Secrets Manager
- Rotate credentials regularly (every 90 days)

```yaml
# Good: Reference secret
env:
  - name: PAGW_AURORA_PASSWORD
    valueFrom:
      secretKeyRef:
        name: pagw-aurora-credentials
        key: password

# Bad: Hardcoded (NEVER DO THIS)
env:
  - name: PAGW_AURORA_PASSWORD
    value: "hardcoded_password"
```

### IAM Role for Service Account (IRSA)
```yaml
# Helm values
serviceAccountName: pagw-custom-sa
annotations:
  eks.amazonaws.com/role-arn: arn:aws:iam::123456789:role/pagw-outboxpublisher-role
```

### Network Policies
```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: outboxpublisher-policy
spec:
  podSelector:
    matchLabels:
      app: outboxpublisher
  policyTypes:
  - Ingress
  - Egress
  egress:
  - to:
    - podSelector: {}  # Aurora endpoint
    ports:
    - protocol: TCP
      port: 5432
  - to:  # SQS endpoint
    - ipBlock:
        cidr: 0.0.0.0/0
    ports:
    - protocol: TCP
      port: 443
```

---

## Rollback Procedures

### Rollback Helm Deployment
```bash
# View release history
helm history outboxpublisher -n pagw-srv-dev

# Rollback to previous version
helm rollback outboxpublisher 2 -n pagw-srv-dev

# Verify rollback
kubectl get pods -n pagw-srv-dev -l app=outboxpublisher
```

### Emergency Stop
```bash
# Scale down to zero replicas
kubectl scale deployment outboxpublisher-app --replicas=0 -n pagw-srv-dev

# Verify no publishing is occurring
SELECT * FROM shedlock WHERE name = 'outbox-publisher';
# Should show no active locks
```

### Manual Processing (Emergency)
```sql
-- Manually mark entries as published (use with caution)
UPDATE outbox
SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP
WHERE id IN (SELECT id FROM outbox WHERE status = 'PENDING' LIMIT 100);
```

---

## Contact and Support

For issues or questions:
- **Team**: PAGW Platform Team
- **Slack**: #pagw-support
- **Email**: pagw-support@elevancehealth.com
- **Runbook**: https://wiki.elevancehealth.com/pagw/runbooks/outboxpublisher
