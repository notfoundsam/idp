output "one_off_tasks" {
  description = "Task definition and awsvpcConfiguration of each one-off run: migrator, its recovery run after a restore and the operator CLI."
  value = {
    for name in ["migrator", "recovery", "cli"] : name => {
      task_definition = aws_ecs_task_definition.this[name].arn
      network = {
        subnets        = var.private_subnet_ids
        securityGroups = [var.security_group_ids[local.security_group[name]]]
        assignPublicIp = "DISABLED"
      }
    }
  }
}

output "services" {
  description = "Name of each ECS service."
  value       = { for name, service in aws_ecs_service.this : name => service.name }
}

output "execution_role_arns" {
  description = "Execution role of each task, which pulls its image and reads its secrets."
  value       = { for name, role in aws_iam_role.execution : name => role.arn }
}

output "idp_metadata_secret_arn" {
  description = "Secret with the SAML metadata of ops-sso, which the operator writes before ops starts."
  value       = aws_secretsmanager_secret.idp_metadata.arn
}
