# The private CA of the environment. Services trust it alone for internal hosts, never the public CAs. Its key lives only in the
# state, which a KMS key of its own encrypts.
resource "tls_private_key" "ca" {
  algorithm   = "ECDSA"
  ecdsa_curve = "P384"
}

resource "tls_self_signed_cert" "ca" {
  private_key_pem       = tls_private_key.ca.private_key_pem
  is_ca_certificate     = true
  validity_period_hours = 10 * 365 * 24
  allowed_uses          = ["cert_signing", "crl_signing"]

  subject {
    common_name  = "${var.name} internal CA"
    organization = "idp"
  }
}

resource "tls_private_key" "server" {
  for_each = var.certificates

  algorithm   = "ECDSA"
  ecdsa_curve = "P256"
}

resource "tls_cert_request" "server" {
  for_each = var.certificates

  private_key_pem = tls_private_key.server[each.key].private_key_pem
  dns_names       = each.value

  subject {
    common_name  = each.value[0]
    organization = "idp"
  }
}

resource "tls_locally_signed_cert" "server" {
  for_each = var.certificates

  cert_request_pem      = tls_cert_request.server[each.key].cert_request_pem
  ca_private_key_pem    = tls_private_key.ca.private_key_pem
  ca_cert_pem           = tls_self_signed_cert.ca.cert_pem
  validity_period_hours = var.certificate_validity_days * 24
  early_renewal_hours   = var.renewal_days * 24
  allowed_uses          = ["digital_signature", "key_encipherment", "server_auth"]
}

resource "aws_secretsmanager_secret" "server_key" {
  for_each = var.certificates

  name                    = "${var.name}/tls/${each.key}"
  description             = "TLS key of ${each.key}, for ${join(", ", each.value)}"
  recovery_window_in_days = 7
}

resource "aws_secretsmanager_secret_version" "server_key" {
  for_each = var.certificates

  secret_id     = aws_secretsmanager_secret.server_key[each.key].id
  secret_string = tls_private_key.server[each.key].private_key_pem_pkcs8
}
