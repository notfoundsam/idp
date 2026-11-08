variable "name" {
  description = "Prefix of every name, such as idp-staging."
  type        = string
}

variable "throttle_ip_hmac_key_version" {
  description = "Version of the generated HMAC key of Throttle. Raising it writes a new key, and the counters start over."
  type        = number
  default     = 1
}
