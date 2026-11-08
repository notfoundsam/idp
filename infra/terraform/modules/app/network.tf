locals {
  # Each load balancer reaches its service on the port of the application and on the management port for the health check.
  balancer_targets = merge([
    for target in [{ balancer = "alb", service = "app" }, { balancer = "ops-alb", service = "ops" }] : {
      for port in [local.http_port, local.management_port] : "${target.service}-${port}" => merge(target, { port = port })
    }
  ]...)
}

resource "aws_vpc_security_group_egress_rule" "balancer" {
  for_each = local.balancer_targets

  security_group_id            = var.security_group_ids[each.value.balancer]
  description                  = "${each.value.service} on ${each.value.port}"
  referenced_security_group_id = var.security_group_ids[each.value.service]
  ip_protocol                  = "tcp"
  from_port                    = each.value.port
  to_port                      = each.value.port
}

resource "aws_vpc_security_group_ingress_rule" "balancer" {
  for_each = local.balancer_targets

  security_group_id            = var.security_group_ids[each.value.service]
  description                  = "Port ${each.value.port} from ${each.value.balancer}"
  referenced_security_group_id = var.security_group_ids[each.value.balancer]
  ip_protocol                  = "tcp"
  from_port                    = each.value.port
  to_port                      = each.value.port
}

resource "aws_vpc_security_group_ingress_rule" "vpn" {
  for_each = toset(var.ops_client_cidrs)

  security_group_id = var.security_group_ids["ops-alb"]
  description       = "HTTPS from the VPN clients in ${each.key}"
  cidr_ipv4         = each.key
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

# SES answers inside the VPC through the interface endpoint email-smtp, SMTP with STARTTLS.
resource "aws_vpc_security_group_egress_rule" "smtp" {
  security_group_id            = var.security_group_ids["worker"]
  description                  = "SMTP to SES through the interface endpoint"
  referenced_security_group_id = var.endpoint_security_group_id
  ip_protocol                  = "tcp"
  from_port                    = 587
  to_port                      = 587
}
