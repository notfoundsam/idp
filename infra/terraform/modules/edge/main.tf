locals {
  typed_headers  = ["Content-Security-Policy", "Strict-Transport-Security", "X-Content-Type-Options", "Referrer-Policy", "X-Frame-Options"]
  custom_headers = { for name, value in var.security_headers : name => value if !contains(local.typed_headers, name) }
  hsts           = lookup(var.security_headers, "Strict-Transport-Security", null)

  static_origin = "static"
  app_origin    = "app"

  # Every code that CloudFront lets a distribution configure.
  error_codes = [400, 403, 404, 405, 414, 416, 500, 501, 502, 503, 504]

  # CloudFront overwrites both when a viewer sends them, so app and the ALB may trust them.
  client_ip_header = "CloudFront-Viewer-Address"
  origin_header    = "X-Origin-Verify"

  # Requests of one client address in five minutes, see docs/security/limits.md.
  rate_window = 300
  rate_limits = {
    all = 20000
    app = 10000
  }
}

resource "aws_cloudfront_function" "spa" {
  name    = "${var.name}-spa"
  comment = "Routes of the apps in S3 and their Cache-Control"
  runtime = "cloudfront-js-2.0"
  code    = file("${path.module}/../../../web/spa-rewrite.js")
  publish = true
}

resource "aws_cloudfront_origin_access_control" "static" {
  name                              = "${var.name}-static"
  description                       = "CloudFront reads the bucket of the frontend files with signed requests"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

resource "aws_cloudfront_response_headers_policy" "static" {
  name    = "${var.name}-static"
  comment = "Security headers of infra/web/security-headers.json"

  security_headers_config {
    dynamic "content_security_policy" {
      for_each = contains(keys(var.security_headers), "Content-Security-Policy") ? [var.security_headers["Content-Security-Policy"]] : []

      content {
        content_security_policy = content_security_policy.value
        override                = true
      }
    }

    dynamic "strict_transport_security" {
      for_each = local.hsts == null ? [] : [local.hsts]

      content {
        access_control_max_age_sec = tonumber(regex("max-age=([0-9]+)", strict_transport_security.value)[0])
        include_subdomains         = strcontains(strict_transport_security.value, "includeSubDomains")
        preload                    = strcontains(strict_transport_security.value, "preload")
        override                   = true
      }
    }

    dynamic "content_type_options" {
      for_each = contains(keys(var.security_headers), "X-Content-Type-Options") ? [1] : []

      content {
        override = true
      }
    }

    dynamic "referrer_policy" {
      for_each = contains(keys(var.security_headers), "Referrer-Policy") ? [var.security_headers["Referrer-Policy"]] : []

      content {
        referrer_policy = referrer_policy.value
        override        = true
      }
    }

    dynamic "frame_options" {
      for_each = contains(keys(var.security_headers), "X-Frame-Options") ? [var.security_headers["X-Frame-Options"]] : []

      content {
        frame_option = frame_options.value
        override     = true
      }
    }
  }

  dynamic "custom_headers_config" {
    for_each = length(local.custom_headers) > 0 ? [1] : []

    content {
      dynamic "items" {
        for_each = local.custom_headers

        content {
          header   = items.key
          value    = items.value
          override = true
        }
      }
    }
  }
}

# The files of a release keep their names, and a new release brings new names: a chunk stays at the edge for a year.
resource "aws_cloudfront_cache_policy" "assets" {
  name        = "${var.name}-assets"
  comment     = "Files under assets/ with a hash in the name"
  min_ttl     = 0
  default_ttl = 31536000
  max_ttl     = 31536000

  parameters_in_cache_key_and_forwarded_to_origin {
    enable_accept_encoding_brotli = true
    enable_accept_encoding_gzip   = true

    cookies_config {
      cookie_behavior = "none"
    }

    headers_config {
      header_behavior = "none"
    }

    query_strings_config {
      query_string_behavior = "none"
    }
  }
}

# Every request for a page reaches S3, so a release takes effect without an invalidation.
resource "aws_cloudfront_cache_policy" "pages" {
  name        = "${var.name}-pages"
  comment     = "The index.html of each app, never cached at the edge"
  min_ttl     = 0
  default_ttl = 0
  max_ttl     = 0

  parameters_in_cache_key_and_forwarded_to_origin {
    enable_accept_encoding_brotli = false
    enable_accept_encoding_gzip   = false

    cookies_config {
      cookie_behavior = "none"
    }

    headers_config {
      header_behavior = "none"
    }

    query_strings_config {
      query_string_behavior = "none"
    }
  }
}

# The cache key holds neither the Host nor the cookies, so a cached response of the API would reach every tenant.
resource "aws_cloudfront_cache_policy" "api" {
  name        = "${var.name}-api"
  comment     = "The API and SAML of app, never cached at the edge"
  min_ttl     = 0
  default_ttl = 0
  max_ttl     = 0

  parameters_in_cache_key_and_forwarded_to_origin {
    enable_accept_encoding_brotli = false
    enable_accept_encoding_gzip   = false

    cookies_config {
      cookie_behavior = "none"
    }

    headers_config {
      header_behavior = "none"
    }

    query_strings_config {
      query_string_behavior = "none"
    }
  }
}

# app selects the tenant by the Host of the viewer, and the ALB certificate matches that Host, not the name of the ALB.
# allViewer alone leaves out the headers CloudFront generates, the address of the viewer among them.
resource "aws_cloudfront_origin_request_policy" "api" {
  name    = "${var.name}-api"
  comment = "Everything of the viewer request and its address reach app"

  cookies_config {
    cookie_behavior = "all"
  }

  headers_config {
    header_behavior = "allViewerAndWhitelistCloudFront"

    headers {
      items = [local.client_ip_header]
    }
  }

  query_strings_config {
    query_string_behavior = "all"
  }
}

resource "aws_acm_certificate" "cloudfront" {
  provider = aws.us_east_1

  domain_name       = "*.${var.domain}"
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_acm_certificate" "alb" {
  domain_name       = "*.${var.domain}"
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

# Waits until the records of the output certificate_validation_records exist at the DNS host, see infra/terraform/README.md.
resource "aws_acm_certificate_validation" "cloudfront" {
  provider = aws.us_east_1

  certificate_arn = aws_acm_certificate.cloudfront.arn
}

resource "aws_acm_certificate_validation" "alb" {
  certificate_arn = aws_acm_certificate.alb.arn
}

data "aws_ec2_managed_prefix_list" "cloudfront" {
  name = "com.amazonaws.global.cloudfront.origin-facing"
}

# The prefix list weighs 55 of the 60 inbound rules a security group takes by default.
resource "aws_vpc_security_group_ingress_rule" "alb_from_cloudfront" {
  security_group_id = var.alb_security_group_id
  description       = "HTTPS from the origin-facing servers of CloudFront"
  prefix_list_id    = data.aws_ec2_managed_prefix_list.cloudfront.id
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = var.alb_arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-Res-2021-06"
  certificate_arn   = aws_acm_certificate_validation.alb.certificate_arn

  default_action {
    type = "fixed-response"

    fixed_response {
      content_type = "text/plain"
      status_code  = "403"
    }
  }
}

# The prefix list admits every distribution of every account, so only the header proves that a request passed this one and
# its WAF. Every rule that forwards to a service carries the same condition, and this last one answers the rest with a 404.
resource "aws_lb_listener_rule" "from_cloudfront" {
  listener_arn = aws_lb_listener.https.arn
  priority     = 50000

  condition {
    http_header {
      http_header_name = local.origin_header
      values           = [var.origin_header_value]
    }
  }

  action {
    type = "fixed-response"

    fixed_response {
      content_type = "text/plain"
      status_code  = "404"
    }
  }
}

# CloudFront takes only web ACLs of us-east-1. Its client address is the viewer, not an edge server.
resource "aws_wafv2_web_acl" "edge" {
  provider = aws.us_east_1

  name        = "${var.name}-edge"
  description = "Limits per client address in front of the tenant domains"
  scope       = "CLOUDFRONT"

  default_action {
    allow {}
  }

  rule {
    name     = "requests-per-address"
    priority = 0

    action {
      block {
        custom_response {
          response_code = 429
        }
      }
    }

    statement {
      rate_based_statement {
        limit                 = local.rate_limits.all
        aggregate_key_type    = "IP"
        evaluation_window_sec = local.rate_window
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.name}-requests-per-address"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "app-requests-per-address"
    priority = 1

    action {
      block {
        custom_response {
          response_code = 429
        }
      }
    }

    statement {
      rate_based_statement {
        limit                 = local.rate_limits.app
        aggregate_key_type    = "IP"
        evaluation_window_sec = local.rate_window

        scope_down_statement {
          or_statement {
            dynamic "statement" {
              for_each = ["/api/", "/saml/"]

              content {
                byte_match_statement {
                  search_string         = statement.value
                  positional_constraint = "STARTS_WITH"

                  field_to_match {
                    uri_path {}
                  }

                  text_transformation {
                    priority = 0
                    type     = "NONE"
                  }
                }
              }
            }
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.name}-app-requests-per-address"
      sampled_requests_enabled   = true
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = "${var.name}-edge"
    sampled_requests_enabled   = true
  }
}

resource "aws_cloudfront_distribution" "this" {
  enabled         = true
  comment         = "Tenant domains of ${var.name}"
  aliases         = ["*.${var.domain}"]
  is_ipv6_enabled = true
  http_version    = "http2and3"
  web_acl_id      = aws_wafv2_web_acl.edge.arn

  origin {
    origin_id                = local.static_origin
    domain_name              = var.static_origin_domain
    origin_access_control_id = aws_cloudfront_origin_access_control.static.id
  }

  origin {
    origin_id   = local.app_origin
    domain_name = var.alb_dns_name

    custom_header {
      name  = local.origin_header
      value = var.origin_header_value
    }

    custom_origin_config {
      http_port              = 80
      https_port             = 443
      origin_protocol_policy = "https-only"
      origin_ssl_protocols   = ["TLSv1.2"]
    }
  }

  # The function answers / and everything outside /login/ and /console/, so the default is S3 behind it.
  default_cache_behavior {
    target_origin_id           = local.static_origin
    viewer_protocol_policy     = "redirect-to-https"
    allowed_methods            = ["GET", "HEAD"]
    cached_methods             = ["GET", "HEAD"]
    cache_policy_id            = aws_cloudfront_cache_policy.pages.id
    response_headers_policy_id = aws_cloudfront_response_headers_policy.static.id

    dynamic "function_association" {
      for_each = ["viewer-request", "viewer-response"]

      content {
        event_type   = function_association.value
        function_arn = aws_cloudfront_function.spa.arn
      }
    }
  }

  dynamic "ordered_cache_behavior" {
    for_each = ["/login/assets/*", "/console/assets/*"]

    content {
      path_pattern               = ordered_cache_behavior.value
      target_origin_id           = local.static_origin
      viewer_protocol_policy     = "redirect-to-https"
      allowed_methods            = ["GET", "HEAD"]
      cached_methods             = ["GET", "HEAD"]
      compress                   = true
      cache_policy_id            = aws_cloudfront_cache_policy.assets.id
      response_headers_policy_id = aws_cloudfront_response_headers_policy.static.id

      dynamic "function_association" {
        for_each = ["viewer-request", "viewer-response"]

        content {
          event_type   = function_association.value
          function_arn = aws_cloudfront_function.spa.arn
        }
      }
    }
  }

  # A client that sent cookies or a form over plain HTTP has already exposed them, so it gets an error instead of a redirect.
  dynamic "ordered_cache_behavior" {
    for_each = ["/api/*", "/saml/*"]

    content {
      path_pattern             = ordered_cache_behavior.value
      target_origin_id         = local.app_origin
      viewer_protocol_policy   = "https-only"
      allowed_methods          = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
      cached_methods           = ["GET", "HEAD"]
      cache_policy_id          = aws_cloudfront_cache_policy.api.id
      origin_request_policy_id = aws_cloudfront_origin_request_policy.api.id
    }
  }

  # Error responses apply to every behavior, so they keep the code and body of the origin and only stop the edge from caching
  # an error, which would otherwise reach every tenant. A missing key of S3 stays a 403, as the bucket denies CloudFront listing.
  dynamic "custom_error_response" {
    for_each = local.error_codes

    content {
      error_code            = custom_error_response.value
      error_caching_min_ttl = 0
    }
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    acm_certificate_arn      = aws_acm_certificate_validation.cloudfront.certificate_arn
    ssl_support_method       = "sni-only"
    minimum_protocol_version = "TLSv1.2_2021"
  }
}
