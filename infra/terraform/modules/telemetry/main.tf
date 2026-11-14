data "aws_caller_identity" "current" {}

data "aws_partition" "current" {}

data "aws_region" "current" {}

locals {
  account = data.aws_caller_identity.current.account_id
  region  = data.aws_region.current.region
  logs    = "arn:${data.aws_partition.current.partition}:logs:${local.region}:${local.account}:log-group"

  # The periods of docs/security/logging.md. X-Ray with Transaction Search keeps spans in aws/spans, once per account and region.
  log_groups = {
    logs     = { name = "/${var.name}/otlp", retention = 90 }
    security = { name = "/${var.name}/otlp/security", retention = 365 }
    # Embedded metric format: CloudWatch reads the metrics out of these events and keeps them 15 months on its own.
    metrics   = { name = "/${var.name}/otlp/metrics", retention = 14 }
    spans     = { name = "aws/spans", retention = 14 }
    collector = { name = "/${var.name}/otel-collector", retention = 90 }
  }

  # Known before the groups exist, so the policies show in the plan.
  log_group_arns = { for key, group in local.log_groups : key => "${local.logs}:${group.name}" }

  # Whatever writes logs may change or read none of them, so a stored line stays until its retention ends.
  log_tampering = [
    "logs:AssociateKmsKey",
    "logs:CreateExportTask",
    "logs:Delete*",
    "logs:DisassociateKmsKey",
    "logs:FilterLogEvents",
    "logs:Get*",
    "logs:PutLogGroupDeletionProtection",
    "logs:PutResourcePolicy",
    "logs:PutRetentionPolicy",
    "logs:PutSubscriptionFilter",
    "logs:Start*",
    "logs:Unmask",
  ]
}

# Deletion protection keeps a group and its lines until somebody turns it off, which CloudTrail records. Nothing that writes may.
resource "aws_cloudwatch_log_group" "this" {
  for_each = local.log_groups

  name                        = each.value.name
  retention_in_days           = each.value.retention
  deletion_protection_enabled = true

  lifecycle {
    postcondition {
      condition     = trimsuffix(self.arn, ":*") == local.log_group_arns[each.key]
      error_message = "The policies name another ARN than the one of the log group ${each.value.name}."
    }
  }
}

data "aws_iam_policy_document" "spans" {
  statement {
    sid       = "XRayWritesSpans"
    effect    = "Allow"
    actions   = ["logs:PutLogEvents"]
    resources = ["${local.log_group_arns.spans}:*"]

    principals {
      type        = "Service"
      identifiers = ["xray.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [local.account]
    }

    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["arn:${data.aws_partition.current.partition}:xray:${local.region}:${local.account}:*"]
    }
  }
}

resource "aws_cloudwatch_log_resource_policy" "spans" {
  policy_name     = "${var.name}-xray-spans"
  policy_document = data.aws_iam_policy_document.spans.json
}

# Transaction Search: X-Ray takes spans over OTLP and stores them in aws/spans, whose retention is that of traces.
resource "aws_xray_trace_segment_destination" "this" {
  destination = "CloudWatchLogs"

  depends_on = [aws_cloudwatch_log_group.this, aws_cloudwatch_log_resource_policy.spans]
}

# For the on-call engineers alone, see infra/terraform/README.md. It reads and changes nothing else.
data "aws_iam_policy_document" "read" {
  statement {
    sid     = "ReadLogs"
    effect  = "Allow"
    actions = ["logs:DescribeLogStreams", "logs:FilterLogEvents", "logs:GetLogEvents", "logs:StartLiveTail", "logs:StartQuery"]
    resources = [
      "${local.logs}:/${var.name}/*",
      "${local.logs}:/${var.name}/*:log-stream:*",
      local.log_group_arns.spans,
      "${local.log_group_arns.spans}:log-stream:*",
    ]
  }

  statement {
    sid       = "Queries"
    effect    = "Allow"
    actions   = ["logs:DescribeLogGroups", "logs:DescribeQueries", "logs:GetLogRecord", "logs:GetQueryResults", "logs:StopQuery"]
    resources = ["*"]
  }

  statement {
    sid       = "ReadTraces"
    effect    = "Allow"
    actions   = ["xray:BatchGetTraces", "xray:GetServiceGraph", "xray:GetTraceGraph", "xray:GetTraceSummaries"]
    resources = ["*"]
  }

  statement {
    sid       = "ReadMetrics"
    effect    = "Allow"
    actions   = ["cloudwatch:DescribeAlarms", "cloudwatch:GetMetricData", "cloudwatch:GetMetricStatistics", "cloudwatch:ListMetrics"]
    resources = ["*"]
  }
}

resource "aws_iam_policy" "read" {
  name        = "${var.name}-telemetry-read"
  description = "Read the logs, traces and metrics of ${var.name}. Only the on-call engineers get it."
  policy      = data.aws_iam_policy_document.read.json
}
