variable "name" {
  description = "Prefix of every name, such as idp-staging."
  type        = string
}

variable "domain" {
  description = "Domain identity of SES that the mail is sent from."
  type        = string
}

variable "from" {
  description = "MAIL_FROM of worker, the only sender the SMTP user may use."
  type        = string

  validation {
    condition     = can(regex("^[^@\\s]+@[^@\\s]+$", var.from))
    error_message = "A mail address such as no-reply@idp.example.com."
  }
}

variable "credentials_version" {
  description = "Version of the SMTP credentials. Raising it replaces the access key, see infra/terraform/README.md."
  type        = number
  default     = 1
}
