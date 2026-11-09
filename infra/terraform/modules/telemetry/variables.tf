variable "name" {
  description = "Prefix of every name, such as idp-staging."
  type        = string
}

variable "cluster_arn" {
  description = "ECS cluster of the collector."
  type        = string
}

variable "namespace_id" {
  description = "Private DNS namespace where the collector registers."
  type        = string
}

variable "namespace" {
  description = "Name of that namespace. The collector answers at otel-collector.<namespace>, the name its certificate holds."
  type        = string
}

variable "private_subnet_ids" {
  description = "Subnets of the collector, without a route to the internet."
  type        = list(string)
}

variable "security_group_ids" {
  description = "Security groups from the network module, by workload. The module adds the rules between the services and the collector."
  type        = map(string)

  validation {
    condition     = alltrue([for name in ["otel-collector", "app", "worker", "ops"] : contains(keys(var.security_group_ids), name)])
    error_message = "Groups for otel-collector, app, worker and ops are needed."
  }
}

variable "collector_image" {
  description = "Image built from infra/otel-collector, with its tag."
  type        = string
}

variable "collector_tls" {
  description = "Server certificate of otel-collector.<namespace> from modules/pki and the secret that holds its key."
  type = object({
    cert_pem       = string
    key_secret_arn = string
  })

  validation {
    condition     = can(regex("^arn:aws[a-z-]*:secretsmanager:[a-z0-9-]+:[0-9]{12}:secret:", var.collector_tls.key_secret_arn))
    error_message = "The key is the ARN of a secret in Secrets Manager."
  }
}

variable "collector_count" {
  description = "Tasks of the collector."
  type        = number
  default     = 2
}

variable "cpu_architecture" {
  description = "CPU architecture of the image."
  type        = string
  default     = "ARM64"
}
