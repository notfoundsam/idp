output "bucket" {
  description = "Name of the bucket."
  value       = aws_s3_bucket.this.id
}

output "bucket_arn" {
  description = "ARN of the bucket."
  value       = aws_s3_bucket.this.arn
}

output "bucket_regional_domain_name" {
  description = "Origin domain of the bucket for CloudFront."
  value       = aws_s3_bucket.this.bucket_regional_domain_name
}
