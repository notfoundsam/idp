output "bucket" {
  description = "ERASURE_JOURNAL_BUCKET of worker and migrator."
  value       = aws_s3_bucket.this.id
}

output "region" {
  description = "ERASURE_JOURNAL_REGION of worker and migrator."
  value       = data.aws_region.current.region
}

output "bucket_arn" {
  description = "ARN of the bucket, for the task roles: worker puts, migrator lists."
  value       = aws_s3_bucket.this.arn
}

output "retention_days" {
  description = "Days Object Lock keeps every entry."
  value       = local.retention_days
}
