variable "bucket" {
  description = "Name of the bucket of the frontend files."
  type        = string
}

variable "cloudfront_distribution_arns" {
  description = "Distributions that may read the bucket through origin access control. Nothing else reads it."
  type        = list(string)
  default     = []
}
