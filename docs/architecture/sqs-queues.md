# SQS Queues Configuration

## Queue Inventory

| Queue Name | Purpose | Consumer Service | DLQ |
|------------|---------|-----------------|-----|
| `pagw-queue-request-parser` | Parse incoming FHIR bundles | pasrequestparser | pagw-dlq-request-parser |
| `pagw-queue-attachment-handler` | Process claim attachments | pasattachmenthandler | pagw-dlq-attachment-handler |
| `pagw-queue-business-validator` | Validate against business rules | pasbusinessvalidator | pagw-dlq-business-validator |
| `pagw-queue-request-enricher` | Enrich with external data | pasrequestenricher | pagw-dlq-request-enricher |
| `pagw-queue-request-converter` | Convert to per-target payloads | pasrequestconverter | pagw-dlq-request-converter |
| `pagw-queue-api-connectors` | Connect to external APIs (Carelon/EAPI) | pasapiconnector | pagw-dlq-api-connectors |
| `pagw-queue-response-builder` | Build FHIR ClaimResponse | pasresponsebuilder | pagw-dlq-response-builder |
| `pagw-queue-callback-handler` | Handle async callbacks | pascallbackhandler | pagw-dlq-callback-handler |
| `pagw-queue-outbox-publisher` | Publish outbox entries | outboxpublisher | pagw-dlq-outbox-publisher |
| `pagw-queue-replay` | Manual replay queue (operator use) | N/A | N/A |

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

### API Connectors Queue (Special Settings)

```hcl
# Higher timeout for external API calls
visibility_timeout_seconds = 600       # 10 minutes
max_receive_count = 5                  # More retries for transient failures
```

## Message Flow

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                          PAGW MESSAGE FLOW                                    │
├──────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│   Lambda-Orchestrator                                                        │
│         │                                                                    │
│         ▼                                                                    │
│   ┌─────────────────────────┐                                               │
│   │  pagw-queue-request-    │                                               │
│   │       parser            │                                               │
│   └───────────┬─────────────┘                                               │
│               │                                                              │
│       ┌───────┴───────┐                                                     │
│       │               │                                                      │
│       ▼               ▼ (if attachments exist - PARALLEL)                   │
│   ┌─────────────────────────┐   ┌─────────────────────────┐                │
│   │  pagw-queue-business-   │   │  pagw-queue-attachment- │                │
│   │       validator         │   │       handler           │                │
│   └───────────┬─────────────┘   └─────────────────────────┘                │
│               │                  (ends here - parallel path)                │
│               ▼                                                              │
│   ┌─────────────────────────┐                                               │
│   │  pagw-queue-request-    │                                               │
│   │       enricher          │                                               │
│   └───────────┬─────────────┘                                               │
│               │                                                              │
│               ▼                                                              │
│   ┌─────────────────────────┐                                               │
│   │  pagw-queue-request-    │                                               │
│   │       converter         │                                               │
│   └───────────┬─────────────┘                                               │
│               │                                                              │
│               ▼                                                              │
│   ┌─────────────────────────┐                                               │
│   │  pagw-queue-api-        │                                               │
│   │       connectors        │                                               │
│   └───────────┬─────────────┘                                               │
│               │                                                              │
│               ▼ (always - per target architecture)                          │
│   ┌─────────────────────────┐                                               │
│   │  pagw-queue-callback-   │                                               │
│   │       handler           │                                               │
│   └───────────┬─────────────┘                                               │
│               │                                                              │
│               ▼                                                              │
│   ┌─────────────────────────┐                                               │
│   │  pagw-queue-response-   │                                               │
│   │       builder           │                                               │
│   └─────────────────────────┘                                               │
│                                                                              │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Flow Summary

| Step | Source Service | Target Queue | Condition |
|------|----------------|--------------|-----------|
| 1 | Lambda-Orchestrator | pagw-queue-request-parser | Always |
| 2 | RequestParser | pagw-queue-business-validator | Main flow (always) |
| 3 | RequestParser | pagw-queue-attachment-handler | If attachments exist (parallel) |
| 4 | BusinessValidator | pagw-queue-request-enricher | Validation passed |
| 5 | BusinessValidator | pagw-queue-response-builder | Validation failed (error response) |
| 6 | RequestEnricher | pagw-queue-request-converter | Always |
| 7 | RequestConverter | pagw-queue-api-connectors | Always |
| 8 | API-Connector | pagw-queue-callback-handler | Always (per target arch) |
| 9 | CallbackHandler | pagw-queue-response-builder | Always |

**Notes:**
- AttachmentHandler is a parallel side path - it processes attachments and stores metadata but does NOT route to the next stage in the main flow.
- Per target architecture, API-Connector always routes to callback-handler which normalizes both sync and async responses before forwarding to response-builder.

## Terraform Configuration

### Main Queue Module

```hcl
module "pagw_sqs_queue" {
  source = "./modules/sqs"
  
  for_each = toset([
    "request-parser",
    "attachment-handler",
    "business-validator",
    "request-enricher",
    "request-converter",
    "api-connectors",
    "response-builder",
    "callback-handler"
  ])
  
  name                       = "pagw-queue-${each.key}"
  visibility_timeout_seconds = each.key == "api-connectors" ? 600 : 300
  message_retention_seconds  = 1209600
  max_message_size          = 262144
  receive_wait_time_seconds = 20
  
  # Dead Letter Queue
  create_dlq        = true
  max_receive_count = each.key == "api-orchestrator" ? 5 : 3
  
  # Encryption
  kms_master_key_id = var.sqs_kms_key_id
  
  # Tags
  tags = {
    Environment = var.environment
    Service     = "pagw"
    Queue       = each.key
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
      "Resource": "arn:aws:sqs:us-east-1:ACCOUNT:pagw-queue-*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "kms:Decrypt"
      ],
      "Resource": "arn:aws:kms:us-east-1:ACCOUNT:key/KEY_ID"
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
      "Resource": "arn:aws:sqs:us-east-1:ACCOUNT:pagw-queue-*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "kms:GenerateDataKey",
        "kms:Decrypt"
      ],
      "Resource": "arn:aws:kms:us-east-1:ACCOUNT:key/KEY_ID"
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

### Grafana Dashboard

```yaml
panels:
  - title: Queue Depth
    type: graph
    targets:
      - expr: aws_sqs_approximate_number_of_messages_visible{queue_name=~"pagw-queue-.*", queue_name!~".*-dlq"}
        legendFormat: "{{ queue_name }}"
  
  - title: Processing Rate
    type: graph
    targets:
      - expr: rate(aws_sqs_number_of_messages_deleted{queue_name=~"pagw-queue-.*"}[5m])
        legendFormat: "{{ queue_name }}"
  
  - title: DLQ Messages
    type: stat
    targets:
      - expr: sum(aws_sqs_approximate_number_of_messages_visible{queue_name=~".*-dlq"})
```

## Troubleshooting

### Queue Backlog Growing

1. Check consumer service health
2. Review CloudWatch logs for errors
3. Check if max concurrent consumers reached
4. Verify database connection pool availability

### Messages Going to DLQ

1. Check DLQ for sample messages
2. Review error in request_tracker table
3. Check CloudWatch logs for exception details
4. See [DLQ Runbook](./dlq-runbook.md)

### High Message Age

1. Check if consumers are processing
2. Look for stuck messages (visibility timeout)
3. Verify SQS permissions for consumer IAM role
