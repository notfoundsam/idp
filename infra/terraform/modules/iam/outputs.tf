output "task_role_arns" {
  description = "Task role of app, worker, ops, the CLI, migrator and the recovery run of migrator, for the task definitions."
  value       = { for name, role in aws_iam_role.task : name => role.arn }
}

output "platform_kek_policy" {
  description = "Key policy of the platform KEK, for modules/keys."
  value       = data.aws_iam_policy_document.platform_kek.json
}

output "tenant_kek_policy" {
  description = "Key policy of every tenant KEK, KMS_AWS_KEY_POLICY of worker."
  value       = data.aws_iam_policy_document.tenant_kek.json
}
