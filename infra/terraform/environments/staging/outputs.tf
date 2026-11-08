output "tenants_base_domain" {
  description = "TENANTS_BASE_DOMAIN of worker, ops and migrator."
  value       = var.domain
}

output "alb_dns_name" {
  description = "DNS name of the public ALB."
  value       = module.network.alb_dns_name
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
  description = "The platform KEK, for the task roles."
  value       = module.keys.platform_kek_arn
}

output "erasure_journal_bucket_arn" {
  description = "Bucket of the erasure journal, for the task roles."
  value       = module.erasure_journal.bucket_arn
}

output "database" {
  description = "Connection settings the services get. The CA certificates are public, the passwords stay in the secrets."
  value = {
    pooled_jdbc_url        = module.data.pooled_jdbc_url
    pooled_tls_ca_pem      = module.data.pooled_tls_ca_pem
    direct_jdbc_url        = module.data.direct_jdbc_url
    provisioner_jdbc_url   = module.data.provisioner_jdbc_url
    direct_tls_ca_pem      = module.data.direct_tls_ca_pem
    role_secret_arns       = module.data.role_secret_arns
    master_user_secret_arn = module.data.master_user_secret_arn
  }
}
