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

variable "environment" {
  description = "Name of the environment, staging or production."
  type        = string

  validation {
    condition     = contains(["staging", "production"], var.environment)
    error_message = "The environment is staging or production."
  }
}

variable "github_repository" {
  description = "Repository whose workflows may assume the roles, as owner/name."
  type        = string
  default     = "notfoundsam/idp"
}

variable "create_github_oidc_provider" {
  description = "An account holds one OIDC provider per URL, so an account shared by two environments creates it in one of them."
  type        = bool
  default     = true
}
