# PAGW SQS Queue Architecture

## Overview

The PAGW microservices use AWS SQS FIFO queues for reliable, ordered message passing between services. All queues use the FIFO queue type to ensure exactly-once processing and message ordering.

**AWS Account:** `482754295601`  
**AWS Region:** `us-east-2`  
**Queue Naming Pattern:** `dev-PAGW-pagw-{service}-queue.fifo`

## Queue Inventory

| Queue Name | Purpose | Producer | Consumer |
|------------|---------|----------|----------|
| `dev-PAGW-pagw-orchestrator-queue.fifo` | Incoming PA requests | External/API | pasorchestrator |
| `dev-PAGW-pagw-orchestrator-complete-queue.fifo` | Orchestration completion | Internal | pasorchestrator |
| `dev-PAGW-pagw-request-parser-queue.fifo` | Raw request parsing | pasorchestrator | pasrequestparser |
| `dev-PAGW-pagw-business-validator-queue.fifo` | Business validation | pasrequestparser | pasbusinessvalidator |
| `dev-PAGW-pagw-request-enricher-queue.fifo` | Data enrichment | pasbusinessvalidator | pasrequestenricher |
| `dev-PAGW-pagw-request-converter-queue.fifo` | FHIR conversion | pasrequestenricher | pasrequestconverter |
| `dev-PAGW-pagw-api-connector-queue.fifo` | Payer API submission | pasrequestconverter | pasapiconnector |
| `dev-PAGW-pagw-callback-handler-queue.fifo` | Callback processing | External | pascallbackhandler |
| `dev-PAGW-pagw-response-builder-queue.fifo` | Response building | pasapiconnector | pasresponsebuilder |
| `dev-PAGW-pagw-subscription-handler-queue.fifo` | Subscription events | External | passubscriptionhandler |
| `dev-PAGW-pagw-attachment-handler-queue.fifo` | Attachment processing | Multiple | pasattachmenthandler |
| `dev-PAGW-pagw-outbox-publisher-queue.fifo` | Outbox event publishing | Internal | outboxpublisher |

## Message Flow

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                                    PA REQUEST FLOW                                          │
└─────────────────────────────────────────────────────────────────────────────────────────────┘

  External Request
        │
        ▼
┌───────────────────┐     orchestrator-queue.fifo      ┌───────────────────┐
│   API Gateway     │ ────────────────────────────────▶│  pasorchestrator  │
└───────────────────┘                                  └───────────────────┘
                                                               │
                                                               ▼
                                                 request-parser-queue.fifo
                                                               │
                                                               ▼
                                                 ┌───────────────────┐
                                                 │ pasrequestparser  │
                                                 └───────────────────┘
                                                               │
                                                               ▼
                                               business-validator-queue.fifo
                                                               │
                                                               ▼
                                               ┌─────────────────────────┐
                                               │ pasbusinessvalidator    │
                                               └─────────────────────────┘
                                                               │
                                                               ▼
                                               request-enricher-queue.fifo
                                                               │
                                                               ▼
                                               ┌───────────────────────┐
                                               │ pasrequestenricher    │
                                               └───────────────────────┘
                                                               │
                                                               ▼
                                               request-converter-queue.fifo
                                                               │
                                                               ▼
                                               ┌───────────────────────┐
                                               │ pasrequestconverter   │
                                               └───────────────────────┘
                                                               │
                                                               ▼
                                               api-connector-queue.fifo
                                                               │
                                                               ▼
                                               ┌───────────────────────┐
                                               │  pasapiconnector      │
                                               └───────────────────────┘
                                                               │
                                                               ▼
                                               response-builder-queue.fifo
                                                               │
                                                               ▼
                                               ┌───────────────────────┐
                                               │  pasresponsebuilder   │
                                               └───────────────────────┘
```

---

## AWS SQS URLs by Service

### 1. pasorchestrator

**Input Queue:**
```
https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-orchestrator-queue.fifo
```

**Output/Complete Queue:**
```
https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-orchestrator-complete-queue.fifo
```

**Next Service Queue (Request Parser):**
```
https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-request-parser-queue.fifo
```

**Environment Variables:**
```yaml
SQS_REQUEST_QUEUE_URL: https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-orchestrator-queue.fifo
SQS_RESPONSE_QUEUE_URL: https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-orchestrator-complete-queue.fifo
SQS_REQUEST_PARSER_QUEUE_URL: https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-request-parser-queue.fifo
```

---

### 2. pasrequestparser

**Input Queue:**
```
https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-request-parser-queue.fifo
```

**Output Queue (Business Validator):**
```
https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-business-validator-queue.fifo
```

**Environment Variables:**
```yaml
SQS_REQUEST_QUEUE_URL: https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-request-parser-queue.fifo
SQS_RESPONSE_QUEUE_URL: https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-business-validator-queue.fifo
```

---

### 3. pasbusinessvalidator

**Input Queue:**
```
https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-business-validator-queue.fifo
```

**Output Queue (Request Enricher):**
```
https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-request-enricher-queue.fifo
```

**Environment Variables:**
```yaml
SQS_REQUEST_QUEUE_URL: https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-business-validator-queue.fifo
SQS_RESPONSE_QUEUE_URL: https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-request-enricher-queue.fifo
```

---

### 4. pasrequestenricher

**Input Queue:**
```
https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-request-enricher-queue.fifo
```

**Output Queue (Request Converter):**
```
https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-request-converter-queue.fifo
```

**Environment Variables:**
```yaml
SQS_REQUEST_QUEUE_URL: https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-request-enricher-queue.fifo
SQS_RESPONSE_QUEUE_URL: https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-request-converter-queue.fifo
```

---

### 5. pasrequestconverter

**Input Queue:**
```
https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-request-converter-queue.fifo
```

**Output Queue (API Connector):**
```
https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-api-connector-queue.fifo
```

**Environment Variables:**
```yaml
SQS_REQUEST_QUEUE_URL: https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-request-converter-queue.fifo
SQS_RESPONSE_QUEUE_URL: https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-api-connector-queue.fifo
```

---

### 6. pasapiconnector

**Input Queue:**
```
https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-api-connector-queue.fifo
```

**Output Queue (Response Builder):**
```
https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-response-builder-queue.fifo
```

**Environment Variables:**
```yaml
SQS_REQUEST_QUEUE_URL: https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-api-connector-queue.fifo
SQS_RESPONSE_QUEUE_URL: https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-response-builder-queue.fifo
```

---

### 7. pascallbackhandler

**Input Queue:**
```
https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-callback-handler-queue.fifo
```

**Output Queue (Response Builder):**
```
https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-response-builder-queue.fifo
```

**Environment Variables:**
```yaml
SQS_REQUEST_QUEUE_URL: https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-callback-handler-queue.fifo
SQS_RESPONSE_QUEUE_URL: https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-response-builder-queue.fifo
```

---

### 8. pasresponsebuilder

**Input Queue:**
```
https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-response-builder-queue.fifo
```

**Output Queue (Orchestrator Complete):**
```
https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-orchestrator-complete-queue.fifo
```

**Environment Variables:**
```yaml
SQS_REQUEST_QUEUE_URL: https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-response-builder-queue.fifo
SQS_RESPONSE_QUEUE_URL: https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-orchestrator-complete-queue.fifo
```

---

### 9. passubscriptionhandler

**Input Queue:**
```
https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-subscription-handler-queue.fifo
```

**Output Queue (Response Builder):**
```
https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-response-builder-queue.fifo
```

**Environment Variables:**
```yaml
SQS_REQUEST_QUEUE_URL: https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-subscription-handler-queue.fifo
SQS_RESPONSE_QUEUE_URL: https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-response-builder-queue.fifo
```

---

### 10. pasattachmenthandler

**Input Queue:**
```
https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-attachment-handler-queue.fifo
```

**Output Queue (Response Builder):**
```
https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-response-builder-queue.fifo
```

**Environment Variables:**
```yaml
SQS_REQUEST_QUEUE_URL: https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-attachment-handler-queue.fifo
SQS_RESPONSE_QUEUE_URL: https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-response-builder-queue.fifo
```

---

### 11. outboxpublisher

**Input Queue:**
```
https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-outbox-publisher-queue.fifo
```

**Environment Variables:**
```yaml
SQS_REQUEST_QUEUE_URL: https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-outbox-publisher-queue.fifo
```

---

## Environment Variable Mapping

| Environment Variable | Description | Example Value |
|---------------------|-------------|---------------|
| `SQS_REQUEST_QUEUE_URL` | Queue URL for incoming messages | `https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-{service}-queue.fifo` |
| `SQS_RESPONSE_QUEUE_URL` | Queue URL for outgoing messages | `https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-{next-service}-queue.fifo` |
| `AWS_REGION` | AWS region for SQS | `us-east-2` |

---

## Kubernetes Helm Values Configuration

Each service's queue URLs are configured in the Helm values files at `pagwk8s/{service}/values-dev.yaml`:

```yaml
# Example from pasorchestrator/values-dev.yaml
env:
  normal:
    SQS_REQUEST_QUEUE_URL: "https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-orchestrator-queue.fifo"
    SQS_RESPONSE_QUEUE_URL: "https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-orchestrator-complete-queue.fifo"
    SQS_REQUEST_PARSER_QUEUE_URL: "https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-request-parser-queue.fifo"
```

---

## Queue Configuration Settings

All PAGW SQS queues use the following configuration:

| Setting | Value | Description |
|---------|-------|-------------|
| Queue Type | FIFO | Ensures exactly-once processing and message ordering |
| Content-Based Deduplication | Enabled | Uses message body SHA-256 hash for deduplication |
| Message Retention | 14 days | Messages retained for 14 days if not processed |
| Visibility Timeout | 30 seconds | Time message is hidden after being received |
| Maximum Message Size | 256 KB | Maximum size of message body |

---

## Troubleshooting

### Common Issues

1. **Message stuck in queue**
   - Check service logs for processing errors
   - Verify IAM permissions for the service pod
   - Check dead-letter queue for failed messages

2. **Duplicate message processing**
   - Ensure FIFO queue configuration is correct
   - Verify `MessageGroupId` is being set correctly
   - Check for `MessageDeduplicationId` conflicts

3. **Message ordering issues**
   - Confirm `MessageGroupId` strategy (per-request vs global)
   - Verify FIFO queue type (`.fifo` suffix)

### AWS CLI Commands

```bash
# List messages in queue (without consuming)
aws sqs get-queue-attributes \
  --queue-url https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-orchestrator-queue.fifo \
  --attribute-names ApproximateNumberOfMessages

# Receive messages (for debugging)
aws sqs receive-message \
  --queue-url https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-orchestrator-queue.fifo \
  --max-number-of-messages 1

# Purge queue (CAUTION: removes all messages)
aws sqs purge-queue \
  --queue-url https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-orchestrator-queue.fifo
```
