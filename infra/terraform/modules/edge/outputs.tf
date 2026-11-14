output "distribution_arn" {
  description = "ARN of the distribution, the one reader of the static bucket."
  value       = aws_cloudfront_distribution.this.arn
}

output "distribution_domain_name" {
  description = "Domain of the distribution, the target of the wildcard record of the tenant domains."
  value       = aws_cloudfront_distribution.this.domain_name
}

output "https_listener_arn" {
  description = "HTTPS listener of the public ALB. It answers 403 without origin_header and 404 until a rule forwards to a service."
  value       = aws_lb_listener.https.arn
}

output "alb_certificate_arn" {
  description = "Issued certificate of *.<domain> in the region of the ALB, which also names ops.<domain>."
  value       = aws_acm_certificate_validation.alb.certificate_arn
}

output "origin_header" {
  description = "Name of the header with origin_header_value, which every rule that forwards to a service requires."
  value       = local.origin_header
}

output "waf_metrics" {
  description = "Metric names of the web ACL and of its rate-based rules, under which CloudWatch counts their blocks in us-east-1."
  value = {
    web_acl = one(aws_wafv2_web_acl.edge.visibility_config).metric_name
    rules   = sort([for rule in aws_wafv2_web_acl.edge.rule : one(rule.visibility_config).metric_name])
  }
}

output "client_ip_header" {
  description = "CLIENT_IP_HEADER of app: the header with the address of the viewer, which CloudFront overwrites."
  value       = local.client_ip_header
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
