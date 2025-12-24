# pascallbackhandler - Deployment Ready Checklist

**Service**: pascallbackhandler  
**Port**: 8088  
**Status**: ✅ DEPLOYMENT READY  
**Date**: December 24, 2025

---

## Changes Made

### 1. Dockerfile Updates ✅
- **Fixed pagwcore copy pattern**: Now copies from `/app/m2/` to `/root/.m2/repository/` (matches pasorchestrator)
- **Added debug output**: Shows copied pagwcore files during build
- **Standardized runtime stage**: Uses same pattern as other services (appuser:appgroup with UID/GID 1001)
- **Proper ARG declarations**: ARGs declared in correct scopes
- **Build optimization**: Added `mvn clean package` to avoid cache issues

### 2. application.yml Updates ✅
- **Added jackson config**: `default-property-inclusion: non_null`
- **Added connection-timeout**: 30000ms for database connections
- **Added s3.enabled flag**: `enabled: true`
- **Added secrets.enabled flag**: `enabled: true`
- **Added kms.phi-key-alias**: For PHI field encryption
- **Enhanced encryption config**: Added `enabled` and `encrypt-phi-fields` flags
- **Added prometheus endpoint**: `/actuator/prometheus` to metrics exposure
- **Added health endpoint config**: `show-details: when_authorized`
- **Added comprehensive logging**: Pattern with pagwId and correlationId MDC fields

### 3. Kubernetes values-*.yaml Updates ✅
- **Fixed containerPort**: Changed from 8080 → 8088 (all environments)
- **Fixed service targetPort**: Changed from 8080 → 8088 (all environments)
- **Environments updated**:
  - ✅ values-dev.yaml
  - ✅ values-preprod.yaml
  - ✅ values-prod.yaml

---

## Configuration Summary

### Application Configuration
```yaml
Server Port: 8088
Spring Profile: pagw,{environment}
Database: PostgreSQL/Aurora (pagwdb)
Queue: pagw-callback-handler-queue
```

### Resource Allocation
| Environment | Replicas | CPU Request | CPU Limit | Memory Request | Memory Limit |
|-------------|----------|-------------|-----------|----------------|--------------|
| DEV         | 1        | 200m        | 500m      | 512Mi          | 1Gi          |
| PREPROD     | 2        | 500m        | 1000m     | 1Gi            | 2Gi          |
| PROD        | 3        | 1000m       | 2000m     | 2Gi            | 4Gi          |

### Health Probes
```yaml
Liveness:
  - Initial Delay: 60s
  - Period: 10s
  - Timeout: 5s
  - Failure Threshold: 3

Readiness:
  - Initial Delay: 30s
  - Period: 10s
  - Timeout: 5s
  - Failure Threshold: 3
```

---

## Deployment Architecture

### Service Flow
```
Payer System → pascallbackhandler (8088)
    ↓
    ├─ Process callback from payer
    ├─ Update request_tracker status
    ├─ Store response in S3
    └─ Queue to orchestrator-complete-queue
```

### AWS Resources
- **SQS Queue**: `dev-PAGW-pagw-callback-handler-queue.fifo`
- **Response Queue**: `dev-PAGW-pagw-orchestrator-complete-queue.fifo`
- **S3 Buckets**: 
  - Request: `crln-pagw-dev-dataz-gbd-phi-useast2`
  - Attachments: `crln-pagw-dev-dataz-gbd-phi-useast2`
  - Audit: `crln-pagw-dev-logz-nogbd-nophi-useast2`
- **Aurora**: `aapsql-apm1082022-00dev01.czq1gklw7b57.us-east-2.rds.amazonaws.com:5432/pagwdb`
- **KMS Key**: PHI field-level encryption

### Kubernetes Resources
- **Namespace**: pagw-srv-dev
- **Service**: ClusterIP on port 443 → 8088
- **ServiceAccount**: pagw-custom-sa (with IRSA for AWS permissions)
- **Secrets**: pagw-aurora-credentials (username/password fallback)

---

## Endpoints

### Health & Monitoring
```bash
# Health check
curl http://pascallbackhandler:8088/actuator/health

# Metrics
curl http://pascallbackhandler:8088/actuator/metrics

# Prometheus
curl http://pascallbackhandler:8088/actuator/prometheus

# Info
curl http://pascallbackhandler:8088/actuator/info
```

### API Endpoints
```bash
# Callback endpoint (example - check controller for actual endpoints)
POST http://pascallbackhandler:8088/callback/receive
```

---

## Build & Deploy

### Build Docker Image
```bash
cd pagw-microservices/pascallbackhandler
docker build -t quay-nonprod.elevancehealth.com/pagw/pascallbackhandler:latest .
docker push quay-nonprod.elevancehealth.com/pagw/pascallbackhandler:latest
```

### Deploy to DEV
```bash
cd pagwk8s/pascallbackhandler
helm upgrade --install pascallbackhandler . \
  -f values-dev.yaml \
  --namespace pagw-srv-dev \
  --set container.image=quay-nonprod.elevancehealth.com/pagw/pascallbackhandler:latest
```

### Deploy to PREPROD
```bash
helm upgrade --install pascallbackhandler . \
  -f values-preprod.yaml \
  --namespace pagw-srv-preprod \
  --set container.image=quay-nonprod.elevancehealth.com/pagw/pascallbackhandler:v1.0.0
```

### Deploy to PROD
```bash
helm upgrade --install pascallbackhandler . \
  -f values-prod.yaml \
  --namespace pagw-srv-prod \
  --set container.image=quay-nonprod.elevancehealth.com/pagw/pascallbackhandler:v1.0.0
```

---

## Verification

### 1. Check Pod Status
```bash
kubectl get pods -n pagw-srv-dev -l app=pascallbackhandler
kubectl logs -n pagw-srv-dev -l app=pascallbackhandler --tail=100
```

### 2. Verify Health
```bash
kubectl exec -n pagw-srv-dev deployment/pascallbackhandler-app -- \
  wget -qO- http://localhost:8088/actuator/health
```

### 3. Check Service
```bash
kubectl get svc -n pagw-srv-dev pascallbackhandler
kubectl describe svc -n pagw-srv-dev pascallbackhandler
```

### 4. Test Connectivity
```bash
# From another pod in the cluster
kubectl run -it --rm debug --image=busybox --restart=Never -- \
  wget -qO- http://pascallbackhandler.pagw-srv-dev.svc.cluster.local:443/actuator/health
```

---

## Configuration Alignment

### ✅ Matches pasorchestrator Pattern
- Dockerfile: Multi-stage build with pagwcore
- application.yml: All standard PAGW configurations
- Logging: MDC fields (pagwId, correlationId)
- Metrics: Prometheus + actuator endpoints
- Security: AppUser with UID 1001
- Health: Proper probes with timeouts

### ✅ Matches pasbusinessvalidator Pattern
- Database connection pooling
- HikariCP configuration
- Jackson serialization
- Spring profiles (pagw + environment)

---

## Environment Variables (Set by Helm)

### Core
- `SPRING_PROFILES_ACTIVE`: pagw,dev
- `PAGW_APPLICATION_ID`: PAS
- `AWS_REGION`: us-east-2

### Database
- `PAGW_AURORA_WRITER_ENDPOINT`: aapsql-apm1082022-00dev01.czq1gklw7b57.us-east-2.rds.amazonaws.com
- `PAGW_AURORA_DATABASE`: pagwdb
- `PAGW_AURORA_PORT`: 5432
- `SPRING_DATASOURCE_USERNAME`: (from secret)
- `SPRING_DATASOURCE_PASSWORD`: (from secret)

### AWS Services
- `PAGW_SQS_REQUEST_QUEUE`: Callback handler queue
- `PAGW_SQS_RESPONSE_QUEUE`: Orchestrator complete queue
- `PAGW_S3_REQUEST_BUCKET`: PHI data bucket
- `PAGW_ENCRYPTION_KMS_ENABLED`: true

---

## Dependencies

### Maven Dependencies
- Spring Boot 3.3.0
- Spring Cloud AWS 3.1.1
- AWS SDK 2.25.0
- PostgreSQL Driver
- pagwcore 1.0.0-SNAPSHOT
- Lombok

### Test Dependencies
- spring-boot-starter-test (JUnit 5, Mockito, AssertJ)
- H2 Database (in-memory for tests)

### Runtime Dependencies
- pagwcore: Built and copied from quay image
- Java 17 (UBI8 OpenJDK)

---

## Test Coverage

### Unit Tests ✅
**Location**: `source/src/test/java/com/anthem/pagw/callback/`

#### CallbackHandlerServiceTest (10 tests)
- ✅ testProcessCallback_AcceptedStatus_ReturnsCompleted
- ✅ testProcessCallback_PendingStatus_ReturnsPending
- ✅ testProcessCallback_RejectedStatus_ReturnsRejected
- ✅ testProcessCallback_ErrorStatus_ReturnsError
- ✅ testProcessCallback_UnknownStatus_ReturnsUnknown
- ✅ testProcessCallback_NullStatus_HandlesGracefully
- ✅ testProcessCallback_ComplexResponse_ExtractsData
- ✅ testProcessCallback_InvalidJson_HandlesException
- ✅ testProcessCallback_EmptyResponse_HandlesGracefully
- ✅ testProcessCallback_PreservesExternalReferenceId

#### CallbackControllerTest (3 tests)
- ✅ testReceiveCallback_Success
- ✅ testReceiveCallback_MissingPagwId_ReturnsBadRequest
- ✅ testHealthCheck_ReturnsOk

### Integration Tests ✅
- ✅ CallbackHandlerApplicationTest - Spring context loading

### Test Configuration
- **Profile**: test
- **Database**: H2 in-memory
- **AWS Services**: Disabled (mocked)
- **Logging**: DEBUG level for com.anthem.pagw

### Run Tests
```bash
cd pagw-microservices/pascallbackhandler/source
mvn test
mvn verify
```

### Test Coverage Report
```bash
mvn jacoco:report
open target/site/jacoco/index.html
```

---

## Comparison with Other Services

| Feature | pasorchestrator | pasbusinessvalidator | pascallbackhandler | Status |
|---------|----------------|---------------------|-------------------|--------|
| Dockerfile pattern | ✅ | ✅ | ✅ | ALIGNED |
| Port standardization | 8080 | 8082 | 8088 | ALIGNED |
| pagwcore integration | ✅ | ✅ | ✅ | ALIGNED |
| Jackson config | ✅ | ✅ | ✅ | ALIGNED |
| Logging MDC | ✅ | ✅ | ✅ | ALIGNED |
| Prometheus metrics | ✅ | ✅ | ✅ | ALIGNED |
| Health probes | ✅ | ✅ | ✅ | ALIGNED |
| K8s values | ✅ | ✅ | ✅ | ALIGNED |

---

## Next Steps

1. **Build Image**: Create Docker image with updated Dockerfile
2. **Tag & Push**: Push to quay-nonprod registry
3. **Deploy DEV**: Test in DEV environment
4. **Verify**: Check logs, health, and callback processing
5. **Test Integration**: Send callback from payer mock
6. **Promote**: Move to PREPROD after successful testing

---

## Notes

- **Port 8088**: Chosen to avoid conflicts with other services
- **Queue**: Callback handler has dedicated FIFO queue
- **PHI Handling**: Uses KMS for field-level encryption
- **Monitoring**: Prometheus scraping enabled on pod annotation
- **Logging**: Structured JSON logs in JSON format option available

---

**Status**: ✅ Ready for deployment to DEV environment
