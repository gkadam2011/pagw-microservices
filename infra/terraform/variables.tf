# Variables for PAGW Infrastructure

variable "aws_region" {
  description = "AWS region for deployment"
  type        = string
  default     = "us-east-1"
}

variable "environment" {
  description = "Environment name (dev, preprod, prod)"
  type        = string
  default     = "dev"
}

variable "app_name" {
  description = "Application name"
  type        = string
  default     = "pagw"
}

variable "vpc_id" {
  description = "VPC ID for resources"
  type        = string
}

variable "private_subnet_ids" {
  description = "List of private subnet IDs for Aurora and Lambda"
  type        = list(string)
}

variable "db_instance_class" {
  description = "Aurora instance class"
  type        = string
  default     = "db.r6g.large"
}

variable "db_name" {
  description = "Database name"
  type        = string
  default     = "pagw"
}

variable "db_master_username" {
  description = "Database master username"
  type        = string
  default     = "pagw_admin"
  sensitive   = true
}

variable "kms_deletion_window_days" {
  description = "KMS key deletion window in days"
  type        = number
  default     = 30
}

variable "sqs_message_retention_seconds" {
  description = "SQS message retention period"
  type        = number
  default     = 1209600  # 14 days
}

variable "sqs_visibility_timeout_seconds" {
  description = "SQS visibility timeout (should be 5x Lambda timeout)"
  type        = number
  default     = 300  # 5 minutes
}

variable "dlq_message_retention_seconds" {
  description = "DLQ message retention period"
  type        = number
  default     = 1209600  # 14 days
}

variable "enable_aurora_serverless" {
  description = "Use Aurora Serverless v2 instead of provisioned"
  type        = bool
  default     = false
}

variable "tags" {
  description = "Additional tags for resources"
  type        = map(string)
  default     = {}
}
