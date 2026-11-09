variable "name" {
  description = "Prefix of every name, such as idp-staging."
  type        = string
}

variable "database_subnet_ids" {
  description = "Subnets of RDS, in at least two availability zones."
  type        = list(string)
}

variable "private_subnet_ids" {
  description = "Subnets of PgBouncer and the bootstrap tasks."
  type        = list(string)
}

variable "security_group_ids" {
  description = "Security groups from the network module, by workload. The module adds every rule of a connection to the database."
  type        = map(string)

  validation {
    condition = alltrue([
      for name in ["database", "pgbouncer", "db-bootstrap", "migrator", "app", "worker", "ops"] : contains(keys(var.security_group_ids), name)
    ])
    error_message = "Groups for database, pgbouncer, db-bootstrap, migrator, app, worker and ops are needed."
  }
}

variable "cluster_arn" {
  description = "ECS cluster of PgBouncer."
  type        = string
}

variable "namespace_id" {
  description = "Private DNS namespace where PgBouncer registers."
  type        = string
}

variable "namespace" {
  description = "Name of that namespace. PgBouncer answers at pgbouncer.<namespace>."
  type        = string
}

variable "pgbouncer_image" {
  description = "Image built from infra/pgbouncer, with its tag."
  type        = string
}

variable "db_bootstrap_image" {
  description = "Image built from infra/db-bootstrap, with its tag."
  type        = string
}

variable "cpu_architecture" {
  description = "CPU architecture of the images."
  type        = string
  default     = "ARM64"
}

variable "internal_ca_pem" {
  description = "Certificate of the internal CA that signed the certificate of PgBouncer. Clients of PgBouncer trust it alone."
  type        = string
}

variable "pgbouncer_tls" {
  description = "Server certificate of PgBouncer for pgbouncer.<namespace> and the secret that holds its key."
  type = object({
    cert_pem       = string
    key_secret_arn = string
  })
}

variable "engine_version" {
  description = "Major version of Postgres. RDS applies minor versions on its own."
  type        = string
  default     = "18"
}

variable "instance_class" {
  description = "Instance class of RDS."
  type        = string
  default     = "db.m7g.large"
}

variable "allocated_storage" {
  description = "Initial storage in GiB."
  type        = number
  default     = 50
}

variable "max_allocated_storage" {
  description = "Limit of storage autoscaling in GiB."
  type        = number
  default     = 500
}

variable "master_username" {
  description = "Master user of RDS. Its password lives in a secret RDS manages, and only the bootstrap task reads it."
  type        = string
  default     = "idp_master"
}

variable "ca_cert_identifier" {
  description = "RDS CA of the server certificate. Direct clients trust the root of this CA alone."
  type        = string
  default     = "rds-ca-rsa2048-g1"

  validation {
    condition     = contains(["rds-ca-rsa2048-g1", "rds-ca-rsa4096-g1", "rds-ca-ecc384-g1"], var.ca_cert_identifier)
    error_message = "One of the RDS CAs rds-ca-rsa2048-g1, rds-ca-rsa4096-g1 or rds-ca-ecc384-g1."
  }
}

variable "deletion_protection" {
  description = "Protects the instance from deletion."
  type        = bool
  default     = true
}

variable "password_versions" {
  description = "Version of each generated role password. Raising one writes a new password into its secret, see infra/terraform/README.md."
  type        = map(number)
  default     = {}
}

variable "pgbouncer_count" {
  description = "PgBouncer tasks, spread over the availability zones."
  type        = number
  default     = 2
}

variable "pgbouncer_pool_size" {
  description = "Server connections of PgBouncer per role and task."
  type        = number
  default     = 20
}

variable "pgbouncer_max_clients" {
  description = "Client connections a PgBouncer task accepts."
  type        = number
  default     = 1000
}

variable "log_retention_days" {
  description = "Days CloudWatch keeps the console lines of PgBouncer and the bootstrap tasks."
  type        = number
  default     = 90
}

variable "log_tampering_actions" {
  description = "Calls of CloudWatch Logs that the execution roles deny, the output log_tampering_actions of modules/telemetry."
  type        = list(string)
}
