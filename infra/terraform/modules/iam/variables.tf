variable "name" {
  description = "Prefix of every name, such as idp-staging."
  type        = string
}

variable "platform_kek_arn" {
  description = "The platform KEK from modules/keys: ops and the CLI encrypt with it, worker decrypts."
  type        = string

  validation {
    condition     = can(regex("^arn:aws[a-z-]*:kms:[a-z0-9-]+:[0-9]{12}:key/[0-9a-f-]+$", var.platform_kek_arn))
    error_message = "The ARN of a KMS key, not of an alias."
  }
}

variable "erasure_journal_bucket_arn" {
  description = "Bucket of the erasure journal from modules/erasure-journal: worker puts entries, the recovery task lists them."
  type        = string

  validation {
    condition     = can(regex("^arn:aws[a-z-]*:s3:::[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$", var.erasure_journal_bucket_arn))
    error_message = "The ARN of an S3 bucket."
  }
}
