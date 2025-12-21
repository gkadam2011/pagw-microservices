# PAS Subscription Handler

Handles FHIR R5-style Subscription notifications and client webhooks for delivering async Prior Authorization responses to subscribers.

## Overview

The Subscription Handler is the final stage in the async pipeline, responsible for:
- Managing subscription registrations
- Delivering notifications to webhook endpoints
- Supporting filter criteria for selective notifications
- Retry logic for failed deliveries

## Pipeline Position

```
SYNC FLOW:
  pasorchestrator → pasrequestparser → pasbusinessvalidator
                                              │
                                              ▼ (202 Accepted)
ASYNC FLOW:
  pasrequestenricher → pasattachmenthandler → pasrequestconverter
         │
         ▼
  pasapiconnector → pasresponsebuilder → pascallbackhandler → passubscriptionhandler
```

## Features

### Subscription Management
- Create/Read/Delete subscriptions via REST API
- Per-tenant subscription isolation
- Automatic expiration support
- Failure tracking with auto-disable

### Notification Delivery
- Webhook (REST) delivery
- Async delivery with retry logic
- Exponential backoff (1s, 2s, 4s)
- Custom headers support
- Content type: `id-only` or `full-resource`

### Filter Criteria
Subscriptions can filter which events to receive:
- `status=complete` - Only completed requests
- `outcome=queued,complete` - Multiple values
- `status=error&outcome=error` - Multiple criteria

## API Endpoints

### Create Subscription
```http
POST /api/v1/subscriptions
X-Tenant-Id: tenant123
Content-Type: application/json

{
  "endpoint": "https://client.example.com/webhook",
  "channelType": "rest-hook",
  "contentType": "full-resource",
  "filterCriteria": "status=complete",
  "headers": {
    "Authorization": "Bearer token123"
  },
  "expiresAt": "2025-12-31T23:59:59Z"
}
```

### List Subscriptions
```http
GET /api/v1/subscriptions
X-Tenant-Id: tenant123
```

### Delete Subscription
```http
DELETE /api/v1/subscriptions/{id}
```

## Notification Payload

### id-only (default)
```json
{
  "notificationId": "uuid",
  "pagwId": "PAGW-12345",
  "externalReferenceId": "client-ref-001",
  "status": "COMPLETED",
  "outcome": "complete",
  "resourceReference": "s3://bucket/202512/PAGW-12345/response/fhir_claim_response.json",
  "timestamp": "2025-12-21T10:30:00Z"
}
```

### full-resource
Includes all fields above plus:
```json
{
  "resourceContent": "{...full FHIR ClaimResponse bundle...}"
}
```

## Configuration

```yaml
pagw:
  aws:
    sqs:
      subscription-handler-queue: pagw-subscription-handler-queue
  subscription:
    webhook-timeout-seconds: 30
    max-retries: 3
    retry-backoff-multiplier: 2
```

## Database Schema

```sql
CREATE TABLE pagw.subscriptions (
    id VARCHAR(50) PRIMARY KEY,
    tenant_id VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'active',
    channel_type VARCHAR(20) NOT NULL DEFAULT 'rest-hook',
    endpoint VARCHAR(500) NOT NULL,
    content_type VARCHAR(20) DEFAULT 'id-only',
    headers JSONB,
    filter_criteria VARCHAR(500),
    expires_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    last_delivered_at TIMESTAMP WITH TIME ZONE,
    failure_count INTEGER DEFAULT 0,
    last_error TEXT
);

CREATE INDEX idx_subscriptions_tenant ON pagw.subscriptions(tenant_id, status);
```

## Local Development

```bash
# Start infrastructure
docker-compose -f docker-compose.infra.yml up -d

# Build and run
cd passubscriptionhandler/source
mvn clean install
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

## Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `PAGW_SQS_REQUEST_QUEUE` | Input queue URL | pagw-subscription-handler-queue |
| `PAGW_S3_REQUEST_BUCKET` | S3 bucket for responses | pagw-request-dev |
| `PAGW_AURORA_WRITER_ENDPOINT` | Aurora writer endpoint | localhost |
