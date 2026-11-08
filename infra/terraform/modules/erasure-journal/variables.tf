variable "bucket" {
  description = "Name of the bucket of the erasure journal."
  type        = string
}

variable "backup_retention_days" {
  description = "Days the database keeps its backups. The journal keeps an entry for as long, plus the margin."
  type        = number

  validation {
    condition     = var.backup_retention_days >= 1
    error_message = "At least one day of backups."
  }
}

variable "margin_days" {
  description = "Days an entry outlives the backups, for a restore that starts late or runs long."
  type        = number
  default     = 15

  validation {
    condition     = var.margin_days >= 7
    error_message = "A margin of at least a week."
  }
}
