variable "name" {
  description = "Prefix of every name, such as idp-staging."
  type        = string
}

variable "platform_kek_policy" {
  description = "Key policy of the platform KEK, the output platform_kek_policy of modules/iam."
  type        = string

  validation {
    condition     = try(jsondecode(var.platform_kek_policy).Version == "2012-10-17" && length(jsondecode(var.platform_kek_policy).Statement) > 0, false)
    error_message = "A JSON policy of version 2012-10-17 with statements."
  }
}

variable "throttle_ip_hmac_key_version" {
  description = "Version of the generated HMAC key of Throttle. Raising it writes a new key, and the counters start over."
  type        = number
  default     = 1
}

variable "origin_header_version" {
  description = "Version of the generated header that CloudFront sends the public ALB. Raising it writes a new value."
  type        = number
  default     = 1
}
