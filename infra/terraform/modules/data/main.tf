data "aws_region" "current" {}

data "aws_partition" "current" {}

# The roots of RDS in the region. PgBouncer trusts all of them, a direct client only the root of ca_cert_identifier.
data "http" "rds_ca" {
  url = "https://truststore.pki.rds.amazonaws.com/${data.aws_region.current.region}/${data.aws_region.current.region}-bundle.pem"

  lifecycle {
    postcondition {
      condition     = self.status_code == 200
      error_message = "The RDS truststore has no bundle for this region."
    }
  }
}

data "tls_certificate" "rds_ca" {
  content = data.http.rds_ca.response_body
}

locals {
  # rds-ca-rsa2048-g1 is issued by "Amazon RDS <region> Root CA RSA2048 G1".
  rds_root_name = "Root CA ${upper(replace(trimprefix(var.ca_cert_identifier, "rds-ca-"), "-", " "))}"
  rds_root_pem  = one([for certificate in data.tls_certificate.rds_ca.certificates : certificate.cert_pem if strcontains(certificate.subject, local.rds_root_name)])

  tls_factory = "sslmode=verify-full&sslfactory=org.postgresql.ssl.SingleCertValidatingFactory"
}

resource "aws_kms_key" "database" {
  description             = "${var.name} database storage, snapshots and Performance Insights"
  enable_key_rotation     = true
  rotation_period_in_days = 365
  deletion_window_in_days = 30
}

resource "aws_kms_alias" "database" {
  name          = "alias/${var.name}-database"
  target_key_id = aws_kms_key.database.key_id
}

resource "aws_db_subnet_group" "this" {
  name       = var.name
  subnet_ids = var.database_subnet_ids
}

resource "aws_db_parameter_group" "this" {
  name_prefix = "${var.name}-postgres${var.engine_version}-"
  family      = "postgres${var.engine_version}"

  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }

  parameter {
    name  = "ssl_min_protocol_version"
    value = "TLSv1.2"
  }

  parameter {
    name  = "password_encryption"
    value = "scram-sha-256"
  }

  lifecycle {
    create_before_destroy = true
  }
}

# Encryption cannot be turned on later, so the instance is encrypted with its own key from the start.
resource "aws_db_instance" "this" {
  identifier                            = var.name
  engine                                = "postgres"
  engine_version                        = var.engine_version
  instance_class                        = var.instance_class
  allocated_storage                     = var.allocated_storage
  max_allocated_storage                 = var.max_allocated_storage
  storage_type                          = "gp3"
  storage_encrypted                     = true
  kms_key_id                            = aws_kms_key.database.arn
  db_name                               = "idp"
  username                              = var.master_username
  manage_master_user_password           = true
  multi_az                              = true
  db_subnet_group_name                  = aws_db_subnet_group.this.name
  vpc_security_group_ids                = [var.security_group_ids["database"]]
  parameter_group_name                  = aws_db_parameter_group.this.name
  ca_cert_identifier                    = var.ca_cert_identifier
  publicly_accessible                   = false
  iam_database_authentication_enabled   = false
  backup_retention_period               = 30
  backup_window                         = "02:00-03:00"
  maintenance_window                    = "sun:03:30-sun:04:30"
  copy_tags_to_snapshot                 = true
  delete_automated_backups              = false
  deletion_protection                   = var.deletion_protection
  skip_final_snapshot                   = false
  final_snapshot_identifier             = "${var.name}-final"
  auto_minor_version_upgrade            = true
  allow_major_version_upgrade           = false
  performance_insights_enabled          = true
  performance_insights_kms_key_id       = aws_kms_key.database.arn
  performance_insights_retention_period = 7

  lifecycle {
    precondition {
      condition     = local.rds_root_pem != null
      error_message = "The RDS bundle of the region holds no ${local.rds_root_name}."
    }
  }
}

resource "aws_cloudwatch_log_group" "this" {
  for_each = toset(["pgbouncer", "db-bootstrap"])

  name              = "/${var.name}/${each.key}"
  retention_in_days = var.log_retention_days
}

data "aws_iam_policy_document" "ecs_tasks" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

# PgBouncer and the bootstrap call no AWS API themselves, so they have execution roles alone, each reading only its secrets.
resource "aws_iam_role" "execution" {
  for_each = toset(["pgbouncer", "db-bootstrap"])

  name               = "${var.name}-${each.key}-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks.json
}

resource "aws_iam_role_policy_attachment" "execution" {
  for_each = aws_iam_role.execution

  role       = each.value.name
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

locals {
  execution_secrets = {
    pgbouncer      = concat([var.pgbouncer_tls.key_secret_arn], [for role in local.runtime_roles : aws_secretsmanager_secret.role[role].arn])
    "db-bootstrap" = concat([aws_db_instance.this.master_user_secret[0].secret_arn], [for secret in aws_secretsmanager_secret.role : secret.arn])
  }
}

data "aws_iam_policy_document" "execution_secrets" {
  for_each = local.execution_secrets

  statement {
    actions   = ["secretsmanager:GetSecretValue"]
    resources = each.value
  }
}

resource "aws_iam_role_policy" "execution_secrets" {
  for_each = local.execution_secrets

  name   = "secrets"
  role   = aws_iam_role.execution[each.key].id
  policy = data.aws_iam_policy_document.execution_secrets[each.key].json
}
