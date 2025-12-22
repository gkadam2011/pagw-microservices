# SQS Queue Parameters Configuration

This document describes the SQS queue parameters for the PAGW microservices pipeline, including the rationale for each configuration.

## Overview

All queues are **FIFO queues** with:
- KMS encryption at rest
- Long polling enabled (20 seconds)
- 14-day message retention
- 256KB max message size
- Shared DLQ (`pagw-dlq`) for failed messages

## Queue Parameter Summary

| # | Queue Name | Visibility Timeout | Max Receive Count | Consumer Service |
|---|------------|-------------------|-------------------|------------------|
| 0 | pagw-dlq | 300s (5 min) | N/A | DLQ (manual review) |
| 1 | pagw-orchestrator-queue | 300s (5 min) | 5 | pasorchestrator |
| 2 | pagw-request-parser-queue | 300s (5 min) | 5 | pasrequestparser |
| 3 | pagw-business-validator-queue | 300s (5 min) | 5 | pasbusinessvalidator |
| 4 | pagw-request-enricher-queue | 600s (10 min) | 7 | pasrequestenricher |
| 5 | pagw-attachment-handler-queue | 600s (10 min) | 5 | pasattachmenthandler |
| 6 | pagw-request-converter-queue | 300s (5 min) | 5 | pasrequestconverter |
| 7 | pagw-canonical-mapper-queue | 300s (5 min) | 5 | pascanonnicalmapper |
| 8 | pagw-api-connector-queue | 900s (15 min) | 8 | pasapiconnector |
| 9 | pagw-response-builder-queue | 300s (5 min) | 5 | pasresponsebuilder |
| 10 | pagw-callback-handler-queue | 600s (10 min) | 8 | pascallbackhandler |
| 11 | pagw-subscription-handler-queue | 600s (10 min) | 8 | passubscriptionhandler |
| 12 | pagw-orchestrator-complete-queue | 300s (5 min) | 5 | pasorchestrator |
| 13 | pagw-outbox-queue | 120s (2 min) | 3 | outboxpublisher |

## Message Flow

```
API Gateway
    |
    v
+------------------------+
| pagw-orchestrator      | --> Entry point for API requests
+------------------------+
    |
    v
+------------------------+
| pagw-request-parser    | --> FHIR bundle parsing
+------------------------+
    |
    v
+------------------------+
| pagw-business-         | --> Business rules validation
| validator              |
+------------------------+
    |
    v
+------------------------+
| pagw-request-enricher  | --> Data enrichment (external APIs)
|                        |     [600s timeout, 7 retries]
+------------------------+
    |
    v
+------------------------+
| pagw-attachment-       | --> S3 file uploads
| handler                |     [600s timeout]
+------------------------+
    |
    v
+------------------------+
| pagw-request-converter | --> Format conversion
+------------------------+
    |
    v
+------------------------+
| pagw-canonical-mapper  | --> FHIR to X12 278 transformation
+------------------------+
    |
    v
+------------------------+
| pagw-api-connector     | --> External payer API calls (CRITICAL)
|                        |     [900s timeout, 8 retries]
+------------------------+
    |
    v
+------------------------+
| pagw-response-builder  | --> X12 to FHIR response building
+------------------------+
    |
    +---------------------------+
    |                           |
    v                           v
+------------------------+  +------------------------+
| pagw-callback-handler  |  | pagw-subscription-     |
| [600s, 8 retries]      |  | handler [600s, 8]      |
+------------------------+  +------------------------+
    |                           |
    +-------------+-------------+
                  |
                  v
+------------------------+
| pagw-orchestrator-     | --> Workflow completion
| complete               |
+------------------------+

+------------------------+
| pagw-outbox            | --> Transactional outbox (all services)
|                        |     [120s timeout, 3 retries]
+------------------------+

+------------------------+
| pagw-dlq               | --> Dead Letter Queue (failed messages)
+------------------------+
```

## Parameter Rationale

### Standard Processing Queues (300s / 5 retries)

These queues handle CPU-bound or fast in-memory operations:

- **pagw-orchestrator-queue**: Entry point, sync processing
- **pagw-request-parser-queue**: FHIR bundle parsing (fast)
- **pagw-business-validator-queue**: Business rules validation (fast)
- **pagw-request-converter-queue**: Format conversion (CPU-bound)
- **pagw-canonical-mapper-queue**: FHIR to X12 278 transformation (CPU-bound)
- **pagw-response-builder-queue**: X12 to FHIR response building (CPU-bound)
- **pagw-orchestrator-complete-queue**: Workflow completion notification

### Extended Timeout Queues (600s / 5-8 retries)

These queues handle external API calls or I/O-bound operations:

#### pagw-request-enricher-queue (600s / 7 retries)
- Calls external enrichment APIs (member lookup, provider registry)
- External APIs may have variable response times
- Extra retries for transient network failures

#### pagw-attachment-handler-queue (600s / 5 retries)
- Handles large file uploads to S3
- File sizes can vary significantly
- Network latency for large transfers

#### pagw-callback-handler-queue (600s / 8 retries)
- Delivers async responses to client webhooks
- Client endpoints may be slow or temporarily unavailable
- More retries to ensure delivery

#### pagw-subscription-handler-queue (600s / 8 retries)
- Delivers FHIR Subscription notifications
- Similar to callback delivery - external webhook calls
- More retries for reliability

### High Timeout Queue (900s / 8 retries)

#### pagw-api-connector-queue (900s / 8 retries)
- **Critical**: Calls external payer APIs (Availity, Change Healthcare, etc.)
- Payer APIs can be **very slow** (up to 10+ minutes for complex requests)
- Network latency, rate limiting, and payer processing time
- More retries for transient failures and rate limit backoff

### Fast Processing Queue (120s / 3 retries)

#### pagw-outbox-queue (120s / 3 retries)
- Transactional outbox pattern - should be fast
- If messages consistently fail, there's a systemic issue
- Fewer retries to surface problems quickly for investigation
- DLQ messages need immediate attention

## Visibility Timeout Guidelines

The visibility timeout should be **at least 6x the expected processing time** to account for:
- Retries within the consumer
- Network latency
- Garbage collection pauses
- Cold starts

| Expected Processing | Recommended Visibility |
|--------------------|----------------------|
| < 30 seconds | 300s (5 min) |
| 30s - 2 min | 600s (10 min) |
| 2 - 5 min | 900s (15 min) |
| > 5 min | Consider async pattern |

## Max Receive Count Guidelines

| Scenario | Recommended Count | Rationale |
|----------|------------------|-----------|
| Internal processing | 5 | Standard retry policy |
| External APIs (enrichment) | 7 | Extra retries for transient failures |
| External APIs (payer/webhook) | 8 | Maximum retries before DLQ |
| Fast operations (outbox) | 3 | Surface issues quickly |

## Dead Letter Queue (DLQ) Strategy

All queues route failed messages to `pagw-dlq`:

1. **Retention**: 14 days for debugging
2. **Alerting**: CloudWatch alarm on DLQ message count > 0
3. **Investigation**: Use request correlation ID to trace failures
4. **Reprocessing**: Manual redrive after fixing issues

See [DLQ Runbook](../operations/dlq-runbook.md) for handling failed messages.

## LocalStack Configuration

For local development, queues are created in `infra/localstack-init.sh`:

```bash
awslocal sqs create-queue --queue-name pagw-orchestrator-queue
awslocal sqs create-queue --queue-name pagw-request-parser-queue
awslocal sqs create-queue --queue-name pagw-business-validator-queue
awslocal sqs create-queue --queue-name pagw-attachment-handler-queue
awslocal sqs create-queue --queue-name pagw-request-enricher-queue
awslocal sqs create-queue --queue-name pagw-request-converter-queue
awslocal sqs create-queue --queue-name pagw-canonical-mapper-queue
awslocal sqs create-queue --queue-name pagw-api-connector-queue
awslocal sqs create-queue --queue-name pagw-response-builder-queue
awslocal sqs create-queue --queue-name pagw-callback-handler-queue
awslocal sqs create-queue --queue-name pagw-subscription-handler-queue
awslocal sqs create-queue --queue-name pagw-orchestrator-complete-queue
awslocal sqs create-queue --queue-name pagw-outbox-queue
awslocal sqs create-queue --queue-name pagw-dlq
```

## Terraform Configuration

Queue definitions are in `pagwtf/sqs-v1.tf`:

```hcl
module "sqs_pagw_api_connector" {
  source  = "cps-terraform.anthem.com/CORP/terraform-aws-sqs/aws"
  version = "0.1.4"

  sqs_queue_name              = "${var.terraform_aws_environment}-${var.terraform_aws_application}-pagw-api-connector-queue.fifo"
  visibility_timeout_seconds  = "900"       # 15 min - external payer APIs
  message_retention_seconds   = "1209600"   # 14 days
  receive_wait_time_seconds   = "20"        # Long polling
  max_message_size            = 262144      # 256KB
  fifo_queue                  = "true"
  content_based_deduplication = "false"
  kms_data_key_reuse_period_seconds = 300

  redrive_policy = jsonencode({
    deadLetterTargetArn = module.sqs_pagw_dlq.sqs_queue_arn
    maxReceiveCount     = 8
  })
}
```

## Related Documentation

- [SQS Queue Architecture](sqs-queues.md) - Queue flow and message routing
- [DLQ Runbook](../operations/dlq-runbook.md) - Handling failed messages
- [Sequence Diagram](sequence_diagram.md) - End-to-end message flow