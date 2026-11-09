mock_provider "aws" {
  override_during = plan

  mock_data "aws_iam_policy_document" {
    defaults = {
      json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}"
    }
  }

  mock_resource "aws_sns_topic" {
    defaults = {
      arn    = "arn:aws:sns:eu-central-1:111111111111:idp-test-alarms"
      region = "eu-central-1"
    }
  }

  mock_resource "aws_kms_key" {
    defaults = {
      arn    = "arn:aws:kms:eu-central-1:111111111111:key/0b4f3c1e-7a52-4d9e-9a3c-5f0e2d8b6a41"
      region = "eu-central-1"
    }
  }

  mock_resource "aws_cloudwatch_metric_alarm" {
    defaults = {
      region = "eu-central-1"
    }
  }

  mock_resource "aws_cloudwatch_log_group" {
    defaults = {
      arn = "arn:aws:logs:eu-central-1:111111111111:log-group:/idp-test/cloudtrail"
    }
  }

  mock_resource "aws_cloudtrail" {
    defaults = {
      arn = "arn:aws:cloudtrail:eu-central-1:111111111111:trail/idp-test-management"
    }
  }

  mock_resource "aws_iam_role" {
    defaults = {
      arn = "arn:aws:iam::111111111111:role/idp-test-cloudtrail"
    }
  }
}

mock_provider "aws" {
  alias           = "us_east_1"
  override_during = plan

  mock_resource "aws_sns_topic" {
    defaults = {
      arn    = "arn:aws:sns:us-east-1:111111111111:idp-test-alarms"
      region = "us-east-1"
    }
  }

  mock_resource "aws_kms_key" {
    defaults = {
      arn    = "arn:aws:kms:us-east-1:111111111111:key/5f0e2d8b-6a41-4d9e-9a3c-0b4f3c1e7a52"
      region = "us-east-1"
    }
  }

  mock_resource "aws_cloudwatch_metric_alarm" {
    defaults = {
      region = "us-east-1"
    }
  }
}

override_data {
  target = data.aws_caller_identity.current
  values = {
    account_id = "111111111111"
  }
}

override_data {
  target = data.aws_partition.current
  values = {
    partition = "aws"
  }
}

override_data {
  target = data.aws_region.current
  values = {
    region = "eu-central-1"
  }
}

variables {
  name               = "idp-test"
  trail_bucket       = "idp-test-trail-111111111111"
  metrics_namespace  = "idp-test"
  security_log_group = "/idp-test/otlp/security"
  task_role_arns = {
    for task in ["app", "worker", "ops", "cli", "migrator", "recovery"] : task => "arn:aws:iam::111111111111:role/idp-test-${task}-task"
  }
  log_tampering_actions = ["logs:Delete*", "logs:PutLogGroupDeletionProtection", "logs:PutRetentionPolicy"]
  waf = {
    web_acl = "idp-test-edge"
    rules   = ["idp-test-app-requests-per-address", "idp-test-requests-per-address"]
  }
  notification_endpoints = [{ protocol = "email", endpoint = "oncall@example.com" }]
}

run "every_alarm_notifies_the_encrypted_topic_of_its_region" {
  command = plan

  assert {
    condition = alltrue([
      for alarm in concat(
        values(aws_cloudwatch_metric_alarm.metrics),
        values(aws_cloudwatch_metric_alarm.audit),
        [aws_cloudwatch_metric_alarm.throttle_refusals, aws_cloudwatch_metric_alarm.certificate_expiry],
      ) : alarm.alarm_actions == toset([aws_sns_topic.alarms.arn]) && alarm.ok_actions == toset([aws_sns_topic.alarms.arn])
    ])
    error_message = "An alarm of the region of the environment notifies nobody or another topic than its own."
  }

  assert {
    condition = alltrue([
      for alarm in aws_cloudwatch_metric_alarm.waf :
      alarm.alarm_actions == toset([aws_sns_topic.alarms_us_east_1.arn]) && alarm.ok_actions == toset([aws_sns_topic.alarms_us_east_1.arn])
    ]) && length(aws_cloudwatch_metric_alarm.waf) == 2
    error_message = "An alarm of the WAF notifies nobody or a topic outside us-east-1."
  }

  assert {
    condition = (
      aws_sns_topic.alarms.kms_master_key_id == aws_kms_key.topic.arn &&
      aws_sns_topic.alarms_us_east_1.kms_master_key_id == aws_kms_key.topic_us_east_1.arn &&
      aws_kms_key.topic.enable_key_rotation && aws_kms_key.topic_us_east_1.enable_key_rotation
    )
    error_message = "A topic is not encrypted with its key, or the key does not rotate."
  }

  assert {
    condition = (
      [for subscription in aws_sns_topic_subscription.alarms : "${subscription.protocol} ${subscription.endpoint}"] == ["email oncall@example.com"] &&
      [for subscription in aws_sns_topic_subscription.alarms_us_east_1 : "${subscription.protocol} ${subscription.endpoint}"] == ["email oncall@example.com"]
    )
    error_message = "An endpoint does not hear from both topics."
  }
}

run "the_alarms_of_the_waf_live_in_us_east_1" {
  command = plan

  assert {
    condition = (
      alltrue([for alarm in aws_cloudwatch_metric_alarm.waf : alarm.region == "us-east-1"]) &&
      aws_sns_topic.alarms_us_east_1.region == "us-east-1" &&
      aws_kms_key.topic_us_east_1.region == "us-east-1"
    )
    error_message = "CloudWatch keeps the metrics of a web ACL of CloudFront in us-east-1 alone, and an alarm elsewhere never fires."
  }

  assert {
    condition = alltrue([
      for alarm in concat(values(aws_cloudwatch_metric_alarm.metrics), values(aws_cloudwatch_metric_alarm.audit)) : alarm.region == "eu-central-1"
    ]) && aws_sns_topic.alarms.region == "eu-central-1"
    error_message = "An alarm on the metrics of the environment lives outside its region."
  }

  assert {
    condition = {
      for rule, alarm in aws_cloudwatch_metric_alarm.waf : rule => "${alarm.namespace} ${alarm.metric_name} ${alarm.statistic} ${alarm.period} ${alarm.threshold}"
      } == {
      "idp-test-app-requests-per-address" = "AWS/WAFV2 BlockedRequests Sum 300 300"
      "idp-test-requests-per-address"     = "AWS/WAFV2 BlockedRequests Sum 300 300"
    }
    error_message = "A rule of the WAF has no alarm on its blocks, or the alarm counts something else."
  }

  assert {
    condition     = alltrue([for rule, alarm in aws_cloudwatch_metric_alarm.waf : alarm.dimensions == tomap({ WebACL = "idp-test-edge", Rule = rule })])
    error_message = "An alarm of the WAF names another web ACL or rule, or a Region, which metrics of CloudFront do not carry."
  }
}

run "the_local_alerts_hold_in_aws_with_their_thresholds" {
  command = plan

  assert {
    condition = {
      for key, alarm in aws_cloudwatch_metric_alarm.metrics : key => one(alarm.metric_query).expression
      } == {
      "unexpected-failures"  = "SELECT SUM(\"idp.failures\") FROM SCHEMA(\"idp-test\", \"service.name\", source, tls) WHERE tls = 'false'"
      "backend-tls-failures" = "SELECT SUM(\"idp.failures\") FROM SCHEMA(\"idp-test\", \"service.name\", source, tls) WHERE tls = 'true'"
      "valkey-failures"      = "SELECT SUM(\"idp.throttle.failures\") FROM SCHEMA(\"idp-test\", \"service.name\")"
      "failed-logins"        = "SELECT SUM(\"idp.login.failures\") FROM SCHEMA(\"idp-test\", \"service.name\")"
      "outbox-lag"           = "SELECT MAX(\"idp.outbox.lag\") FROM SCHEMA(\"idp-test\", \"service.name\")"
      "outbox-dead"          = "SELECT MAX(\"idp.outbox.dead\") FROM SCHEMA(\"idp-test\", \"service.name\")"
    }
    error_message = "An alert of dev-tools/lgtm/alerts.yaml is missing in AWS or reads another metric."
  }

  assert {
    condition = {
      for key, alarm in aws_cloudwatch_metric_alarm.metrics :
      key => "${alarm.comparison_operator} ${alarm.threshold} ${one(alarm.metric_query).period}x${alarm.evaluation_periods}/${alarm.datapoints_to_alarm} ${alarm.treat_missing_data}"
      } == {
      "unexpected-failures"  = "GreaterThanThreshold 0 300x1/1 notBreaching"
      "backend-tls-failures" = "GreaterThanThreshold 0 300x1/1 notBreaching"
      "valkey-failures"      = "GreaterThanThreshold 0 300x1/1 notBreaching"
      "failed-logins"        = "GreaterThanThreshold 100 60x5/5 notBreaching"
      "outbox-lag"           = "GreaterThanThreshold 300 60x5/5 breaching"
      "outbox-dead"          = "GreaterThanThreshold 0 300x1/1 notBreaching"
    }
    error_message = "A threshold differs from docs/security/logging.md, or an outbox without a worker stays silent."
  }

  assert {
    condition = (
      aws_cloudwatch_log_metric_filter.throttle_refusals.log_group_name == "/idp-test/otlp/security" &&
      aws_cloudwatch_log_metric_filter.throttle_refusals.pattern == "\"event=throttle.rejected\"" &&
      aws_cloudwatch_metric_alarm.throttle_refusals.metric_name == "idp.throttle.rejected" &&
      aws_cloudwatch_metric_alarm.throttle_refusals.namespace == "idp-test" &&
      "${aws_cloudwatch_metric_alarm.throttle_refusals.statistic} ${aws_cloudwatch_metric_alarm.throttle_refusals.period} ${aws_cloudwatch_metric_alarm.throttle_refusals.threshold}" == "Sum 300 300"
    )
    error_message = "The refusals of Throttle are not counted from the lines of idp.security, or alarm at another threshold than 300 in 5 minutes."
  }

  assert {
    condition = (
      aws_cloudwatch_metric_alarm.certificate_expiry.comparison_operator == "LessThanThreshold" &&
      aws_cloudwatch_metric_alarm.certificate_expiry.threshold == 30 &&
      toset([for query in aws_cloudwatch_metric_alarm.certificate_expiry.metric_query : query.expression if query.return_data == true]) == toset(["(expiry - EPOCH(expiry)) / 86400"]) &&
      one(flatten([for query in aws_cloudwatch_metric_alarm.certificate_expiry.metric_query : query.metric])).metric_name == "idp.saml.certificate.expiry" &&
      one(flatten([for query in aws_cloudwatch_metric_alarm.certificate_expiry.metric_query : query.metric])).dimensions == tomap({ "service.name" = "worker" })
    )
    error_message = "The alarm of the signing certificates does not count the days left until the earliest end, or not at 30."
  }
}

run "decrypt_alarms_for_every_principal_but_app_worker_and_recovery" {
  command = plan

  assert {
    condition     = aws_cloudwatch_log_metric_filter.audit["kms-decrypt"].pattern == "{ ($.eventSource = \"kms.amazonaws.com\") && ($.eventName = \"Decrypt\") && ($.userIdentity.invokedBy NOT EXISTS) && (($.userIdentity.sessionContext.sessionIssuer.arn NOT EXISTS) || (($.userIdentity.sessionContext.sessionIssuer.arn != \"arn:aws:iam::111111111111:role/idp-test-app-task\") && ($.userIdentity.sessionContext.sessionIssuer.arn != \"arn:aws:iam::111111111111:role/idp-test-worker-task\") && ($.userIdentity.sessionContext.sessionIssuer.arn != \"arn:aws:iam::111111111111:role/idp-test-recovery-task\"))) }"
    error_message = "The Decrypt filter leaves out other principals than the task roles of app, worker and recovery, or misses the root user and users."
  }

  assert {
    condition = alltrue([
      for task in ["ops", "cli", "migrator"] : !strcontains(aws_cloudwatch_log_metric_filter.audit["kms-decrypt"].pattern, var.task_role_arns[task])
    ])
    error_message = "A Decrypt by ops, the CLI or migrator would stay silent."
  }

  assert {
    condition = (
      strcontains(aws_cloudwatch_log_metric_filter.audit["kms-key-deletion"].pattern, "($.eventName = \"ScheduleKeyDeletion\")") &&
      strcontains(aws_cloudwatch_log_metric_filter.audit["kms-key-deletion"].pattern, var.task_role_arns["worker"]) &&
      strcontains(aws_cloudwatch_log_metric_filter.audit["kms-key-deletion"].pattern, var.task_role_arns["recovery"]) &&
      alltrue([for task in ["app", "ops", "cli", "migrator"] : !strcontains(aws_cloudwatch_log_metric_filter.audit["kms-key-deletion"].pattern, var.task_role_arns[task])])
    )
    error_message = "A key deletion stays silent for another principal than worker and recovery, or offboarding alarms."
  }
}

run "the_runbooks_alarm_on_keys_snapshots_the_root_user_iam_and_the_trail" {
  command = plan

  assert {
    condition = {
      for key, filter in aws_cloudwatch_log_metric_filter.audit : key => filter.pattern if !contains(["kms-decrypt", "kms-key-deletion", "iam-changes"], key)
      } == {
      "kms-key-policy"      = "{ ($.eventSource = \"kms.amazonaws.com\") && (($.eventName = \"DisableKey\") || ($.eventName = \"PutKeyPolicy\")) }"
      "rds-snapshot-export" = "{ ($.eventSource = \"rds.amazonaws.com\") && (($.eventName = \"CopyDBClusterSnapshot\") || ($.eventName = \"CopyDBSnapshot\") || ($.eventName = \"ModifyDBClusterSnapshotAttribute\") || ($.eventName = \"ModifyDBSnapshotAttribute\") || ($.eventName = \"StartExportTask\")) }"
      "root-activity"       = "{ ($.userIdentity.type = \"Root\") && ($.userIdentity.invokedBy NOT EXISTS) && ($.eventType != \"AwsServiceEvent\") }"
      "monitoring-changes"  = "{ (($.eventSource = \"cloudtrail.amazonaws.com\") && (($.eventName = \"DeleteTrail\") || ($.eventName = \"PutEventSelectors\") || ($.eventName = \"StopLogging\") || ($.eventName = \"UpdateTrail\"))) || (($.eventSource = \"logs.amazonaws.com\") && (($.eventName = \"DeleteLogGroup\") || ($.eventName = \"DeleteLogStream\") || ($.eventName = \"DeleteMetricFilter\") || ($.eventName = \"DeleteRetentionPolicy\") || ($.eventName = \"PutLogGroupDeletionProtection\") || ($.eventName = \"PutRetentionPolicy\"))) || (($.eventSource = \"monitoring.amazonaws.com\") && (($.eventName = \"DeleteAlarms\") || ($.eventName = \"DisableAlarmActions\"))) }"
    }
    error_message = "A call of the runbooks or of an administrator that hides or changes the audit stays silent."
  }

  assert {
    condition = alltrue([
      for name in ["PutRolePolicy", "AttachRolePolicy", "CreatePolicyVersion", "UpdateAssumeRolePolicy", "CreateAccessKey", "DeleteRolePolicy"] :
      strcontains(aws_cloudwatch_log_metric_filter.audit["iam-changes"].pattern, "($.eventName = \"${name}\")")
    ]) && startswith(aws_cloudwatch_log_metric_filter.audit["iam-changes"].pattern, "{ ($.eventSource = \"iam.amazonaws.com\") && ")
    error_message = "A change of an IAM policy, a trust policy or an access key stays silent."
  }

  assert {
    condition     = alltrue([for filter in aws_cloudwatch_log_metric_filter.audit : length(filter.pattern) <= 1024])
    error_message = "CloudWatch Logs refuses a filter pattern longer than 1024 characters."
  }

  assert {
    condition = alltrue([
      for key, alarm in aws_cloudwatch_metric_alarm.audit : (
        aws_cloudwatch_log_metric_filter.audit[key].log_group_name == "/idp-test/cloudtrail" &&
        one(aws_cloudwatch_log_metric_filter.audit[key].metric_transformation).namespace == alarm.namespace &&
        one(aws_cloudwatch_log_metric_filter.audit[key].metric_transformation).name == alarm.metric_name &&
        alarm.statistic == "Sum" && alarm.comparison_operator == "GreaterThanThreshold" && alarm.threshold == 0 &&
        alarm.treat_missing_data == "notBreaching"
      )
    ]) && length(aws_cloudwatch_metric_alarm.audit) == 7
    error_message = "An audit alarm reads another metric than its filter on the trail, or waits for more than one call."
  }
}

run "the_trail_records_every_management_event_into_a_protected_group" {
  command = plan

  assert {
    condition = (
      aws_cloudtrail.this.is_multi_region_trail &&
      aws_cloudtrail.this.include_global_service_events &&
      aws_cloudtrail.this.enable_log_file_validation &&
      aws_cloudtrail.this.enable_logging &&
      one(aws_cloudtrail.this.event_selector).read_write_type == "All" &&
      one(aws_cloudtrail.this.event_selector).include_management_events &&
      length(coalesce(one(aws_cloudtrail.this.event_selector).exclude_management_event_sources, [])) == 0
    )
    error_message = "The trail misses a region, IAM, the reads of KMS or the digests of its files."
  }

  assert {
    condition = (
      aws_cloudtrail.this.cloud_watch_logs_group_arn == "arn:aws:logs:eu-central-1:111111111111:log-group:/idp-test/cloudtrail:*" &&
      aws_cloudtrail.this.s3_bucket_name == "idp-test-trail-111111111111" &&
      aws_cloudwatch_log_group.trail.deletion_protection_enabled &&
      aws_cloudwatch_log_group.trail.retention_in_days == 365
    )
    error_message = "The trail writes elsewhere, or its group can be deleted or keeps less than a year."
  }

  assert {
    condition = (
      length([for statement in data.aws_iam_policy_document.trail.statement : statement if statement.effect == "Allow"]) == 1 &&
      one([for statement in data.aws_iam_policy_document.trail.statement : statement.actions if statement.effect == "Allow"]) == toset(["logs:CreateLogStream", "logs:PutLogEvents"]) &&
      one([for statement in data.aws_iam_policy_document.trail.statement : statement.resources if statement.effect == "Allow"]) == toset(["arn:aws:logs:eu-central-1:111111111111:log-group:/idp-test/cloudtrail:log-stream:*"]) &&
      one([for statement in data.aws_iam_policy_document.trail.statement : statement.actions if statement.effect == "Deny"]) == toset(var.log_tampering_actions)
    )
    error_message = "CloudTrail may do more than append to its own group, or may change what it wrote."
  }

  assert {
    condition = (
      one(data.aws_iam_policy_document.trail_assume.statement).principals == toset([{ type = "Service", identifiers = toset(["cloudtrail.amazonaws.com"]) }]) &&
      one(one(data.aws_iam_policy_document.trail_assume.statement).condition).values == tolist(["arn:aws:cloudtrail:eu-central-1:111111111111:trail/idp-test-management"])
    )
    error_message = "Another service or another trail may assume the role of the trail."
  }
}

run "nobody_deletes_what_the_trail_delivered" {
  command = plan

  assert {
    condition = anytrue([
      for statement in data.aws_iam_policy_document.trail_bucket.statement :
      statement.effect == "Deny" && statement.actions == toset(["s3:DeleteObject", "s3:DeleteObjectVersion"]) && statement.principals == toset([{ type = "*", identifiers = toset(["*"]) }])
    ])
    error_message = "Somebody may delete a file of the trail before its retention ends."
  }

  assert {
    condition = alltrue([
      for statement in data.aws_iam_policy_document.trail_bucket.statement : (
        statement.principals == toset([{ type = "Service", identifiers = toset(["cloudtrail.amazonaws.com"]) }]) &&
        contains([for condition in statement.condition : "${condition.variable}=${join(",", condition.values)}"], "aws:SourceArn=arn:aws:cloudtrail:eu-central-1:111111111111:trail/idp-test-management")
      ) if statement.effect == "Allow"
    ]) && one([for statement in data.aws_iam_policy_document.trail_bucket.statement : statement.resources if contains(statement.actions, "s3:PutObject")]) == toset(["arn:aws:s3:::idp-test-trail-111111111111/AWSLogs/111111111111/*"])
    error_message = "Another principal or trail may write into the bucket of the trail, or outside the prefix of the account."
  }

  assert {
    condition = (
      aws_s3_bucket_public_access_block.trail.block_public_policy && aws_s3_bucket_public_access_block.trail.restrict_public_buckets &&
      one(one(aws_s3_bucket_lifecycle_configuration.trail.rule).expiration).days == 365 &&
      one(one(aws_s3_bucket_lifecycle_configuration.trail.rule).noncurrent_version_expiration).noncurrent_days == 365
    )
    error_message = "The bucket of the trail can be public, or removes an event or an older version before a year."
  }
}

run "only_alarms_of_the_account_and_region_use_the_key_of_a_topic" {
  command = plan

  assert {
    condition = alltrue([
      for region, document in data.aws_iam_policy_document.topic_key : (
        one([for statement in document.statement : statement.actions if statement.sid == "AlarmsPublish"]) == toset(["kms:Decrypt", "kms:GenerateDataKey*"]) &&
        toset(flatten([for statement in document.statement : [for condition in statement.condition : "${condition.variable}=${join(",", condition.values)}"] if statement.sid == "AlarmsPublish"])) == toset([
          "aws:SourceAccount=111111111111",
          "aws:SourceArn=arn:aws:cloudwatch:${region == "home" ? "eu-central-1" : "us-east-1"}:111111111111:alarm:*",
        ])
      )
    ])
    error_message = "CloudWatch of another account or region may use the key of a topic, or for more than to publish."
  }
}

run "a_missing_recovery_role_refused" {
  command = plan

  variables {
    task_role_arns = {
      app    = "arn:aws:iam::111111111111:role/idp-test-app-task"
      worker = "arn:aws:iam::111111111111:role/idp-test-worker-task"
    }
  }

  expect_failures = [var.task_role_arns]
}

run "a_role_with_a_path_refused" {
  command = plan

  variables {
    task_role_arns = {
      for task in ["app", "worker", "recovery"] : task => "arn:aws:iam::111111111111:role/services/idp-test-${task}-task"
    }
  }

  expect_failures = [var.task_role_arns]
}

run "a_plain_http_endpoint_refused" {
  command = plan

  variables {
    notification_endpoints = [{ protocol = "https", endpoint = "http://hooks.example.com/alarms" }]
  }

  expect_failures = [var.notification_endpoints]
}

run "a_trail_shorter_than_a_year_refused" {
  command = plan

  variables {
    trail_retention_days = 90
  }

  expect_failures = [var.trail_retention_days]
}

run "a_web_acl_without_rules_refused" {
  command = plan

  variables {
    waf = { web_acl = "idp-test-edge", rules = [] }
  }

  expect_failures = [var.waf]
}
