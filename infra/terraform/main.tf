# PAGW Infrastructure - Main Terraform Configuration
# Provisions S3, SQS, KMS, Aurora, and RDS Proxy for PHI-compliant architecture

terraform {
  required_version = ">= 1.5.0"
  
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
  
  # Backend configuration - uncomment and configure for your environment
  # backend "s3" {
  #   bucket         = "pagw-terraform-state"
  #   key            = "pagw/terraform.tfstate"
  #   region         = "us-east-1"
  #   encrypt        = true
  #   dynamodb_table = "pagw-terraform-locks"
  # }
}

provider "aws" {
  region = var.aws_region
  
  default_tags {
    tags = {
      Project     = "PAGW"
      Environment = var.environment
      ManagedBy   = "Terraform"
      PHI         = "true"
      Compliance  = "HIPAA"
    }
  }
}

# Data sources
data "aws_caller_identity" "current" {}
data "aws_region" "current" {}
