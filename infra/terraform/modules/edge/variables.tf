variable "name" {
  description = "Prefix of every name, such as idp-staging."
  type        = string
}

variable "security_headers" {
  description = "Headers of the static files by name, the contents of infra/web/security-headers.json, which nginx serves locally."
  type        = map(string)

  validation {
    condition     = length(var.security_headers) > 0
    error_message = "No security headers."
  }

  validation {
    condition     = alltrue([for name, value in var.security_headers : can(regex("^[A-Za-z][A-Za-z0-9-]*$", name)) && can(regex("^[ -~]+$", value))])
    error_message = "A header name is a token and its value printable ASCII."
  }

  # CloudFront sets these through typed fields, and only under their own spelling.
  validation {
    condition = alltrue([
      for name in keys(var.security_headers) :
      contains(["Content-Security-Policy", "Strict-Transport-Security", "X-Content-Type-Options", "Referrer-Policy", "X-Frame-Options"], name)
      if contains(["content-security-policy", "strict-transport-security", "x-content-type-options", "referrer-policy", "x-frame-options"], lower(name))
    ])
    error_message = "Spell Content-Security-Policy, Strict-Transport-Security, X-Content-Type-Options, Referrer-Policy and X-Frame-Options this way."
  }

  validation {
    condition     = length(lookup(var.security_headers, "Content-Security-Policy", "")) <= 1783
    error_message = "CloudFront takes a Content-Security-Policy of at most 1783 characters."
  }

  validation {
    condition     = can(regex("^max-age=[0-9]+(; includeSubDomains)?(; preload)?$", lookup(var.security_headers, "Strict-Transport-Security", "max-age=0")))
    error_message = "Strict-Transport-Security is max-age=<seconds>, then optionally includeSubDomains and preload."
  }

  validation {
    condition     = contains(["nosniff"], lookup(var.security_headers, "X-Content-Type-Options", "nosniff"))
    error_message = "X-Content-Type-Options has only the value nosniff."
  }

  validation {
    condition = contains([
      "no-referrer", "no-referrer-when-downgrade", "origin", "origin-when-cross-origin", "same-origin", "strict-origin",
      "strict-origin-when-cross-origin", "unsafe-url",
    ], lookup(var.security_headers, "Referrer-Policy", "no-referrer"))
    error_message = "Referrer-Policy is one value that CloudFront knows."
  }

  validation {
    condition     = contains(["DENY", "SAMEORIGIN"], lookup(var.security_headers, "X-Frame-Options", "DENY"))
    error_message = "X-Frame-Options is DENY or SAMEORIGIN."
  }
}
