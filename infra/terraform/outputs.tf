# Terraform Outputs for PAGW Infrastructure

# KMS Keys
output "kms_s3_key_arn" {
  description = "ARN of KMS key for S3 encryption"
  value       = aws_kms_key.s3_key.arn
}

output "kms_s3_key_alias" {
  description = "Alias of KMS key for S3 encryption"
  value       = aws_kms_alias.s3_key_alias.name
}

output "kms_sqs_key_arn" {
  description = "ARN of KMS key for SQS encryption"
  value       = aws_kms_key.sqs_key.arn
}

output "kms_rds_key_arn" {
  description = "ARN of KMS key for RDS encryption"
  value       = aws_kms_key.rds_key.arn
}

output "kms_phi_field_key_arn" {
  description = "ARN of KMS key for field-level PHI encryption"
  value       = aws_kms_key.phi_field_key.arn
}

output "kms_phi_field_key_alias" {
  description = "Alias of KMS key for field-level PHI encryption"
  value       = aws_kms_alias.phi_field_key_alias.name
}

# S3 Buckets
output "s3_raw_bucket_name" {
  description = "Name of S3 bucket for raw requests"
  value       = aws_s3_bucket.raw.id
}

output "s3_raw_bucket_arn" {
  description = "ARN of S3 bucket for raw requests"
  value       = aws_s3_bucket.raw.arn
}

output "s3_enriched_bucket_name" {
  description = "Name of S3 bucket for enriched data"
  value       = aws_s3_bucket.enriched.id
}

output "s3_final_bucket_name" {
  description = "Name of S3 bucket for final responses"
  value       = aws_s3_bucket.final.id
}

output "s3_attachments_bucket_name" {
  description = "Name of S3 bucket for attachments"
  value       = aws_s3_bucket.attachments.id
}

# SQS Queues
output "sqs_queue_urls" {
  description = "Map of SQS queue names to URLs"
  value = {
    for k, v in aws_sqs_queue.main : k => v.url
  }
}

output "sqs_queue_arns" {
  description = "Map of SQS queue names to ARNs"
  value = {
    for k, v in aws_sqs_queue.main : k => v.arn
  }
}

output "sqs_dlq_urls" {
  description = "Map of DLQ names to URLs"
  value = {
    for k, v in aws_sqs_queue.dlq : k => v.url
  }
}

output "sqs_dlq_arns" {
  description = "Map of DLQ names to ARNs"
  value = {
    for k, v in aws_sqs_queue.dlq : k => v.arn
  }
}

# Aurora/RDS
output "aurora_cluster_endpoint" {
  description = "Aurora cluster writer endpoint"
  value       = aws_rds_cluster.aurora.endpoint
}

output "aurora_cluster_reader_endpoint" {
  description = "Aurora cluster reader endpoint"
  value       = aws_rds_cluster.aurora.reader_endpoint
}

output "aurora_cluster_id" {
  description = "Aurora cluster identifier"
  value       = aws_rds_cluster.aurora.id
}

output "rds_proxy_endpoint" {
  description = "RDS Proxy endpoint (use this for application connections)"
  value       = aws_db_proxy.aurora.endpoint
}

output "db_credentials_secret_arn" {
  description = "ARN of Secrets Manager secret containing DB credentials"
  value       = aws_secretsmanager_secret.db_credentials.arn
}

# Security Groups
output "aurora_security_group_id" {
  description = "ID of Aurora security group"
  value       = aws_security_group.aurora.id
}

output "rds_proxy_security_group_id" {
  description = "ID of RDS Proxy security group"
  value       = aws_security_group.rds_proxy.id
}

# Environment configuration output (for application config)
output "application_config" {
  description = "Configuration values for application deployment"
  value = {
    # S3
    S3_BUCKET_RAW         = aws_s3_bucket.raw.id
    S3_BUCKET_ENRICHED    = aws_s3_bucket.enriched.id
    S3_BUCKET_FINAL       = aws_s3_bucket.final.id
    S3_BUCKET_ATTACHMENTS = aws_s3_bucket.attachments.id
    
    # Database
    RDS_PROXY_ENDPOINT = aws_db_proxy.aurora.endpoint
    DB_NAME            = var.db_name
    DB_SECRET_ARN      = aws_secretsmanager_secret.db_credentials.arn
    
    # KMS
    KMS_PHI_KEY_ALIAS = aws_kms_alias.phi_field_key_alias.name
    
    # DynamoDB
    DYNAMODB_IDEMPOTENCY_TABLE = aws_dynamodb_table.idempotency.name
    
    # SQS Queue URLs
    SQS_QUEUE_REQUEST_PARSER     = aws_sqs_queue.main["request-parser"].url
    SQS_QUEUE_ATTACHMENT_HANDLER = aws_sqs_queue.main["attachment-handler"].url
    SQS_QUEUE_BUSINESS_VALIDATOR = aws_sqs_queue.main["business-validator"].url
    SQS_QUEUE_REQUEST_ENRICHER   = aws_sqs_queue.main["request-enricher"].url
    SQS_QUEUE_CANONICAL_MAPPER   = aws_sqs_queue.main["canonical-mapper"].url
    SQS_QUEUE_API_ORCHESTRATOR   = aws_sqs_queue.main["api-orchestrator"].url
    SQS_QUEUE_CALLBACK_HANDLER   = aws_sqs_queue.main["callback-handler"].url
    SQS_QUEUE_RESPONSE_BUILDER   = aws_sqs_queue.main["response-builder"].url
    SQS_QUEUE_OUTBOX_PUBLISHER   = aws_sqs_queue.main["outbox-publisher"].url
    SQS_QUEUE_REPLAY             = aws_sqs_queue.main["replay"].url
  }
  sensitive = true
}

# DynamoDB Outputs
output "dynamodb_idempotency_table_name" {
  description = "Name of the DynamoDB idempotency table"
  value       = aws_dynamodb_table.idempotency.name
}

output "dynamodb_idempotency_table_arn" {
  description = "ARN of the DynamoDB idempotency table"
  value       = aws_dynamodb_table.idempotency.arn
}
