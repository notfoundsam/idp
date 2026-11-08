# Rotated as AwsKeyManagementService rotates the KEKs of tenants: KMS keeps every backing key, so nothing is wrapped again.
resource "aws_kms_key" "platform" {
  description             = "${var.name} platform KEK: contacts of applications and admin reinvites"
  enable_key_rotation     = true
  rotation_period_in_days = 365
  deletion_window_in_days = 30
}

resource "aws_kms_alias" "platform" {
  name          = "alias/${var.name}-platform"
  target_key_id = aws_kms_key.platform.key_id
}

resource "aws_secretsmanager_secret" "throttle_ip_hmac_key" {
  name                    = "${var.name}/app/throttle-ip-hmac-key"
  description             = "HMAC key of client IPs in Valkey counter names, THROTTLE_IP_HMAC_KEY of app"
  recovery_window_in_days = 7
}

ephemeral "aws_secretsmanager_random_password" "throttle_ip_hmac_key" {
  password_length     = 64
  exclude_punctuation = true
}

resource "aws_secretsmanager_secret_version" "throttle_ip_hmac_key" {
  secret_id                = aws_secretsmanager_secret.throttle_ip_hmac_key.id
  secret_string_wo         = ephemeral.aws_secretsmanager_random_password.throttle_ip_hmac_key.random_password
  secret_string_wo_version = var.throttle_ip_hmac_key_version
}
