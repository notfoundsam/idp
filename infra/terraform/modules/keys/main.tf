# Rotated as AwsKeyManagementService rotates the KEKs of tenants: KMS keeps every backing key, so nothing is wrapped again.
resource "aws_kms_key" "platform" {
  description             = "${var.name} platform KEK: contacts of applications and admin reinvites"
  enable_key_rotation     = true
  rotation_period_in_days = 365
  deletion_window_in_days = 30
  policy                  = var.platform_kek_policy
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

locals {
  db_roles = ["idp_owner", "provisioner", "app_runtime", "worker_runtime", "ops_runtime"]
}

resource "aws_secretsmanager_secret" "db_role" {
  for_each = toset(local.db_roles)

  name                    = "${var.name}/db/${each.key}"
  description             = "Password of the database role ${each.key}"
  recovery_window_in_days = 7
}

# Write-only, so a password never reaches the plan or the state. A new version writes a new one.
ephemeral "aws_secretsmanager_random_password" "db_role" {
  for_each = toset(local.db_roles)

  password_length     = 40
  exclude_punctuation = true
}

resource "aws_secretsmanager_secret_version" "db_role" {
  for_each = toset(local.db_roles)

  secret_id                = aws_secretsmanager_secret.db_role[each.key].id
  secret_string_wo         = ephemeral.aws_secretsmanager_random_password.db_role[each.key].random_password
  secret_string_wo_version = lookup(var.db_password_versions, each.key, 1)
}

resource "aws_secretsmanager_secret" "valkey_auth_token" {
  name                    = "${var.name}/valkey/auth-token"
  description             = "AUTH token of Valkey, VALKEY_PASSWORD of app"
  recovery_window_in_days = 7
}

ephemeral "aws_secretsmanager_random_password" "valkey_auth_token" {
  password_length     = 64
  exclude_punctuation = true
}

resource "aws_secretsmanager_secret_version" "valkey_auth_token" {
  secret_id                = aws_secretsmanager_secret.valkey_auth_token.id
  secret_string_wo         = ephemeral.aws_secretsmanager_random_password.valkey_auth_token.random_password
  secret_string_wo_version = var.valkey_auth_token_version
}

# ElastiCache takes the token from the secret, so a replaced group and the secret never disagree.
ephemeral "aws_secretsmanager_secret_version" "valkey_auth_token" {
  secret_id  = aws_secretsmanager_secret_version.valkey_auth_token.secret_id
  version_id = aws_secretsmanager_secret_version.valkey_auth_token.version_id
}

# CloudFront and the ALB keep the value in their configuration, so it reaches the state through them in any case.
data "aws_secretsmanager_secret_version" "origin_header" {
  secret_id  = aws_secretsmanager_secret.origin_header.id
  version_id = aws_secretsmanager_secret_version.origin_header.version_id
}
