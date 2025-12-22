# SQS Queue Parameters Configuration

This document describes the SQS queue parameters for the PAGW PAS microservices pipeline, including the rationale for each configuration.

## Overview

All queues are **FIFO queues** with:
- KMS encryption at rest
- Long polling enabled (20 seconds)
- 14-day message retention
- 256KB max message size
- Shared DLQ (`pas-error-dlq`) for failed messages

## Queue Parameter Summary

| # | Queue Name | Visibility Timeout | Max Receive Count | Use Case |
|---|------------|-------------------|-------------------|----------|
| 0 | pas-error-dlq | 300s (5 min) | N/A | Dead Letter Queue |
| 1 | pas-api-orchestrator-queue | 300s (5 min) | 5 | API entry point |
| 2 | pas-request-validator | 300s (5 min) | 5 | FHIR validation |
| 3 | pas-business-validator | 300s (5 min) | 5 | Business rules |
| 4 | pas-request-enricher | 600s (10 min) | 7 | External enrichment APIs |
| 5 | pagw-pas-attachment-handler | 600s (10 min) | 5 | S3 file uploads |
| 6 | pas-canonical-mapper-queue | 300s (5 min) | 5 | FHIR→X12 transform |
| 7 | pas-payer-router-queue | 300s (5 min) | 5 | Payer routing |
| 8 | pas-response-builder-queue | 300s (5 min) | 5 | X12→FHIR response |
| 9 | pagw-callback-queue | 600s (10 min) | 8 | Async callbacks |
| 10 | pas-orchestrator-complete | 300s (5 min) | 5 | Workflow completion |
| 11 | pas-notification-queue | 300s (5 min) | 5 | Status notifications |
| 12 | pagw-request-parser-queue | 300s (5 min) | 5 | FHIR bundle parsing |
| 13 | pagw-request-converter-queue | 300s (5 min) | 5 | Format conversion |
| 14 | pagw-api-connector-queue | 900s (15 min) | 8 | External payer APIs |
| 15 | pagw-subscription-handler-queue | 600s (10 min) | 8 | FHIR Subscriptions |
| 16 | pagw-outbox-queue | 120s (2 min) | 3 | Transactional outbox |
| 17 | pagw-callback-handler-queue | 600s (10 min) | 8 | Client webhooks |

## Parameter Rationale

### Standard Processing Queues (300s / 5 retries)

These queues handle CPU-bound or fast in-memory operations:

- **pas-api-orchestrator-queue**: Entry point, sync processing
- **pas-request-validator**: FHIR schema validation (fast)
- **pas-business-validator**: Business rules validation (fast)
- **pas-canonical-mapper-queue**: FHIR to X12 278 transformation (CPU-bound)
- **pas-payer-router-queue**: Payer endpoint routing (database lookup)
- **pas-response-builder-queue**: X12 to FHIR response building (CPU-bound)
- **pas-orchestrator-complete**: Workflow completion notification
- **pas-notification-queue**: Status update notifications
- **pagw-request-parser-queue**: FHIR bundle parsing
- **pagw-request-converter-queue**: Format conversion

### Extended Timeout Queues (600s / 5-8 retries)

These queues handle external API calls or I/O-bound operations:

#### pas-request-enricher (600s / 7 retries)
- Calls external enrichment APIs (member lookup, provider registry)
- External APIs may have variable response times
- Extra retries for transient network failures

#### pagw-pas-attachment-handler (600s / 5 retries)
- Handles large file uploads to S3
- File sizes can vary significantly
- Network latency for large transfers

#### pagw-callback-queue (600s / 8 retries)
- Delivers async responses to client webhooks
- Client endpoints may be slow or temporarily unavailable
- More retries to ensure delivery

#### pagw-subscription-handler-queue (600s / 8 retries)
- Delivers FHIR Subscription notifications
- Similar to callback delivery - external webhook calls
- More retries for reliability

#### pagw-callback-handler-queue (600s / 8 retries)
- Processes callback requests
- Client webhook endpoints may have variable latency
- More retries for transient failures

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

All queues route failed messages to `pas-error-dlq`:

1. **Retention**: 14 days for debugging
2. **Alerting**: CloudWatch alarm on DLQ message count > 0
3. **Investigation**: Use request correlation ID to trace failures
4. **Reprocessing**: Manual redrive after fixing issues

See [DLQ Runbook](../operations/dlq-runbook.md) for handling failed messages.

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
    deadLetterTargetArn = module.sqs_pagw_pas_error_dlq.sqs_queue_arn
    maxReceiveCount     = 8
  })
}
```

## Related Documentation

- [SQS Queue Architecture](sqs-queues.md) - Queue flow and message routing
- [DLQ Runbook](../operations/dlq-runbook.md) - Handling failed messages
- [Sequence Diagram](sequence_diagram.md) - End-to-end message flow
