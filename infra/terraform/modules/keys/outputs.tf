output "platform_kek_arn" {
  description = "The platform KEK, for the task roles of modules/iam: ops and the CLI only encrypt with it, worker decrypts."
  value       = aws_kms_key.platform.arn
}

output "platform_kek_uri" {
  description = "PLATFORM_KEK_URI of worker, ops and migrator."
  value       = "aws-kms://${aws_kms_key.platform.arn}"
}

output "origin_header_value" {
  description = "Value of the header that CloudFront sends the public ALB, for modules/edge."
  value       = data.aws_secretsmanager_secret_version.origin_header.secret_string
  sensitive   = true
}

output "db_role_secret_arns" {
  description = "Secret with the password of each database role, for modules/data and the services."
  value       = { for role, secret in aws_secretsmanager_secret.db_role : role => secret.arn }
}

output "valkey_auth_token_secret_arn" {
  description = "Secret with the AUTH token of Valkey, VALKEY_PASSWORD of app."
  value       = aws_secretsmanager_secret.valkey_auth_token.arn
}

output "valkey_auth_token" {
  description = "The AUTH token of Valkey as the secret holds it, for modules/valkey alone."
  value       = ephemeral.aws_secretsmanager_secret_version.valkey_auth_token.secret_string
  ephemeral   = true
}

output "throttle_ip_hmac_key_secret_arn" {
  description = "Secret with THROTTLE_IP_HMAC_KEY of app."
  value       = aws_secretsmanager_secret.throttle_ip_hmac_key.arn
}
