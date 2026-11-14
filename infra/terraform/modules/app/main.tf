data "aws_region" "current" {}

data "aws_partition" "current" {}

locals {
  http_port       = 8080
  management_port = 8081
  run_dir         = "/run/idp"
  metadata_file   = "${local.run_dir}/idp-metadata.xml"

  shared_environment = {
    SPRING_PROFILES_ACTIVE = var.profile
    KMS_NEW_KEYS           = "aws-kms"
    KMS_AWS_REGION         = data.aws_region.current.region
  }

  pooled_database = {
    DB_URL    = var.database.pooled_jdbc_url
    DB_TLS_CA = var.database.pooled_tls_ca_pem
  }

  # migrator exports nothing, its lines reach the console alone.
  telemetry = {
    OTEL_EXPORTER_OTLP_ENDPOINT = var.telemetry.endpoint
    OTEL_METRICS_EXPORTER       = "otlp"
    # The collector turns metrics into EMF, which takes the change since the last export.
    OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE = "delta"
    OTLP_TLS_CA                                       = var.telemetry.ca_pem
  }

  ops_environment = merge(var.services.ops.environment, local.shared_environment, local.pooled_database, local.telemetry, {
    DB_USERNAME         = "ops_runtime"
    TENANTS_BASE_DOMAIN = var.domain
  })

  migrator_environment = merge(var.services.migrator.environment, local.shared_environment, {
    DB_URL              = var.database.direct_jdbc_url
    DB_TLS_CA           = var.database.direct_tls_ca_pem
    DB_USERNAME         = "idp_owner"
    TENANTS_BASE_DOMAIN = var.domain
  })

  # The CLI is a one-off run of the ops image, so it shares the settings of ops but serves nothing.
  tasks = {
    app = {
      image = "app"
      cpu   = 1024
      # Argon2id takes its memory from the heap of every login running at once.
      memory = 2048
      environment = merge(var.services.app.environment, local.shared_environment, local.pooled_database, local.telemetry, {
        DB_USERNAME        = "app_runtime"
        APP_VERSION        = var.release
        MIN_CLIENT_VERSION = var.min_client_version
        SERVER_TLS_CERT    = var.server_tls.app.cert_pem
      })
      secrets = merge(var.services.app.secrets, {
        DB_PASSWORD    = var.database.role_secret_arns["app_runtime"]
        SERVER_TLS_KEY = var.server_tls.app.key_secret_arn
      })
      command = null
    }
    worker = {
      image  = "worker"
      cpu    = 512
      memory = 1024
      environment = merge(var.services.worker.environment, local.shared_environment, local.pooled_database, local.telemetry, {
        DB_USERNAME             = "worker_runtime"
        PROVISIONER_DB_URL      = var.database.provisioner_jdbc_url
        PROVISIONER_DB_TLS_CA   = var.database.direct_tls_ca_pem
        PROVISIONER_DB_USERNAME = "provisioner"
        TENANTS_BASE_DOMAIN     = var.domain
        KMS_AWS_KEY_POLICY      = var.tenant_kek_policy
        # A KEK replaced by a move still opens the backups taken before it.
        KMS_PREVIOUS_KEK_RETENTION = "${var.previous_kek_retention_days}d"
      })
      secrets = merge(var.services.worker.secrets, {
        DB_PASSWORD             = var.database.role_secret_arns["worker_runtime"]
        PROVISIONER_DB_PASSWORD = var.database.role_secret_arns["provisioner"]
      })
      command = null
    }
    ops = {
      image       = "ops"
      cpu         = 512
      memory      = 1024
      environment = merge(local.ops_environment, { IDP_METADATA_FILE = local.metadata_file, SERVER_TLS_CERT = var.server_tls.ops.cert_pem })
      secrets = merge(var.services.ops.secrets, {
        DB_PASSWORD    = var.database.role_secret_arns["ops_runtime"]
        SERVER_TLS_KEY = var.server_tls.ops.key_secret_arn
      })
      command = null
    }
    cli = {
      image       = "ops"
      cpu         = 512
      memory      = 1024
      environment = local.ops_environment
      secrets     = merge(var.services.ops.secrets, { DB_PASSWORD = var.database.role_secret_arns["ops_runtime"] })
      command     = ["cli"]
    }
    migrator = {
      image       = "migrator"
      cpu         = 1024
      memory      = 2048
      environment = local.migrator_environment
      secrets     = merge(var.services.migrator.secrets, { DB_PASSWORD = var.database.role_secret_arns["idp_owner"] })
      command     = null
    }
    # migrator after a restore, see docs/security/restore-runbook.md. Only its task role may destroy KEKs and list the
    # erasure journal, so a release never holds those rights.
    recovery = {
      image       = "migrator"
      cpu         = 1024
      memory      = 2048
      environment = merge(local.migrator_environment, { REAPPLY_ERASURES = "true" })
      secrets     = merge(var.services.migrator.secrets, { DB_PASSWORD = var.database.role_secret_arns["idp_owner"] })
      command     = null
    }
  }

  services       = toset(["app", "worker", "ops"])
  balanced       = toset(["app", "ops"])
  security_group = { app = "app", worker = "worker", ops = "ops", cli = "ops", migrator = "migrator", recovery = "migrator" }

  # A secret with a JSON key is read through the ARN of the whole secret.
  execution_secrets = {
    for name, task in local.tasks : name => distinct(concat(
      [for arn in values(task.secrets) : regex("^arn:[^:]+:secretsmanager:[^:]+:[0-9]+:secret:[^:]+", arn)],
      name == "ops" ? [aws_secretsmanager_secret.idp_metadata.arn] : [],
    ))
  }
}

# The console of each task. Deletion protection keeps a group and its lines until somebody turns it off, which CloudTrail records.
resource "aws_cloudwatch_log_group" "this" {
  for_each = local.tasks

  name                        = "/${var.name}/${each.key}"
  retention_in_days           = var.log_retention_days
  deletion_protection_enabled = true
}

# Public, but ops trusts the keys in it, so only those who may change IAM can change it. The operator writes it, see
# infra/terraform/README.md.
resource "aws_secretsmanager_secret" "idp_metadata" {
  name                    = "${var.name}/ops/idp-metadata"
  description             = "SAML metadata of the service tenant ops-sso, which ops trusts"
  recovery_window_in_days = 7
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

# Each task pulls its image and reads its own secrets alone.
resource "aws_iam_role" "execution" {
  for_each = local.tasks

  name               = "${var.name}-${each.key}-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks.json
}

resource "aws_iam_role_policy_attachment" "execution" {
  for_each = aws_iam_role.execution

  role       = each.value.name
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "execution_secrets" {
  for_each = local.execution_secrets

  statement {
    sid       = "OwnSecrets"
    effect    = "Allow"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = each.value
  }

  # ECS appends the console lines with this role, and whatever is granted later reads, changes or deletes none of them.
  statement {
    sid       = "NoLogReadOrChange"
    effect    = "Deny"
    actions   = var.log_tampering_actions
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "execution_secrets" {
  for_each = local.execution_secrets

  name   = "secrets"
  role   = aws_iam_role.execution[each.key].id
  policy = data.aws_iam_policy_document.execution_secrets[each.key].json
}
