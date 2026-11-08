output "distribution_arn" {
  description = "ARN of the distribution, the one reader of the static bucket."
  value       = aws_cloudfront_distribution.this.arn
}

output "distribution_domain_name" {
  description = "Domain of the distribution, the target of the wildcard record of the tenant domains."
  value       = aws_cloudfront_distribution.this.domain_name
}

output "https_listener_arn" {
  description = "HTTPS listener of the public ALB, which answers 404 until a rule forwards to a service."
  value       = aws_lb_listener.https.arn
}

output "certificate_validation_records" {
  description = "DNS records that validate the certificates of CloudFront and of the ALB."
  value = distinct([
    for option in concat(tolist(aws_acm_certificate.cloudfront.domain_validation_options), tolist(aws_acm_certificate.alb.domain_validation_options)) : {
      name  = option.resource_record_name
      type  = option.resource_record_type
      value = option.resource_record_value
    }
  ])
}
