variable "name" {
  description = "Prefix of every name, such as idp-staging."
  type        = string
}

variable "cidr_block" {
  description = "Address range of the VPC, a /16."
  type        = string
}

variable "availability_zones" {
  description = "Number of availability zones. RDS Multi-AZ and the ALB need at least two."
  type        = number
  default     = 2

  validation {
    condition     = var.availability_zones >= 2 && var.availability_zones <= 4
    error_message = "Between two and four availability zones."
  }
}

variable "interface_endpoints" {
  description = "AWS services the tasks reach through interface endpoints. The VPC has no route to the internet."
  type        = list(string)
  default     = ["ecr.api", "ecr.dkr", "logs", "secretsmanager", "kms", "sts", "email-smtp", "xray"]
}

variable "services" {
  description = "Workloads that get a security group of their own, which other modules add rules to."
  type        = list(string)
  default     = ["app", "worker", "ops", "migrator", "pgbouncer", "db-bootstrap", "otel-collector"]
}

variable "deletion_protection" {
  description = "Protects the public ALB from deletion."
  type        = bool
  default     = true
}
