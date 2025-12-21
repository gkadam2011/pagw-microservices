# Lambda to Microservices Migration Guide

## Overview

This guide provides a detailed mapping from the existing Lambda-based PAGW implementation to the new Kubernetes microservices architecture.

## Service Mapping

| Lambda Function | K8s Microservice | Queue | Port |
|-----------------|------------------|-------|------|
| `pas_api` | `pasorchestrator` (Lambda-Orchestrator) | - (HTTP ingress) | 8080 |
| `pas_orchestrator` | `pasrequestparser` (Lambda-RequestParser) | pagw-queue-request-parser | 8081 |
| `pas_validator` | `pasbusinessvalidator` (Lambda-BusinessValidator) | pagw-queue-business-validator | 8082 |
| `pas_enricher` | `pasrequestenricher` (Lambda-RequestEnricher) | pagw-queue-request-enricher | 8083 |
| `pas_mapper` | `pasrequestconverter` (Lambda-RequestConverter) | pagw-queue-request-converter | 8085 |
| `pas_submission` | `pasapiconnector` (API-Connector/Fargate) | pagw-queue-api-connectors | 8086 |
| `pas_response` | `pasresponsebuilder` (Lambda-ResponseBuilder) | pagw-queue-response-builder | 8087 |
| `pas_callback` | `pascallbackhandler` (Lambda-CallbackHandler) | pagw-queue-callback-handler | 8088 |
| (new) | `pasattachmenthandler` (Lambda-AttachmentHandler) | pagw-queue-attachment-handler | 8084 |
| (new) | `outboxpublisher` | - (scheduled) | 8089 |

## Architecture Comparison

### Lambda Architecture (Current)

```
                    ┌─────────────────────────────────────────────────────────────┐
                    │                      AWS Lambda                              │
                    │                                                              │
Provider ──▶ API GW ──▶ pas_api ──▶ SQS ──▶ pas_orchestrator ──▶ SQS ──▶ ...     │
                    │                           │                                  │
                    │                           ▼                                  │
                    │                    Aurora PostgreSQL                         │
                    └─────────────────────────────────────────────────────────────┘
```

### Microservices Architecture (Target)

```
                    ┌─────────────────────────────────────────────────────────────┐
                    │                    Kubernetes (EKS)                          │
                    │                                                              │
                    │   ┌────────────┐    ┌────────────┐    ┌────────────┐        │
Provider ──▶ Ingress ──▶│Orchestrator│──▶ │   Outbox   │──▶ │   Parser   │ ...    │
                    │   └────────────┘    │  Publisher │    └────────────┘        │
                    │         │           └────────────┘          │               │
                    │         ▼                 │                 ▼               │
                    │   ┌─────────────────────────────────────────────────┐       │
                    │   │              Aurora PostgreSQL (RDS Proxy)       │       │
                    │   └─────────────────────────────────────────────────┘       │
                    └─────────────────────────────────────────────────────────────┘
```

## Migration Steps

### Phase 1: Infrastructure Setup (Week 1-2)

1. **Deploy Terraform Infrastructure**
   ```bash
   cd infra/terraform
   terraform init
   terraform plan -var-file=environments/dev.tfvars
   terraform apply -var-file=environments/dev.tfvars
   ```

2. **Run Database Migrations**
   ```bash
   # Connect to RDS via bastion
   psql -h $RDS_PROXY_ENDPOINT -U pagw_admin -d pagw < db/migrations/0001_create_request_tracker.sql
   psql -h $RDS_PROXY_ENDPOINT -U pagw_admin -d pagw < db/migrations/0002_create_outbox.sql
   # ... run all migrations
   ```

3. **Deploy pagwcore to JFrog**
   ```bash
   cd pagwcore
   mvn clean deploy -DskipTests
   ```

### Phase 2: Deploy Core Services (Week 2-3)

1. **Deploy Orchestrator (pasorchestrator)**
   ```bash
   # Build and push image
   cd pagw-microservices/pasorchestrator
   docker build -t pagw/pasorchestrator:1.0.0 .
   docker push registry.anthem.com/pagw/pasorchestrator:1.0.0
   
   # Deploy to K8s
   helm upgrade --install pasorchestrator ./charts/pasorchestrator \
     -f values-dev.yaml \
     --namespace pagw
   ```

2. **Deploy Outbox Publisher**
   ```bash
   # Deploy scheduled job
   helm upgrade --install outboxpublisher ./charts/outboxpublisher \
     -f values-dev.yaml \
     --namespace pagw
   ```

3. **Verify Basic Flow**
   ```bash
   # Submit test claim
   curl -X POST https://pagw-dev.anthem.com/v1/claims \
     -H "Content-Type: application/json" \
     -d @test/sample-claim.json
   
   # Check request_tracker
   psql -c "SELECT * FROM request_tracker ORDER BY created_at DESC LIMIT 5"
   
   # Check outbox
   psql -c "SELECT * FROM outbox WHERE published = false"
   ```

### Phase 3: Deploy Processing Services (Week 3-4)

Deploy services in order:

1. `pasrequestparser`
2. `pasattachmenthandler`
3. `pasbusinessvalidator`
4. `pasrequestenricher`
5. `pascanonnicalmapper`

For each service:
```bash
# Build
cd pagw-microservices/<service>
docker build -t pagw/<service>:1.0.0 .
docker push registry.anthem.com/pagw/<service>:1.0.0

# Deploy
helm upgrade --install <service> ./charts/<service> \
  -f values-dev.yaml \
  --namespace pagw

# Verify SQS consumption
aws sqs get-queue-attributes \
  --queue-url $QUEUE_URL \
  --attribute-names ApproximateNumberOfMessagesVisible
```

### Phase 4: Deploy Submission & Response Services (Week 4-5)

1. **Deploy API Orchestrator**
   ```bash
   helm upgrade --install pasapiorchestrator ./charts/pasapiorchestrator \
     -f values-dev.yaml \
     --namespace pagw
   ```

2. **Deploy Response Builder**
   ```bash
   helm upgrade --install pasresponsebuilder ./charts/pasresponsebuilder \
     -f values-dev.yaml \
     --namespace pagw
   ```

3. **Deploy Callback Handler**
   ```bash
   helm upgrade --install pascallbackhandler ./charts/pascallbackhandler \
     -f values-dev.yaml \
     --namespace pagw
   ```

### Phase 5: Traffic Migration (Week 5-6)

1. **Enable Parallel Processing (Shadow Mode)**
   ```yaml
   # API Gateway configuration
   routes:
     - path: /v1/claims
       backends:
         - lambda: pas_api (primary)
         - k8s: pasorchestrator (shadow)
   ```

2. **Compare Results**
   - Monitor both paths
   - Compare response times
   - Verify data consistency

3. **Gradual Traffic Shift**
   ```yaml
   # Canary deployment
   - lambda: pas_api (90%)
   - k8s: pasorchestrator (10%)
   
   # Increase gradually
   - lambda: pas_api (50%)
   - k8s: pasorchestrator (50%)
   
   # Full cutover
   - k8s: pasorchestrator (100%)
   ```

### Phase 6: Lambda Decommission (Week 6-7)

1. **Disable Lambda triggers**
2. **Monitor for straggler traffic**
3. **Delete Lambda functions**
4. **Clean up Lambda-specific IAM roles**

## Code Migration Details

### Handler Pattern Change

**Lambda (Python):**
```python
def handler(event, context):
    message = json.loads(event['Records'][0]['body'])
    pagw_id = message['pagwId']
    
    # Process...
    
    # Publish to next queue
    sqs.send_message(
        QueueUrl=os.environ['NEXT_QUEUE'],
        MessageBody=json.dumps(result)
    )
```

**Microservice (Java):**
```java
@SqsListener("${pagw.aws.sqs.queue-name}")
@Transactional
public void handleMessage(String messageBody) {
    PagwMessage message = JsonUtils.fromJson(messageBody, PagwMessage.class);
    String pagwId = message.getPagwId();
    
    // Process...
    
    // Write to outbox (same transaction)
    outboxService.writeOutbox(NEXT_QUEUE, result);
}
```

### Key Differences

| Aspect | Lambda | Microservice |
|--------|--------|--------------|
| Invocation | Event-driven | SQS Listener |
| Scaling | Automatic | HPA based on metrics |
| Cold Start | Yes | No |
| Connection Pooling | Per-invocation | Persistent pool |
| Transactions | Limited | Full ACID with outbox |
| State | Stateless | Can maintain state |
| Deployment | SAM/CDK | Helm/Tekton |

## Environment Variables Mapping

| Lambda Env Var | K8s ConfigMap/Secret |
|----------------|---------------------|
| `S3_BUCKET_RAW` | `pagw.aws.s3.raw-bucket` |
| `DB_SECRET_ARN` | `pagw.aws.secrets.db-secret-arn` |
| `RDS_PROXY_ENDPOINT` | `spring.datasource.url` |
| `NEXT_QUEUE_URL` | (Outbox pattern - no direct publish) |
| `KMS_KEY_ID` | `pagw.encryption.kms-key-id` |

## Rollback Procedure

If issues are encountered:

1. **Redirect traffic back to Lambda**
   ```yaml
   routes:
     - path: /v1/claims
       backends:
         - lambda: pas_api (100%)
   ```

2. **Drain K8s queues**
   - Let existing messages complete
   - Or move to Lambda SQS triggers

3. **Scale down K8s services**
   ```bash
   kubectl scale deployment --replicas=0 -n pagw --all
   ```

## Success Criteria

- [ ] All services deployed and healthy
- [ ] End-to-end flow working
- [ ] Response times within SLA
- [ ] No increase in error rates
- [ ] DLQ messages < Lambda baseline
- [ ] Database connections stable
- [ ] Monitoring/alerting in place
