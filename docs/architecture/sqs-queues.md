# SQS Queues Configuration

## Queue Inventory

| Queue Name | Purpose | Consumer Service | Producer Service | DLQ |
|------------|---------|-----------------|------------------|-----|
| `pagw-orchestrator-queue` | Initial request intake | pasorchestrator | External (API Gateway) | pagw-dlq |
| `pagw-request-parser-queue` | Parse incoming FHIR bundles | pasrequestparser | pasorchestrator | pagw-dlq |
| `pagw-attachment-handler-queue` | Process claim attachments | pasattachmenthandler | pasrequestparser | pagw-dlq |
| `pagw-business-validator-queue` | Validate against business rules | pasbusinessvalidator | pasrequestparser | pagw-dlq |
| `pagw-request-enricher-queue` | Enrich with external data | pasrequestenricher | pasbusinessvalidator | pagw-dlq |
| `pagw-request-converter-queue` | Convert to per-target payloads | pasrequestconverter | pasrequestenricher | pagw-dlq |
| `pagw-api-connector-queue` | Connect to external APIs (Carelon/EAPI) | pasapiconnector | pasrequestconverter | pagw-dlq |
| `pagw-callback-handler-queue` | Handle async callbacks | pascallbackhandler | pasapiconnector | pagw-dlq |
| `pagw-response-builder-queue` | Build FHIR ClaimResponse | pasresponsebuilder | pascallbackhandler | pagw-dlq |
| `pagw-subscription-handler-queue` | Handle FHIR subscriptions | passubscriptionhandler | pasresponsebuilder | pagw-dlq |
| `pagw-orchestrator-complete-queue` | Final response to orchestrator | pasorchestrator | pasresponsebuilder | pagw-dlq |
| `pagw-outbox-queue` | Outbox pattern publishing | outboxpublisher | All services (via DB) | pagw-dlq |
| `pagw-dlq` | Dead letter queue for failed messages | Operator/Manual | All queues | N/A |

## Queue Configuration

### Standard Settings (All Queues)

```hcl
visibility_timeout_seconds = 300       # 5 minutes (5x Lambda timeout)
message_retention_seconds  = 1209600   # 14 days
max_message_size          = 262144     # 256 KB
receive_wait_time_seconds = 20         # Long polling
delay_seconds             = 0          # No delay
```

### Dead Letter Queue Settings

```hcl
max_receive_count = 3                  # Retry 3 times before DLQ
dlq_message_retention_seconds = 1209600 # 14 days
```

### API Connector Queue (Special Settings)

```hcl
# Higher timeout for external API calls
visibility_timeout_seconds = 600       # 10 minutes
max_receive_count = 5                  # More retries for transient failures
```

## Environment-Specific Queue Names

Queue names include environment prefix in AWS:

| Environment | Queue Pattern | Example |
|-------------|---------------|---------|
| Local | `pagw-{service}-queue` | `pagw-request-parser-queue` |
| Dev | `dev-pagw-{service}-queue` | `dev-pagw-request-parser-queue` |
| Perf | `perf-pagw-{service}-queue` | `perf-pagw-request-parser-queue` |
| Prod | `prod-pagw-{service}-queue` | `prod-pagw-request-parser-queue` |

Configuration via environment variables:
```bash
PAGW_AWS_SQS_REQUEST_PARSER_QUEUE=dev-pagw-request-parser-queue
PAGW_AWS_SQS_BUSINESS_VALIDATOR_QUEUE=dev-pagw-business-validator-queue
# etc.
```

## Message Flow

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                          PAGW MESSAGE FLOW                                    │
├──────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│   API Gateway / External                                                     │
│         │                                                                    │
│         ▼                                                                    │
│   ┌─────────────────────────┐                                               │
│   │  pagw-orchestrator-     │ ← HTTP POST /api/v1/pas/submit                │
│   │       queue             │                                               │
│   └───────────┬─────────────┘                                               │
│               │                                                              │
│               ▼                                                              │
│   ┌─────────────────────────┐                                               │
│   │  pagw-request-parser-   │                                               │
│   │       queue             │                                               │
│   └───────────┬─────────────┘                                               │
│               │                                                              │
│       ┌───────┴───────┐                                                     │
│       │               │                                                      │
│       ▼               ▼ (if attachments exist - PARALLEL)                   │
│   ┌─────────────────────────┐   ┌─────────────────────────┐                │
│   │  pagw-business-         │   │  pagw-attachment-       │                │
│   │  validator-queue        │   │  handler-queue          │                │
│   └───────────┬─────────────┘   └─────────────────────────┘                │
│               │                  (ends here - parallel path)                │
│               ▼                                                              │
│   ┌─────────────────────────┐                                               │
│   │  pagw-request-enricher- │                                               │
│   │       queue             │                                               │
│   └───────────┬─────────────┘                                               │
│               │                                                              │
│               ▼                                                              │
│   ┌─────────────────────────┐                                               │
│   │  pagw-request-converter-│                                               │
│   │       queue             │                                               │
│   └───────────┬─────────────┘                                               │
│               │                                                              │
│               ▼                                                              │
│   ┌─────────────────────────┐                                               │
│   │  pagw-api-connector-    │                                               │
│   │       queue             │                                               │
│   └───────────┬─────────────┘                                               │
│               │                                                              │
│               ▼                                                              │
│   ┌─────────────────────────┐                                               │
│   │  pagw-callback-handler- │                                               │
│   │       queue             │                                               │
│   └───────────┬─────────────┘                                               │
│               │                                                              │
│               ▼                                                              │
│   ┌─────────────────────────┐                                               │
│   │  pagw-response-builder- │                                               │
│   │       queue             │                                               │
│   └───────────┬─────────────┘                                               │
│               │                                                              │
│       ┌───────┴───────┐                                                     │
│       │               │                                                      │
│       ▼               ▼ (if subscription exists)                            │
│   ┌─────────────────────────┐   ┌─────────────────────────┐                │
│   │  pagw-orchestrator-     │   │  pagw-subscription-     │                │
│   │  complete-queue         │   │  handler-queue          │                │
│   └─────────────────────────┘   └─────────────────────────┘                │
│                                                                              │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Flow Summary

| Step | Source Service | Target Queue | Condition |
|------|----------------|--------------|-----------|
| 1 | API Gateway | pagw-orchestrator-queue | Always (HTTP request) |
| 2 | Orchestrator | pagw-request-parser-queue | Always |
| 3 | RequestParser | pagw-business-validator-queue | Main flow (always) |
| 4 | RequestParser | pagw-attachment-handler-queue | If attachments exist (parallel) |
| 5 | BusinessValidator | pagw-request-enricher-queue | Validation passed |
| 6 | BusinessValidator | pagw-response-builder-queue | Validation failed (error response) |
| 7 | RequestEnricher | pagw-request-converter-queue | Always |
| 8 | RequestConverter | pagw-api-connector-queue | Always |
| 9 | ApiConnector | pagw-callback-handler-queue | Always (per target arch) |
| 10 | CallbackHandler | pagw-response-builder-queue | Always |
| 11 | ResponseBuilder | pagw-orchestrator-complete-queue | Always |
| 12 | ResponseBuilder | pagw-subscription-handler-queue | If subscription exists |

**Notes:**
- AttachmentHandler is a parallel side path - it processes attachments and stores metadata but does NOT route to the next stage in the main flow.
- Per target architecture, ApiConnector always routes to callback-handler which normalizes both sync and async responses before forwarding to response-builder.
- All services use the **Outbox Pattern** - messages are written to the database first, then published by outboxpublisher service.

## Terraform Configuration

### Main Queue Module

```hcl
module "pagw_sqs_queue" {
  source = "./modules/sqs"
  
  for_each = toset([
    "orchestrator",
    "request-parser",
    "attachment-handler",
    "business-validator",
    "request-enricher",
    "request-converter",
    "api-connector",
    "callback-handler",
    "response-builder",
    "subscription-handler",
    "orchestrator-complete",
    "outbox"
  ])
  
  name                       = "${var.environment}-pagw-${each.key}-queue"
  visibility_timeout_seconds = each.key == "api-connector" ? 600 : 300
  message_retention_seconds  = 1209600
  max_message_size          = 262144
  receive_wait_time_seconds = 20
  
  # Dead Letter Queue (shared)
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.pagw_dlq.arn
    maxReceiveCount     = each.key == "api-connector" ? 5 : 3
  })
  
  # Encryption
  kms_master_key_id = var.sqs_kms_key_id
  
  # Tags
  tags = {
    Environment = var.environment
    Service     = "pagw"
    Queue       = each.key
  }
}

# Shared Dead Letter Queue
resource "aws_sqs_queue" "pagw_dlq" {
  name                      = "${var.environment}-pagw-dlq"
  message_retention_seconds = 1209600  # 14 days
  kms_master_key_id         = var.sqs_kms_key_id
  
  tags = {
    Environment = var.environment
    Service     = "pagw"
    Type        = "dlq"
  }
}
```

### IAM Policies

#### Service Consumer Policy

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "sqs:ReceiveMessage",
        "sqs:DeleteMessage",
        "sqs:GetQueueAttributes",
        "sqs:ChangeMessageVisibility"
      ],
      "Resource": "arn:aws:sqs:us-east-2:ACCOUNT:*-pagw-*-queue"
    },
    {
      "Effect": "Allow",
      "Action": [
        "kms:Decrypt"
      ],
      "Resource": "arn:aws:kms:us-east-2:ACCOUNT:key/KEY_ID"
    }
  ]
}
```

#### Outbox Publisher Policy

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
      "Resource": "arn:aws:sqs:us-east-2:ACCOUNT:*-pagw-*-queue"
    },
    {
      "Effect": "Allow",
      "Action": [
        "kms:GenerateDataKey",
        "kms:Decrypt"
      ],
      "Resource": "arn:aws:kms:us-east-2:ACCOUNT:key/KEY_ID"
    }
  ]
}
```

## Monitoring

### CloudWatch Metrics

| Metric | Alarm Threshold | Description |
|--------|-----------------|-------------|
| `ApproximateNumberOfMessagesVisible` | > 1000 | Queue backlog |
| `ApproximateAgeOfOldestMessage` | > 3600 | Message age > 1 hour |
| `NumberOfMessagesReceived` | < 1 (5 min) | No processing activity |
| `ApproximateNumberOfMessagesVisible` (DLQ) | > 0 | DLQ has messages |

### CloudWatch Alarms

```hcl
resource "aws_cloudwatch_metric_alarm" "pagw_dlq_alarm" {
  alarm_name          = "${var.environment}-pagw-dlq-messages"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "ApproximateNumberOfMessagesVisible"
  namespace           = "AWS/SQS"
  period              = 300
  statistic           = "Sum"
  threshold           = 0
  alarm_description   = "Messages in PAGW DLQ require investigation"
  
  dimensions = {
    QueueName = "${var.environment}-pagw-dlq"
  }
  
  alarm_actions = [var.sns_alerts_topic_arn]
}
```

### Grafana Dashboard

```yaml
panels:
  - title: Queue Depth
    type: graph
    targets:
      - expr: aws_sqs_approximate_number_of_messages_visible{queue_name=~".*pagw.*queue", queue_name!~".*dlq"}
        legendFormat: "{{ queue_name }}"
  
  - title: Processing Rate
    type: graph
    targets:
      - expr: rate(aws_sqs_number_of_messages_deleted{queue_name=~".*pagw.*queue"}[5m])
        legendFormat: "{{ queue_name }}"
  
  - title: DLQ Messages
    type: stat
    targets:
      - expr: sum(aws_sqs_approximate_number_of_messages_visible{queue_name=~".*pagw.*dlq"})
```

## Troubleshooting

### Queue Backlog Growing

1. Check consumer service health (`kubectl get pods -l app=pas*`)
2. Review CloudWatch logs for errors
3. Check if max concurrent consumers reached
4. Verify database connection pool availability
5. Check outboxpublisher service status (if messages not being published)

### Messages Going to DLQ

1. Check DLQ for sample messages:
   ```bash
   aws sqs receive-message --queue-url $DLQ_URL --max-number-of-messages 1
   ```
2. Review error in `request_tracker` table
3. Check CloudWatch logs for exception details
4. Common causes:
   - JSON parsing errors (malformed FHIR)
   - Database constraint violations
   - External API timeouts
   - Missing required fields
5. See [DLQ Runbook](../operations/dlq-runbook.md)

### High Message Age

1. Check if consumers are processing
2. Look for stuck messages (visibility timeout issues)
3. Verify SQS permissions for consumer IAM role
4. Check if outboxpublisher is running

### Outbox Not Publishing

1. Check outboxpublisher service logs
2. Verify `outbox_entries` table has pending entries:
   ```sql
   SELECT * FROM pagw.outbox_entries 
   WHERE status = 'PENDING' 
   ORDER BY created_at;
   ```
3. Check ShedLock table for stuck locks:
   ```sql
   SELECT * FROM pagw.shedlock WHERE name = 'outbox-publisher';
   ```

## Service Configuration Reference

Each service configures its queue listener via application properties:

```yaml
# application-dev.yml example
pagw:
  aws:
    sqs:
      request-parser-queue: dev-pagw-request-parser-queue
      business-validator-queue: dev-pagw-business-validator-queue
      attachment-handler-queue: dev-pagw-attachment-handler-queue
      request-enricher-queue: dev-pagw-request-enricher-queue
      request-converter-queue: dev-pagw-request-converter-queue
      api-connector-queue: dev-pagw-api-connector-queue
      callback-handler-queue: dev-pagw-callback-handler-queue
      response-builder-queue: dev-pagw-response-builder-queue
      subscription-handler-queue: dev-pagw-subscription-handler-queue
```
