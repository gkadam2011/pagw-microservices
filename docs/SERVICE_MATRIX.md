# PAGW Service Matrix

**Last Updated:** December 25, 2025  
**Environment:** DEV (us-east-2)

## Quick Reference

| Service | Container Port | K8s Service Port | HTTP Endpoints | SQS Consumer | Role |
|---------|----------------|------------------|----------------|--------------|------|
| **pasorchestrator** | 8080 | 443 | `/pas/api/v1/*` | orchestrator-queue | Orchestration & API Gateway |
| **pasrequestparser** | 8081 | 443 | `/pas/api/v1/parse` | request-parser-queue | FHIR Parsing & Validation |
| **pasbusinessvalidator** | 8082 | 443 | `/pas/api/v1/validate` | business-validator-queue | Business Rules & Validation |
| **pasrequestenricher** | 8083 | 443 | Internal | request-enricher-queue | Eligibility & Provider Lookup |
| **pasattachmenthandler** | 8084 | 443 | Internal | attachment-handler-queue | CDex Attachment Processing |
| **pasrequestconverter** | 8085 | 443 | Internal | request-converter-queue | FHIR → X12 278 Conversion |
| **pasapiconnector** | 8086 | 443 | Internal | api-connector-queue | Payer API Integration |
| **pasresponsebuilder** | 8087 | 443 | Internal | response-builder-queue | X12 → FHIR Response Building |
| **pascallbackhandler** | 8088 | 443 | `/callback` | callback-handler-queue | Async Callback Processing |
| **outboxpublisher** | 8089 | 443 | Internal | outbox-queue | Transactional Outbox Pattern |
| **passubscriptionhandler** | 8080 | 443 | Internal | subscription-handler-queue | Subscription Notifications |
| **pasmockpayer** | 8095 | 8095 | `/api/*` | N/A (HTTP) | Mock Payer for Testing |

---

## Detailed Service Configuration

### 1. pasorchestrator
**Main Orchestration Service - API Gateway**

#### Deployment
- **Namespace:** pagw-srv-dev
- **Service Account:** pagw-custom-sa
- **Replicas:** Auto-scaled (HPA)
- **Container Port:** 8080
- **K8s Service:** ClusterIP on port 443 → 8080

#### HTTP Endpoints
| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/pas/api/v1/submit` | POST | Submit PA request (sync/async) |
| `/pas/api/v1/inquiry` | POST | Query existing PA status |
| `/pas/api/v1/update` | POST | Update pending PA |
| `/pas/api/v1/cancel` | POST | Cancel PA request |
| `/pas/api/v1/status/{pagwId}` | GET | Get request status |
| `/pas/api/v1/subscriptions` | POST/GET | Manage subscriptions |
| `/pas/api/v1/health` | GET | Health check |

#### SQS Queues
| Queue | Type | Purpose |
|-------|------|---------|
| **Consumer:** `dev-PAGW-pagw-orchestrator-queue.fifo` | Input | Async workflow orchestration |
| **Consumer:** `dev-PAGW-pagw-orchestrator-complete-queue.fifo` | Completion | Completed workflow results |
| **Producer:** `dev-PAGW-pagw-request-parser-queue.fifo` | Output | Send to parser |
| **Producer:** `dev-PAGW-pagw-business-validator-queue.fifo` | Output | Send to validator (sync mode) |
| **DLQ:** `dev-PAGW-pagw-dlq.fifo` | Error | Failed messages |

#### Database Access
- **Writer:** aapsql-apm1082022-00dev01.czq1gklw7b57.us-east-2.rds.amazonaws.com:5432
- **Reader:** aapsql-apm1082022-devcl01.cluster-czq1gklw7b57.us-east-2.rds.amazonaws.com:5432
- **Database:** pagwdb
- **Schema:** pagw
- **Pool:** Max 10, Min 2

#### S3 Buckets
- **Requests:** crln-pagw-dev-dataz-gbd-phi-useast2 (raw FHIR bundles)
- **Audit:** crln-pagw-dev-logz-nogbd-nophi-useast2 (audit logs)

#### DynamoDB
- **Idempotency:** pagw-idempotency-dev

#### Resources
- **CPU:** 200m request, 500m limit
- **Memory:** 512Mi request, 1Gi limit

#### Probes
- **Liveness:** 60s initial, 10s period
- **Readiness:** 30s initial, 10s period

#### Sync Processing
- **Enabled:** true
- **Timeout:** 13 seconds
- **Parser URL:** http://pasrequestparser:443
- **Validator URL:** http://pasbusinessvalidator:443

---

### 2. pasrequestparser
**FHIR Parsing & Validation**

#### Deployment
- **Namespace:** pagw-srv-dev
- **Container Port:** 8081
- **K8s Service:** ClusterIP on port 443 → 8081

#### HTTP Endpoints
| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/pas/api/v1/parse` | POST | Parse and validate FHIR bundle (internal) |
| `/pas/api/v1/health` | GET | Health check |

#### SQS Queues
| Queue | Type | Purpose |
|-------|------|---------|
| **Consumer:** `dev-PAGW-pagw-request-parser-queue.fifo` | Input | Parse FHIR bundles |
| **Producer:** `dev-PAGW-pagw-business-validator-queue.fifo` | Output | Send to validator |
| **DLQ:** `dev-PAGW-pagw-dlq.fifo` | Error | Parse failures |

#### Database Access
- Same as pasorchestrator

#### S3 Buckets
- **Requests:** Read raw bundles, write parsed data
- **Parsed Data:** `parsed-data/{tenant}/{pagwId}-parsed.json`

#### Resources
- **CPU:** 200m request, 500m limit
- **Memory:** 512Mi request, 1Gi limit

#### Key Features
- HAPI FHIR R4 parser
- Da Vinci PAS IG validation
- FHIR data extraction (Round 3)
- Stores parsed data to S3

---

### 3. pasbusinessvalidator
**Business Rules & Validation**

#### Deployment
- **Namespace:** pagw-srv-dev
- **Container Port:** 8082
- **K8s Service:** ClusterIP on port 443 → 8082

#### HTTP Endpoints
| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/pas/api/v1/validate` | POST | Validate business rules (internal) |
| `/pas/api/v1/health` | GET | Health check |

#### SQS Queues
| Queue | Type | Purpose |
|-------|------|---------|
| **Consumer:** `dev-PAGW-pagw-business-validator-queue.fifo` | Input | Validate business rules |
| **Producer:** `dev-PAGW-pagw-request-enricher-queue.fifo` | Output | Send to enricher |
| **DLQ:** `dev-PAGW-pagw-dlq.fifo` | Error | Validation failures |

#### Database Access
- Same as pasorchestrator

#### S3 Buckets
- **Requests:** Read parsed bundles

#### Resources
- **CPU:** 200m request, 500m limit
- **Memory:** 512Mi request, 1Gi limit

#### Probes
- **Liveness:** 90s initial (more complex rules)
- **Readiness:** 45s initial

#### Key Features
- Decision engine rules
- Auto-approval logic
- Payer routing decisions
- Priority assignment

---

### 4. pasrequestenricher
**Member & Provider Data Enrichment**

#### Deployment
- **Namespace:** pagw-srv-dev
- **Container Port:** 8083
- **K8s Service:** ClusterIP on port 443 → 8083

#### SQS Queues
| Queue | Type | Purpose |
|-------|------|---------|
| **Consumer:** `dev-PAGW-pagw-request-enricher-queue.fifo` | Input | Enrich data |
| **Producer:** `dev-PAGW-pagw-request-converter-queue.fifo` | Output | Send to converter |
| **DLQ:** `dev-PAGW-pagw-dlq.fifo` | Error | Enrichment failures |

#### External Dependencies
- **Eligibility Service:** http://eligibility-service:8080
- **Provider Directory:** http://provider-directory:8080

#### Database Access
- Same as pasorchestrator

#### S3 Buckets
- **Requests:** Read/write enriched data

#### Resources
- **CPU:** 200m request, 500m limit
- **Memory:** 512Mi request, 1Gi limit

#### Key Features
- Member eligibility lookup
- Provider NPI validation
- Taxonomy code enrichment
- Address standardization

---

### 5. pasattachmenthandler
**CDex Attachment Processing**

#### Deployment
- **Namespace:** pagw-srv-dev
- **Container Port:** 8084
- **K8s Service:** ClusterIP on port 443 → 8084

#### SQS Queues
| Queue | Type | Purpose |
|-------|------|---------|
| **Consumer:** `dev-PAGW-pagw-attachment-handler-queue.fifo` | Input | Process attachments |
| **DLQ:** `dev-PAGW-pagw-dlq.fifo` | Error | Processing failures |

#### Database Access
- Same as pasorchestrator

#### S3 Buckets
- **Attachments:** crln-pagw-dev-dataz-gbd-phi-useast2
  - Upload clinical documents
  - Process attachments referenced in FHIR

#### Resources
- **CPU:** 200m request, 500m limit
- **Memory:** 512Mi request, 1Gi limit

#### Key Features
- CDex $submit-attachment operation
- Binary attachment processing
- Metadata extraction
- Status tracking in `attachment_tracker` table

---

### 6. pasrequestconverter
**FHIR → X12 278 Conversion**

#### Deployment
- **Namespace:** pagw-srv-dev
- **Container Port:** 8085
- **K8s Service:** ClusterIP on port 443 → 8085

#### SQS Queues
| Queue | Type | Purpose |
|-------|------|---------|
| **Consumer:** `dev-PAGW-pagw-request-converter-queue.fifo` | Input | Convert FHIR to X12 |
| **Producer:** `dev-PAGW-pagw-api-connector-queue.fifo` | Output | Send to payer connector |
| **DLQ:** `dev-PAGW-pagw-dlq.fifo` | Error | Conversion failures |

#### Database Access
- Same as pasorchestrator

#### S3 Buckets
- **Requests:** Write X12 278 request

#### Resources
- **CPU:** 200m request, 500m limit
- **Memory:** 512Mi request, 1Gi limit

#### Key Features
- FHIR → X12 278 Request mapping
- Payer-specific X12 formats
- Loop/segment generation
- Trading partner profile application

---

### 7. pasapiconnector
**Payer API Integration**

#### Deployment
- **Namespace:** pagw-srv-dev
- **Container Port:** 8086
- **K8s Service:** ClusterIP on port 443 → 8086

#### SQS Queues
| Queue | Type | Purpose |
|-------|------|---------|
| **Consumer:** `dev-PAGW-pagw-api-connector-queue.fifo` | Input | Send to payers |
| **Producer:** `dev-PAGW-pagw-response-builder-queue.fifo` | Output | Send response to builder |
| **DLQ:** `dev-PAGW-pagw-dlq.fifo` | Error | API failures |

#### Database Access
- Same as pasorchestrator

#### S3 Buckets
- **Requests:** Read X12 278, write payer responses

#### Resources
- **CPU:** 200m request, 500m limit
- **Memory:** 512Mi request, 1Gi limit

#### Key Features
- HTTP client for payer APIs
- OAuth 2.0 / mTLS authentication
- Retry logic with exponential backoff
- Circuit breaker patterns
- Payer-specific routing

---

### 8. pasresponsebuilder
**X12 → FHIR Response Conversion**

#### Deployment
- **Namespace:** pagw-srv-dev
- **Container Port:** 8087
- **K8s Service:** ClusterIP on port 443 → 8087

#### SQS Queues
| Queue | Type | Purpose |
|-------|------|---------|
| **Consumer:** `dev-PAGW-pagw-response-builder-queue.fifo` | Input | Build FHIR response |
| **Producer:** `dev-PAGW-pagw-orchestrator-complete-queue.fifo` | Output | Notify orchestrator |
| **DLQ:** `dev-PAGW-pagw-dlq.fifo` | Error | Build failures |

#### Database Access
- Same as pasorchestrator

#### S3 Buckets
- **Requests:** Write final FHIR ClaimResponse

#### Resources
- **CPU:** 200m request, 500m limit
- **Memory:** 512Mi request, 1Gi limit

#### Key Features
- X12 278 Response → FHIR ClaimResponse
- Disposition mapping (approved/denied/pended)
- ClaimResponse.item population
- Extension handling

---

### 9. pascallbackhandler
**Async Callback Processing**

#### Deployment
- **Namespace:** pagw-srv-dev
- **Container Port:** 8088
- **K8s Service:** ClusterIP on port 443 → 8088

#### HTTP Endpoints
| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/callback` | POST | Receive async callbacks from payers |
| `/health` | GET | Health check |

#### SQS Queues
| Queue | Type | Purpose |
|-------|------|---------|
| **Consumer:** `dev-PAGW-pagw-callback-handler-queue.fifo` | Input | Process callbacks |
| **Producer:** `dev-PAGW-pagw-orchestrator-complete-queue.fifo` | Output | Notify orchestrator |
| **DLQ:** `dev-PAGW-pagw-dlq.fifo` | Error | Callback failures |

#### Database Access
- Same as pasorchestrator

#### S3 Buckets
- **Requests:** Read/write callback payloads

#### Resources
- **CPU:** 200m request, 500m limit
- **Memory:** 512Mi request, 1Gi limit

#### Key Features
- Async callback endpoint
- Correlation with original request
- Status update processing
- Retry handling

---

### 10. outboxpublisher
**Transactional Outbox Pattern**

#### Deployment
- **Namespace:** pagw-srv-dev
- **Container Port:** 8089
- **K8s Service:** ClusterIP on port 443 → 8089

#### SQS Queues
| Queue | Type | Purpose |
|-------|------|---------|
| **Consumer:** `dev-PAGW-pagw-outbox-queue.fifo` | Trigger | Process outbox entries |
| **Producer:** Dynamic (based on message) | Output | Route to target queues |
| **DLQ:** `dev-PAGW-pagw-dlq.fifo` | Error | Publishing failures |

#### Database Access
- **Table:** `outbox_events`
- Reads pending events, marks published

#### Resources
- **CPU:** 200m request, 500m limit
- **Memory:** 512Mi request, 1Gi limit

#### Key Features
- Transactional outbox pattern
- Exactly-once delivery guarantee
- Polling scheduler (every 5s)
- Retry with exponential backoff

---

### 11. passubscriptionhandler
**FHIR Subscription Notifications**

#### Deployment
- **Namespace:** pagw-srv-dev
- **Container Port:** 8080
- **K8s Service:** ClusterIP on port 443 → 8080

#### SQS Queues
| Queue | Type | Purpose |
|-------|------|---------|
| **Consumer:** `dev-PAGW-pagw-subscription-handler-queue.fifo` | Input | Subscription events |
| **Producer:** `dev-PAGW-pagw-orchestrator-complete-queue.fifo` | Output | Notify orchestrator |
| **DLQ:** `dev-PAGW-pagw-dlq.fifo` | Error | Notification failures |

#### Database Access
- **Table:** `subscriptions`
- Manages active subscriptions

#### S3 Buckets
- **Requests:** Read ClaimResponse for notifications

#### Resources
- **CPU:** 200m request, 500m limit
- **Memory:** 512Mi request, 1Gi limit

#### Key Features
- FHIR Subscription resource support
- Webhook notifications
- Topic-based subscriptions
- Status change triggers

---

### 12. pasmockpayer
**Mock Payer for Testing**

#### Deployment
- **Namespace:** pagw-srv-dev
- **Container Port:** 8095
- **K8s Service:** ClusterIP on port 8095 → 8095

#### HTTP Endpoints
| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/x12/278` | POST | Mock X12 278 submission |
| `/actuator/health` | GET | Health check |

#### Resources
- **CPU:** 100m request, 250m limit
- **Memory:** 256Mi request, 512Mi limit

#### Key Features
- Mock approval/denial responses
- Configurable delays
- Pre-defined scenarios
- Testing only (not deployed to prod)

---

## Shared Infrastructure

### Database (Aurora PostgreSQL)
- **Writer:** aapsql-apm1082022-00dev01.czq1gklw7b57.us-east-2.rds.amazonaws.com:5432
- **Reader (Cluster):** aapsql-apm1082022-devcl01.cluster-czq1gklw7b57.us-east-2.rds.amazonaws.com:5432
- **Database:** pagwdb
- **Schema:** pagw
- **Credentials:** AWS Secrets Manager (arn:aws:secretsmanager:us-east-2:482754295601:secret:dev/rds/aurora/postgresql/pagw-h63RqT)
- **Fallback:** K8s secret `pagw-aurora-credentials`

### S3 Buckets
| Bucket | Purpose | Encryption |
|--------|---------|------------|
| crln-pagw-dev-dataz-gbd-phi-useast2 | Requests, attachments, parsed data (PHI) | KMS |
| crln-pagw-dev-logz-nogbd-nophi-useast2 | Audit logs (no PHI) | KMS |

**Folder Structure:**
```
crln-pagw-dev-dataz-gbd-phi-useast2/
├── raw-requests/{tenant}/{pagwId}/
│   └── request.json                    # Original FHIR Bundle
├── parsed-data/{tenant}/
│   └── {pagwId}-parsed.json            # Extracted FHIR data (Round 3)
├── x12-requests/{tenant}/{pagwId}/
│   └── request-278.txt                 # X12 278 Request
├── x12-responses/{tenant}/{pagwId}/
│   └── response-278.txt                # X12 278 Response
├── final-responses/{tenant}/{pagwId}/
│   └── response.json                   # FHIR ClaimResponse
└── attachments/{tenant}/{pagwId}/
    └── {documentId}.pdf                # Clinical documents
```

### DynamoDB
- **Table:** pagw-idempotency-dev
- **Purpose:** Idempotency key tracking
- **TTL:** 24 hours

### KMS Encryption
- **Key:** arn:aws:kms:us-east-2:482754295601:key/b8575906-6114-4656-a61f-3c24724b9def
- **Algorithm:** AES-256-GCM
- **Usage:** S3 server-side encryption, sensitive field encryption

### SQS Queue Naming Convention
```
{env}-PAGW-pagw-{service}-queue.fifo
```

**All Queues:**
1. `dev-PAGW-pagw-orchestrator-queue.fifo`
2. `dev-PAGW-pagw-orchestrator-complete-queue.fifo`
3. `dev-PAGW-pagw-request-parser-queue.fifo`
4. `dev-PAGW-pagw-business-validator-queue.fifo`
5. `dev-PAGW-pagw-request-enricher-queue.fifo`
6. `dev-PAGW-pagw-request-converter-queue.fifo`
7. `dev-PAGW-pagw-api-connector-queue.fifo`
8. `dev-PAGW-pagw-response-builder-queue.fifo`
9. `dev-PAGW-pagw-callback-handler-queue.fifo`
10. `dev-PAGW-pagw-attachment-handler-queue.fifo`
11. `dev-PAGW-pagw-subscription-handler-queue.fifo`
12. `dev-PAGW-pagw-outbox-queue.fifo`
13. `dev-PAGW-pagw-dlq.fifo` (Dead Letter Queue)

### Kubernetes Resources
- **Namespace:** pagw-srv-dev
- **Service Account:** pagw-custom-sa
  - IAM Role: Allows S3, SQS, RDS, KMS, DynamoDB access
- **Image Pull Secret:** pagw-tekton-pull-secret
- **All services:** ClusterIP (internal)

---

## Service Communication Flow

### Synchronous Mode (13s timeout)
```
Client → pasorchestrator:443 (/pas/api/v1/submit?syncMode=true)
          ↓ HTTP
       pasrequestparser:443 (/pas/api/v1/parse)
          ↓ HTTP
       pasbusinessvalidator:443 (/pas/api/v1/validate)
          ↓ Returns decision
       pasorchestrator → Client (immediate response)
```

### Asynchronous Mode (Complete Workflow)
```
Client → pasorchestrator:443 (/pas/api/v1/submit)
          ↓ Queued (returns 202 Accepted)
          
SQS Flow:
pasorchestrator → request-parser-queue
                     ↓
               pasrequestparser → business-validator-queue
                     ↓
               pasbusinessvalidator → request-enricher-queue
                     ↓
               pasrequestenricher → request-converter-queue
                     ↓
               pasrequestconverter → api-connector-queue
                     ↓
               pasapiconnector → response-builder-queue
                     ↓
               pasresponsebuilder → orchestrator-complete-queue
                     ↓
               pasorchestrator (updates status, notifies subscription)
```

### Parallel Branch (Attachments)
```
pasrequestparser → attachment-handler-queue
                      ↓
                  pasattachmenthandler (processes CDex attachments)
```

---

## Monitoring & Observability

### Health Checks
All services expose:
- **Liveness:** `/actuator/health/liveness`
- **Readiness:** `/actuator/health/readiness`
- **General:** `/actuator/health`

### Metrics
- Prometheus endpoints on `/actuator/prometheus`
- CloudWatch metrics via EMF logs

### Logging
- **Level:** INFO (configurable via Helm)
- **Format:** JSON (structured logging)
- **CloudWatch:** Log Group per service
- **Retention:** 30 days

### Tracing
- X-Ray tracing enabled
- Correlation ID propagation (X-Correlation-ID header)
- Trace ID in all log entries

---

## Network Policies

### Ingress
- **External:** Only pasorchestrator exposed via ALB/NLB
- **Internal:** All services ClusterIP (private)

### Egress
- Internet access for payer APIs (pasapiconnector)
- VPC endpoints for AWS services (S3, SQS, RDS, KMS, DynamoDB)
- No direct internet for other services

---

## Scaling Configuration

### Horizontal Pod Autoscaling (HPA)
| Service | Min | Max | Target CPU | Target Memory |
|---------|-----|-----|------------|---------------|
| pasorchestrator | 2 | 10 | 70% | 80% |
| pasrequestparser | 2 | 8 | 70% | 80% |
| pasbusinessvalidator | 2 | 8 | 70% | 80% |
| pasrequestenricher | 1 | 6 | 70% | 80% |
| pasattachmenthandler | 1 | 4 | 70% | 80% |
| pasrequestconverter | 2 | 6 | 70% | 80% |
| pasapiconnector | 2 | 8 | 70% | 80% |
| pasresponsebuilder | 2 | 6 | 70% | 80% |
| pascallbackhandler | 1 | 4 | 70% | 80% |
| outboxpublisher | 1 | 3 | 70% | 80% |
| passubscriptionhandler | 1 | 3 | 70% | 80% |
| pasmockpayer | 1 | 1 | N/A | N/A |

---

## Deployment Order

For clean deployment, follow this order:

1. **Database Migration:** pasorchestrator (Flyway V001-V006)
2. **Core Services:**
   - pasorchestrator
   - pasrequestparser
   - pasbusinessvalidator
3. **Enrichment & Conversion:**
   - pasrequestenricher
   - pasrequestconverter
4. **Integration:**
   - pasapiconnector
   - pasresponsebuilder
5. **Supporting Services:**
   - pasattachmenthandler
   - pascallbackhandler
   - passubscriptionhandler
   - outboxpublisher
6. **Testing:** pasmockpayer (optional)

---

## Environment-Specific Overrides

### Preprod
- Replace `-dev-` with `-preprod-` in all AWS resource names
- Adjust replica counts (min: 3, max: 15 for high availability)
- Enable stricter probes

### Prod
- Replace `-dev-` with `-prod-` in all AWS resource names
- Full HA configuration (min: 3+ replicas per service)
- Multi-AZ deployment
- Enhanced monitoring & alerting

---

## Quick Commands

### Check Service Status
```bash
kubectl get pods -n pagw-srv-dev
kubectl get svc -n pagw-srv-dev
```

### View Logs
```bash
kubectl logs -n pagw-srv-dev deployment/pasorchestrator-app -f
kubectl logs -n pagw-srv-dev deployment/pasrequestparser-app -f
```

### Test Health
```bash
kubectl port-forward -n pagw-srv-dev svc/pasorchestrator 8080:443
curl http://localhost:8080/actuator/health
```

### Check SQS Queue Depths
```bash
aws sqs get-queue-attributes \
  --queue-url https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-request-parser-queue.fifo \
  --attribute-names ApproximateNumberOfMessages
```

---

**Related Documentation:**
- [OPERATIONAL_DATA_MODEL.md](./OPERATIONAL_DATA_MODEL.md)
- [IMPLEMENTATION_ROADMAP.md](./IMPLEMENTATION_ROADMAP.md)
- [PAGW-Demo-Guide.md](./PAGW-Demo-Guide.md)
- [ENDPOINT_MIGRATION_V1_TO_API_V1.md](./ENDPOINT_MIGRATION_V1_TO_API_V1.md)
