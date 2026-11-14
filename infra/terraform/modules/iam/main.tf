data "aws_caller_identity" "current" {}

data "aws_partition" "current" {}

data "aws_region" "current" {}

locals {
  account = data.aws_caller_identity.current.account_id
  kms     = "arn:${data.aws_partition.current.partition}:kms:${data.aws_region.current.region}:${local.account}"

  # The platform KEK is created with a policy that names these roles, so nothing here may wait for the key.
  tasks      = ["app", "worker", "ops", "cli", "migrator", "recovery"]
  role_names = { for task in local.tasks : task => "${var.name}-${task}-task" }
  # Known before the roles exist, so the key policies in kms.tf show in the plan.
  role_arns = { for task, name in local.role_names : task => "arn:${data.aws_partition.current.partition}:iam::${local.account}:role/${name}" }

  # AwsKeyManagementService names a tenant KEK alias/tenant-<id>, and one created by a move alias/tenant-<id>-v<version>.
  tenant_kek_aliases = "${local.kms}:alias/tenant-*"

  platform_kek_encrypt = { sid = "PlatformKekEncrypt", actions = ["kms:Encrypt"], resources = [var.platform_kek_arn] }

  # A tenant KEK appears at runtime, so no ARN names it here: worker attaches the policy tenant_kek of kms.tf to each one it
  # creates, and that policy grants the rights on the key. IAM holds the calls that name no key, and those on aliases.
  allowed = {
    app = []
    worker = [
      { sid = "CreateTenantKeks", actions = ["kms:CreateKey", "kms:ListAliases"], resources = ["*"] },
      { sid = "DescribeKeys", actions = ["kms:DescribeKey"], resources = ["${local.kms}:key/*"] },
      { sid = "TenantKekAliases", actions = ["kms:CreateAlias", "kms:DeleteAlias"], resources = [local.tenant_kek_aliases] },
      { sid = "PlatformKekDecrypt", actions = ["kms:Decrypt"], resources = [var.platform_kek_arn] },
      { sid = "ErasureJournalPut", actions = ["s3:PutObject"], resources = ["${var.erasure_journal_bucket_arn}/*"] },
    ]
    ops = [local.platform_kek_encrypt]
    # AWS answers GetCallerIdentity to any principal, so the statement names what the CLI does rather than grants it.
    cli      = [local.platform_kek_encrypt, { sid = "CallerIdentity", actions = ["sts:GetCallerIdentity"], resources = ["*"] }]
    migrator = []
    recovery = [
      { sid = "ListTenantKekAliases", actions = ["kms:ListAliases"], resources = ["*"] },
      { sid = "TenantKekAliases", actions = ["kms:DeleteAlias"], resources = [local.tenant_kek_aliases] },
      { sid = "ErasureJournalList", actions = ["s3:ListBucket"], resources = [var.erasure_journal_bucket_arn] },
    ]
  }

  # A deny outlasts any allow added to the role later. The services send telemetry through the collector and their console through
  # ECS, so none of them reads, changes or deletes a stored line.
  denied = {
    for task in local.tasks : task => concat(
      [{ sid = "NoShellInTasks", actions = ["ecs:ExecuteCommand"], resources = ["*"] }],
      [{ sid = "NoTelemetryStores", actions = ["cloudwatch:*", "logs:*", "xray:*"], resources = ["*"] }],
      contains(["ops", "cli"], task) ? [{ sid = "NoSecrets", actions = ["secretsmanager:*"], resources = ["*"] }] : [],
    )
  }
}

data "aws_iam_policy_document" "ecs_tasks" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [local.account]
    }

    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["arn:${data.aws_partition.current.partition}:ecs:${data.aws_region.current.region}:${local.account}:*"]
    }
  }
}

# The identity of the code in a task. ECS pulls the image and reads the secrets with the execution role of modules/app.
resource "aws_iam_role" "task" {
  for_each = local.role_names

  name               = each.value
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks.json

  lifecycle {
    postcondition {
      condition     = self.arn == local.role_arns[each.key]
      error_message = "The key policies name another ARN than the one of the role ${each.value}."
    }
  }
}

data "aws_iam_policy_document" "task" {
  for_each = local.allowed

  dynamic "statement" {
    for_each = each.value

    content {
      sid       = statement.value.sid
      effect    = "Allow"
      actions   = statement.value.actions
      resources = statement.value.resources
    }
  }

  dynamic "statement" {
    for_each = local.denied[each.key]

    content {
      sid       = statement.value.sid
      effect    = "Deny"
      actions   = statement.value.actions
      resources = statement.value.resources
    }
  }
}

resource "aws_iam_role_policy" "task" {
  for_each = local.allowed

  name   = "task"
  role   = aws_iam_role.task[each.key].id
  policy = data.aws_iam_policy_document.task[each.key].json
}
