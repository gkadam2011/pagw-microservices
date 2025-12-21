# Environment Variable Synchronization Report

**Date**: Auto-generated
**Source**: pagw-microservices application.yml files
**Target**: pagw-platform-deploy Helm templates

## Summary

All microservice `application.yml` files have been updated to align with the Helm deployment templates in `pagw-platform-deploy`.

## Environment Variable Mapping

### Aurora PostgreSQL

| Helm Template Variable | Spring Property | Status |
|------------------------|-----------------|--------|
| `PAGW_AURORA_WRITER_ENDPOINT` | `spring.datasource.url` (host part) | ✅ Synced |
| `PAGW_AURORA_READER_ENDPOINT` | `pagw.aws.aurora.reader-endpoint` | ✅ Synced |
| `PAGW_AURORA_DATABASE` | `spring.datasource.url` (database) | ✅ Synced |
| `PAGW_AURORA_PORT` | `spring.datasource.url` (port) | ✅ Synced |
| `PAGW_AURORA_SECRET_ARN` | `pagw.aws.aurora.secret-arn` | ✅ Synced |
| `PAGW_AURORA_MAX_POOL_SIZE` | `spring.datasource.hikari.maximum-pool-size` | ✅ Synced |
| `PAGW_AURORA_MIN_IDLE` | `spring.datasource.hikari.minimum-idle` | ✅ Synced |
| `PAGW_AURORA_IAM_AUTH_ENABLED` | `pagw.aws.aurora.iam-auth-enabled` | ✅ Synced |
| `PAGW_AURORA_USERNAME` | `spring.datasource.username` | ✅ Synced |
| `PAGW_AURORA_PASSWORD` | `spring.datasource.password` | ✅ Synced |

### AWS General

| Helm Template Variable | Spring Property | Status |
|------------------------|-----------------|--------|
| `AWS_REGION` | `pagw.aws.region` | ✅ Synced (default: `us-east-2`) |
| `AWS_ACCOUNT_ID` | `pagw.aws.account-id` | ✅ Synced |
| `PAGW_APPLICATION_ID` | `pagw.application-id` | ✅ Synced |

### SQS Queues

| Helm Template Variable | Spring Property | Status |
|------------------------|-----------------|--------|
| `PAGW_SQS_REQUEST_QUEUE` | `pagw.aws.sqs.request-queue` | ✅ Synced |
| `PAGW_SQS_RESPONSE_QUEUE` | `pagw.aws.sqs.response-queue` | ✅ Synced |
| `PAGW_SQS_DLQ` | `pagw.aws.sqs.dlq` | ✅ Synced |

### S3 Buckets

| Helm Template Variable | Spring Property | Status |
|------------------------|-----------------|--------|
| `PAGW_S3_ATTACHMENTS_BUCKET` | `pagw.aws.s3.attachments-bucket` | ✅ Synced |
| `PAGW_S3_AUDIT_BUCKET` | `pagw.aws.s3.audit-bucket` | ✅ Synced |

### ElastiCache (Redis)

| Helm Template Variable | Spring Property | Status |
|------------------------|-----------------|--------|
| `PAGW_ELASTICACHE_ENDPOINT` | `pagw.aws.elasticache.endpoint` | ✅ Synced |
| `PAGW_ELASTICACHE_PORT` | `pagw.aws.elasticache.port` | ✅ Synced |
| `PAGW_ELASTICACHE_SSL_ENABLED` | `pagw.aws.elasticache.ssl-enabled` | ✅ Synced |
| `PAGW_REDIS_AUTH_SECRET_ARN` | `pagw.aws.elasticache.auth-secret-arn` | ✅ Synced |

## Changes Made

### JDBC URL Format

**Before:**
```yaml
url: jdbc:postgresql://${PAGW_AURORA_ENDPOINT:localhost}:5432/${PAGW_AURORA_DATABASE:pagw}
```

**After:**
```yaml
url: jdbc:postgresql://${PAGW_AURORA_WRITER_ENDPOINT:localhost}:${PAGW_AURORA_PORT:5432}/${PAGW_AURORA_DATABASE:pagw}
```

### AWS Region Default

**Before:**
```yaml
region: ${AWS_REGION:us-east-1}
```

**After:**
```yaml
region: ${AWS_REGION:us-east-2}
```

> **Note:** Default region changed to `us-east-2` to match dev/sit environments. Helm values will override for preprod/prod to `us-east-1`.

### HikariCP Pool Configuration

**Before:**
```yaml
hikari:
  maximum-pool-size: 10
  minimum-idle: 2
```

**After:**
```yaml
hikari:
  maximum-pool-size: ${PAGW_AURORA_MAX_POOL_SIZE:10}
  minimum-idle: ${PAGW_AURORA_MIN_IDLE:2}
```

### SQS Queue Variables

**Before:** Service-specific variables like `SQS_QUEUE_REQUEST_PARSER`, `SQS_BUSINESS_VALIDATOR_QUEUE`, etc.

**After:** Standardized `PAGW_SQS_REQUEST_QUEUE`, `PAGW_SQS_RESPONSE_QUEUE`, `PAGW_SQS_DLQ` across all services.

### S3 Bucket Variables

**Before:** Multiple bucket variables like `S3_BUCKET_RAW`, `S3_BUCKET_FINAL`, `S3_BUCKET_ATTACHMENTS`

**After:** Standardized `PAGW_S3_ATTACHMENTS_BUCKET`, `PAGW_S3_AUDIT_BUCKET`

## Services Updated

| Service | Port | application.yml Updated |
|---------|------|-------------------------|
| pasorchestrator | 8080 | ✅ |
| pasrequestparser | 8081 | ✅ |
| pasbusinessvalidator | 8082 | ✅ |
| pasrequestenricher | 8083 | ✅ |
| pasattachmenthandler | 8084 | ✅ |
| pasrequestconverter | 8085 | ✅ |
| pasapiconnector | 8086 | ✅ |
| pasresponsebuilder | 8087 | ✅ |
| pascallbackhandler | 8088 | ✅ |
| outboxpublisher | 8089 | ✅ |

## Helm Values Validation

The following environment variables from `pagw-platform-deploy` Helm templates are now correctly consumed:

### From `templates/deployment.yaml`:
```yaml
env:
  - name: SPRING_PROFILES_ACTIVE
    value: "pagw,{{ .Values.env.environment }}"
  - name: PAGW_APPLICATION_ID
    value: {{ .Values.pagw.applicationId | default "PAS" | quote }}
  - name: AWS_REGION
    value: {{ .Values.env.region | quote }}
  - name: AWS_ACCOUNT_ID
    value: {{ .Values.pagw.aws.accountId | quote }}
  - name: PAGW_AURORA_WRITER_ENDPOINT
    value: {{ .Values.pagw.aws.aurora.writerEndpoint | quote }}
  - name: PAGW_AURORA_DATABASE
    value: {{ .Values.pagw.aws.aurora.database | default "pagw" | quote }}
  - name: PAGW_AURORA_PORT
    value: {{ .Values.pagw.aws.aurora.port | default "5432" | quote }}
  - name: PAGW_AURORA_SECRET_ARN
    value: {{ .Values.pagw.aws.aurora.secretArn | quote }}
  - name: PAGW_AURORA_MAX_POOL_SIZE
    value: {{ .Values.pagw.aws.aurora.maxPoolSize | default "10" | quote }}
  - name: PAGW_AURORA_MIN_IDLE
    value: {{ .Values.pagw.aws.aurora.minIdle | default "2" | quote }}
  - name: PAGW_SQS_REQUEST_QUEUE
    value: {{ .Values.pagw.aws.sqs.requestQueue | quote }}
  - name: PAGW_SQS_RESPONSE_QUEUE
    value: {{ .Values.pagw.aws.sqs.responseQueue | quote }}
  - name: PAGW_SQS_DLQ
    value: {{ .Values.pagw.aws.sqs.dlqQueue | quote }}
  - name: PAGW_S3_ATTACHMENTS_BUCKET
    value: {{ .Values.pagw.aws.s3.attachmentsBucket | quote }}
  - name: PAGW_S3_AUDIT_BUCKET
    value: {{ .Values.pagw.aws.s3.auditBucket | quote }}
  - name: PAGW_ELASTICACHE_ENDPOINT
    value: {{ .Values.pagw.aws.elasticache.endpoint | quote }}
  - name: PAGW_ELASTICACHE_PORT
    value: {{ .Values.pagw.aws.elasticache.port | default "6379" | quote }}
```

### From `values-dev.yaml`:
```yaml
env:
  region: us-east-2
  environment: dev

pagw:
  aws:
    aurora:
      writerEndpoint: "aapsql-apm1082022-00dev01.czq1gklw7b57.us-east-2.rds.amazonaws.com"
      database: pagwdb
      port: "5432"
      secretArn: "arn:aws:secretsmanager:us-east-2:482754295601:secret:dev/rds/aurora/postgresql/pagw-h63RqT"
    sqs:
      requestQueue: "https://sqs.us-east-2.amazonaws.com/..."
      responseQueue: "https://sqs.us-east-2.amazonaws.com/..."
      dlqQueue: "https://sqs.us-east-2.amazonaws.com/..."
    s3:
      attachmentsBucket: "crln-pagw-dev-dataz-gbd-phi-useast2"
      auditBucket: "crln-pagw-dev-logz-nogbd-nophi-useast2"
```

## Remaining Tasks

1. **Profile-specific configurations**: ✅ DONE - All `application-{dev,sit,preprod,prod}.yml` files updated for all 10 services.

2. **ProviderRegistryService**: ✅ DONE - Updated to use `PAGW_AURORA_WRITER_ENDPOINT` instead of `PAGW_AURORA_ENDPOINT`.

3. **Integration Tests**: Update any integration tests that rely on the old environment variable names.

4. **Local Development**: Update `docker-compose.local.yml` to use the new environment variable names.
