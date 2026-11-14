locals {
  root = "arn:${data.aws_partition.current.partition}:iam::${local.account}:root"
  # Conditions on the ARN keep holding for a role created again under the same name, a principal would not.
  operator = [local.role_arns.ops, local.role_arns.cli]
}

# The account statement lets IAM grant the rest, as the default policy does. The denies hold for the root user too, who can
# still replace the policy, which CloudTrail records.
data "aws_iam_policy_document" "platform_kek" {
  statement {
    sid       = "Account"
    effect    = "Allow"
    actions   = ["kms:*"]
    resources = ["*"]

    principals {
      type        = "AWS"
      identifiers = [local.root]
    }
  }

  statement {
    sid         = "OperatorEncryptsAlone"
    effect      = "Deny"
    not_actions = ["kms:Encrypt"]
    resources   = ["*"]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "ArnEquals"
      variable = "aws:PrincipalArn"
      values   = local.operator
    }
  }

  statement {
    sid       = "DecryptByWorkerAlone"
    effect    = "Deny"
    actions   = ["kms:Decrypt"]
    resources = ["*"]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "ArnNotEquals"
      variable = "aws:PrincipalArn"
      values   = [local.role_arns.worker]
    }
  }

  statement {
    sid       = "NoReEncryptOrGrants"
    effect    = "Deny"
    actions   = ["kms:ReEncrypt*", "kms:CreateGrant"]
    resources = ["*"]

    principals {
      type        = "*"
      identifiers = ["*"]
    }
  }

  statement {
    sid       = "NoDeletionByServices"
    effect    = "Deny"
    actions   = ["kms:ScheduleKeyDeletion"]
    resources = ["*"]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "ArnEquals"
      variable = "aws:PrincipalArn"
      values   = values(local.role_arns)
    }
  }
}

# KMS_AWS_KEY_POLICY of worker, which attaches it to every tenant KEK it creates. A tenant KEK appears at runtime, so the rights
# of the services on it live here rather than in IAM. A change reaches only the keys created after it.
data "aws_iam_policy_document" "tenant_kek" {
  statement {
    sid       = "Account"
    effect    = "Allow"
    actions   = ["kms:*"]
    resources = ["*"]

    principals {
      type        = "AWS"
      identifiers = [local.root]
    }
  }

  statement {
    sid       = "AppDecrypts"
    effect    = "Allow"
    actions   = ["kms:Decrypt"]
    resources = ["*"]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "ArnEquals"
      variable = "aws:PrincipalArn"
      values   = [local.role_arns.app]
    }
  }

  # Provisioning wraps the tenant key, a move to another KMS wraps it again, and offboarding destroys the KEK.
  statement {
    sid    = "WorkerManages"
    effect = "Allow"
    actions = [
      "kms:CreateAlias",
      "kms:Decrypt",
      "kms:DeleteAlias",
      "kms:EnableKeyRotation",
      "kms:Encrypt",
      "kms:ScheduleKeyDeletion",
    ]
    resources = ["*"]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "ArnEquals"
      variable = "aws:PrincipalArn"
      values   = [local.role_arns.worker]
    }
  }

  # After a restore, the erasure journal destroys again the KEKs of tenants deleted since the backup.
  statement {
    sid       = "RecoveryDestroys"
    effect    = "Allow"
    actions   = ["kms:Decrypt", "kms:DeleteAlias", "kms:ScheduleKeyDeletion"]
    resources = ["*"]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "ArnEquals"
      variable = "aws:PrincipalArn"
      values   = [local.role_arns.recovery]
    }
  }

  statement {
    sid       = "NoOperator"
    effect    = "Deny"
    actions   = ["kms:*"]
    resources = ["*"]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "ArnEquals"
      variable = "aws:PrincipalArn"
      values   = local.operator
    }
  }

  statement {
    sid       = "DecryptByServicesAlone"
    effect    = "Deny"
    actions   = ["kms:Decrypt"]
    resources = ["*"]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "ArnNotEquals"
      variable = "aws:PrincipalArn"
      values   = [local.role_arns.app, local.role_arns.worker, local.role_arns.recovery]
    }
  }

  statement {
    sid       = "DeletionByOffboardingAlone"
    effect    = "Deny"
    actions   = ["kms:ScheduleKeyDeletion"]
    resources = ["*"]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "ArnNotEquals"
      variable = "aws:PrincipalArn"
      values   = [local.role_arns.worker, local.role_arns.recovery]
    }
  }

  statement {
    sid       = "NoReEncryptOrGrants"
    effect    = "Deny"
    actions   = ["kms:ReEncrypt*", "kms:CreateGrant"]
    resources = ["*"]

    principals {
      type        = "*"
      identifiers = ["*"]
    }
  }
}
