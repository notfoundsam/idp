output "host" {
  description = "MAIL_HOST of worker: the SMTP endpoint of SES, which the interface endpoint email-smtp answers inside the VPC."
  value       = "email-smtp.${data.aws_region.current.region}.amazonaws.com"
}

output "port" {
  description = "MAIL_PORT of worker: SMTP with STARTTLS."
  value       = 587
}

output "from" {
  description = "MAIL_FROM of worker."
  value       = var.from
}

output "dkim_records" {
  description = "CNAME records of Easy DKIM, which the DNS host of the domain serves before SES sends anything."
  value = [
    for token in aws_sesv2_email_identity.domain.dkim_signing_attributes[0].tokens : {
      name  = "${token}._domainkey.${var.domain}"
      type  = "CNAME"
      value = "${token}.dkim.amazonses.com"
    }
  ]
}

output "credentials_secret_arn" {
  description = "Secret with the keys username and password, MAIL_USERNAME and MAIL_PASSWORD of worker."
  value       = aws_secretsmanager_secret.smtp.arn
}
