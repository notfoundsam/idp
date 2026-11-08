output "ca_cert_pem" {
  description = "Certificate of the internal CA, the one trust anchor of clients of internal hosts."
  value       = tls_self_signed_cert.ca.cert_pem
}

output "certificates" {
  description = "Server certificate of each service and the secret that holds its key."
  value = {
    for name, certificate in tls_locally_signed_cert.server : name => {
      cert_pem       = certificate.cert_pem
      key_secret_arn = aws_secretsmanager_secret.server_key[name].arn
    }
  }
}
