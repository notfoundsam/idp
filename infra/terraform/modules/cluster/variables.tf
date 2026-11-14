variable "name" {
  description = "Name of the cluster, such as idp-staging."
  type        = string
}

variable "vpc_id" {
  description = "VPC of the private DNS namespace."
  type        = string
}

variable "namespace" {
  description = "Private DNS zone where tasks find each other, such as pgbouncer.<namespace>. Internal certificates name hosts in it."
  type        = string
  default     = "idp.internal"
}
