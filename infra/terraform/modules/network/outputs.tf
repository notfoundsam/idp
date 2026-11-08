output "vpc_id" {
  description = "The VPC."
  value       = aws_vpc.this.id
}

output "public_subnet_ids" {
  description = "Subnets of the public ALB."
  value       = [for subnet in aws_subnet.public : subnet.id]
}

output "private_subnet_ids" {
  description = "Subnets of the ECS tasks, without a route to the internet."
  value       = [for subnet in aws_subnet.private : subnet.id]
}

output "database_subnet_ids" {
  description = "Subnets of RDS and ElastiCache."
  value       = [for subnet in aws_subnet.database : subnet.id]
}

output "security_group_ids" {
  description = "Security group of each workload, by name: alb, ops-alb, database, valkey and every service."
  value       = { for name, group in aws_security_group.this : name => group.id }
}

output "endpoint_security_group_id" {
  description = "Security group of the interface endpoints, the destination of SMTP to SES."
  value       = aws_security_group.endpoints.id
}

output "alb_arn" {
  description = "The public ALB."
  value       = aws_lb.public.arn
}

output "alb_dns_name" {
  description = "DNS name of the public ALB, the origin of CloudFront."
  value       = aws_lb.public.dns_name
}

output "ops_alb_arn" {
  description = "The internal ALB of ops."
  value       = aws_lb.ops.arn
}

output "ops_alb_dns_name" {
  description = "DNS name of the internal ALB. It resolves to private addresses, which only the VPN reaches."
  value       = aws_lb.ops.dns_name
}
