data "aws_region" "current" {}

data "aws_partition" "current" {}

data "aws_caller_identity" "current" {}

locals {
  ecs = "arn:${data.aws_partition.current.partition}:ecs:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}"

  # The recovery run stays out: only an operator who restored a backup registers it, so no release passes its role.
  registered = ["app", "worker", "ops", "migrator", "cli"]
  one_offs   = ["migrator", "cli"]

  role_passwords_family = regex("task-definition/([^:/]+):[0-9]+$", var.role_passwords_task.task_definition)[0]

  run_families = concat([for name in local.one_offs : var.task_families[name]], [local.role_passwords_family])

  passed_roles = distinct(concat(
    [for name in local.registered : var.task_role_arns[name]],
    [for name in local.registered : var.execution_role_arns[name]],
    [var.bootstrap_execution_role_arn],
  ))

  # What the scripts of infra/deploy read before they touch anything.
  config = {
    region          = data.aws_region.current.region
    cluster         = var.cluster_name
    bucket          = var.static_bucket
    platform        = "linux/arm64"
    repositories    = var.repository_urls
    infra_image_tag = var.infra_image_tag
    services        = { for name, service in var.services : name => { service = service, family = var.task_families[name] } }
    tasks = merge(
      { for name in local.one_offs : name => { family = var.task_families[name], network = var.one_off_tasks[name].network } },
      { role-passwords = { family = local.role_passwords_family, network = var.role_passwords_task.network } },
    )
    smoke  = { host = var.smoke_host, secret = aws_secretsmanager_secret.smoke_login.arn }
    alarms = { names = var.watched_alarms }
  }
}

# A person writes the login of the synthetic user here, see docs/release.md, so its password never reaches the state.
resource "aws_secretsmanager_secret" "smoke_login" {
  name                    = "${var.name}/release/smoke-login"
  description             = "Login, password and TOTP secret of the synthetic user that the smoke test of a release signs in as"
  recovery_window_in_days = 7
}

resource "aws_ssm_parameter" "release" {
  name        = "/${var.name}/release"
  description = "What a release of ${var.name} rolls out to, read by infra/deploy"
  type        = "String"
  value       = jsonencode(local.config)
}

data "aws_iam_policy_document" "deploy" {
  statement {
    sid       = "ReadReleaseSettings"
    actions   = ["ssm:GetParameter"]
    resources = [aws_ssm_parameter.release.arn]
  }

  statement {
    sid       = "EcrLogin"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  # A release pushes its images and pulls every image that will run, whose OS packages it scans.
  statement {
    sid = "PushAndScanImages"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:BatchGetImage",
      "ecr:CompleteLayerUpload",
      "ecr:DescribeImages",
      "ecr:GetDownloadUrlForLayer",
      "ecr:InitiateLayerUpload",
      "ecr:PutImage",
      "ecr:UploadLayerPart",
    ]
    resources = values(var.repository_arns)
  }

  statement {
    sid       = "ListStaticFiles"
    actions   = ["s3:ListBucket"]
    resources = ["arn:${data.aws_partition.current.partition}:s3:::${var.static_bucket}"]
  }

  statement {
    sid       = "WriteStaticFiles"
    actions   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
    resources = ["arn:${data.aws_partition.current.partition}:s3:::${var.static_bucket}/*"]
  }

  # ECS scopes neither call to a family.
  statement {
    sid       = "RegisterRevisions"
    actions   = ["ecs:DescribeTaskDefinition", "ecs:RegisterTaskDefinition"]
    resources = ["*"]
  }

  statement {
    sid       = "TagNewRevisions"
    actions   = ["ecs:TagResource"]
    resources = [for name in local.registered : "${local.ecs}:task-definition/${var.task_families[name]}:*"]

    condition {
      test     = "StringEquals"
      variable = "ecs:CreateAction"
      values   = ["RegisterTaskDefinition"]
    }
  }

  statement {
    sid       = "RollOutServices"
    actions   = ["ecs:DescribeServices", "ecs:UpdateService"]
    resources = [for service in values(var.services) : "${local.ecs}:service/${var.cluster_name}/${service}"]
  }

  statement {
    sid       = "RunReleaseTasks"
    actions   = ["ecs:RunTask"]
    resources = [for family in local.run_families : "${local.ecs}:task-definition/${family}:*"]

    condition {
      test     = "ArnEquals"
      variable = "ecs:cluster"
      values   = ["${local.ecs}:cluster/${var.cluster_name}"]
    }
  }

  statement {
    sid       = "WatchReleaseTasks"
    actions   = ["ecs:DescribeTasks"]
    resources = ["${local.ecs}:task/${var.cluster_name}/*"]
  }

  statement {
    sid       = "ReadSmokeLogin"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [aws_secretsmanager_secret.smoke_login.arn]
  }

  statement {
    sid     = "WatchAlarms"
    actions = ["cloudwatch:DescribeAlarms"]
    resources = [
      for alarm in var.watched_alarms :
      "arn:${data.aws_partition.current.partition}:cloudwatch:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:alarm:${alarm}"
    ]
  }

  statement {
    sid       = "PassTaskRoles"
    actions   = ["iam:PassRole"]
    resources = local.passed_roles

    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role_policy" "deploy" {
  name   = "release"
  role   = var.deploy_role_name
  policy = data.aws_iam_policy_document.deploy.json
}
