data "aws_region" "current" {}

# SES sends for the domain once the DKIM records of the output dkim_records exist at the DNS host, see infra/terraform/README.md.
resource "aws_sesv2_email_identity" "domain" {
  email_identity = var.domain

  dkim_signing_attributes {
    next_signing_key_length = "RSA_2048_BIT"
  }
}

# SES takes SMTP only with credentials derived from the access key of an IAM user. The user may send from one address alone.
resource "aws_iam_user" "smtp" {
  name = "${var.name}-ses-smtp"
  path = "/${var.name}/"
}

data "aws_iam_policy_document" "smtp" {
  statement {
    actions   = ["ses:SendRawEmail"]
    resources = [aws_sesv2_email_identity.domain.arn]

    condition {
      test     = "StringEquals"
      variable = "ses:FromAddress"
      values   = [var.from]
    }
  }
}

resource "aws_iam_user_policy" "smtp" {
  name   = "send"
  user   = aws_iam_user.smtp.name
  policy = data.aws_iam_policy_document.smtp.json
}

resource "terraform_data" "credentials_version" {
  input = var.credentials_version
}

# Only the state and the secret hold the key, as with the keys of modules/pki: AWS has no write-only way to create one.
resource "aws_iam_access_key" "smtp" {
  user = aws_iam_user.smtp.name

  lifecycle {
    create_before_destroy = true
    replace_triggered_by  = [terraform_data.credentials_version]
  }
}

resource "aws_secretsmanager_secret" "smtp" {
  name                    = "${var.name}/mail/smtp"
  description             = "SMTP credentials of SES, MAIL_USERNAME and MAIL_PASSWORD of worker"
  recovery_window_in_days = 7
}

resource "aws_secretsmanager_secret_version" "smtp" {
  secret_id = aws_secretsmanager_secret.smtp.id
  secret_string = jsonencode({
    username = aws_iam_access_key.smtp.id
    password = aws_iam_access_key.smtp.ses_smtp_password_v4
  })
}
