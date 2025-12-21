# DLQ Runbook - Dead Letter Queue Operations

## Overview

This runbook provides procedures for handling messages in Dead Letter Queues (DLQs) across the PAGW platform. Messages land in DLQs when they fail processing after maximum retry attempts.

## DLQ Inventory

| Main Queue | DLQ Name | Max Retries | Visibility Timeout |
|------------|----------|-------------|-------------------|
| pagw-queue-request-parser | pagw-queue-request-parser-dlq | 3 | 300s |
| pagw-queue-attachment-handler | pagw-queue-attachment-handler-dlq | 3 | 300s |
| pagw-queue-business-validator | pagw-queue-business-validator-dlq | 3 | 300s |
| pagw-queue-request-enricher | pagw-queue-request-enricher-dlq | 3 | 300s |
| pagw-queue-canonical-mapper | pagw-queue-canonical-mapper-dlq | 3 | 300s |
| pagw-queue-api-orchestrator | pagw-queue-api-orchestrator-dlq | 5 | 600s |
| pagw-queue-response-builder | pagw-queue-response-builder-dlq | 3 | 300s |
| pagw-queue-callback-handler | pagw-queue-callback-handler-dlq | 3 | 300s |

## Monitoring & Alerting

### CloudWatch Alarms

```yaml
# DLQ Message Count Alarm
DLQMessageCountAlarm:
  Type: AWS::CloudWatch::Alarm
  Properties:
    AlarmName: pagw-dlq-messages-present
    AlarmDescription: Messages present in DLQ
    MetricName: ApproximateNumberOfMessagesVisible
    Namespace: AWS/SQS
    Statistic: Sum
    Period: 60
    EvaluationPeriods: 1
    Threshold: 1
    ComparisonOperator: GreaterThanOrEqualToThreshold
    AlarmActions:
      - !Ref SNSAlertTopic
```

### Grafana Dashboard Queries

```promql
# DLQ message count by queue
aws_sqs_approximate_number_of_messages_visible{queue_name=~".*-dlq"}

# DLQ message age
aws_sqs_approximate_age_of_oldest_message{queue_name=~".*-dlq"}
```

## Triage Procedures

### Step 1: Identify the Issue

1. **Check DLQ message count**
   ```bash
   aws sqs get-queue-attributes \
     --queue-url https://sqs.us-east-1.amazonaws.com/ACCOUNT/pagw-queue-request-parser-dlq \
     --attribute-names ApproximateNumberOfMessagesVisible
   ```

2. **Receive sample messages (don't delete)**
   ```bash
   aws sqs receive-message \
     --queue-url https://sqs.us-east-1.amazonaws.com/ACCOUNT/pagw-queue-request-parser-dlq \
     --max-number-of-messages 1 \
     --visibility-timeout 0
   ```

3. **Extract pagwId from message**
   ```bash
   # Message body is JSON, extract pagwId
   echo $MESSAGE_BODY | jq -r '.pagwId'
   ```

### Step 2: Check Request Tracker

```sql
-- Get request status and error info
SELECT 
    pagw_id,
    status,
    last_stage,
    last_error_code,
    last_error_msg,
    created_at,
    updated_at
FROM request_tracker 
WHERE pagw_id = 'PAGW-20251220-XXXXXXXX';

-- Get event history
SELECT 
    event_type,
    status,
    processed_at,
    details
FROM event_tracker 
WHERE pagw_id = 'PAGW-20251220-XXXXXXXX'
ORDER BY processed_at;
```

### Step 3: Analyze Error Type

#### Common Error Categories

| Error Code | Category | Action |
|------------|----------|--------|
| `PARSE_FAILED` | Data Issue | Fix data, reprocess |
| `VALIDATION_ERROR` | Business Rule | Review rules, manual fix |
| `MISSING_NPI` | Data Issue | Enrich data, reprocess |
| `EXTERNAL_API_ERROR` | Transient | Retry after delay |
| `CONNECTION_TIMEOUT` | Infrastructure | Check connectivity, retry |
| `ENCRYPTION_ERROR` | Security | Check KMS permissions |
| `S3_ACCESS_DENIED` | IAM | Check bucket policies |

### Step 4: Resolution Actions

#### Option A: Reprocess Message

For transient errors or after fixing data:

```bash
# Move message back to main queue
aws sqs send-message \
  --queue-url https://sqs.us-east-1.amazonaws.com/ACCOUNT/pagw-queue-request-parser \
  --message-body "$MESSAGE_BODY" \
  --message-attributes '{"pagwId":{"DataType":"String","StringValue":"PAGW-20251220-XXXXXXXX"}}'

# Delete from DLQ
aws sqs delete-message \
  --queue-url https://sqs.us-east-1.amazonaws.com/ACCOUNT/pagw-queue-request-parser-dlq \
  --receipt-handle "$RECEIPT_HANDLE"
```

#### Option B: Manual Processing

For complex issues requiring manual intervention:

1. **Download the payload from S3**
   ```bash
   aws s3 cp s3://pagw-raw-dev/pagw/pas/raw/PAGW-202/PAGW-20251220-XXXXXXXX.json ./
   ```

2. **Fix the data locally**

3. **Re-submit through orchestrator**
   ```bash
   curl -X POST https://api.pagw.anthem.com/v1/claims \
     -H "Content-Type: application/json" \
     -H "X-Idempotency-Key: PAGW-20251220-XXXXXXXX-retry" \
     -d @fixed_claim.json
   ```

4. **Update request tracker**
   ```sql
   UPDATE request_tracker 
   SET status = 'MANUALLY_REPROCESSED',
       last_error_msg = 'Manually fixed and resubmitted'
   WHERE pagw_id = 'PAGW-20251220-XXXXXXXX';
   ```

#### Option C: Abandon Message

For unrecoverable errors:

```sql
-- Mark as abandoned
UPDATE request_tracker 
SET status = 'ABANDONED',
    last_error_code = 'UNRECOVERABLE',
    last_error_msg = 'Abandoned after manual review - reason: [REASON]'
WHERE pagw_id = 'PAGW-20251220-XXXXXXXX';
```

```bash
# Delete from DLQ
aws sqs delete-message \
  --queue-url https://sqs.us-east-1.amazonaws.com/ACCOUNT/pagw-queue-request-parser-dlq \
  --receipt-handle "$RECEIPT_HANDLE"
```

## Bulk Operations

### Redrive All Messages

Use AWS Console or CLI to redrive all messages from DLQ to main queue:

```bash
# Start redrive
aws sqs start-message-move-task \
  --source-arn arn:aws:sqs:us-east-1:ACCOUNT:pagw-queue-request-parser-dlq \
  --destination-arn arn:aws:sqs:us-east-1:ACCOUNT:pagw-queue-request-parser
```

### Purge DLQ (Use with Caution!)

Only after confirming all messages are unrecoverable:

```bash
aws sqs purge-queue \
  --queue-url https://sqs.us-east-1.amazonaws.com/ACCOUNT/pagw-queue-request-parser-dlq
```

## Automation Scripts

### DLQ Monitor Script

```python
#!/usr/bin/env python3
"""
DLQ Monitor - Check all PAGW DLQs and alert on messages present
"""
import boto3
import json

DLQS = [
    'pagw-queue-request-parser-dlq',
    'pagw-queue-attachment-handler-dlq',
    'pagw-queue-business-validator-dlq',
    'pagw-queue-request-enricher-dlq',
    'pagw-queue-canonical-mapper-dlq',
    'pagw-queue-api-orchestrator-dlq',
    'pagw-queue-response-builder-dlq',
    'pagw-queue-callback-handler-dlq',
]

def check_dlqs():
    sqs = boto3.client('sqs')
    issues = []
    
    for dlq_name in DLQS:
        url = sqs.get_queue_url(QueueName=dlq_name)['QueueUrl']
        attrs = sqs.get_queue_attributes(
            QueueUrl=url,
            AttributeNames=['ApproximateNumberOfMessagesVisible']
        )
        count = int(attrs['Attributes']['ApproximateNumberOfMessagesVisible'])
        
        if count > 0:
            issues.append({
                'queue': dlq_name,
                'count': count
            })
    
    return issues

if __name__ == '__main__':
    issues = check_dlqs()
    if issues:
        print("⚠️  DLQ ALERT - Messages found:")
        for issue in issues:
            print(f"  - {issue['queue']}: {issue['count']} messages")
    else:
        print("✅ All DLQs empty")
```

### Bulk Reprocess Script

```python
#!/usr/bin/env python3
"""
Bulk reprocess messages from DLQ
"""
import boto3
import json
import time

def reprocess_dlq(dlq_name, main_queue_name, max_messages=10):
    sqs = boto3.client('sqs')
    
    dlq_url = sqs.get_queue_url(QueueName=dlq_name)['QueueUrl']
    main_url = sqs.get_queue_url(QueueName=main_queue_name)['QueueUrl']
    
    processed = 0
    
    while processed < max_messages:
        response = sqs.receive_message(
            QueueUrl=dlq_url,
            MaxNumberOfMessages=10,
            WaitTimeSeconds=5
        )
        
        if 'Messages' not in response:
            break
        
        for msg in response['Messages']:
            # Send to main queue
            sqs.send_message(
                QueueUrl=main_url,
                MessageBody=msg['Body']
            )
            
            # Delete from DLQ
            sqs.delete_message(
                QueueUrl=dlq_url,
                ReceiptHandle=msg['ReceiptHandle']
            )
            
            processed += 1
            print(f"Reprocessed message {processed}")
        
        time.sleep(1)  # Rate limiting
    
    return processed

if __name__ == '__main__':
    import sys
    dlq = sys.argv[1] if len(sys.argv) > 1 else 'pagw-queue-request-parser-dlq'
    main = dlq.replace('-dlq', '')
    count = reprocess_dlq(dlq, main)
    print(f"Reprocessed {count} messages from {dlq}")
```

## Escalation

### Level 1 - On-Call Engineer
- Triage DLQ messages
- Perform standard reprocess/redrive operations
- Update request tracker status

### Level 2 - Platform Team
- Complex data fixes
- Infrastructure issues
- Configuration changes

### Level 3 - Architecture/Engineering Lead
- Design changes required
- External system integration issues
- Security-related failures

## Contact Information

| Role | Contact |
|------|---------|
| On-Call | pagw-oncall@anthem.com |
| Platform Team | pagw-platform@anthem.com |
| Security Team | security-ops@anthem.com |
| ServiceNow | Create PAGW ticket |
