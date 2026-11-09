output "tenants_base_domain" {
  description = "TENANTS_BASE_DOMAIN of worker, ops and migrator."
  value       = var.domain
}

output "alb_dns_name" {
  description = "DNS name of the public ALB."
  value       = module.network.alb_dns_name
}

output "https_listener_arn" {
  description = "HTTPS listener of the public ALB, for the rules of the services."
  value       = module.edge.https_listener_arn
}

output "distribution_domain_name" {
  description = "Target of the record *.<domain>, a CNAME or an alias at the DNS host."
  value       = module.edge.distribution_domain_name
}

output "certificate_validation_records" {
  description = "DNS records to create at the DNS host before the certificates are issued, see infra/terraform/README.md."
  value       = module.edge.certificate_validation_records
}

output "static_bucket" {
  description = "Bucket of the frontend files."
  value       = module.static.bucket
}

output "repository_urls" {
  description = "ECR repository of each image."
  value       = module.registry.repository_urls
}

output "cluster_name" {
  description = "ECS cluster of the environment."
  value       = module.cluster.cluster_name
}

output "bootstrap_task_definitions" {
  description = "Task definitions of the database bootstrap, see infra/terraform/README.md."
  value       = module.data.bootstrap_task_definitions
}

output "bootstrap_network" {
  description = "awsvpcConfiguration of a bootstrap run."
  value = {
    subnets        = module.network.private_subnet_ids
    securityGroups = [module.network.security_group_ids["db-bootstrap"]]
    assignPublicIp = "DISABLED"
  }
}

output "services" {
  description = "Settings of app, worker, ops and migrator beside the database: environment by value, secrets by ARN."
  value       = local.services
}

output "platform_kek_arn" {
  description = "The platform KEK."
  value       = module.keys.platform_kek_arn
}

output "erasure_journal_bucket_arn" {
  description = "Bucket of the erasure journal."
  value       = module.erasure_journal.bucket_arn
}

output "task_role_arns" {
  description = "Task role of each task, the principals that CloudTrail names for the calls of the services."
  value       = module.iam.task_role_arns
}

output "database" {
  description = "Connection settings the services get. The CA certificates are public, the passwords stay in the secrets."
  value       = merge(local.database, { master_user_secret_arn = module.data.master_user_secret_arn })
}

output "one_off_tasks" {
  description = "Task definition and awsvpcConfiguration of migrator and of the operator CLI, see infra/terraform/README.md."
  value       = module.app.one_off_tasks
}

output "ops_alb_dns_name" {
  description = "Target of the record ops.<domain>: the internal ALB of ops, which only the VPN reaches."
  value       = module.network.ops_alb_dns_name
}

output "idp_metadata_secret_arn" {
  description = "Secret that the operator fills with the SAML metadata of ops-sso before ops starts."
  value       = module.app.idp_metadata_secret_arn
}
