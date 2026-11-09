locals {
  health_paths = {
    app = "/actuator/health/readiness"
    ops = "/actuator/health"
  }

  # Below the last rule of the listener at 50000, which answers 404.
  app_routes = {
    api  = { priority = 100, path = "/api/*" }
    saml = { priority = 200, path = "/saml/*" }
  }
}

# Requests reach the port of the application, and only the health check of the ALB reaches the management port. Both serve
# TLS with a certificate of the internal CA, which the ALB does not verify: it checks the certificate of no target.
resource "aws_lb_target_group" "this" {
  for_each = local.balanced

  name                 = "${var.name}-${each.key}"
  vpc_id               = var.vpc_id
  target_type          = "ip"
  protocol             = "HTTPS"
  protocol_version     = "HTTP1"
  port                 = local.http_port
  deregistration_delay = 30

  health_check {
    enabled             = true
    protocol            = "HTTPS"
    port                = tostring(local.management_port)
    path                = local.health_paths[each.key]
    matcher             = "200"
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  # ops keeps its sessions in memory, for as long as the idle timeout of a session.
  stickiness {
    type            = "lb_cookie"
    enabled         = each.key == "ops"
    cookie_duration = 1800
  }
}

# The prefix list of CloudFront admits every distribution, so each rule also requires the header of this one.
resource "aws_lb_listener_rule" "app" {
  for_each = local.app_routes

  listener_arn = var.https_listener_arn
  priority     = each.value.priority

  condition {
    path_pattern {
      values = [each.value.path]
    }
  }

  condition {
    http_header {
      http_header_name = var.origin_header
      values           = [var.origin_header_value]
    }
  }

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.this["app"].arn
  }
}

resource "aws_lb_listener" "ops" {
  load_balancer_arn = var.ops_alb_arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-Res-2021-06"
  certificate_arn   = var.ops_certificate_arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.this["ops"].arn
  }
}
