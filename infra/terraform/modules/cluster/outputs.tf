output "cluster_arn" {
  description = "The ECS cluster."
  value       = aws_ecs_cluster.this.arn
}

output "cluster_name" {
  description = "Name of the ECS cluster."
  value       = aws_ecs_cluster.this.name
}

output "namespace_id" {
  description = "The private DNS namespace."
  value       = aws_service_discovery_private_dns_namespace.this.id
}

output "namespace" {
  description = "Name of the private DNS namespace."
  value       = aws_service_discovery_private_dns_namespace.this.name
}
