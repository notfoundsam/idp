locals {
  audit_namespace = "${var.name}/audit"

  # A task calls AWS with a session of its task role, which CloudTrail names as the issuer. A user or the root user has none.
  issuer = "$.userIdentity.sessionContext.sessionIssuer.arn"
  outside = {
    for key, tasks in { decrypt = ["app", "worker", "recovery"], deletion = ["worker", "recovery"] } :
    key => "((${local.issuer} NOT EXISTS) || (${join(" && ", [for task in tasks : "(${local.issuer} != \"${var.task_role_arns[task]}\")"])}))"
  }

  calls = {
    decrypt  = { "kms.amazonaws.com" = ["Decrypt"] }
    deletion = { "kms.amazonaws.com" = ["ScheduleKeyDeletion"] }
    keys     = { "kms.amazonaws.com" = ["DisableKey", "PutKeyPolicy"] }
    snapshots = {
      "rds.amazonaws.com" = ["CopyDBClusterSnapshot", "CopyDBSnapshot", "ModifyDBClusterSnapshotAttribute", "ModifyDBSnapshotAttribute", "StartExportTask"]
    }
    iam = {
      "iam.amazonaws.com" = [
        "AttachGroupPolicy", "AttachRolePolicy", "AttachUserPolicy", "CreateAccessKey", "CreateLoginProfile", "CreatePolicy",
        "CreatePolicyVersion", "DeleteGroupPolicy", "DeletePolicy", "DeletePolicyVersion", "DeleteRolePolicy", "DeleteUserPolicy",
        "DetachGroupPolicy", "DetachRolePolicy", "DetachUserPolicy", "PutGroupPolicy", "PutRolePolicy", "PutUserPolicy",
        "UpdateAssumeRolePolicy", "UpdateLoginProfile",
      ]
    }
    monitoring = {
      "cloudtrail.amazonaws.com" = ["DeleteTrail", "PutEventSelectors", "StopLogging", "UpdateTrail"]
      "logs.amazonaws.com"       = ["DeleteLogGroup", "DeleteLogStream", "DeleteMetricFilter", "DeleteRetentionPolicy", "PutLogGroupDeletionProtection", "PutRetentionPolicy"]
      "monitoring.amazonaws.com" = ["DeleteAlarms", "DisableAlarmActions"]
    }
  }

  call_patterns = {
    for key, sources in local.calls : key => join(" || ", [
      for source, names in sources : format(
        length(sources) == 1 ? "%s" : "(%s)",
        "($.eventSource = \"${source}\") && ${format(length(names) == 1 ? "%s" : "(%s)", join(" || ", [for name in names : "($.eventName = \"${name}\")"]))}",
      )
    ])
  }

  # The runbooks of docs/security name these. Calls that an AWS service makes for a principal, such as Secrets Manager decrypting a
  # secret, carry invokedBy and are left out.
  audit_alarms = {
    "kms-decrypt" = {
      description = "Decrypt with a KMS key by a principal other than the task roles of app, worker and recovery, see docs/security/key-compromise-runbook.md."
      pattern     = "{ ${local.call_patterns.decrypt} && ($.userIdentity.invokedBy NOT EXISTS) && ${local.outside.decrypt} }"
    }
    "kms-key-deletion" = {
      description = "ScheduleKeyDeletion by a principal other than the task roles of worker and recovery."
      pattern     = "{ ${local.call_patterns.deletion} && ${local.outside.deletion} }"
    }
    "kms-key-policy" = {
      description = "A key policy replaced or a key disabled. Put back the policy of infra/terraform and find who changed it."
      pattern     = "{ ${local.call_patterns.keys} }"
    }
    "rds-snapshot-export" = {
      description = "A snapshot of RDS copied, shared or exported, see docs/security/key-compromise-runbook.md."
      pattern     = "{ ${local.call_patterns.snapshots} }"
    }
    "root-activity" = {
      description = "The root user of the account made a call."
      pattern     = "{ ($.userIdentity.type = \"Root\") && ($.userIdentity.invokedBy NOT EXISTS) && ($.eventType != \"AwsServiceEvent\") }"
    }
    "iam-changes" = {
      description = "An IAM policy, a trust policy, an access key or a console password changed."
      pattern     = "{ ${local.call_patterns.iam} }"
    }
    "monitoring-changes" = {
      description = "The trail, a log group, its retention or deletion protection, a metric filter or an alarm changed."
      pattern     = "{ ${local.call_patterns.monitoring} }"
    }
  }
}

resource "aws_cloudwatch_log_metric_filter" "audit" {
  for_each = local.audit_alarms

  name           = each.key
  log_group_name = aws_cloudwatch_log_group.trail.name
  pattern        = each.value.pattern

  metric_transformation {
    name      = each.key
    namespace = local.audit_namespace
    value     = "1"
    unit      = "Count"
  }
}

resource "aws_cloudwatch_metric_alarm" "audit" {
  for_each = local.audit_alarms

  alarm_name          = "${var.name}-${each.key}"
  alarm_description   = each.value.description
  namespace           = local.audit_namespace
  metric_name         = aws_cloudwatch_log_metric_filter.audit[each.key].metric_transformation[0].name
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]

  tags = {
    Severity = "critical"
  }
}
