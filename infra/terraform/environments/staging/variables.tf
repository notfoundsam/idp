variable "region" {
  description = "AWS region of the environment."
  type        = string
}

variable "account_id" {
  description = "AWS account of the environment. The provider refuses any other."
  type        = string

  validation {
    condition     = can(regex("^[0-9]{12}$", var.account_id))
    error_message = "An AWS account ID has 12 digits."
  }
}

variable "domain" {
  description = "Domain of the product. Tenants live at <subdomain>.<domain>, and passkeys are bound to it, so it never changes."
  type        = string

  validation {
    condition     = can(regex("^([a-z0-9]([a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,}$", var.domain))
    error_message = "A lowercase DNS name such as idp.example.com."
  }
}

variable "vpc_cidr" {
  description = "Address range of the VPC, a /16."
  type        = string
  default     = "10.20.0.0/16"
}

variable "availability_zones" {
  description = "Number of availability zones."
  type        = number
  default     = 2
}

variable "infra_image_tag" {
  description = "Tag of the pgbouncer and db-bootstrap images, which a release builds from infra/ and pushes to ECR."
  type        = string
}

variable "db_instance_class" {
  description = "Instance class of RDS."
  type        = string
  default     = "db.t4g.medium"
}

variable "db_password_versions" {
  description = "Version of each generated database password, by role. Raising one replaces that password."
  type        = map(number)
  default     = {}
}

variable "valkey_node_type" {
  description = "Node type of ElastiCache."
  type        = string
  default     = "cache.t4g.micro"
}

variable "credential_versions" {
  description = "Version of each other generated credential: valkey_auth_token, throttle_ip_hmac_key, smtp and origin_header. Raising one replaces it."
  type        = map(number)
  default     = {}

  validation {
    condition     = alltrue([for name in keys(var.credential_versions) : contains(["valkey_auth_token", "throttle_ip_hmac_key", "smtp", "origin_header"], name)])
    error_message = "Only valkey_auth_token, throttle_ip_hmac_key, smtp and origin_header have versions."
  }
}
