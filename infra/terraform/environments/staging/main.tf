locals {
  environment = "staging"
  name        = "idp-${local.environment}"
  # staging is prod, except that a load test may switch the limits of app off.
  profile = "staging"
}

module "network" {
  source = "../../modules/network"

  name               = local.name
  cidr_block         = var.vpc_cidr
  availability_zones = var.availability_zones
}

module "registry" {
  source = "../../modules/registry"

  name = local.name
}

module "static" {
  source = "../../modules/static"

  bucket                       = "${local.name}-static-${var.account_id}"
  cloudfront_distribution_arns = [module.edge.distribution_arn]
}

module "edge" {
  source = "../../modules/edge"

  providers = {
    aws           = aws
    aws.us_east_1 = aws.us_east_1
  }

  name                  = local.name
  domain                = var.domain
  alb_arn               = module.network.alb_arn
  alb_dns_name          = module.network.alb_dns_name
  alb_security_group_id = module.network.security_group_ids["alb"]
  origin_header_value   = module.keys.origin_header_value
  static_origin_domain  = module.static.bucket_regional_domain_name
  security_headers      = jsondecode(file("${path.module}/../../../web/security-headers.json"))
}

module "cluster" {
  source = "../../modules/cluster"

  name   = local.name
  vpc_id = module.network.vpc_id
}

module "pki" {
  source = "../../modules/pki"

  name = local.name
  # The ALB reaches app and ops by address and checks no name, the services reach the collector by its name in the namespace.
  certificates = {
    pgbouncer        = ["pgbouncer.${module.cluster.namespace}"]
    app              = ["app.${module.cluster.namespace}"]
    ops              = ["ops.${module.cluster.namespace}"]
    "otel-collector" = ["otel-collector.${module.cluster.namespace}"]
  }
}

module "telemetry" {
  source = "../../modules/telemetry"

  name               = local.name
  cluster_arn        = module.cluster.cluster_arn
  namespace_id       = module.cluster.namespace_id
  namespace          = module.cluster.namespace
  private_subnet_ids = module.network.private_subnet_ids
  security_group_ids = module.network.security_group_ids
  collector_image    = "${module.registry.repository_urls["otel-collector"]}:${var.infra_image_tag}"
  collector_tls      = module.pki.certificates["otel-collector"]
}

module "alarms" {
  source = "../../modules/alarms"

  providers = {
    aws           = aws
    aws.us_east_1 = aws.us_east_1
  }

  name                   = local.name
  trail_bucket           = "${local.name}-trail-${var.account_id}"
  metrics_namespace      = module.telemetry.metrics_namespace
  security_log_group     = module.telemetry.log_groups["security"]
  task_role_arns         = module.iam.task_role_arns
  log_tampering_actions  = module.telemetry.log_tampering_actions
  waf                    = module.edge.waf_metrics
  notification_endpoints = var.alarm_endpoints
}

module "data" {
  source = "../../modules/data"

  name                  = local.name
  database_subnet_ids   = module.network.database_subnet_ids
  private_subnet_ids    = module.network.private_subnet_ids
  security_group_ids    = module.network.security_group_ids
  cluster_arn           = module.cluster.cluster_arn
  namespace_id          = module.cluster.namespace_id
  namespace             = module.cluster.namespace
  pgbouncer_image       = "${module.registry.repository_urls["pgbouncer"]}:${var.infra_image_tag}"
  db_bootstrap_image    = "${module.registry.repository_urls["db-bootstrap"]}:${var.infra_image_tag}"
  internal_ca_pem       = module.pki.ca_cert_pem
  pgbouncer_tls         = module.pki.certificates["pgbouncer"]
  instance_class        = var.db_instance_class
  role_secret_arns      = module.keys.db_role_secret_arns
  log_tampering_actions = module.telemetry.log_tampering_actions
}

module "valkey" {
  source = "../../modules/valkey"

  name               = local.name
  subnet_ids         = module.network.database_subnet_ids
  security_group_ids = module.network.security_group_ids
  node_type          = var.valkey_node_type
  auth_token         = module.keys.valkey_auth_token
  auth_token_version = lookup(var.credential_versions, "valkey_auth_token", 1)
}

module "keys" {
  source = "../../modules/keys"

  name                         = local.name
  platform_kek_policy          = module.iam.platform_kek_policy
  throttle_ip_hmac_key_version = lookup(var.credential_versions, "throttle_ip_hmac_key", 1)
  origin_header_version        = lookup(var.credential_versions, "origin_header", 1)
  valkey_auth_token_version    = lookup(var.credential_versions, "valkey_auth_token", 1)
  db_password_versions         = var.db_password_versions
}

module "iam" {
  source = "../../modules/iam"

  name                       = local.name
  platform_kek_arn           = module.keys.platform_kek_arn
  erasure_journal_bucket_arn = module.erasure_journal.bucket_arn
}

module "erasure_journal" {
  source = "../../modules/erasure-journal"

  bucket                = "${local.name}-erasures-${var.account_id}"
  backup_retention_days = module.data.backup_retention_days
}

module "mail" {
  source = "../../modules/mail"

  name                = local.name
  domain              = var.domain
  from                = "no-reply@${var.domain}"
  credentials_version = lookup(var.credential_versions, "smtp", 1)
}

# What the services get beside the database, by variable: values in environment, ARNs of secrets that ECS fills in secrets.
locals {
  erasure_journal = {
    ERASURE_JOURNAL_BUCKET = module.erasure_journal.bucket
    ERASURE_JOURNAL_REGION = module.erasure_journal.region
  }

  services = {
    app = {
      environment = {
        VALKEY_URL       = module.valkey.url
        VALKEY_TLS_CA    = module.valkey.tls_ca_pem
        CLIENT_IP_HEADER = module.edge.client_ip_header
      }
      secrets = {
        VALKEY_PASSWORD      = module.keys.valkey_auth_token_secret_arn
        THROTTLE_IP_HMAC_KEY = module.keys.throttle_ip_hmac_key_secret_arn
      }
    }
    worker = {
      environment = merge(local.erasure_journal, {
        PLATFORM_KEK_URI = module.keys.platform_kek_uri
        MAIL_HOST        = module.mail.host
        MAIL_PORT        = tostring(module.mail.port)
        MAIL_FROM        = module.mail.from
      })
      secrets = {
        MAIL_USERNAME = "${module.mail.credentials_secret_arn}:username::"
        MAIL_PASSWORD = "${module.mail.credentials_secret_arn}:password::"
      }
    }
    ops = {
      environment = {
        PLATFORM_KEK_URI = module.keys.platform_kek_uri
      }
      secrets = {}
    }
    migrator = {
      environment = merge(local.erasure_journal, {
        PLATFORM_KEK_URI = module.keys.platform_kek_uri
      })
      secrets = {}
    }
  }

  database = {
    pooled_jdbc_url      = module.data.pooled_jdbc_url
    pooled_tls_ca_pem    = module.data.pooled_tls_ca_pem
    direct_jdbc_url      = module.data.direct_jdbc_url
    provisioner_jdbc_url = module.data.provisioner_jdbc_url
    direct_tls_ca_pem    = module.data.direct_tls_ca_pem
    role_secret_arns     = module.keys.db_role_secret_arns
  }
}

module "app" {
  source = "../../modules/app"

  name                       = local.name
  profile                    = local.profile
  domain                     = var.domain
  release                    = var.release
  min_client_version         = var.min_client_version
  repository_urls            = module.registry.repository_urls
  cluster_arn                = module.cluster.cluster_arn
  vpc_id                     = module.network.vpc_id
  private_subnet_ids         = module.network.private_subnet_ids
  security_group_ids         = module.network.security_group_ids
  endpoint_security_group_id = module.network.endpoint_security_group_id
  services                   = local.services
  database                   = local.database
  task_role_arns             = module.iam.task_role_arns
  tenant_kek_policy          = module.iam.tenant_kek_policy
  # Both answer how long a restore may need what existed before it: the journal for erasures, the KEK for the backup itself.
  previous_kek_retention_days = module.erasure_journal.retention_days
  https_listener_arn          = module.edge.https_listener_arn
  origin_header               = module.edge.origin_header
  origin_header_value         = module.keys.origin_header_value
  ops_alb_arn                 = module.network.ops_alb_arn
  ops_certificate_arn         = module.edge.alb_certificate_arn
  ops_client_cidrs            = var.ops_client_cidrs
  desired_counts              = var.desired_counts
  server_tls                  = { for name in ["app", "ops"] : name => module.pki.certificates[name] }
  telemetry                   = { endpoint = module.telemetry.endpoint, ca_pem = module.pki.ca_cert_pem }
  log_tampering_actions       = module.telemetry.log_tampering_actions
}

# infra/terraform/bootstrap creates the role, this configuration grants what a release touches.
data "aws_iam_role" "deploy" {
  name = "${local.name}-github-deploy"
}

module "release" {
  source = "../../modules/release"

  name                         = local.name
  deploy_role_name             = data.aws_iam_role.deploy.name
  cluster_name                 = module.cluster.cluster_name
  static_bucket                = module.static.bucket
  repository_urls              = module.registry.repository_urls
  repository_arns              = module.registry.repository_arns
  infra_image_tag              = var.infra_image_tag
  services                     = module.app.services
  task_families                = module.app.task_families
  one_off_tasks                = module.app.one_off_tasks
  task_role_arns               = module.iam.task_role_arns
  execution_role_arns          = module.app.execution_role_arns
  bootstrap_execution_role_arn = module.data.bootstrap_execution_role_arn
  smoke_host                   = "release-smoke.${var.domain}"
  watched_alarms               = module.alarms.release_alarms
  role_passwords_task = {
    task_definition = module.data.bootstrap_task_definitions["role-passwords"]
    network = {
      subnets        = module.network.private_subnet_ids
      securityGroups = [module.network.security_group_ids["db-bootstrap"]]
      assignPublicIp = "DISABLED"
    }
  }
}

moved {
  from = aws_iam_role_policy.deploy
  to   = module.release.aws_iam_role_policy.deploy
}

# The generated secrets live in modules/keys, so the modules that use them plan in terraform test.
moved {
  from = module.data.aws_secretsmanager_secret.role
  to   = module.keys.aws_secretsmanager_secret.db_role
}

moved {
  from = module.data.aws_secretsmanager_secret_version.role
  to   = module.keys.aws_secretsmanager_secret_version.db_role
}

moved {
  from = module.valkey.aws_secretsmanager_secret.auth_token
  to   = module.keys.aws_secretsmanager_secret.valkey_auth_token
}

moved {
  from = module.valkey.aws_secretsmanager_secret_version.auth_token
  to   = module.keys.aws_secretsmanager_secret_version.valkey_auth_token
}
