# KMS Keys for PHI Encryption
# Separate keys for different data types for better access control

# Master KMS key for S3 encryption
resource "aws_kms_key" "s3_key" {
  description             = "KMS key for PAGW S3 bucket encryption (PHI data)"
  deletion_window_in_days = var.kms_deletion_window_days
  enable_key_rotation     = true
  
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "Enable IAM User Permissions"
        Effect = "Allow"
        Principal = {
          AWS = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:root"
        }
        Action   = "kms:*"
        Resource = "*"
      },
      {
        Sid    = "Allow S3 Service"
        Effect = "Allow"
        Principal = {
          Service = "s3.amazonaws.com"
        }
        Action = [
          "kms:Decrypt",
          "kms:GenerateDataKey*"
        ]
        Resource = "*"
      }
    ]
  })
  
  tags = {
    Name    = "${var.app_name}-s3-kms-key-${var.environment}"
    Purpose = "S3 PHI encryption"
  }
}

resource "aws_kms_alias" "s3_key_alias" {
  name          = "alias/${var.app_name}-s3-${var.environment}"
  target_key_id = aws_kms_key.s3_key.key_id
}

# KMS key for SQS encryption
resource "aws_kms_key" "sqs_key" {
  description             = "KMS key for PAGW SQS queue encryption"
  deletion_window_in_days = var.kms_deletion_window_days
  enable_key_rotation     = true
  
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "Enable IAM User Permissions"
        Effect = "Allow"
        Principal = {
          AWS = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:root"
        }
        Action   = "kms:*"
        Resource = "*"
      },
      {
        Sid    = "Allow SQS Service"
        Effect = "Allow"
        Principal = {
          Service = "sqs.amazonaws.com"
        }
        Action = [
          "kms:Decrypt",
          "kms:GenerateDataKey*"
        ]
        Resource = "*"
      }
    ]
  })
  
  tags = {
    Name    = "${var.app_name}-sqs-kms-key-${var.environment}"
    Purpose = "SQS message encryption"
  }
}

resource "aws_kms_alias" "sqs_key_alias" {
  name          = "alias/${var.app_name}-sqs-${var.environment}"
  target_key_id = aws_kms_key.sqs_key.key_id
}

# KMS key for Aurora/RDS encryption
resource "aws_kms_key" "rds_key" {
  description             = "KMS key for PAGW Aurora database encryption (PHI data)"
  deletion_window_in_days = var.kms_deletion_window_days
  enable_key_rotation     = true
  
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "Enable IAM User Permissions"
        Effect = "Allow"
        Principal = {
          AWS = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:root"
        }
        Action   = "kms:*"
        Resource = "*"
      },
      {
        Sid    = "Allow RDS Service"
        Effect = "Allow"
        Principal = {
          Service = "rds.amazonaws.com"
        }
        Action = [
          "kms:Decrypt",
          "kms:GenerateDataKey*"
        ]
        Resource = "*"
      }
    ]
  })
  
  tags = {
    Name    = "${var.app_name}-rds-kms-key-${var.environment}"
    Purpose = "RDS PHI encryption"
  }
}

resource "aws_kms_alias" "rds_key_alias" {
  name          = "alias/${var.app_name}-rds-${var.environment}"
  target_key_id = aws_kms_key.rds_key.key_id
}

# KMS key for field-level PHI encryption (application-level encryption)
resource "aws_kms_key" "phi_field_key" {
  description             = "KMS key for field-level PHI encryption in PAGW"
  deletion_window_in_days = var.kms_deletion_window_days
  enable_key_rotation     = true
  
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "Enable IAM User Permissions"
        Effect = "Allow"
        Principal = {
          AWS = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:root"
        }
        Action   = "kms:*"
        Resource = "*"
      }
    ]
  })
  
  tags = {
    Name    = "${var.app_name}-phi-field-kms-key-${var.environment}"
    Purpose = "Field-level PHI encryption"
  }
}

resource "aws_kms_alias" "phi_field_key_alias" {
  name          = "alias/${var.app_name}-phi-field-${var.environment}"
  target_key_id = aws_kms_key.phi_field_key.key_id
}

# KMS key for Secrets Manager
resource "aws_kms_key" "secrets_key" {
  description             = "KMS key for PAGW Secrets Manager encryption"
  deletion_window_in_days = var.kms_deletion_window_days
  enable_key_rotation     = true
  
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "Enable IAM User Permissions"
        Effect = "Allow"
        Principal = {
          AWS = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:root"
        }
        Action   = "kms:*"
        Resource = "*"
      },
      {
        Sid    = "Allow Secrets Manager Service"
        Effect = "Allow"
        Principal = {
          Service = "secretsmanager.amazonaws.com"
        }
        Action = [
          "kms:Decrypt",
          "kms:GenerateDataKey*"
        ]
        Resource = "*"
      }
    ]
  })
  
  tags = {
    Name    = "${var.app_name}-secrets-kms-key-${var.environment}"
    Purpose = "Secrets Manager encryption"
  }
}

resource "aws_kms_alias" "secrets_key_alias" {
  name          = "alias/${var.app_name}-secrets-${var.environment}"
  target_key_id = aws_kms_key.secrets_key.key_id
}
