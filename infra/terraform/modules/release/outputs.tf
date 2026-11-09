output "parameter_name" {
  description = "SSM parameter with the settings of a release, which the release workflow reads first."
  value       = aws_ssm_parameter.release.name
}
