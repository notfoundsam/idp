output "repository_urls" {
  description = "URL of each repository, by image name."
  value       = { for name, repository in aws_ecr_repository.this : name => repository.repository_url }
}

output "repository_arns" {
  description = "ARN of each repository, by image name."
  value       = { for name, repository in aws_ecr_repository.this : name => repository.arn }
}
