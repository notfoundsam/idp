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
  description = "Security group of each workload, by name: alb, database and every service."
  value       = { for name, group in aws_security_group.this : name => group.id }
}

output "alb_arn" {
  description = "The public ALB."
  value       = aws_lb.public.arn
}

output "alb_dns_name" {
  description = "DNS name of the public ALB, the origin of CloudFront."
  value       = aws_lb.public.dns_name
}
