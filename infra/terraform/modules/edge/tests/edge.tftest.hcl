mock_provider "aws" {
  override_during = plan

  mock_resource "aws_acm_certificate" {
    defaults = {
      arn    = "arn:aws:acm:eu-central-1:111111111111:certificate/0b4f3c1e-7a52-4d9e-9a3c-5f0e2d8b6a41"
      region = "eu-central-1"
    }
  }
}

mock_provider "aws" {
  alias           = "us_east_1"
  override_during = plan

  mock_resource "aws_acm_certificate" {
    defaults = {
      arn    = "arn:aws:acm:us-east-1:111111111111:certificate/5f0e2d8b-6a41-4d9e-9a3c-0b4f3c1e7a52"
      region = "us-east-1"
    }
  }

  mock_resource "aws_wafv2_web_acl" {
    defaults = {
      arn    = "arn:aws:wafv2:us-east-1:111111111111:global/webacl/idp-test-edge/a1b2c3d4-5678-90ab-cdef-111111111111"
      region = "us-east-1"
    }
  }
}

override_data {
  target = data.aws_ec2_managed_prefix_list.cloudfront
  values = {
    id = "pl-a3a144ca"
  }
}

override_resource {
  target          = aws_lb_listener.https
  override_during = plan
  values = {
    arn = "arn:aws:elasticloadbalancing:eu-central-1:111111111111:listener/app/idp-test-public/50dc6c495c0c9188/f2f7dc8efc522ab2"
  }
}

override_resource {
  target          = aws_cloudfront_function.spa
  override_during = plan
  values = {
    arn = "arn:aws:cloudfront::111111111111:function/idp-test-spa"
  }
}

override_resource {
  target          = aws_cloudfront_origin_access_control.static
  override_during = plan
  values = {
    id = "oac-static"
  }
}

override_resource {
  target          = aws_cloudfront_response_headers_policy.static
  override_during = plan
  values = {
    id = "headers-static"
  }
}

override_resource {
  target          = aws_cloudfront_cache_policy.assets
  override_during = plan
  values = {
    id = "cache-assets"
  }
}

override_resource {
  target          = aws_cloudfront_cache_policy.pages
  override_during = plan
  values = {
    id = "cache-pages"
  }
}

override_resource {
  target          = aws_cloudfront_cache_policy.api
  override_during = plan
  values = {
    id = "cache-api"
  }
}

override_resource {
  target          = aws_cloudfront_origin_request_policy.api
  override_during = plan
  values = {
    id = "origin-request-api"
  }
}

variables {
  name                  = "idp-test"
  domain                = "idp.example.com"
  alb_arn               = "arn:aws:elasticloadbalancing:eu-central-1:111111111111:loadbalancer/app/idp-test-public/50dc6c495c0c9188"
  alb_dns_name          = "idp-test-public-1234567890.eu-central-1.elb.amazonaws.com"
  alb_security_group_id = "sg-0a1b2c3d4e5f60718"
  origin_header_value   = "Origin0Header0Of0The0Test0Distribution0Only0Letters0And0Digits0"
  static_origin_domain  = "idp-test-static-111111111111.s3.eu-central-1.amazonaws.com"
  security_headers      = jsondecode(file("../../../web/security-headers.json"))
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

run "tenant_domains_are_served_with_the_certificate_of_us_east_1" {
  command = plan

  assert {
    condition     = aws_cloudfront_distribution.this.aliases == toset(["*.idp.example.com"])
    error_message = "The distribution does not serve the subdomains of the domain."
  }

  assert {
    condition = (
      aws_acm_certificate.cloudfront.domain_name == "*.idp.example.com" &&
      aws_acm_certificate.cloudfront.validation_method == "DNS" &&
      aws_acm_certificate.cloudfront.region == "us-east-1"
    )
    error_message = "The certificate of CloudFront is not a wildcard of the domain in us-east-1."
  }

  assert {
    condition = (
      one(aws_cloudfront_distribution.this.viewer_certificate).acm_certificate_arn == aws_acm_certificate_validation.cloudfront.certificate_arn &&
      aws_acm_certificate_validation.cloudfront.certificate_arn == aws_acm_certificate.cloudfront.arn &&
      one(aws_cloudfront_distribution.this.viewer_certificate).ssl_support_method == "sni-only"
    )
    error_message = "The distribution does not serve the validated certificate by SNI."
  }

  assert {
    condition     = one(aws_cloudfront_distribution.this.viewer_certificate).minimum_protocol_version == "TLSv1.2_2021"
    error_message = "Viewers may connect with less than TLS 1.2 or weaker ciphers."
  }
}

run "the_apps_come_from_s3_behind_the_function" {
  command = plan

  assert {
    condition = anytrue([
      for origin in aws_cloudfront_distribution.this.origin : (
        origin.origin_id == "static" &&
        origin.domain_name == var.static_origin_domain &&
        origin.origin_access_control_id == aws_cloudfront_origin_access_control.static.id &&
        length(origin.custom_origin_config) == 0
      )
    ])
    error_message = "The bucket is not an origin read through origin access control."
  }

  assert {
    condition = (
      one(aws_cloudfront_distribution.this.default_cache_behavior).target_origin_id == "static" &&
      one(aws_cloudfront_distribution.this.default_cache_behavior).cache_policy_id == aws_cloudfront_cache_policy.pages.id &&
      one(aws_cloudfront_distribution.this.default_cache_behavior).response_headers_policy_id == aws_cloudfront_response_headers_policy.static.id
    )
    error_message = "Pages, the redirect of / and the 404 of unknown paths do not come from S3 with the headers of the static files."
  }

  assert {
    condition     = [for behavior in aws_cloudfront_distribution.this.ordered_cache_behavior : behavior.path_pattern if behavior.target_origin_id == "static"] == ["/login/assets/*", "/console/assets/*"]
    error_message = "The chunks of the apps are not behaviors of S3."
  }

  assert {
    condition = alltrue([
      for behavior in aws_cloudfront_distribution.this.ordered_cache_behavior : (
        behavior.cache_policy_id == aws_cloudfront_cache_policy.assets.id &&
        behavior.response_headers_policy_id == aws_cloudfront_response_headers_policy.static.id &&
        behavior.compress
      )
      if behavior.target_origin_id == "static"
    ])
    error_message = "Chunks are not cached for a year, compressed and sent with the headers of the static files."
  }

  assert {
    condition = alltrue([
      for behavior in concat(aws_cloudfront_distribution.this.default_cache_behavior, [for behavior in aws_cloudfront_distribution.this.ordered_cache_behavior : behavior if behavior.target_origin_id == "static"]) :
      behavior.function_association == toset([
        { event_type = "viewer-request", function_arn = aws_cloudfront_function.spa.arn },
        { event_type = "viewer-response", function_arn = aws_cloudfront_function.spa.arn },
      ])
    ])
    error_message = "A behavior of S3 lacks the function on the request or on the response."
  }

  assert {
    condition = alltrue([
      for behavior in concat(aws_cloudfront_distribution.this.default_cache_behavior, [for behavior in aws_cloudfront_distribution.this.ordered_cache_behavior : behavior if behavior.target_origin_id == "static"]) :
      behavior.viewer_protocol_policy == "redirect-to-https" && behavior.allowed_methods == toset(["GET", "HEAD"])
    ])
    error_message = "A page is served over plain HTTP or S3 gets more than reads."
  }
}

run "api_and_saml_reach_app_uncached_with_the_whole_viewer_request" {
  command = plan

  assert {
    condition = anytrue([
      for origin in aws_cloudfront_distribution.this.origin : (
        origin.origin_id == "app" &&
        origin.domain_name == var.alb_dns_name &&
        one(origin.custom_origin_config).origin_protocol_policy == "https-only" &&
        one(origin.custom_origin_config).https_port == 443 &&
        one(origin.custom_origin_config).origin_ssl_protocols == toset(["TLSv1.2"])
      )
    ])
    error_message = "CloudFront reaches the ALB without TLS 1.2."
  }

  assert {
    condition     = [for behavior in aws_cloudfront_distribution.this.ordered_cache_behavior : behavior.path_pattern if behavior.target_origin_id == "app"] == ["/api/*", "/saml/*"]
    error_message = "The API and SAML do not reach app."
  }

  assert {
    condition = alltrue([
      for behavior in aws_cloudfront_distribution.this.ordered_cache_behavior : (
        behavior.cache_policy_id == aws_cloudfront_cache_policy.api.id &&
        behavior.origin_request_policy_id == aws_cloudfront_origin_request_policy.api.id &&
        behavior.allowed_methods == toset(["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"])
      )
      if behavior.target_origin_id == "app"
    ])
    error_message = "A behavior of app caches, drops a part of the request or refuses a method."
  }

  assert {
    condition = alltrue([
      for behavior in aws_cloudfront_distribution.this.ordered_cache_behavior : (
        length(behavior.function_association) == 0 &&
        (behavior.response_headers_policy_id == null || behavior.response_headers_policy_id == "")
      )
      if behavior.target_origin_id == "app"
    ])
    error_message = "The function or the headers of the static files touch the API."
  }

  assert {
    condition = (
      aws_cloudfront_cache_policy.api.min_ttl == 0 &&
      aws_cloudfront_cache_policy.api.default_ttl == 0 &&
      aws_cloudfront_cache_policy.api.max_ttl == 0
    )
    error_message = "The edge caches a response of app, which would reach every tenant."
  }

  assert {
    condition = (
      one(aws_cloudfront_origin_request_policy.api.headers_config).header_behavior == "allViewerAndWhitelistCloudFront" &&
      one(aws_cloudfront_origin_request_policy.api.cookies_config).cookie_behavior == "all" &&
      one(aws_cloudfront_origin_request_policy.api.query_strings_config).query_string_behavior == "all"
    )
    error_message = "app does not get the Host, every cookie and the query string of the viewer."
  }
}

run "app_gets_the_address_of_the_viewer_from_cloudfront" {
  command = plan

  assert {
    condition     = one(one(aws_cloudfront_origin_request_policy.api.headers_config).headers).items == toset(["CloudFront-Viewer-Address"])
    error_message = "CloudFront does not send the address of the viewer, or sends another header it generates."
  }

  assert {
    condition     = output.client_ip_header == "CloudFront-Viewer-Address"
    error_message = "app would read the client address from another header than the one CloudFront sets."
  }
}

run "the_waf_of_us_east_1_limits_each_client_address" {
  command = plan

  assert {
    condition = (
      aws_wafv2_web_acl.edge.scope == "CLOUDFRONT" &&
      aws_wafv2_web_acl.edge.region == "us-east-1" &&
      aws_cloudfront_distribution.this.web_acl_id == aws_wafv2_web_acl.edge.arn
    )
    error_message = "The distribution has no web ACL of CloudFront in us-east-1."
  }

  assert {
    condition     = length(one(aws_wafv2_web_acl.edge.default_action).allow) == 1
    error_message = "The web ACL blocks what no rule names."
  }

  assert {
    condition = alltrue([
      for rule in aws_wafv2_web_acl.edge.rule : (
        one(one(rule.statement).rate_based_statement).aggregate_key_type == "IP" &&
        one(one(rule.statement).rate_based_statement).evaluation_window_sec == 300 &&
        length(one(one(rule.statement).rate_based_statement).forwarded_ip_config) == 0 &&
        one(one(one(rule.action).block).custom_response).response_code == 429
      )
    ])
    error_message = "A rule counts by something else than the address of the viewer, or refuses without a 429."
  }

  assert {
    condition = {
      for rule in aws_wafv2_web_acl.edge.rule : rule.name => one(one(rule.statement).rate_based_statement).limit
      } == {
      "requests-per-address"     = 20000
      "app-requests-per-address" = 10000
    }
    error_message = "The limits differ from docs/security/limits.md."
  }

  assert {
    condition = output.waf_metrics == {
      web_acl = "idp-test-edge"
      rules   = tolist(["idp-test-app-requests-per-address", "idp-test-requests-per-address"])
    }
    error_message = "The alarms of modules/alarms would count the blocks of another web ACL or rule."
  }

  assert {
    condition = alltrue([
      for rule in aws_wafv2_web_acl.edge.rule :
      length(one(one(rule.statement).rate_based_statement).scope_down_statement) == 0
      if rule.name == "requests-per-address"
    ])
    error_message = "The limit of all requests leaves some out."
  }

  assert {
    condition = alltrue([
      for rule in aws_wafv2_web_acl.edge.rule : [
        for statement in one(one(one(one(rule.statement).rate_based_statement).scope_down_statement).or_statement).statement : {
          path       = one(statement.byte_match_statement).search_string
          constraint = one(statement.byte_match_statement).positional_constraint
          uri_path   = length(one(one(statement.byte_match_statement).field_to_match).uri_path) == 1
        }
        ] == [
        { path = "/api/", constraint = "STARTS_WITH", uri_path = true },
        { path = "/saml/", constraint = "STARTS_WITH", uri_path = true },
      ]
      if rule.name == "app-requests-per-address"
    ])
    error_message = "The limit of app counts other paths than /api/ and /saml/."
  }
}

run "the_alb_takes_https_from_cloudfront_alone" {
  command = plan

  assert {
    condition = (
      aws_vpc_security_group_ingress_rule.alb_from_cloudfront.security_group_id == var.alb_security_group_id &&
      aws_vpc_security_group_ingress_rule.alb_from_cloudfront.prefix_list_id == "pl-a3a144ca" &&
      data.aws_ec2_managed_prefix_list.cloudfront.name == "com.amazonaws.global.cloudfront.origin-facing" &&
      aws_vpc_security_group_ingress_rule.alb_from_cloudfront.cidr_ipv4 == null &&
      aws_vpc_security_group_ingress_rule.alb_from_cloudfront.cidr_ipv6 == null
    )
    error_message = "The ALB admits addresses beyond the origin-facing servers of CloudFront."
  }

  assert {
    condition = (
      aws_vpc_security_group_ingress_rule.alb_from_cloudfront.ip_protocol == "tcp" &&
      aws_vpc_security_group_ingress_rule.alb_from_cloudfront.from_port == 443 &&
      aws_vpc_security_group_ingress_rule.alb_from_cloudfront.to_port == 443
    )
    error_message = "CloudFront reaches the ALB on another port than HTTPS."
  }
}

run "the_alb_refuses_a_request_without_the_header_of_this_distribution" {
  command = plan

  assert {
    condition = anytrue([
      for origin in aws_cloudfront_distribution.this.origin :
      origin.custom_header == toset([{ name = "X-Origin-Verify", value = var.origin_header_value }])
      if origin.origin_id == "app"
    ])
    error_message = "CloudFront does not send the generated header to the ALB."
  }

  assert {
    condition = alltrue([
      for origin in aws_cloudfront_distribution.this.origin : length(origin.custom_header) == 0 if origin.origin_id == "static"
    ])
    error_message = "The header of the ALB reaches S3."
  }

  assert {
    condition = (
      one(aws_lb_listener.https.default_action).type == "fixed-response" &&
      one(one(aws_lb_listener.https.default_action).fixed_response).status_code == "403"
    )
    error_message = "A request without the header gets something else than a 403."
  }

  assert {
    condition = (
      aws_lb_listener_rule.from_cloudfront.listener_arn == aws_lb_listener.https.arn &&
      aws_lb_listener_rule.from_cloudfront.priority == 50000 &&
      one(one(aws_lb_listener_rule.from_cloudfront.condition).http_header).http_header_name == "X-Origin-Verify" &&
      one(one(aws_lb_listener_rule.from_cloudfront.condition).http_header).values == toset([var.origin_header_value])
    )
    error_message = "The last rule of the listener does not require the header of the distribution."
  }

  assert {
    condition = (
      one(aws_lb_listener_rule.from_cloudfront.action).type == "fixed-response" &&
      one(one(aws_lb_listener_rule.from_cloudfront.action).fixed_response).status_code == "404"
    )
    error_message = "A request of the distribution that no service takes reaches one."
  }

  assert {
    condition     = output.origin_header == "X-Origin-Verify"
    error_message = "The rules of the services would require another header."
  }
}

run "only_pages_redirect_to_https" {
  command = plan

  assert {
    condition = alltrue([
      for behavior in aws_cloudfront_distribution.this.ordered_cache_behavior :
      behavior.viewer_protocol_policy == "https-only" if behavior.target_origin_id == "app"
    ])
    error_message = "A plain HTTP request to the API or SAML is redirected instead of refused."
  }

  assert {
    condition     = length(aws_cloudfront_distribution.this.ordered_cache_behavior) == 4
    error_message = "A behavior beyond the chunks, the API and SAML exists."
  }
}

run "errors_keep_their_code_and_are_not_cached" {
  command = plan

  assert {
    condition     = toset([for response in aws_cloudfront_distribution.this.custom_error_response : response.error_code]) == toset([400, 403, 404, 405, 414, 416, 500, 501, 502, 503, 504])
    error_message = "An error code that CloudFront caches by default has no rule."
  }

  assert {
    condition = alltrue([
      for response in aws_cloudfront_distribution.this.custom_error_response : (
        response.error_caching_min_ttl == 0 &&
        (response.response_code == null || response.response_code == 0) &&
        (response.response_page_path == null || response.response_page_path == "")
      )
    ])
    error_message = "The edge caches an error or replaces the answer of the origin, also of the API."
  }
}

run "the_alb_serves_tls_with_the_certificate_of_its_region" {
  command = plan

  assert {
    condition = (
      aws_lb_listener.https.load_balancer_arn == var.alb_arn &&
      aws_lb_listener.https.port == 443 &&
      aws_lb_listener.https.protocol == "HTTPS" &&
      aws_lb_listener.https.certificate_arn == aws_acm_certificate_validation.alb.certificate_arn &&
      aws_acm_certificate_validation.alb.certificate_arn == aws_acm_certificate.alb.arn
    )
    error_message = "The ALB does not serve HTTPS with the validated certificate."
  }

  assert {
    condition = (
      aws_acm_certificate.alb.domain_name == "*.idp.example.com" &&
      aws_acm_certificate.alb.validation_method == "DNS" &&
      aws_acm_certificate.alb.region == "eu-central-1"
    )
    error_message = "The certificate of the ALB does not match the Host of a tenant or lives outside the region of the ALB."
  }

  assert {
    condition     = aws_lb_listener.https.ssl_policy == "ELBSecurityPolicy-TLS13-1-2-Res-2021-06"
    error_message = "The ALB accepts less than TLS 1.2 or ciphers without forward secrecy."
  }

  assert {
    condition     = one(aws_lb_listener.https.default_action).type == "fixed-response"
    error_message = "A request that no rule takes reaches a service."
  }
}

run "the_records_of_both_certificates_are_listed_once" {
  command = plan

  override_resource {
    target          = aws_acm_certificate.cloudfront
    override_during = plan
    values = {
      arn = "arn:aws:acm:us-east-1:111111111111:certificate/5f0e2d8b-6a41-4d9e-9a3c-0b4f3c1e7a52"
      domain_validation_options = [{
        domain_name           = "*.idp.example.com"
        resource_record_name  = "_a1.idp.example.com."
        resource_record_type  = "CNAME"
        resource_record_value = "_b1.acm-validations.aws."
      }]
    }
  }

  override_resource {
    target          = aws_acm_certificate.alb
    override_during = plan
    values = {
      arn = "arn:aws:acm:eu-central-1:111111111111:certificate/0b4f3c1e-7a52-4d9e-9a3c-5f0e2d8b6a41"
      domain_validation_options = [{
        domain_name           = "*.idp.example.com"
        resource_record_name  = "_a1.idp.example.com."
        resource_record_type  = "CNAME"
        resource_record_value = "_b1.acm-validations.aws."
      }]
    }
  }

  assert {
    condition = (
      length(output.certificate_validation_records) == 1 &&
      toset(output.certificate_validation_records) == toset([{ name = "_a1.idp.example.com.", type = "CNAME", value = "_b1.acm-validations.aws." }])
    )
    error_message = "A record that both certificates share is listed twice."
  }
}

run "the_records_of_both_certificates_are_listed" {
  command = plan

  override_resource {
    target          = aws_acm_certificate.cloudfront
    override_during = plan
    values = {
      arn = "arn:aws:acm:us-east-1:111111111111:certificate/5f0e2d8b-6a41-4d9e-9a3c-0b4f3c1e7a52"
      domain_validation_options = [{
        domain_name           = "*.idp.example.com"
        resource_record_name  = "_a1.idp.example.com."
        resource_record_type  = "CNAME"
        resource_record_value = "_b1.acm-validations.aws."
      }]
    }
  }

  override_resource {
    target          = aws_acm_certificate.alb
    override_during = plan
    values = {
      arn = "arn:aws:acm:eu-central-1:111111111111:certificate/0b4f3c1e-7a52-4d9e-9a3c-5f0e2d8b6a41"
      domain_validation_options = [{
        domain_name           = "*.idp.example.com"
        resource_record_name  = "_a2.idp.example.com."
        resource_record_type  = "CNAME"
        resource_record_value = "_b2.acm-validations.aws."
      }]
    }
  }

  assert {
    condition = toset(output.certificate_validation_records) == toset([
      { name = "_a1.idp.example.com.", type = "CNAME", value = "_b1.acm-validations.aws." },
      { name = "_a2.idp.example.com.", type = "CNAME", value = "_b2.acm-validations.aws." },
    ])
    error_message = "The record of a certificate is missing."
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

run "a_domain_in_capitals_refused" {
  command = plan

  variables {
    domain = "IdP.example.com"
  }

  expect_failures = [var.domain]
}

run "a_wildcard_domain_refused" {
  command = plan

  variables {
    domain = "*.idp.example.com"
  }

  expect_failures = [var.domain]
}

run "a_domain_with_a_trailing_dot_refused" {
  command = plan

  variables {
    domain = "idp.example.com."
  }

  expect_failures = [var.domain]
}

run "an_alb_origin_with_a_scheme_refused" {
  command = plan

  variables {
    alb_dns_name = "https://idp-test-public-1234567890.eu-central-1.elb.amazonaws.com"
  }

  expect_failures = [var.alb_dns_name]
}

run "a_network_load_balancer_refused" {
  command = plan

  variables {
    alb_arn = "arn:aws:elasticloadbalancing:eu-central-1:111111111111:loadbalancer/net/idp-test-public/50dc6c495c0c9188"
  }

  expect_failures = [var.alb_arn]
}

run "a_security_group_by_name_refused" {
  command = plan

  variables {
    alb_security_group_id = "idp-test-alb"
  }

  expect_failures = [var.alb_security_group_id]
}

run "an_origin_header_with_a_wildcard_refused" {
  command = plan

  variables {
    origin_header_value = "Origin0Header0Of0The0Test0Distribution*"
  }

  expect_failures = [var.origin_header_value]
}

run "a_short_origin_header_refused" {
  command = plan

  variables {
    origin_header_value = "Short0Header"
  }

  expect_failures = [var.origin_header_value]
}

run "an_origin_header_longer_than_the_alb_compares_refused" {
  command = plan

  variables {
    origin_header_value = join("", [for index in range(129) : "a"])
  }

  expect_failures = [var.origin_header_value]
}

run "the_website_endpoint_of_a_bucket_refused" {
  command = plan

  variables {
    static_origin_domain = "idp-test-static-111111111111.s3-website.eu-central-1.amazonaws.com"
  }

  expect_failures = [var.static_origin_domain]
}
