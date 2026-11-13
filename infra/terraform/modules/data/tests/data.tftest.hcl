mock_provider "aws" {
  override_during = plan

  mock_data "aws_iam_policy_document" {
    defaults = {
      json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}"
    }
  }
}

mock_provider "http" {
  mock_data "http" {
    defaults = {
      status_code   = 200
      response_body = "bundle of the RDS roots"
    }
  }
}

mock_provider "tls" {
  mock_data "tls_certificate" {
    defaults = {
      certificates = [{
        is_ca    = true
        subject  = "CN=Amazon RDS eu-central-1 Root CA RSA2048 G1,OU=Amazon RDS,O=Amazon Web Services\\, Inc.,ST=WA,L=Seattle,C=US"
        cert_pem = "-----BEGIN CERTIFICATE-----\nrsa2048 root\n-----END CERTIFICATE-----\n"
      }]
    }
  }
}

override_data {
  target = data.aws_region.current
  values = {
    region = "eu-central-1"
  }
}

override_data {
  target = data.aws_partition.current
  values = {
    partition = "aws"
  }
}

override_resource {
  target          = aws_db_instance.this
  override_during = plan
  values = {
    address            = "idp-test.abcdefghijkl.eu-central-1.rds.amazonaws.com"
    endpoint           = "idp-test.abcdefghijkl.eu-central-1.rds.amazonaws.com:5432"
    port               = 5432
    master_user_secret = [{ secret_arn = "arn:aws:secretsmanager:eu-central-1:111111111111:secret:rds!db-master-AbCdEf", kms_key_id = "", secret_status = "active" }]
  }
}

variables {
  name                = "idp-test"
  database_subnet_ids = ["subnet-0a1b2c3d4e5f60721", "subnet-0a1b2c3d4e5f60722"]
  private_subnet_ids  = ["subnet-0a1b2c3d4e5f60711", "subnet-0a1b2c3d4e5f60712"]
  security_group_ids = {
    for name in ["database", "pgbouncer", "db-bootstrap", "migrator", "app", "worker", "ops"] : name => "sg-${name}"
  }
  cluster_arn        = "arn:aws:ecs:eu-central-1:111111111111:cluster/idp-test"
  namespace_id       = "ns-0a1b2c3d4e5f6071"
  namespace          = "idp.internal"
  pgbouncer_image    = "111111111111.dkr.ecr.eu-central-1.amazonaws.com/idp-test/pgbouncer:0.1.0"
  db_bootstrap_image = "111111111111.dkr.ecr.eu-central-1.amazonaws.com/idp-test/db-bootstrap:0.1.0"
  internal_ca_pem    = "internal CA"
  pgbouncer_tls = {
    cert_pem       = "pgbouncer certificate"
    key_secret_arn = "arn:aws:secretsmanager:eu-central-1:111111111111:secret:idp-test/tls/pgbouncer-AbCdEf"
  }
  role_secret_arns = {
    for role in ["idp_owner", "provisioner", "app_runtime", "worker_runtime", "ops_runtime"] : role => "arn:aws:secretsmanager:eu-central-1:111111111111:secret:idp-test/db/${role}-AbCdEf"
  }
  log_tampering_actions = ["logs:Delete*", "logs:PutRetentionPolicy"]
}

run "postgres_takes_tls_and_scram_alone" {
  command = plan

  assert {
    condition = {
      for parameter in aws_db_parameter_group.this.parameter : parameter.name => parameter.value
      } == {
      "rds.force_ssl"            = "1"
      "ssl_min_protocol_version" = "TLSv1.2"
      "password_encryption"      = "scram-sha-256"
    }
    error_message = "RDS takes connections without TLS or below TLS 1.2, or stores passwords as MD5."
  }

  assert {
    condition     = aws_db_parameter_group.this.family == "postgres18" && aws_db_instance.this.ca_cert_identifier == "rds-ca-rsa2048-g1"
    error_message = "The parameters belong to another major version than the instance runs, or the instance has another CA than direct clients trust."
  }

  assert {
    condition     = output.direct_tls_ca_pem == "-----BEGIN CERTIFICATE-----\nrsa2048 root\n-----END CERTIFICATE-----\n"
    error_message = "Direct clients trust another certificate than the root of ca_cert_identifier."
  }

  assert {
    condition = alltrue([
      for url in [output.pooled_jdbc_url, output.direct_jdbc_url, output.provisioner_jdbc_url] : strcontains(url, "sslmode=verify-full")
    ])
    error_message = "A client connects without checking the certificate and the name of the server."
  }
}

run "the_database_and_its_backups_stay_encrypted_private_and_protected" {
  command = plan

  assert {
    condition = (
      aws_db_instance.this.storage_encrypted &&
      aws_db_instance.this.performance_insights_enabled &&
      aws_kms_key.database.enable_key_rotation
    )
    error_message = "The storage, the snapshots or Performance Insights are not encrypted with the rotating key of the database."
  }

  assert {
    condition = (
      !aws_db_instance.this.publicly_accessible &&
      aws_db_instance.this.vpc_security_group_ids == toset(["sg-database"]) &&
      !aws_db_instance.this.iam_database_authentication_enabled
    )
    error_message = "The database is reachable from outside the VPC or by another group, or takes IAM logins."
  }

  assert {
    condition = (
      aws_db_instance.this.deletion_protection &&
      !aws_db_instance.this.skip_final_snapshot &&
      !aws_db_instance.this.delete_automated_backups &&
      aws_db_instance.this.backup_retention_period == 30 &&
      aws_db_instance.this.multi_az
    )
    error_message = "The database can be deleted without a snapshot, keeps backups for less than 30 days, or lives in one zone."
  }

  assert {
    condition     = aws_db_instance.this.manage_master_user_password && !aws_db_instance.this.allow_major_version_upgrade
    error_message = "The master password lives in the state, or a major version arrives without a migration test."
  }
}

run "only_the_clients_of_adr_005_reach_postgres_directly" {
  command = plan

  assert {
    condition     = toset([for rule in aws_vpc_security_group_ingress_rule.database : rule.referenced_security_group_id]) == toset(["sg-pgbouncer", "sg-db-bootstrap", "sg-migrator", "sg-worker"])
    error_message = "app or ops reach Postgres past PgBouncer, or one of its direct clients cannot."
  }

  assert {
    condition     = toset([for rule in aws_vpc_security_group_ingress_rule.pgbouncer : rule.referenced_security_group_id]) == toset(["sg-app", "sg-worker", "sg-ops"])
    error_message = "Another workload than app, worker and ops reaches PgBouncer."
  }
}

run "each_task_reads_the_passwords_of_its_roles_alone" {
  command = plan

  assert {
    condition = [for secret in one(jsondecode(aws_ecs_task_definition.pgbouncer.container_definitions)).secrets : secret.name] == [
      "CLIENT_TLS_KEY", "APP_RUNTIME_PASSWORD", "WORKER_RUNTIME_PASSWORD", "OPS_RUNTIME_PASSWORD",
    ]
    error_message = "PgBouncer gets the password of idp_owner or provisioner, or misses one of a runtime role."
  }

  assert {
    condition = [
      for name, task in aws_ecs_task_definition.bootstrap : "${name}: ${join(",", sort([for secret in one(jsondecode(task.container_definitions)).secrets : secret.name]))}"
      ] == [
      "owner: IDP_OWNER_PASSWORD,PGPASSWORD,PROVISIONER_PASSWORD",
      "role-passwords: APP_RUNTIME_PASSWORD,OPS_RUNTIME_PASSWORD,PGPASSWORD,WORKER_RUNTIME_PASSWORD",
    ]
    error_message = "A bootstrap step reads more passwords than it sets."
  }

  assert {
    condition = alltrue(flatten([
      for task in concat([aws_ecs_task_definition.pgbouncer], values(aws_ecs_task_definition.bootstrap)) : [
        for container in jsondecode(task.container_definitions) : container.readonlyRootFilesystem
      ]
    ]))
    error_message = "PgBouncer or a bootstrap step can write its root file system."
  }
}

run "a_missing_role_secret_refused" {
  command = plan

  variables {
    role_secret_arns = { idp_owner = "arn:aws:secretsmanager:eu-central-1:111111111111:secret:idp-test/db/idp_owner-AbCdEf" }
  }

  expect_failures = [var.role_secret_arns]
}

run "another_rds_ca_than_the_known_ones_refused" {
  command = plan

  variables {
    ca_cert_identifier = "rds-ca-2019"
  }

  expect_failures = [var.ca_cert_identifier]
}
