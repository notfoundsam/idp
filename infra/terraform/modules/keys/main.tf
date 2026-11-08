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

resource "aws_secretsmanager_secret" "origin_header" {
  name                    = "${var.name}/edge/origin-header"
  description             = "Value of the header that CloudFront sends the public ALB, which refuses a request without it"
  recovery_window_in_days = 7
}

# Letters and digits alone, as a rule of the ALB reads * and ? as wildcards.
ephemeral "aws_secretsmanager_random_password" "origin_header" {
  password_length     = 64
  exclude_punctuation = true
}

resource "aws_secretsmanager_secret_version" "origin_header" {
  secret_id                = aws_secretsmanager_secret.origin_header.id
  secret_string_wo         = ephemeral.aws_secretsmanager_random_password.origin_header.random_password
  secret_string_wo_version = var.origin_header_version
}

# CloudFront and the ALB keep the value in their configuration, so it reaches the state through them in any case.
data "aws_secretsmanager_secret_version" "origin_header" {
  secret_id  = aws_secretsmanager_secret.origin_header.id
  version_id = aws_secretsmanager_secret_version.origin_header.version_id
}
