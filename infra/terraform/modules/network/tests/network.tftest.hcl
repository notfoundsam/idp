mock_provider "aws" {
  override_during = plan
}

override_data {
  target = data.aws_availability_zones.available
  values = {
    names = ["eu-central-1a", "eu-central-1b", "eu-central-1c"]
  }
}

override_data {
  target = data.aws_region.current
  values = {
    region = "eu-central-1"
  }
}

override_resource {
  target          = aws_subnet.private["eu-central-1a"]
  override_during = plan
  values = {
    id = "subnet-0a1b2c3d4e5f6071a"
  }
}

override_resource {
  target          = aws_subnet.private["eu-central-1b"]
  override_during = plan
  values = {
    id = "subnet-0a1b2c3d4e5f6071b"
  }
}

override_resource {
  target          = aws_security_group.this["ops-alb"]
  override_during = plan
  values = {
    id = "sg-0000000000000a1b1"
  }
}

override_resource {
  target          = aws_security_group.endpoints
  override_during = plan
  values = {
    id = "sg-0000000000000e0d0"
  }
}

override_resource {
  target          = aws_vpc_endpoint.s3
  override_during = plan
  values = {
    prefix_list_id = "pl-6ea54007"
  }
}

variables {
  name       = "idp-test"
  cidr_block = "10.20.0.0/16"
}

run "the_public_alb_drops_invalid_headers_and_refuses_desync" {
  command = plan

  assert {
    condition     = aws_lb.public.drop_invalid_header_fields
    error_message = "The ALB passes header fields that are not valid HTTP on to the services."
  }

  assert {
    condition     = aws_lb.public.desync_mitigation_mode == "strictest"
    error_message = "The ALB forwards requests that could be read as two, an HTTP desync."
  }

  assert {
    condition     = aws_lb.public.load_balancer_type == "application" && !aws_lb.public.internal && aws_lb.public.enable_deletion_protection
    error_message = "The public ALB is not an internet-facing application load balancer protected from deletion."
  }
}

run "the_alb_of_ops_is_internal_in_the_private_subnets" {
  command = plan

  assert {
    condition     = aws_lb.ops.internal && aws_lb.ops.load_balancer_type == "application" && aws_lb.ops.enable_deletion_protection
    error_message = "The ALB of ops is not an internal application load balancer protected from deletion."
  }

  assert {
    condition     = length(aws_lb.ops.subnets) == 2 && alltrue([for subnet in aws_lb.ops.subnets : contains([for private in aws_subnet.private : private.id], subnet)])
    error_message = "The ALB of ops lives outside the private subnets."
  }

  assert {
    condition     = aws_lb.ops.drop_invalid_header_fields && aws_lb.ops.desync_mitigation_mode == "strictest"
    error_message = "The ALB of ops passes invalid header fields or requests that could be read as two."
  }

  assert {
    condition     = aws_lb.ops.security_groups == toset([aws_security_group.this["ops-alb"].id])
    error_message = "The ALB of ops shares a security group."
  }
}

run "services_reach_aws_through_the_endpoints_alone" {
  command = plan

  assert {
    condition = alltrue([
      for rule in concat(values(aws_vpc_security_group_egress_rule.endpoints), values(aws_vpc_security_group_egress_rule.s3)) :
      rule.cidr_ipv4 == null && rule.cidr_ipv6 == null && rule.ip_protocol == "tcp" && rule.from_port == 443 && rule.to_port == 443
    ])
    error_message = "A service leaves the VPC by an address range instead of an endpoint."
  }

  assert {
    condition     = alltrue([for rule in values(aws_vpc_security_group_egress_rule.endpoints) : rule.referenced_security_group_id == aws_security_group.endpoints.id])
    error_message = "HTTPS of a service goes elsewhere than to the interface endpoints."
  }

  assert {
    condition     = alltrue([for rule in values(aws_vpc_security_group_egress_rule.s3) : rule.prefix_list_id == aws_vpc_endpoint.s3.prefix_list_id])
    error_message = "S3 is reached elsewhere than through the gateway endpoint."
  }

  assert {
    condition     = contains(var.interface_endpoints, "email-smtp")
    error_message = "SES is out of reach without a route to the internet."
  }
}
