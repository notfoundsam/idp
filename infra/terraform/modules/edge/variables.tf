variable "name" {
  description = "Prefix of every name, such as idp-staging."
  type        = string
}

variable "domain" {
  description = "Domain of the product. Every tenant domain is a subdomain of it, served by the distribution."
  type        = string

  validation {
    condition     = can(regex("^([a-z0-9]([a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,}$", var.domain))
    error_message = "A lowercase DNS name such as idp.example.com."
  }
}

variable "alb_arn" {
  description = "The public ALB that gets the HTTPS listener."
  type        = string

  validation {
    condition     = can(regex("^arn:aws[a-z-]*:elasticloadbalancing:[a-z0-9-]+:[0-9]{12}:loadbalancer/app/", var.alb_arn))
    error_message = "The ARN of an application load balancer."
  }
}

variable "alb_dns_name" {
  description = "DNS name of the public ALB, the origin of /api/* and /saml/*."
  type        = string

  validation {
    condition     = can(regex("^([a-z0-9]([a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,}$", var.alb_dns_name))
    error_message = "A DNS name without a scheme, port or path."
  }
}

variable "alb_security_group_id" {
  description = "Security group of the public ALB, which gets HTTPS from CloudFront alone."
  type        = string

  validation {
    condition     = can(regex("^sg-[0-9a-f]+$", var.alb_security_group_id))
    error_message = "The id of a security group."
  }
}

variable "origin_header_value" {
  description = "Secret value of the header that CloudFront sends the ALB, from modules/keys."
  type        = string
  sensitive   = true

  # A rule of the ALB reads * and ? in a header value as wildcards and compares at most 128 characters.
  validation {
    condition     = can(regex("^[A-Za-z0-9]{32,128}$", var.origin_header_value))
    error_message = "Between 32 and 128 letters and digits."
  }
}

variable "static_origin_domain" {
  description = "Regional domain name of the bucket of the frontend files, the origin of everything else."
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9.-]*\\.s3\\.([a-z0-9-]+\\.)?amazonaws\\.com$", var.static_origin_domain))
    error_message = "The regional domain name of an S3 bucket."
  }
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
