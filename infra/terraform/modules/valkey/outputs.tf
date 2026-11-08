output "url" {
  description = "VALKEY_URL of app: the primary endpoint over TLS."
  value       = "rediss://${aws_elasticache_replication_group.this.primary_endpoint_address}:${aws_elasticache_replication_group.this.port}"
}

output "tls_ca_pem" {
  description = "VALKEY_TLS_CA of app: the roots of Amazon Trust Services, the only CAs app trusts for Valkey."
  value       = local.tls_ca_pem
}

output "auth_token_secret_arn" {
  description = "Secret with the AUTH token, VALKEY_PASSWORD of app."
  value       = aws_secretsmanager_secret.auth_token.arn
}
