variable "name" {
  description = "Prefix of every name, such as idp-staging."
  type        = string
}

variable "certificates" {
  description = "Server certificates the internal CA issues, by service: the DNS names each one covers."
  type        = map(list(string))
}

variable "certificate_validity_days" {
  description = "Lifetime of a server certificate."
  type        = number
  default     = 365
}

variable "renewal_days" {
  description = "Days before expiry from which an apply issues a new server certificate."
  type        = number
  default     = 60
}
