output "state_bucket" {
  description = "Bucket of the environment state, the `bucket` of its backend.hcl."
  value       = aws_s3_bucket.state.id
}

output "state_kms_key_arn" {
  description = "Key that encrypts the state, the `kms_key_id` of its backend.hcl."
  value       = aws_kms_key.state.arn
}

output "terraform_role_arn" {
  description = "Role that GitHub Actions assume to plan and apply the environment."
  value       = aws_iam_role.github["terraform"].arn
}

output "deploy_role_name" {
  description = "Role that GitHub Actions assume to release. The environment configuration grants its rights."
  value       = aws_iam_role.github["deploy"].name
}
