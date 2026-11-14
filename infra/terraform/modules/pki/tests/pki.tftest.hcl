mock_provider "aws" {}

mock_provider "tls" {}

variables {
  name = "idp-test"
  certificates = {
    pgbouncer = ["pgbouncer.idp.internal"]
    app       = ["app.idp.internal"]
  }
}

run "the_internal_ca_signs_certificates_and_nothing_else" {
  command = plan

  assert {
    condition     = tls_private_key.ca.algorithm == "ECDSA" && tls_private_key.ca.ecdsa_curve == "P384"
    error_message = "The key of the internal CA is weaker than ECDSA P-384."
  }

  assert {
    condition = (
      tls_self_signed_cert.ca.is_ca_certificate &&
      toset(tls_self_signed_cert.ca.allowed_uses) == toset(["cert_signing", "crl_signing"]) &&
      one(tls_self_signed_cert.ca.subject).common_name == "idp-test internal CA"
    )
    error_message = "The internal CA is no CA, or its key may also serve TLS."
  }
}

run "each_service_gets_a_server_certificate_for_its_names_alone" {
  command = plan

  assert {
    condition     = [for name, request in tls_cert_request.server : "${name}=${join(",", request.dns_names)}"] == ["app=app.idp.internal", "pgbouncer=pgbouncer.idp.internal"]
    error_message = "A certificate covers other names than the service asked for."
  }

  assert {
    condition = alltrue([
      for certificate in tls_locally_signed_cert.server :
      toset(certificate.allowed_uses) == toset(["digital_signature", "key_encipherment", "server_auth"]) &&
      certificate.validity_period_hours == 365 * 24 && certificate.early_renewal_hours == 60 * 24
    ])
    error_message = "A server certificate may sign others or authenticate clients, or lives longer than a year, or is not renewed 60 days before its end."
  }

  assert {
    condition     = alltrue([for key in tls_private_key.server : key.algorithm == "ECDSA" && key.ecdsa_curve == "P256"])
    error_message = "A server key is weaker than ECDSA P-256."
  }
}

run "the_key_of_each_service_lives_in_a_secret_of_its_own" {
  command = plan

  assert {
    condition = (
      { for name, secret in aws_secretsmanager_secret.server_key : name => secret.name } == { pgbouncer = "idp-test/tls/pgbouncer", app = "idp-test/tls/app" } &&
      alltrue([for secret in aws_secretsmanager_secret.server_key : secret.recovery_window_in_days == 7])
    )
    error_message = "The key of a service lives elsewhere than infra/terraform/README.md says, or a deleted secret cannot be recovered."
  }
}
