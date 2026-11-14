output "endpoint" {
  description = "OTEL_EXPORTER_OTLP_ENDPOINT of app, worker and ops: the collector over HTTPS under the name of its certificate."
  value       = "https://${aws_service_discovery_service.collector.name}.${var.namespace}:${local.port}"
}

output "log_groups" {
  description = "Name of each log group of telemetry: logs, security (lines of idp.security), metrics (EMF events), spans and collector."
  value       = { for key, group in aws_cloudwatch_log_group.this : key => group.name }
}

output "metrics_namespace" {
  description = "CloudWatch namespace of the metrics of the services, with the dimension service.name."
  value       = local.collector_environment.METRICS_NAMESPACE
}

output "read_policy_arn" {
  description = "Policy that reads the logs, traces and metrics, for the permission set of the on-call engineers alone."
  value       = aws_iam_policy.read.arn
}

output "log_tampering_actions" {
  description = "Calls of CloudWatch Logs that read, change or delete stored lines, which every role that writes logs denies."
  value       = local.log_tampering
}

output "collector_task_role_arn" {
  description = "Task role of the collector, the only principal of the services that writes to the telemetry log groups."
  value       = aws_iam_role.task.arn
}
