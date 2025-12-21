# DynamoDB Tables for PAGW

# Idempotency table for request deduplication
resource "aws_dynamodb_table" "idempotency" {
  name         = "${var.app_name}-idempotency-${var.environment}"
  billing_mode = "PAY_PER_REQUEST"  # On-demand capacity for variable workloads
  hash_key     = "idempotencyKey"

  attribute {
    name = "idempotencyKey"
    type = "S"
  }

  # TTL for automatic cleanup of old entries
  ttl {
    attribute_name = "expiresAt"
    enabled        = true
  }

  # Point-in-time recovery for data protection
  point_in_time_recovery {
    enabled = var.environment == "prod" ? true : false
  }

  # Server-side encryption
  server_side_encryption {
    enabled     = true
    kms_key_arn = aws_kms_key.dynamodb_key.arn
  }

  tags = {
    Name        = "${var.app_name}-idempotency-${var.environment}"
    Purpose     = "Request idempotency tracking"
    Environment = var.environment
  }
}

# KMS key for DynamoDB encryption
resource "aws_kms_key" "dynamodb_key" {
  description             = "KMS key for PAGW DynamoDB encryption"
  deletion_window_in_days = var.kms_deletion_window_days
  enable_key_rotation     = true

  tags = {
    Name        = "${var.app_name}-dynamodb-key-${var.environment}"
    Purpose     = "DynamoDB encryption"
    Environment = var.environment
  }
}

resource "aws_kms_alias" "dynamodb_key_alias" {
  name          = "alias/${var.app_name}-dynamodb-${var.environment}"
  target_key_id = aws_kms_key.dynamodb_key.key_id
}

# Global Secondary Index for querying by tenant (optional, uncomment if needed)
# resource "aws_dynamodb_table" "idempotency_gsi" {
#   # Add GSI for tenant-based queries if needed
# }
