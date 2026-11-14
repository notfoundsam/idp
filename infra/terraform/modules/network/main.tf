data "aws_region" "current" {}

data "aws_availability_zones" "available" {
  state = "available"

  filter {
    name   = "opt-in-status"
    values = ["opt-in-not-required"]
  }
}

locals {
  zones = slice(data.aws_availability_zones.available.names, 0, var.availability_zones)

  # A /20 per zone and tier: public from the first block, private from the fifth, database from the ninth.
  public_subnets   = { for index, zone in local.zones : zone => cidrsubnet(var.cidr_block, 4, index) }
  private_subnets  = { for index, zone in local.zones : zone => cidrsubnet(var.cidr_block, 4, 4 + index) }
  database_subnets = { for index, zone in local.zones : zone => cidrsubnet(var.cidr_block, 4, 8 + index) }

  security_groups = concat(["alb", "ops-alb", "database", "valkey"], var.services)
}

resource "aws_vpc" "this" {
  cidr_block           = var.cidr_block
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = {
    Name = var.name
  }
}

# Without rules nothing can use the default group by accident.
resource "aws_default_security_group" "this" {
  vpc_id = aws_vpc.this.id
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id

  tags = {
    Name = var.name
  }
}

resource "aws_subnet" "public" {
  for_each = local.public_subnets

  vpc_id            = aws_vpc.this.id
  availability_zone = each.key
  cidr_block        = each.value

  tags = {
    Name = "${var.name}-public-${each.key}"
  }
}

resource "aws_subnet" "private" {
  for_each = local.private_subnets

  vpc_id            = aws_vpc.this.id
  availability_zone = each.key
  cidr_block        = each.value

  tags = {
    Name = "${var.name}-private-${each.key}"
  }
}

resource "aws_subnet" "database" {
  for_each = local.database_subnets

  vpc_id            = aws_vpc.this.id
  availability_zone = each.key
  cidr_block        = each.value

  tags = {
    Name = "${var.name}-database-${each.key}"
  }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id

  tags = {
    Name = "${var.name}-public"
  }
}

resource "aws_route" "public_internet" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.this.id
}

# Tasks reach AWS only through the endpoints below, so the private tiers have no route out.
resource "aws_route_table" "private" {
  vpc_id = aws_vpc.this.id

  tags = {
    Name = "${var.name}-private"
  }
}

resource "aws_route_table" "database" {
  vpc_id = aws_vpc.this.id

  tags = {
    Name = "${var.name}-database"
  }
}

resource "aws_route_table_association" "public" {
  for_each = aws_subnet.public

  subnet_id      = each.value.id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table_association" "private" {
  for_each = aws_subnet.private

  subnet_id      = each.value.id
  route_table_id = aws_route_table.private.id
}

resource "aws_route_table_association" "database" {
  for_each = aws_subnet.database

  subnet_id      = each.value.id
  route_table_id = aws_route_table.database.id
}

resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${data.aws_region.current.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [aws_route_table.private.id]

  tags = {
    Name = "${var.name}-s3"
  }
}

resource "aws_security_group" "endpoints" {
  name        = "${var.name}-endpoints"
  description = "Interface endpoints of AWS services"
  vpc_id      = aws_vpc.this.id

  tags = {
    Name = "${var.name}-endpoints"
  }
}

# HTTPS for the AWS APIs, 587 for SMTP with STARTTLS to SES.
resource "aws_vpc_security_group_ingress_rule" "endpoints" {
  for_each = toset(["443", "587"])

  security_group_id = aws_security_group.endpoints.id
  description       = "Port ${each.key} from the VPC"
  cidr_ipv4         = aws_vpc.this.cidr_block
  ip_protocol       = "tcp"
  from_port         = tonumber(each.key)
  to_port           = tonumber(each.key)
}

resource "aws_vpc_endpoint" "interface" {
  for_each = toset(var.interface_endpoints)

  vpc_id              = aws_vpc.this.id
  service_name        = "com.amazonaws.${data.aws_region.current.region}.${each.key}"
  vpc_endpoint_type   = "Interface"
  private_dns_enabled = true
  subnet_ids          = [for subnet in aws_subnet.private : subnet.id]
  security_group_ids  = [aws_security_group.endpoints.id]

  tags = {
    Name = "${var.name}-${each.key}"
  }
}

# One group per workload and no rules: the module that connects two workloads adds the rules on both sides.
resource "aws_security_group" "this" {
  for_each = toset(local.security_groups)

  name        = "${var.name}-${each.key}"
  description = "${each.key} of ${var.name}"
  vpc_id      = aws_vpc.this.id

  tags = {
    Name = "${var.name}-${each.key}"
  }
}

# Fargate pulls images and secrets through the network of the task.
resource "aws_vpc_security_group_egress_rule" "endpoints" {
  for_each = toset(var.services)

  security_group_id            = aws_security_group.this[each.key].id
  description                  = "AWS APIs through interface endpoints"
  referenced_security_group_id = aws_security_group.endpoints.id
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
}

resource "aws_vpc_security_group_egress_rule" "s3" {
  for_each = toset(var.services)

  security_group_id = aws_security_group.this[each.key].id
  description       = "S3 through the gateway endpoint, image layers included"
  prefix_list_id    = aws_vpc_endpoint.s3.prefix_list_id
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

# Listeners and their certificates belong to the edge, targets to the services.
resource "aws_lb" "public" {
  name                       = "${var.name}-public"
  load_balancer_type         = "application"
  internal                   = false
  security_groups            = [aws_security_group.this["alb"].id]
  subnets                    = [for subnet in aws_subnet.public : subnet.id]
  drop_invalid_header_fields = true
  desync_mitigation_mode     = "strictest"
  enable_deletion_protection = var.deletion_protection
}

# ops answers only inside the VPC, and the module of the services admits the VPN to it.
resource "aws_lb" "ops" {
  name                       = "${var.name}-ops"
  load_balancer_type         = "application"
  internal                   = true
  security_groups            = [aws_security_group.this["ops-alb"].id]
  subnets                    = [for subnet in aws_subnet.private : subnet.id]
  drop_invalid_header_fields = true
  desync_mitigation_mode     = "strictest"
  enable_deletion_protection = var.deletion_protection
}
