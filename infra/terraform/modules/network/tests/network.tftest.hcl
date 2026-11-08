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
