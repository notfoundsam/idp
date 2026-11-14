output "parameter_name" {
  description = "SSM parameter with the settings of a release, which the release workflow reads first."
  value       = aws_ssm_parameter.release.name
}

output "smoke_login_secret" {
  description = "Secret that holds the login of the synthetic user of the smoke test, written by a person, see docs/release.md."
  value       = aws_secretsmanager_secret.smoke_login.name
}
