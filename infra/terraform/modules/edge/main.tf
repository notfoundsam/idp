locals {
  typed_headers  = ["Content-Security-Policy", "Strict-Transport-Security", "X-Content-Type-Options", "Referrer-Policy", "X-Frame-Options"]
  custom_headers = { for name, value in var.security_headers : name => value if !contains(local.typed_headers, name) }
  hsts           = lookup(var.security_headers, "Strict-Transport-Security", null)
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
