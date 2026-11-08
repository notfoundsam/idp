variable "name" {
  description = "Prefix of every repository, such as idp-staging."
  type        = string
}

variable "repositories" {
  description = "Images of the environment: the four deployables and the database images of infra/."
  type        = list(string)
  default     = ["app", "worker", "ops", "migrator", "pgbouncer", "db-bootstrap"]
}

variable "kept_images" {
  description = "Images kept per repository, enough to roll back past several releases."
  type        = number
  default     = 100
}
