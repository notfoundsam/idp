locals {
  environment = "production"
  name        = "idp-${local.environment}"
  # staging is prod, except that a load test may switch the limits of app off.
  profile = "prod"
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
  certificates = {
    pgbouncer = ["pgbouncer.${module.cluster.namespace}"]
  }
}

module "data" {
  source = "../../modules/data"

  name                = local.name
  database_subnet_ids = module.network.database_subnet_ids
  private_subnet_ids  = module.network.private_subnet_ids
  security_group_ids  = module.network.security_group_ids
  cluster_arn         = module.cluster.cluster_arn
  namespace_id        = module.cluster.namespace_id
  namespace           = module.cluster.namespace
  pgbouncer_image     = "${module.registry.repository_urls["pgbouncer"]}:${var.infra_image_tag}"
  db_bootstrap_image  = "${module.registry.repository_urls["db-bootstrap"]}:${var.infra_image_tag}"
  internal_ca_pem     = module.pki.ca_cert_pem
  pgbouncer_tls       = module.pki.certificates["pgbouncer"]
  instance_class      = var.db_instance_class
  password_versions   = var.db_password_versions
}

module "valkey" {
  source = "../../modules/valkey"

  name               = local.name
  subnet_ids         = module.network.database_subnet_ids
  security_group_ids = module.network.security_group_ids
  node_type          = var.valkey_node_type
  auth_token_version = lookup(var.credential_versions, "valkey_auth_token", 1)
}

module "keys" {
  source = "../../modules/keys"

  name                         = local.name
  throttle_ip_hmac_key_version = lookup(var.credential_versions, "throttle_ip_hmac_key", 1)
  origin_header_version        = lookup(var.credential_versions, "origin_header", 1)
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
        VALKEY_PASSWORD      = module.valkey.auth_token_secret_arn
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
    role_secret_arns     = module.data.role_secret_arns
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
  https_listener_arn         = module.edge.https_listener_arn
  origin_header              = module.edge.origin_header
  origin_header_value        = module.keys.origin_header_value
  ops_alb_arn                = module.network.ops_alb_arn
  ops_certificate_arn        = module.edge.alb_certificate_arn
  ops_client_cidrs           = var.ops_client_cidrs
  desired_counts             = var.desired_counts
}

# infra/terraform/bootstrap creates the role, this configuration grants what a release touches.
data "aws_iam_role" "deploy" {
  name = "${local.name}-github-deploy"
}

data "aws_iam_policy_document" "deploy" {
  statement {
    sid       = "EcrLogin"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid = "PushImages"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:BatchGetImage",
      "ecr:CompleteLayerUpload",
      "ecr:DescribeImages",
      "ecr:InitiateLayerUpload",
      "ecr:PutImage",
      "ecr:UploadLayerPart",
    ]
    resources = values(module.registry.repository_arns)
  }

  statement {
    sid       = "ListStaticFiles"
    actions   = ["s3:ListBucket"]
    resources = [module.static.bucket_arn]
  }

  statement {
    sid       = "WriteStaticFiles"
    actions   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
    resources = ["${module.static.bucket_arn}/*"]
  }
}

resource "aws_iam_role_policy" "deploy" {
  name   = "release"
  role   = data.aws_iam_role.deploy.name
  policy = data.aws_iam_policy_document.deploy.json
}
