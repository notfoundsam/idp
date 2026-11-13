output "topic_arns" {
  description = "Topics that the alarms notify: home in the region of the environment, us_east_1 for the WAF of CloudFront."
  value = {
    home      = aws_sns_topic.alarms.arn
    us_east_1 = aws_sns_topic.alarms_us_east_1.arn
  }
}

output "trail_arn" {
  description = "The trail of the management events of the account."
  value       = aws_cloudtrail.this.arn
}

output "release_alarms" {
  description = "The critical alarms on the metrics of the services, which a release watches and which roll it back when they go to ALARM."
  value       = sort([for key, alarm in local.metric_alarms : aws_cloudwatch_metric_alarm.metrics[key].alarm_name if alarm.severity == "critical"])
}

output "trail_log_group" {
  description = "Log group of the trail, which the audit alarms read and only the on-call engineers query."
  value       = aws_cloudwatch_log_group.trail.name
}
