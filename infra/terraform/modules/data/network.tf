locals {
  # migrator and the provisioner in worker need session state, so they skip PgBouncer, see ADR-005.
  database_clients  = ["pgbouncer", "db-bootstrap", "migrator", "worker"]
  pgbouncer_clients = ["app", "worker", "ops"]
}

resource "aws_vpc_security_group_ingress_rule" "database" {
  for_each = toset(local.database_clients)

  security_group_id            = var.security_group_ids["database"]
  description                  = "Postgres from ${each.key}"
  referenced_security_group_id = var.security_group_ids[each.key]
  ip_protocol                  = "tcp"
  from_port                    = aws_db_instance.this.port
  to_port                      = aws_db_instance.this.port
}

resource "aws_vpc_security_group_egress_rule" "database" {
  for_each = toset(local.database_clients)

  security_group_id            = var.security_group_ids[each.key]
  description                  = "Postgres on RDS"
  referenced_security_group_id = var.security_group_ids["database"]
  ip_protocol                  = "tcp"
  from_port                    = aws_db_instance.this.port
  to_port                      = aws_db_instance.this.port
}

resource "aws_vpc_security_group_ingress_rule" "pgbouncer" {
  for_each = toset(local.pgbouncer_clients)

  security_group_id            = var.security_group_ids["pgbouncer"]
  description                  = "PgBouncer from ${each.key}"
  referenced_security_group_id = var.security_group_ids[each.key]
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}

resource "aws_vpc_security_group_egress_rule" "pgbouncer" {
  for_each = toset(local.pgbouncer_clients)

  security_group_id            = var.security_group_ids[each.key]
  description                  = "PgBouncer"
  referenced_security_group_id = var.security_group_ids["pgbouncer"]
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}
