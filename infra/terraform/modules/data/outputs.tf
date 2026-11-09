output "database_address" {
  description = "Host name of RDS, which its certificate names."
  value       = aws_db_instance.this.address
}

output "database_kms_key_arn" {
  description = "Key of the database storage and its snapshots."
  value       = aws_kms_key.database.arn
}

output "backup_retention_days" {
  description = "Days RDS keeps automated backups. The erasure journal keeps its entries longer."
  value       = aws_db_instance.this.backup_retention_period
}

output "master_user_secret_arn" {
  description = "Secret that RDS keeps the master password in. Only the owner bootstrap reads it."
  value       = aws_db_instance.this.master_user_secret[0].secret_arn
}

output "role_secret_arns" {
  description = "Secret with the password of each database role."
  value       = { for role, secret in aws_secretsmanager_secret.role : role => secret.arn }
}

output "pooled_jdbc_url" {
  description = "DB_URL of app, worker and ops: PgBouncer with verify-full against the certificate in DB_TLS_CA."
  value       = "jdbc:postgresql://pgbouncer.${var.namespace}:5432/${aws_db_instance.this.db_name}?prepareThreshold=0&${local.tls_factory}&sslfactoryarg=env:DB_TLS_CA"
}

output "pooled_tls_ca_pem" {
  description = "DB_TLS_CA of app, worker and ops: the internal CA."
  value       = var.internal_ca_pem
}

output "direct_jdbc_url" {
  description = "DB_URL of migrator: RDS with verify-full against the certificate in DB_TLS_CA."
  value       = "jdbc:postgresql://${aws_db_instance.this.endpoint}/${aws_db_instance.this.db_name}?${local.tls_factory}&sslfactoryarg=env:DB_TLS_CA"
}

output "provisioner_jdbc_url" {
  description = "PROVISIONER_DB_URL of worker: RDS as idp_owner with verify-full against the certificate in PROVISIONER_DB_TLS_CA."
  value       = "jdbc:postgresql://${aws_db_instance.this.endpoint}/${aws_db_instance.this.db_name}?options=-c%20role%3Didp_owner&${local.tls_factory}&sslfactoryarg=env:PROVISIONER_DB_TLS_CA"
}

output "direct_tls_ca_pem" {
  description = "DB_TLS_CA of migrator and PROVISIONER_DB_TLS_CA of worker: the one RDS root of ca_cert_identifier."
  value       = local.rds_root_pem
}

output "bootstrap_task_definitions" {
  description = "Task definitions of the owner bootstrap and of the role passwords, see infra/terraform/README.md."
  value       = { for name, definition in aws_ecs_task_definition.bootstrap : name => definition.arn }
}

output "bootstrap_execution_role_arn" {
  description = "Execution role of the bootstrap tasks, which whoever runs one passes to ECS."
  value       = aws_iam_role.execution["db-bootstrap"].arn
}
