mock_provider "aws" {}

mock_provider "http" {
  mock_data "http" {
    defaults = {
      status_code   = 200
      response_body = "root of Amazon Trust Services"
    }
  }
}

mock_provider "tls" {
  mock_data "tls_certificate" {
    defaults = {
      certificates = [{
        is_ca    = true
        subject  = "CN=Amazon Root CA 1,O=Amazon,C=US"
        cert_pem = "-----BEGIN CERTIFICATE-----\nroot\n-----END CERTIFICATE-----\n"
      }]
    }
  }
}

variables {
  name               = "idp-test"
  subnet_ids         = ["subnet-0a1b2c3d4e5f60711", "subnet-0a1b2c3d4e5f60712"]
  security_group_ids = { valkey = "sg-0000000000000a1b1", app = "sg-0000000000000a1b2", worker = "sg-0000000000000a1b3" }
  auth_token         = "token-of-the-test"
}

run "valkey_speaks_tls_alone_and_asks_for_the_token" {
  command = plan

  assert {
    condition = (
      aws_elasticache_replication_group.this.engine == "valkey" &&
      aws_elasticache_replication_group.this.transit_encryption_enabled &&
      aws_elasticache_replication_group.this.transit_encryption_mode == "required" &&
      aws_elasticache_replication_group.this.at_rest_encryption_enabled
    )
    error_message = "Valkey takes connections without TLS, or keeps its data unencrypted."
  }

  assert {
    condition = (
      aws_elasticache_replication_group.this.auth_token_wo_version == 1 &&
      aws_elasticache_replication_group.this.auth_token_update_strategy == "ROTATE" &&
      aws_elasticache_replication_group.this.apply_immediately
    )
    error_message = "A new token would not reach ElastiCache, or would cut off app before it redeploys."
  }

  assert {
    condition     = aws_elasticache_replication_group.this.snapshot_retention_limit == 0
    error_message = "ElastiCache snapshots the counters of Throttle, which hold client addresses."
  }

  assert {
    condition = (
      aws_elasticache_replication_group.this.automatic_failover_enabled &&
      aws_elasticache_replication_group.this.multi_az_enabled &&
      aws_elasticache_replication_group.this.num_cache_clusters == 2
    )
    error_message = "A failed node stops Throttle."
  }
}

run "app_alone_reaches_valkey" {
  command = plan

  assert {
    condition = (
      [for rule in aws_vpc_security_group_ingress_rule.valkey : "${rule.security_group_id} <- ${rule.referenced_security_group_id}"] == ["sg-0000000000000a1b1 <- sg-0000000000000a1b2"] &&
      [for rule in aws_vpc_security_group_egress_rule.valkey : "${rule.security_group_id} -> ${rule.referenced_security_group_id}"] == ["sg-0000000000000a1b2 -> sg-0000000000000a1b1"]
    )
    error_message = "Another workload than app reaches Valkey."
  }
}

run "app_trusts_the_roots_of_amazon_trust_services_alone" {
  command = plan

  assert {
    condition     = toset(keys(data.http.amazon_root)) == toset(["AmazonRootCA1", "AmazonRootCA2", "AmazonRootCA3", "AmazonRootCA4"])
    error_message = "app trusts another CA than the roots of Amazon Trust Services for Valkey."
  }

  assert {
    condition     = alltrue([for root in data.http.amazon_root : startswith(root.url, "https://www.amazontrust.com/repository/")])
    error_message = "A root comes from elsewhere than Amazon Trust Services."
  }

  assert {
    condition     = output.tls_ca_pem == join("", [for _ in range(4) : "-----BEGIN CERTIFICATE-----\nroot\n-----END CERTIFICATE-----\n"])
    error_message = "VALKEY_TLS_CA misses a root."
  }
}

run "a_single_node_refused" {
  command = plan

  variables {
    nodes = 1
  }

  expect_failures = [var.nodes]
}

run "valkey_without_a_group_for_app_refused" {
  command = plan

  variables {
    security_group_ids = { valkey = "sg-0000000000000a1b1" }
  }

  expect_failures = [var.security_group_ids]
}
