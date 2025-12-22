# AWS Dev Environment Deployment Checklist

## Overview
This checklist covers the configuration and verification steps for deploying PAGW microservices to the AWS dev environment.

---

## 1. Environment Configuration Summary

### Region: `us-east-2`

### S3 Buckets
| Purpose | Bucket Name | Environment Variable |
|---------|-------------|---------------------|
| Request/Response Data | `crln-pagw-dev-dataz-gbd-phi-useast2` | `PAGW_S3_REQUEST_BUCKET` |
| Attachments | `crln-pagw-dev-dataz-gbd-phi-useast2` | `PAGW_S3_ATTACHMENTS_BUCKET` |
| Audit Logs | `crln-pagw-dev-logz-nogbd-nophi-useast2` | `PAGW_S3_AUDIT_BUCKET` |

### S3 Folder Structure
All services use a consistent folder structure within the request bucket:
```
{YYYYMM}/{pagwId}/request/     # Original FHIR bundles
{YYYYMM}/{pagwId}/response/    # Provider responses
{YYYYMM}/{pagwId}/attachments/ # Attachment files
```

### SQS Queues
Terraform creates FIFO queues with pattern: `dev-PAGW-pagw-{service}-queue.fifo`

| Queue | Purpose |
|-------|---------|
| `dev-PAGW-pagw-orchestrator-queue.fifo` | Entry point - API requests |
| `dev-PAGW-pagw-request-parser-queue.fifo` | FHIR bundle parsing |
| `dev-PAGW-pagw-business-validator-queue.fifo` | Business validation |
| `dev-PAGW-pagw-request-enricher-queue.fifo` | Data enrichment (async start) |
| `dev-PAGW-pagw-attachment-handler-queue.fifo` | Attachment S3 uploads |
| `dev-PAGW-pagw-request-converter-queue.fifo` | FHIR to X12/payer format conversion |
| `dev-PAGW-pagw-api-connector-queue.fifo` | External payer API calls |
| `dev-PAGW-pagw-response-builder-queue.fifo` | Response assembly |
| `dev-PAGW-pagw-callback-handler-queue.fifo` | Client webhook callbacks |
| `dev-PAGW-pagw-subscription-handler-queue.fifo` | FHIR Subscription notifications |
| `dev-PAGW-pagw-orchestrator-complete-queue.fifo` | Workflow completion |
| `dev-PAGW-pagw-outbox-queue.fifo` | Transactional outbox publishing |
| `dev-PAGW-pagw-dlq.fifo` | Dead letter queue |

### KMS Keys
| Key ARN / Alias | Purpose |
|-------|---------|
| `arn:aws:kms:us-east-2:482754295601:key/b8575906-6114-4656-a61f-3c24724b9def` | S3 PHI bucket encryption |
| `alias/aws/sqs` | SQS queue encryption (AWS managed) |
| `alias/aws/rds` | Aurora database encryption (AWS managed) |
| `alias/aws/dynamodb` | DynamoDB encryption (AWS managed) |

### DynamoDB Tables
| Table Name | Purpose |
|------------|---------|
| `pagw-idempotency-dev` | Request deduplication / idempotency tracking |

---

## 2. Pre-Deployment Checklist

### Infrastructure Verification
- [ ] Verify S3 buckets exist with correct names
- [ ] Verify S3 buckets have KMS encryption enabled
- [ ] Verify S3 buckets have versioning enabled
- [ ] Verify S3 buckets block public access
- [ ] Verify all SQS queues exist
- [ ] Verify DynamoDB idempotency table exists
- [ ] Verify SQS queues have KMS encryption enabled
- [ ] Verify DLQs are attached to main queues
- [ ] Verify KMS keys exist with correct aliases
- [ ] Verify Aurora cluster is accessible
- [ ] Verify Aurora secrets in Secrets Manager

### Database Migration
- [ ] Run Flyway migrations against Aurora dev cluster
- [ ] Verify `request_tracker` table exists
- [ ] Verify `outbox` table exists
- [ ] Verify `attachment_tracker` table exists
- [ ] Verify `provider_registry` table exists
- [ ] Verify `event_tracker` table exists
- [ ] Verify `audit_log` table exists
- [ ] Verify `idempotency` table exists
- [ ] Verify `payer_configuration` table exists

### Secrets Configuration
- [ ] Aurora database credentials in Secrets Manager
- [ ] Provider API credentials configured
- [ ] All services have IAM roles with secretsmanager:GetSecretValue permission

---

## 3. Required Environment Variables

Each service deployment (via Helm values) needs these environment variables:

### Common Variables (All Services)
```yaml
env:
  SPRING_PROFILES_ACTIVE: "dev"
  
  # Aurora Database
  PAGW_AURORA_WRITER_ENDPOINT: "<cluster-writer-endpoint>"
  PAGW_AURORA_READER_ENDPOINT: "<cluster-reader-endpoint>"
  PAGW_AURORA_PORT: "5432"
  PAGW_AURORA_DATABASE: "pagwdb"
  PAGW_AURORA_SECRET_ARN: "arn:aws:secretsmanager:us-east-2:ACCOUNT:secret:pagw/aurora/dev"
  
  # S3 Buckets
  PAGW_S3_REQUEST_BUCKET: "crln-pagw-dev-dataz-gbd-phi-useast2"
  PAGW_S3_ATTACHMENTS_BUCKET: "crln-pagw-dev-dataz-gbd-phi-useast2"
  PAGW_S3_AUDIT_BUCKET: "crln-pagw-dev-logz-nogbd-nophi-useast2"
  
  # KMS Encryption
  PAGW_ENCRYPTION_KMS_ENABLED: "true"
  PAGW_ENCRYPTION_KMS_KEY_ID: "arn:aws:kms:us-east-2:482754295601:key/b8575906-6114-4656-a61f-3c24724b9def"
  
  # DynamoDB
  PAGW_DYNAMODB_IDEMPOTENCY_TABLE: "pagw-idempotency-dev"
```

### Service-Specific SQS Queues
Each service needs its input/output queue URLs configured:

| Service | Input Queue | Output Queue |
|---------|-------------|---------------|
| pasorchestrator | pagw-orchestrator-queue.fifo | pagw-request-parser-queue.fifo |
| pasrequestparser | pagw-request-parser-queue.fifo | pagw-business-validator-queue.fifo |
| pasbusinessvalidator | pagw-business-validator-queue.fifo | pagw-request-enricher-queue.fifo |
| pasrequestenricher | pagw-request-enricher-queue.fifo | pagw-attachment-handler-queue.fifo |
| pasattachmenthandler | pagw-attachment-handler-queue.fifo | pagw-request-converter-queue.fifo |
| pasrequestconverter | pagw-request-converter-queue.fifo | pagw-api-connector-queue.fifo |
| pasapiconnector | pagw-api-connector-queue.fifo | pagw-response-builder-queue.fifo |
| pasresponsebuilder | pagw-response-builder-queue.fifo | pagw-callback-handler-queue.fifo |
| pascallbackhandler | pagw-callback-handler-queue.fifo | pagw-orchestrator-complete-queue.fifo |
| passubscriptionhandler | pagw-subscription-handler-queue.fifo | pagw-orchestrator-complete-queue.fifo |
| outboxpublisher | pagw-outbox-queue.fifo | (routes to target queues) |

---

## 4. Service Deployment Order

### Pipeline Architecture

```
SYNC FLOW (returns 202 Accepted):
  API Gateway → pasorchestrator → pasrequestparser → pasbusinessvalidator
                                                            │
                                                            ▼ (triggers async via outbox)
ASYNC FLOW (background processing):
  pasrequestenricher → pasattachmenthandler → pasrequestconverter → pasapiconnector
                                                                          │
                                                                          ▼
                                                            pasresponsebuilder ─┬─→ pascallbackhandler ─────┬─→ pasorchestrator (complete)
                                        │                           │
                                        └─→ passubscriptionhandler ─┘

SUPPORT SERVICES:
  outboxpublisher - Polls outbox table, publishes to target queues
```

### Recommended Deployment Order

Services can be deployed independently, but recommended order:

1. **Core Infrastructure Services**
   - `outboxpublisher` - Handles async publishing

2. **Sync Pipeline (request intake)**
   - `pasorchestrator` - Request lifecycle management
   - `pasrequestparser` - FHIR parsing
   - `pasbusinessvalidator` - Validation (returns 202 to client)

3. **Async Pipeline (background processing)**
   - `pasrequestenricher` - Data enrichment
   - `pasattachmenthandler` - Attachment processing
   - `pasrequestconverter` - FHIR to X12/payer format conversion
   - `pasapiconnector` - External payer API calls

4. **Response Pipeline**
   - `pasresponsebuilder` - Response assembly
   - `pascallbackhandler` - Async callbacks from payers
   - `passubscriptionhandler` - Client webhook notifications

---

## 5. Post-Deployment Verification

### Health Checks
- [ ] All pods are Running
- [ ] Health endpoints return 200 OK
- [ ] Readiness probes passing
- [ ] Liveness probes passing

### Connectivity Tests
- [ ] Services can connect to Aurora
- [ ] Services can access S3 buckets
- [ ] Services can read from SQS queues
- [ ] Services can write to SQS queues
- [ ] Services can access Secrets Manager
- [ ] Services can use KMS keys
- [ ] Services can access DynamoDB tables

### End-to-End Test
1. Send test PAS request to API Gateway
2. Verify request appears in S3:
   - `{YYYYMM}/{pagwId}/request/original_bundle.json`
3. Verify request flows through all stages:
   - Check each queue receives and processes message
4. Verify response stored in S3:
   - `{YYYYMM}/{pagwId}/response/claim_response.json`
5. Verify audit log entry created in Aurora

---

## 6. Known Configuration Differences

### Terraform vs Application Config

| Component | Terraform Pattern | Actual Resource Name |
|-----------|------------------|-------------------|
| S3 PHI Bucket | Corporate naming | `crln-pagw-dev-dataz-gbd-phi-useast2` |
| S3 Audit Bucket | Corporate naming | `crln-pagw-dev-logz-nogbd-nophi-useast2` |
| SQS Queues | `{env}-PAGW-pagw-{service}-queue.fifo` | e.g., `dev-PAGW-pagw-orchestrator-queue.fifo` |
| DynamoDB | `pagw-idempotency-{env}` | `pagw-idempotency-dev` |

**Note:** S3 bucket names follow a corporate naming convention. The Terraform configuration is a reference - actual bucket names are provisioned by the platform team.

---

## 7. Monitoring Setup

### CloudWatch Metrics
- [ ] SQS queue depth alarms configured
- [ ] DLQ message count alarms configured
- [ ] S3 bucket size monitoring
- [ ] Aurora connection count monitoring

### Logging
- [ ] All services logging to CloudWatch Logs
- [ ] Log retention policy set (30 days recommended)
- [ ] Log insights queries configured for debugging

### Dashboards
- [ ] Service health dashboard
- [ ] Request throughput dashboard
- [ ] Error rate dashboard
- [ ] Latency percentile dashboard

---

## 8. Rollback Plan

If deployment fails:

1. **Immediate:** Roll back to previous Helm release
   ```bash
   helm rollback <service-name> --namespace pagw-dev
   ```

2. **Database:** Flyway migrations are forward-only
   - For breaking changes, create new "undo" migrations

3. **Configuration:** Revert values files to previous version
   ```bash
   git checkout HEAD~1 -- values-dev.yaml
   ```

---

## 9. Emergency Contacts

| Role | Contact |
|------|---------|
| Platform Team | @platform-team |
| Database Admin | @dba-oncall |
| Security Team | @security-oncall |

---

## 10. Appendix: Quick Commands

### Check Service Logs
```bash
kubectl logs -f deployment/<service-name> -n pagw-dev
```

### Check SQS Queue Depth
```bash
aws sqs get-queue-attributes \
  --queue-url <queue-url> \
  --attribute-names ApproximateNumberOfMessages \
  --region us-east-2
```

### Check S3 Bucket Contents
```bash
aws s3 ls s3://crln-pagw-dev-dataz-gbd-phi-useast2/ --recursive
```

### Run Database Query
```bash
kubectl exec -it deployment/pasorchestrator -n pagw-dev -- \
  psql -h $PAGW_AURORA_WRITER_ENDPOINT -U pagw_admin -d pagwdb
```
