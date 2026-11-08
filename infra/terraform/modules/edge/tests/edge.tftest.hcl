mock_provider "aws" {}

variables {
  name             = "idp-test"
  security_headers = jsondecode(file("../../../web/security-headers.json"))
}

run "headers_of_the_static_files_come_from_security_headers_json" {
  command = plan

  assert {
    condition     = aws_cloudfront_response_headers_policy.static.security_headers_config[0].content_security_policy[0].content_security_policy == var.security_headers["Content-Security-Policy"]
    error_message = "The policy differs from security-headers.json."
  }

  assert {
    condition = (
      aws_cloudfront_response_headers_policy.static.security_headers_config[0].strict_transport_security[0].access_control_max_age_sec == 31536000 &&
      aws_cloudfront_response_headers_policy.static.security_headers_config[0].strict_transport_security[0].include_subdomains &&
      !aws_cloudfront_response_headers_policy.static.security_headers_config[0].strict_transport_security[0].preload
    )
    error_message = "HSTS differs from security-headers.json."
  }

  assert {
    condition     = length(aws_cloudfront_response_headers_policy.static.security_headers_config[0].content_type_options) == 1
    error_message = "nosniff is missing."
  }

  assert {
    condition     = aws_cloudfront_response_headers_policy.static.security_headers_config[0].referrer_policy[0].referrer_policy == "no-referrer"
    error_message = "Referrer-Policy differs from security-headers.json."
  }

  assert {
    condition = alltrue([
      for config in aws_cloudfront_response_headers_policy.static.security_headers_config : alltrue(flatten([
        [for header in config.content_security_policy : header.override],
        [for header in config.strict_transport_security : header.override],
        [for header in config.content_type_options : header.override],
        [for header in config.referrer_policy : header.override],
      ]))
    ])
    error_message = "A header of S3 could replace a security header."
  }

  assert {
    condition     = length(aws_cloudfront_response_headers_policy.static.security_headers_config[0].frame_options) == 0 && length(aws_cloudfront_response_headers_policy.static.custom_headers_config) == 0
    error_message = "A header that security-headers.json does not name is set."
  }
}

run "a_header_without_a_typed_field_becomes_a_custom_header" {
  command = plan

  variables {
    security_headers = {
      "X-Frame-Options"    = "DENY"
      "Permissions-Policy" = "camera=(), microphone=()"
    }
  }

  assert {
    condition     = aws_cloudfront_response_headers_policy.static.security_headers_config[0].frame_options[0].frame_option == "DENY"
    error_message = "X-Frame-Options is missing."
  }

  assert {
    condition = (
      length(aws_cloudfront_response_headers_policy.static.custom_headers_config[0].items) == 1 &&
      one(aws_cloudfront_response_headers_policy.static.custom_headers_config[0].items).header == "Permissions-Policy" &&
      one(aws_cloudfront_response_headers_policy.static.custom_headers_config[0].items).value == "camera=(), microphone=()" &&
      one(aws_cloudfront_response_headers_policy.static.custom_headers_config[0].items).override
    )
    error_message = "Permissions-Policy is not a custom header."
  }

  assert {
    condition     = length(aws_cloudfront_response_headers_policy.static.security_headers_config[0].content_security_policy) == 0
    error_message = "A policy is set without one in the headers."
  }
}

run "hsts_with_preload" {
  command = plan

  variables {
    security_headers = { "Strict-Transport-Security" = "max-age=63072000; includeSubDomains; preload" }
  }

  assert {
    condition = (
      aws_cloudfront_response_headers_policy.static.security_headers_config[0].strict_transport_security[0].access_control_max_age_sec == 63072000 &&
      aws_cloudfront_response_headers_policy.static.security_headers_config[0].strict_transport_security[0].preload
    )
    error_message = "HSTS lost its max-age or preload."
  }
}

run "the_function_routes_the_apps_with_the_tested_code" {
  command = plan

  assert {
    condition     = aws_cloudfront_function.spa.runtime == "cloudfront-js-2.0" && aws_cloudfront_function.spa.publish
    error_message = "The function runs on another runtime or is not published."
  }

  assert {
    condition     = aws_cloudfront_function.spa.code == file("../../../web/spa-rewrite.js")
    error_message = "The function is not infra/web/spa-rewrite.js."
  }
}

run "chunks_stay_at_the_edge_and_pages_do_not" {
  command = plan

  assert {
    condition     = aws_cloudfront_cache_policy.assets.default_ttl == 31536000 && aws_cloudfront_cache_policy.assets.max_ttl == 31536000
    error_message = "Chunks do not stay for a year."
  }

  assert {
    condition = (
      aws_cloudfront_cache_policy.pages.min_ttl == 0 &&
      aws_cloudfront_cache_policy.pages.default_ttl == 0 &&
      aws_cloudfront_cache_policy.pages.max_ttl == 0
    )
    error_message = "Pages are cached at the edge."
  }

  assert {
    condition = alltrue([
      for policy in [aws_cloudfront_cache_policy.assets, aws_cloudfront_cache_policy.pages] : alltrue([
        for parameters in policy.parameters_in_cache_key_and_forwarded_to_origin : (
          one(parameters.cookies_config).cookie_behavior == "none" &&
          one(parameters.headers_config).header_behavior == "none" &&
          one(parameters.query_strings_config).query_string_behavior == "none"
        )
      ])
    ])
    error_message = "S3 gets cookies, headers or query strings."
  }
}

run "the_bucket_is_read_with_signed_requests" {
  command = plan

  assert {
    condition     = aws_cloudfront_origin_access_control.static.signing_behavior == "always" && aws_cloudfront_origin_access_control.static.origin_access_control_origin_type == "s3"
    error_message = "CloudFront could read the bucket unsigned."
  }
}

run "no_headers_refused" {
  command = plan

  variables {
    security_headers = {}
  }

  expect_failures = [var.security_headers]
}

run "a_value_with_a_line_break_refused" {
  command = plan

  variables {
    security_headers = { "X-Test" = "a\nb" }
  }

  expect_failures = [var.security_headers]
}

run "a_typed_header_in_another_spelling_refused" {
  command = plan

  variables {
    security_headers = { "content-security-policy" = "default-src 'none'" }
  }

  expect_failures = [var.security_headers]
}

run "hsts_without_max_age_refused" {
  command = plan

  variables {
    security_headers = { "Strict-Transport-Security" = "includeSubDomains" }
  }

  expect_failures = [var.security_headers]
}

run "another_nosniff_value_refused" {
  command = plan

  variables {
    security_headers = { "X-Content-Type-Options" = "sniff" }
  }

  expect_failures = [var.security_headers]
}

run "an_unknown_referrer_policy_refused" {
  command = plan

  variables {
    security_headers = { "Referrer-Policy" = "never" }
  }

  expect_failures = [var.security_headers]
}

run "a_policy_longer_than_cloudfront_takes_refused" {
  command = plan

  variables {
    security_headers = { "Content-Security-Policy" = "default-src 'none'; ${join("; ", [for index in range(200) : "img-src https://img${index}.example.com"])}" }
  }

  expect_failures = [var.security_headers]
}
