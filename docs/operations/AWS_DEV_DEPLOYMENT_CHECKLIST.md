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
Terraform creates queues with pattern: `pagw-queue-{service}-dev`

| Queue | Purpose |
|-------|---------|
| `pagw-queue-request-parser-dev` | Incoming requests from orchestrator |
| `pagw-queue-business-validator-dev` | After parsing |
| `pagw-queue-request-enricher-dev` | After validation (async start) |
| `pagw-queue-attachment-handler-dev` | After enrichment |
| `pagw-queue-request-converter-dev` | After attachment processing |
| `pagw-queue-api-connector-dev` | After format conversion |
| `pagw-queue-response-builder-dev` | After external API call |
| `pagw-queue-callback-handler-dev` | Async callbacks from payers |
| `pagw-queue-subscription-handler-dev` | Client webhook notifications |
| `pagw-queue-outbox-publisher-dev` | Outbox pattern publishing |
| `pagw-queue-replay-dev` | Manual replay queue |
| `pagw-queue-dlq-dev` | Dead letter queue |

### KMS Keys
| Alias | Purpose |
|-------|---------|
| `alias/pagw-phi-field-dev` | Field-level PHI encryption |
| `alias/pagw-s3-dev` | S3 bucket encryption |
| `alias/pagw-sqs-dev` | SQS queue encryption |
| `alias/pagw-rds-dev` | Aurora database encryption |
| `alias/pagw-dynamodb-dev` | DynamoDB encryption |

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
  
  # KMS
  PAGW_KMS_KEY_ID: "alias/pagw-phi-field-dev"
  
  # ElastiCache (if enabled)
  PAGW_ELASTICACHE_ENDPOINT: "<elasticache-endpoint>"
  PAGW_ELASTICACHE_PORT: "6379"
  PAGW_ELASTICACHE_SSL_ENABLED: "true"
```

### Service-Specific SQS Queues
Each service needs its input/output queue URLs configured:

| Service | Input Queue Var | Output Queue Var |
|---------|----------------|------------------|
| pasrequestparser | PAGW_SQS_REQUEST_QUEUE | PAGW_SQS_NEXT_QUEUE |
| pasbusinessvalidator | PAGW_SQS_REQUEST_QUEUE | PAGW_SQS_NEXT_QUEUE |
| pasrequestenricher | PAGW_SQS_REQUEST_QUEUE | PAGW_SQS_NEXT_QUEUE |
| pasattachmenthandler | PAGW_SQS_REQUEST_QUEUE | PAGW_SQS_NEXT_QUEUE |
| pasrequestconverter | PAGW_SQS_REQUEST_QUEUE | PAGW_SQS_NEXT_QUEUE |
| pasapiconnector | PAGW_SQS_REQUEST_QUEUE | PAGW_SQS_RESPONSE_QUEUE |
| pasresponsebuilder | PAGW_SQS_REQUEST_QUEUE | PAGW_SQS_RESPONSE_QUEUE |
| pascallbackhandler | PAGW_SQS_REQUEST_QUEUE | PAGW_SQS_RESPONSE_QUEUE |
| passubscriptionhandler | PAGW_SQS_REQUEST_QUEUE | N/A (webhook delivery) |
| pasorchestrator | PAGW_SQS_REQUEST_QUEUE | PAGW_SQS_RESPONSE_QUEUE |
| outboxpublisher | N/A (polls DB) | Various queues |

---

## 4. Service Deployment Order

### Pipeline Architecture

```
SYNC FLOW (returns 202 Accepted):
  pasorchestrator → pasrequestparser → pasbusinessvalidator
                                              │
                                              ▼ (triggers async)
ASYNC FLOW (background processing):
  pasrequestenricher → pasattachmenthandler → pasrequestconverter
         │
         ▼
  pasapiconnector → pasresponsebuilder → pascallbackhandler → passubscriptionhandler
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
   - `pasrequestconverter` - Payer format conversion
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

| Component | Terraform Pattern | App Config Default |
|-----------|------------------|-------------------|
| S3 Buckets | `pagw-{type}-dev-{account_id}` | `crln-pagw-dev-dataz-gbd-phi-useast2` |
| SQS Queues | `pagw-queue-{service}-dev` | Configured via env vars |

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
