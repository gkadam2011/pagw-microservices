# SQS Queues for PAGW - All encrypted with KMS

locals {
  # Define all queues with their configurations
  queues = {
    "request-parser" = {
      visibility_timeout = 300
      max_receive_count  = 3
    }
    "attachment-handler" = {
      visibility_timeout = 600  # Longer for file processing
      max_receive_count  = 3
    }
    "business-validator" = {
      visibility_timeout = 300
      max_receive_count  = 3
    }
    "request-enricher" = {
      visibility_timeout = 300
      max_receive_count  = 3
    }
    "canonical-mapper" = {
      visibility_timeout = 300
      max_receive_count  = 3
    }
    "api-orchestrator" = {
      visibility_timeout = 600  # Longer for external API calls
      max_receive_count  = 3
    }
    "callback-handler" = {
      visibility_timeout = 300
      max_receive_count  = 5  # More retries for callbacks
    }
    "response-builder" = {
      visibility_timeout = 300
      max_receive_count  = 3
    }
    "outbox-publisher" = {
      visibility_timeout = 60
      max_receive_count  = 3
    }
    "replay" = {
      visibility_timeout = 300
      max_receive_count  = 1  # Manual replay - no auto retry
    }
  }
}

# Dead Letter Queues
resource "aws_sqs_queue" "dlq" {
  for_each = local.queues
  
  name                       = "${var.app_name}-dlq-${each.key}-${var.environment}"
  message_retention_seconds  = var.dlq_message_retention_seconds
  
  # KMS encryption
  kms_master_key_id                 = aws_kms_key.sqs_key.id
  kms_data_key_reuse_period_seconds = 300
  
  tags = {
    Name    = "${var.app_name}-dlq-${each.key}-${var.environment}"
    Purpose = "Dead letter queue for ${each.key}"
    Type    = "DLQ"
  }
}

# Main Queues
resource "aws_sqs_queue" "main" {
  for_each = local.queues
  
  name                       = "${var.app_name}-queue-${each.key}-${var.environment}"
  visibility_timeout_seconds = each.value.visibility_timeout
  message_retention_seconds  = var.sqs_message_retention_seconds
  delay_seconds              = 0
  max_message_size           = 262144  # 256 KB
  receive_wait_time_seconds  = 20      # Long polling
  
  # KMS encryption
  kms_master_key_id                 = aws_kms_key.sqs_key.id
  kms_data_key_reuse_period_seconds = 300
  
  # Redrive policy to DLQ
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dlq[each.key].arn
    maxReceiveCount     = each.value.max_receive_count
  })
  
  tags = {
    Name    = "${var.app_name}-queue-${each.key}-${var.environment}"
    Purpose = "Main queue for ${each.key}"
    Type    = "MAIN"
  }
}

# Redrive allow policy for DLQs (allows main queue to send to DLQ)
resource "aws_sqs_queue_redrive_allow_policy" "dlq" {
  for_each = local.queues
  
  queue_url = aws_sqs_queue.dlq[each.key].id
  
  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [aws_sqs_queue.main[each.key].arn]
  })
}

# CloudWatch alarms for DLQ monitoring
resource "aws_cloudwatch_metric_alarm" "dlq_not_empty" {
  for_each = local.queues
  
  alarm_name          = "${var.app_name}-dlq-${each.key}-not-empty-${var.environment}"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "ApproximateNumberOfMessagesVisible"
  namespace           = "AWS/SQS"
  period              = 60
  statistic           = "Sum"
  threshold           = 0
  alarm_description   = "DLQ for ${each.key} has messages - requires investigation"
  
  dimensions = {
    QueueName = aws_sqs_queue.dlq[each.key].name
  }
  
  # Add SNS topic ARN for notifications
  # alarm_actions = [aws_sns_topic.alerts.arn]
  
  tags = {
    Name = "${var.app_name}-dlq-alarm-${each.key}-${var.environment}"
  }
}

# CloudWatch alarms for queue age monitoring
resource "aws_cloudwatch_metric_alarm" "queue_age" {
  for_each = local.queues
  
  alarm_name          = "${var.app_name}-queue-${each.key}-age-${var.environment}"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 3
  metric_name         = "ApproximateAgeOfOldestMessage"
  namespace           = "AWS/SQS"
  period              = 60
  statistic           = "Maximum"
  threshold           = 300  # 5 minutes
  alarm_description   = "Queue ${each.key} has old messages - possible processing issue"
  
  dimensions = {
    QueueName = aws_sqs_queue.main[each.key].name
  }
  
  tags = {
    Name = "${var.app_name}-queue-age-alarm-${each.key}-${var.environment}"
  }
}
