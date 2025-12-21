# Aurora PostgreSQL Cluster with RDS Proxy for PAGW

# Security group for Aurora
resource "aws_security_group" "aurora" {
  name        = "${var.app_name}-aurora-sg-${var.environment}"
  description = "Security group for PAGW Aurora cluster"
  vpc_id      = var.vpc_id
  
  # Only allow traffic from RDS Proxy security group
  ingress {
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.rds_proxy.id]
    description     = "PostgreSQL from RDS Proxy"
  }
  
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
  
  tags = {
    Name = "${var.app_name}-aurora-sg-${var.environment}"
  }
}

# Security group for RDS Proxy
resource "aws_security_group" "rds_proxy" {
  name        = "${var.app_name}-rds-proxy-sg-${var.environment}"
  description = "Security group for PAGW RDS Proxy"
  vpc_id      = var.vpc_id
  
  # Allow traffic from application security group (add your app SG)
  ingress {
    from_port   = 5432
    to_port     = 5432
    protocol    = "tcp"
    cidr_blocks = ["10.0.0.0/8"]  # Replace with your VPC CIDR
    description = "PostgreSQL from applications"
  }
  
  egress {
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.aurora.id]
    description     = "PostgreSQL to Aurora"
  }
  
  tags = {
    Name = "${var.app_name}-rds-proxy-sg-${var.environment}"
  }
}

# DB subnet group
resource "aws_db_subnet_group" "aurora" {
  name        = "${var.app_name}-aurora-subnet-group-${var.environment}"
  description = "Subnet group for PAGW Aurora cluster"
  subnet_ids  = var.private_subnet_ids
  
  tags = {
    Name = "${var.app_name}-aurora-subnet-group-${var.environment}"
  }
}

# Aurora cluster parameter group
resource "aws_rds_cluster_parameter_group" "aurora" {
  name        = "${var.app_name}-aurora-cluster-params-${var.environment}"
  family      = "aurora-postgresql15"
  description = "Cluster parameter group for PAGW Aurora"
  
  parameter {
    name  = "log_statement"
    value = "all"
  }
  
  parameter {
    name  = "log_min_duration_statement"
    value = "1000"  # Log queries taking > 1 second
  }
  
  # Force SSL connections
  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }
  
  tags = {
    Name = "${var.app_name}-aurora-cluster-params-${var.environment}"
  }
}

# DB parameter group
resource "aws_db_parameter_group" "aurora" {
  name        = "${var.app_name}-aurora-db-params-${var.environment}"
  family      = "aurora-postgresql15"
  description = "DB parameter group for PAGW Aurora instances"
  
  tags = {
    Name = "${var.app_name}-aurora-db-params-${var.environment}"
  }
}

# Generate random password for DB
resource "random_password" "db_password" {
  length           = 32
  special          = true
  override_special = "!#$%&*()-_=+[]{}<>:?"
}

# Store DB credentials in Secrets Manager
resource "aws_secretsmanager_secret" "db_credentials" {
  name        = "${var.app_name}/aurora/credentials-${var.environment}"
  description = "Aurora database credentials for PAGW"
  kms_key_id  = aws_kms_key.secrets_key.arn
  
  tags = {
    Name = "${var.app_name}-db-credentials-${var.environment}"
  }
}

resource "aws_secretsmanager_secret_version" "db_credentials" {
  secret_id = aws_secretsmanager_secret.db_credentials.id
  secret_string = jsonencode({
    username = var.db_master_username
    password = random_password.db_password.result
    engine   = "postgres"
    host     = aws_rds_cluster.aurora.endpoint
    port     = 5432
    dbname   = var.db_name
  })
}

# Aurora cluster
resource "aws_rds_cluster" "aurora" {
  cluster_identifier = "${var.app_name}-aurora-${var.environment}"
  engine             = "aurora-postgresql"
  engine_version     = "15.4"
  engine_mode        = var.enable_aurora_serverless ? "provisioned" : "provisioned"
  
  database_name   = var.db_name
  master_username = var.db_master_username
  master_password = random_password.db_password.result
  
  db_subnet_group_name            = aws_db_subnet_group.aurora.name
  vpc_security_group_ids          = [aws_security_group.aurora.id]
  db_cluster_parameter_group_name = aws_rds_cluster_parameter_group.aurora.name
  
  # Encryption at rest with KMS
  storage_encrypted = true
  kms_key_id        = aws_kms_key.rds_key.arn
  
  # Backup configuration
  backup_retention_period   = 35  # Max for HIPAA compliance
  preferred_backup_window   = "03:00-04:00"
  preferred_maintenance_window = "sun:04:00-sun:05:00"
  
  # Enable deletion protection in production
  deletion_protection = var.environment == "prod" ? true : false
  skip_final_snapshot = var.environment == "prod" ? false : true
  final_snapshot_identifier = var.environment == "prod" ? "${var.app_name}-aurora-final-${var.environment}" : null
  
  # Enable Performance Insights
  # Note: enabled on instances, not cluster
  
  # IAM authentication
  iam_database_authentication_enabled = true
  
  # Serverless v2 scaling (if enabled)
  dynamic "serverlessv2_scaling_configuration" {
    for_each = var.enable_aurora_serverless ? [1] : []
    content {
      min_capacity = 0.5
      max_capacity = 16
    }
  }
  
  tags = {
    Name     = "${var.app_name}-aurora-${var.environment}"
    DataType = "PHI"
  }
}

# Aurora instances
resource "aws_rds_cluster_instance" "aurora" {
  count = var.enable_aurora_serverless ? 1 : 2  # 2 instances for HA
  
  identifier         = "${var.app_name}-aurora-${var.environment}-${count.index}"
  cluster_identifier = aws_rds_cluster.aurora.id
  instance_class     = var.enable_aurora_serverless ? "db.serverless" : var.db_instance_class
  engine             = aws_rds_cluster.aurora.engine
  engine_version     = aws_rds_cluster.aurora.engine_version
  
  db_parameter_group_name = aws_db_parameter_group.aurora.name
  
  # Performance Insights
  performance_insights_enabled    = true
  performance_insights_kms_key_id = aws_kms_key.rds_key.arn
  
  # Enhanced monitoring
  monitoring_interval = 60
  monitoring_role_arn = aws_iam_role.rds_monitoring.arn
  
  # Auto minor version upgrade
  auto_minor_version_upgrade = true
  
  tags = {
    Name = "${var.app_name}-aurora-instance-${var.environment}-${count.index}"
  }
}

# IAM role for RDS enhanced monitoring
resource "aws_iam_role" "rds_monitoring" {
  name = "${var.app_name}-rds-monitoring-role-${var.environment}"
  
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "monitoring.rds.amazonaws.com"
        }
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "rds_monitoring" {
  role       = aws_iam_role.rds_monitoring.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"
}

# RDS Proxy
resource "aws_db_proxy" "aurora" {
  name                   = "${var.app_name}-rds-proxy-${var.environment}"
  debug_logging          = var.environment != "prod"
  engine_family          = "POSTGRESQL"
  idle_client_timeout    = 1800
  require_tls            = true
  vpc_security_group_ids = [aws_security_group.rds_proxy.id]
  vpc_subnet_ids         = var.private_subnet_ids
  
  auth {
    auth_scheme               = "SECRETS"
    iam_auth                  = "DISABLED"
    secret_arn                = aws_secretsmanager_secret.db_credentials.arn
    client_password_auth_type = "POSTGRES_SCRAM_SHA_256"
  }
  
  tags = {
    Name = "${var.app_name}-rds-proxy-${var.environment}"
  }
  
  depends_on = [aws_secretsmanager_secret_version.db_credentials]
}

# RDS Proxy default target group
resource "aws_db_proxy_default_target_group" "aurora" {
  db_proxy_name = aws_db_proxy.aurora.name
  
  connection_pool_config {
    connection_borrow_timeout    = 120
    max_connections_percent      = 100
    max_idle_connections_percent = 50
  }
}

# RDS Proxy target
resource "aws_db_proxy_target" "aurora" {
  db_proxy_name          = aws_db_proxy.aurora.name
  target_group_name      = aws_db_proxy_default_target_group.aurora.name
  db_cluster_identifier  = aws_rds_cluster.aurora.id
}
