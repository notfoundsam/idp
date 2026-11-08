locals {
  environment = "production"
  name        = "idp-${local.environment}"
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

  bucket = "${local.name}-static-${var.account_id}"
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
