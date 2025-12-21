# S3 Buckets for PAGW - All encrypted with KMS for PHI compliance

# Raw requests bucket
resource "aws_s3_bucket" "raw" {
  bucket = "${var.app_name}-raw-${var.environment}-${data.aws_caller_identity.current.account_id}"
  
  tags = {
    Name     = "${var.app_name}-raw-${var.environment}"
    Purpose  = "Raw FHIR bundles storage"
    DataType = "PHI"
  }
}

resource "aws_s3_bucket_versioning" "raw" {
  bucket = aws_s3_bucket.raw.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "raw" {
  bucket = aws_s3_bucket.raw.id
  
  rule {
    apply_server_side_encryption_by_default {
      kms_master_key_id = aws_kms_key.s3_key.arn
      sse_algorithm     = "aws:kms"
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_public_access_block" "raw" {
  bucket = aws_s3_bucket.raw.id
  
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_lifecycle_configuration" "raw" {
  bucket = aws_s3_bucket.raw.id
  
  rule {
    id     = "archive-old-data"
    status = "Enabled"
    
    transition {
      days          = 90
      storage_class = "STANDARD_IA"
    }
    
    transition {
      days          = 365
      storage_class = "GLACIER"
    }
    
    # PHI retention - typically 7 years for HIPAA
    expiration {
      days = 2555  # ~7 years
    }
  }
}

# Enriched data bucket
resource "aws_s3_bucket" "enriched" {
  bucket = "${var.app_name}-enriched-${var.environment}-${data.aws_caller_identity.current.account_id}"
  
  tags = {
    Name     = "${var.app_name}-enriched-${var.environment}"
    Purpose  = "Enriched request data"
    DataType = "PHI"
  }
}

resource "aws_s3_bucket_versioning" "enriched" {
  bucket = aws_s3_bucket.enriched.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "enriched" {
  bucket = aws_s3_bucket.enriched.id
  
  rule {
    apply_server_side_encryption_by_default {
      kms_master_key_id = aws_kms_key.s3_key.arn
      sse_algorithm     = "aws:kms"
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_public_access_block" "enriched" {
  bucket = aws_s3_bucket.enriched.id
  
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# Final responses bucket
resource "aws_s3_bucket" "final" {
  bucket = "${var.app_name}-final-${var.environment}-${data.aws_caller_identity.current.account_id}"
  
  tags = {
    Name     = "${var.app_name}-final-${var.environment}"
    Purpose  = "Final response data"
    DataType = "PHI"
  }
}

resource "aws_s3_bucket_versioning" "final" {
  bucket = aws_s3_bucket.final.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "final" {
  bucket = aws_s3_bucket.final.id
  
  rule {
    apply_server_side_encryption_by_default {
      kms_master_key_id = aws_kms_key.s3_key.arn
      sse_algorithm     = "aws:kms"
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_public_access_block" "final" {
  bucket = aws_s3_bucket.final.id
  
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# Attachments bucket
resource "aws_s3_bucket" "attachments" {
  bucket = "${var.app_name}-attachments-${var.environment}-${data.aws_caller_identity.current.account_id}"
  
  tags = {
    Name     = "${var.app_name}-attachments-${var.environment}"
    Purpose  = "Document attachments"
    DataType = "PHI"
  }
}

resource "aws_s3_bucket_versioning" "attachments" {
  bucket = aws_s3_bucket.attachments.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "attachments" {
  bucket = aws_s3_bucket.attachments.id
  
  rule {
    apply_server_side_encryption_by_default {
      kms_master_key_id = aws_kms_key.s3_key.arn
      sse_algorithm     = "aws:kms"
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_public_access_block" "attachments" {
  bucket = aws_s3_bucket.attachments.id
  
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# Access logging bucket (not PHI but needs encryption)
resource "aws_s3_bucket" "access_logs" {
  bucket = "${var.app_name}-access-logs-${var.environment}-${data.aws_caller_identity.current.account_id}"
  
  tags = {
    Name     = "${var.app_name}-access-logs-${var.environment}"
    Purpose  = "S3 access logs for audit"
    DataType = "AUDIT"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "access_logs" {
  bucket = aws_s3_bucket.access_logs.id
  
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"  # Use S3-managed keys for log bucket
    }
  }
}

resource "aws_s3_bucket_public_access_block" "access_logs" {
  bucket = aws_s3_bucket.access_logs.id
  
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# Enable access logging on all PHI buckets
resource "aws_s3_bucket_logging" "raw" {
  bucket = aws_s3_bucket.raw.id
  
  target_bucket = aws_s3_bucket.access_logs.id
  target_prefix = "raw/"
}

resource "aws_s3_bucket_logging" "enriched" {
  bucket = aws_s3_bucket.enriched.id
  
  target_bucket = aws_s3_bucket.access_logs.id
  target_prefix = "enriched/"
}

resource "aws_s3_bucket_logging" "final" {
  bucket = aws_s3_bucket.final.id
  
  target_bucket = aws_s3_bucket.access_logs.id
  target_prefix = "final/"
}

resource "aws_s3_bucket_logging" "attachments" {
  bucket = aws_s3_bucket.attachments.id
  
  target_bucket = aws_s3_bucket.access_logs.id
  target_prefix = "attachments/"
}
