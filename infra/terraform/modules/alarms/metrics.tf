locals {
  # The alerts of dev-tools/lgtm/alerts.yaml with their local thresholds, until staging measures its own, see docs/security/logging.md.
  # Metrics Insights sums over every service, and a SCHEMA names one set of dimensions, so no series counts twice.
  namespace  = "\"${var.metrics_namespace}\""
  by_service = "SCHEMA(${local.namespace}, \"service.name\")"
  by_source  = "SCHEMA(${local.namespace}, \"service.name\", source, tls)"

  metric_alarms = {
    "unexpected-failures" = {
      description = "A request, an outbox event or a job failed in a way no code handles. The line event=failure names the causes."
      severity    = "warning"
      query       = "SELECT SUM(\"idp.failures\") FROM ${local.by_source} WHERE tls = 'false'"
      period      = 300
      periods     = 1
      threshold   = 0
      comparison  = "GreaterThanThreshold"
      missing     = "notBreaching"
    }
    "backend-tls-failures" = {
      description = "A connection to Postgres, Valkey, the KMS or SMTP failed in TLS. A certificate, a proxy or an endpoint changed."
      severity    = "critical"
      query       = "SELECT SUM(\"idp.failures\") FROM ${local.by_source} WHERE tls = 'true'"
      period      = 300
      periods     = 1
      threshold   = 0
      comparison  = "GreaterThanThreshold"
      missing     = "notBreaching"
    }
    "valkey-failures" = {
      description = "Valkey fails, and Throttle fails open, so only the counters in Postgres still hold."
      severity    = "critical"
      query       = "SELECT SUM(\"idp.throttle.failures\") FROM ${local.by_service}"
      period      = 300
      periods     = 1
      threshold   = 0
      comparison  = "GreaterThanThreshold"
      missing     = "notBreaching"
    }
    "failed-logins" = {
      description = "More than 100 failed logins a minute over all tenants for 5 minutes. The journal of each tenant names the users."
      severity    = "warning"
      query       = "SELECT SUM(\"idp.login.failures\") FROM ${local.by_service}"
      period      = 60
      periods     = 5
      threshold   = 100
      comparison  = "GreaterThanThreshold"
      missing     = "notBreaching"
    }
    # No value means that no worker reports, which is as bad as a late event.
    "outbox-lag" = {
      description = "A pending event waits more than 5 minutes past its available_at, or no worker reports. No worker runs, or one died holding claims."
      severity    = "critical"
      query       = "SELECT MAX(\"idp.outbox.lag\") FROM ${local.by_service}"
      period      = 60
      periods     = 5
      threshold   = 300
      comparison  = "GreaterThanThreshold"
      missing     = "breaching"
    }
    "outbox-dead" = {
      description = "An event ran out of attempts and needs a person, see the lines \"is dead\" of worker."
      severity    = "warning"
      query       = "SELECT MAX(\"idp.outbox.dead\") FROM ${local.by_service}"
      period      = 300
      periods     = 1
      threshold   = 0
      comparison  = "GreaterThanThreshold"
      missing     = "notBreaching"
    }
  }

  throttle_refusals = "idp.throttle.rejected"
}

resource "aws_cloudwatch_metric_alarm" "metrics" {
  for_each = local.metric_alarms

  alarm_name          = "${var.name}-${each.key}"
  alarm_description   = each.value.description
  evaluation_periods  = each.value.periods
  datapoints_to_alarm = each.value.periods
  comparison_operator = each.value.comparison
  threshold           = each.value.threshold
  treat_missing_data  = each.value.missing
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]

  metric_query {
    id          = "q"
    expression  = each.value.query
    period      = each.value.period
    return_data = true
  }

  tags = {
    Severity = each.value.severity
  }
}

# The lines of idp.security are JSON of the exporter, whose body holds the text of the line.
resource "aws_cloudwatch_log_metric_filter" "throttle_refusals" {
  name           = local.throttle_refusals
  log_group_name = var.security_log_group
  pattern        = "\"event=throttle.rejected\""

  metric_transformation {
    name      = local.throttle_refusals
    namespace = var.metrics_namespace
    value     = "1"
    unit      = "Count"
  }
}

resource "aws_cloudwatch_metric_alarm" "throttle_refusals" {
  alarm_name          = "${var.name}-throttle-refusals"
  alarm_description   = "More than 300 refusals of Throttle in 5 minutes. The lines of idp.security name the scope, the dimension and the tenant."
  namespace           = var.metrics_namespace
  metric_name         = aws_cloudwatch_log_metric_filter.throttle_refusals.metric_transformation[0].name
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  comparison_operator = "GreaterThanThreshold"
  threshold           = 300
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]

  tags = {
    Severity = "warning"
  }
}

# worker reports the earliest end of a published signing certificate in seconds since the epoch, once an hour.
resource "aws_cloudwatch_metric_alarm" "certificate_expiry" {
  alarm_name          = "${var.name}-certificate-expiry"
  alarm_description   = "A signing certificate expires within 30 days, and SPs that check the dates will refuse the responses. Rotate the signing key of the tenant."
  evaluation_periods  = 1
  comparison_operator = "LessThanThreshold"
  threshold           = 30
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]

  metric_query {
    id = "expiry"

    metric {
      namespace   = var.metrics_namespace
      metric_name = "idp.saml.certificate.expiry"
      dimensions  = { "service.name" = "worker" }
      stat        = "Minimum"
      period      = 3600
    }
  }

  metric_query {
    id          = "days"
    expression  = "(expiry - EPOCH(expiry)) / 86400"
    label       = "Days left"
    return_data = true
  }

  tags = {
    Severity = "warning"
  }
}

# The rate-based rules block a client over its limit, see docs/security/limits.md. Many blocks mean an attack or a limit too tight.
resource "aws_cloudwatch_metric_alarm" "waf" {
  provider = aws.us_east_1
  for_each = toset(var.waf.rules)

  alarm_name          = "${each.value}-blocked"
  alarm_description   = "More than 300 requests in 5 minutes blocked by the rule ${each.value} of the WAF of CloudFront."
  namespace           = "AWS/WAFV2"
  metric_name         = "BlockedRequests"
  dimensions          = { WebACL = var.waf.web_acl, Rule = each.value }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  comparison_operator = "GreaterThanThreshold"
  threshold           = 300
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms_us_east_1.arn]
  ok_actions          = [aws_sns_topic.alarms_us_east_1.arn]

  tags = {
    Severity = "warning"
  }
}
