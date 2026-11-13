variable "name" {
  description = "Prefix of every name, such as idp-staging."
  type        = string
}

variable "subnet_ids" {
  description = "Subnets of ElastiCache, in at least two availability zones."
  type        = list(string)
}

variable "security_group_ids" {
  description = "Security groups from the network module, by workload. The module adds every rule of a connection to Valkey."
  type        = map(string)

  validation {
    condition     = alltrue([for name in ["valkey", "app"] : contains(keys(var.security_group_ids), name)])
    error_message = "Groups for valkey and app are needed."
  }
}

variable "engine_version" {
  description = "Version of Valkey."
  type        = string
  default     = "8.1"
}

variable "node_type" {
  description = "Node type of ElastiCache."
  type        = string
  default     = "cache.t4g.small"
}

variable "nodes" {
  description = "Primary and replicas, spread over the availability zones. Failover needs at least two."
  type        = number
  default     = 2

  validation {
    condition     = var.nodes >= 2 && var.nodes <= 6
    error_message = "Between two and six nodes."
  }
}

variable "auth_token" {
  description = "AUTH token, the output valkey_auth_token of modules/keys, which ElastiCache takes write-only."
  type        = string
  ephemeral   = true
  sensitive   = true
}

variable "auth_token_version" {
  description = "Version of the AUTH token in modules/keys. Raising it hands ElastiCache the new token, see infra/terraform/README.md."
  type        = number
  default     = 1
}

variable "maintenance_window" {
  description = "Weekly window of ElastiCache maintenance, in UTC."
  type        = string
  default     = "sun:04:30-sun:05:30"
}