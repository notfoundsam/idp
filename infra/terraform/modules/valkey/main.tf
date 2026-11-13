locals {
  clients       = ["app"]
  trusted_roots = ["AmazonRootCA1", "AmazonRootCA2", "AmazonRootCA3", "AmazonRootCA4"]
  tls_ca_pem    = join("", [for root in local.trusted_roots : one(data.tls_certificate.amazon_root[root].certificates).cert_pem])
}

# ElastiCache certificates chain to the roots of Amazon Trust Services, and app trusts these alone, never the JVM trust store.
data "http" "amazon_root" {
  for_each = toset(local.trusted_roots)

  url = "https://www.amazontrust.com/repository/${each.key}.pem"

  lifecycle {
    postcondition {
      condition     = self.status_code == 200
      error_message = "Amazon Trust Services publishes no ${each.key}."
    }
  }
}

data "tls_certificate" "amazon_root" {
  for_each = data.http.amazon_root

  content = each.value.response_body

  lifecycle {
    postcondition {
      condition     = length(self.certificates) == 1 && alltrue([for certificate in self.certificates : certificate.is_ca && strcontains(certificate.subject, "Amazon Root CA")])
      error_message = "${each.key} is not one root of Amazon Trust Services."
    }
  }
}

resource "aws_elasticache_subnet_group" "this" {
  name       = var.name
  subnet_ids = var.subnet_ids
}

# Throttle counters only, which start over without harm, so nothing is snapshotted.
resource "aws_elasticache_replication_group" "this" {
  replication_group_id       = var.name
  description                = "Throttle counters of ${var.name}"
  engine                     = "valkey"
  engine_version             = var.engine_version
  node_type                  = var.node_type
  num_cache_clusters         = var.nodes
  automatic_failover_enabled = true
  multi_az_enabled           = true
  port                       = 6379
  subnet_group_name          = aws_elasticache_subnet_group.this.name
  security_group_ids         = [var.security_group_ids["valkey"]]
  at_rest_encryption_enabled = true
  transit_encryption_enabled = true
  transit_encryption_mode    = "required"
  auth_token_wo              = var.auth_token
  auth_token_wo_version      = var.auth_token_version
  auth_token_update_strategy = "ROTATE"
  snapshot_retention_limit   = 0
  maintenance_window         = var.maintenance_window
  auto_minor_version_upgrade = true
  # ElastiCache changes a token only with this set, and the old one stays valid beside it until a person removes it, see
  # infra/terraform/README.md.
  apply_immediately = true
}

resource "aws_vpc_security_group_ingress_rule" "valkey" {
  for_each = toset(local.clients)

  security_group_id            = var.security_group_ids["valkey"]
  description                  = "Valkey from ${each.key}"
  referenced_security_group_id = var.security_group_ids[each.key]
  ip_protocol                  = "tcp"
  from_port                    = aws_elasticache_replication_group.this.port
  to_port                      = aws_elasticache_replication_group.this.port
}

resource "aws_vpc_security_group_egress_rule" "valkey" {
  for_each = toset(local.clients)

  security_group_id            = var.security_group_ids[each.key]
  description                  = "Valkey on ElastiCache"
  referenced_security_group_id = var.security_group_ids["valkey"]
  ip_protocol                  = "tcp"
  from_port                    = aws_elasticache_replication_group.this.port
  to_port                      = aws_elasticache_replication_group.this.port
}
