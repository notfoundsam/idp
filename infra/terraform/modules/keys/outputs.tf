output "platform_kek_arn" {
  description = "The platform KEK, for the task roles: ops only encrypts with it, worker decrypts."
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

output "throttle_ip_hmac_key_secret_arn" {
  description = "Secret with THROTTLE_IP_HMAC_KEY of app."
  value       = aws_secretsmanager_secret.throttle_ip_hmac_key.arn
}
