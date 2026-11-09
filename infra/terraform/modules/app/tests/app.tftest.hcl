mock_provider "aws" {
  override_during = plan

  mock_data "aws_iam_policy_document" {
    defaults = {
      json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}"
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
  target          = aws_secretsmanager_secret.idp_metadata
  override_during = plan
  values = {
    arn = "arn:aws:secretsmanager:eu-central-1:111111111111:secret:idp-test/ops/idp-metadata-Md5Xyz"
  }
}

override_resource {
  target          = aws_lb_target_group.this["app"]
  override_during = plan
  values = {
    arn = "arn:aws:elasticloadbalancing:eu-central-1:111111111111:targetgroup/idp-test-app/73e2d6bc24d8a067"
  }
}

override_resource {
  target          = aws_lb_target_group.this["ops"]
  override_during = plan
  values = {
    arn = "arn:aws:elasticloadbalancing:eu-central-1:111111111111:targetgroup/idp-test-ops/83e2d6bc24d8a067"
  }
}

variables {
  name    = "idp-test"
  profile = "prod"
  domain  = "idp.example.com"
  release = "1.4.0"
  repository_urls = {
    app      = "111111111111.dkr.ecr.eu-central-1.amazonaws.com/idp-test/app"
    worker   = "111111111111.dkr.ecr.eu-central-1.amazonaws.com/idp-test/worker"
    ops      = "111111111111.dkr.ecr.eu-central-1.amazonaws.com/idp-test/ops"
    migrator = "111111111111.dkr.ecr.eu-central-1.amazonaws.com/idp-test/migrator"
  }
  cluster_arn        = "arn:aws:ecs:eu-central-1:111111111111:cluster/idp-test"
  vpc_id             = "vpc-0a1b2c3d4e5f60718"
  private_subnet_ids = ["subnet-0a1b2c3d4e5f60711", "subnet-0a1b2c3d4e5f60712"]
  security_group_ids = {
    alb       = "sg-0000000000000a1b0"
    "ops-alb" = "sg-0000000000000a1b1"
    app       = "sg-0000000000000a1b2"
    worker    = "sg-0000000000000a1b3"
    ops       = "sg-0000000000000a1b4"
    migrator  = "sg-0000000000000a1b5"
  }
  endpoint_security_group_id = "sg-0000000000000e0d0"
  services = {
    app = {
      environment = {
        CLIENT_IP_HEADER = "CloudFront-Viewer-Address"
        VALKEY_URL       = "rediss://master.idp-test.abc123.euc1.cache.amazonaws.com:6379"
      }
      secrets = {
        VALKEY_PASSWORD      = "arn:aws:secretsmanager:eu-central-1:111111111111:secret:idp-test/valkey/auth-token-Ab12Cd"
        THROTTLE_IP_HMAC_KEY = "arn:aws:secretsmanager:eu-central-1:111111111111:secret:idp-test/app/throttle-ip-hmac-key-Ef34Gh"
      }
    }
    worker = {
      environment = {
        MAIL_HOST = "email-smtp.eu-central-1.amazonaws.com"
      }
      secrets = {
        MAIL_USERNAME = "arn:aws:secretsmanager:eu-central-1:111111111111:secret:idp-test/mail/smtp-Ij56Kl:username::"
        MAIL_PASSWORD = "arn:aws:secretsmanager:eu-central-1:111111111111:secret:idp-test/mail/smtp-Ij56Kl:password::"
      }
    }
    ops = {
      environment = {
        PLATFORM_KEK_URI = "aws-kms://arn:aws:kms:eu-central-1:111111111111:key/0b4f3c1e-7a52-4d9e-9a3c-5f0e2d8b6a41"
      }
      secrets = {}
    }
    migrator = {
      environment = {
        PLATFORM_KEK_URI = "aws-kms://arn:aws:kms:eu-central-1:111111111111:key/0b4f3c1e-7a52-4d9e-9a3c-5f0e2d8b6a41"
      }
      secrets = {}
    }
  }
  database = {
    pooled_jdbc_url      = "jdbc:postgresql://pgbouncer.idp.internal:5432/idp?prepareThreshold=0&sslmode=verify-full&sslfactory=org.postgresql.ssl.SingleCertValidatingFactory&sslfactoryarg=env:DB_TLS_CA"
    pooled_tls_ca_pem    = "internal CA"
    direct_jdbc_url      = "jdbc:postgresql://idp-test.abc.eu-central-1.rds.amazonaws.com:5432/idp?sslmode=verify-full&sslfactory=org.postgresql.ssl.SingleCertValidatingFactory&sslfactoryarg=env:DB_TLS_CA"
    provisioner_jdbc_url = "jdbc:postgresql://idp-test.abc.eu-central-1.rds.amazonaws.com:5432/idp?options=-c%20role%3Didp_owner&sslmode=verify-full&sslfactory=org.postgresql.ssl.SingleCertValidatingFactory&sslfactoryarg=env:PROVISIONER_DB_TLS_CA"
    direct_tls_ca_pem    = "RDS root"
    role_secret_arns = {
      app_runtime    = "arn:aws:secretsmanager:eu-central-1:111111111111:secret:idp-test/db/app_runtime-Aa11Aa"
      worker_runtime = "arn:aws:secretsmanager:eu-central-1:111111111111:secret:idp-test/db/worker_runtime-Bb22Bb"
      ops_runtime    = "arn:aws:secretsmanager:eu-central-1:111111111111:secret:idp-test/db/ops_runtime-Cc33Cc"
      idp_owner      = "arn:aws:secretsmanager:eu-central-1:111111111111:secret:idp-test/db/idp_owner-Dd44Dd"
      provisioner    = "arn:aws:secretsmanager:eu-central-1:111111111111:secret:idp-test/db/provisioner-Ee55Ee"
    }
  }
  task_role_arns = {
    app      = "arn:aws:iam::111111111111:role/idp-test-app-task"
    worker   = "arn:aws:iam::111111111111:role/idp-test-worker-task"
    ops      = "arn:aws:iam::111111111111:role/idp-test-ops-task"
    cli      = "arn:aws:iam::111111111111:role/idp-test-cli-task"
    migrator = "arn:aws:iam::111111111111:role/idp-test-migrator-task"
    recovery = "arn:aws:iam::111111111111:role/idp-test-recovery-task"
  }
  tenant_kek_policy           = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"Account\",\"Effect\":\"Allow\",\"Action\":\"kms:*\",\"Resource\":\"*\",\"Principal\":{\"AWS\":\"arn:aws:iam::111111111111:root\"}}]}"
  previous_kek_retention_days = 45
  https_listener_arn          = "arn:aws:elasticloadbalancing:eu-central-1:111111111111:listener/app/idp-test-public/50dc6c495c0c9188/f2f7dc8efc522ab2"
  origin_header               = "X-Origin-Verify"
  origin_header_value         = "Origin0Header0Of0The0Test0Distribution0Only0Letters0And0Digits0"
  ops_alb_arn                 = "arn:aws:elasticloadbalancing:eu-central-1:111111111111:loadbalancer/app/idp-test-ops/60dc6c495c0c9188"
  ops_certificate_arn         = "arn:aws:acm:eu-central-1:111111111111:certificate/0b4f3c1e-7a52-4d9e-9a3c-5f0e2d8b6a41"
  ops_client_cidrs            = ["10.100.0.0/22"]
}

run "every_rule_to_app_requires_the_header_of_the_distribution" {
  command = plan

  assert {
    condition     = toset([for rule in aws_lb_listener_rule.app : one(flatten([for condition in rule.condition : condition.path_pattern])).values]) == toset([toset(["/api/*"]), toset(["/saml/*"])])
    error_message = "app does not get exactly /api/* and /saml/*."
  }

  assert {
    condition = alltrue([
      for rule in aws_lb_listener_rule.app : anytrue([
        for condition in rule.condition :
        length(condition.http_header) == 1 &&
        one(condition.http_header).http_header_name == "X-Origin-Verify" &&
        one(condition.http_header).values == toset([var.origin_header_value])
      ])
    ])
    error_message = "A rule forwards to app without the header of the distribution."
  }

  assert {
    condition     = alltrue([for rule in aws_lb_listener_rule.app : rule.listener_arn == var.https_listener_arn && rule.priority < 50000])
    error_message = "A rule to app is not on the HTTPS listener before its last rule at 50000."
  }

  assert {
    condition     = alltrue([for rule in aws_lb_listener_rule.app : one(rule.action).type == "forward"])
    error_message = "A rule of app forwards elsewhere."
  }

  assert {
    condition     = alltrue([for rule in aws_lb_listener_rule.app : one(rule.action).target_group_arn == aws_lb_target_group.this["app"].arn])
    error_message = "A rule of app forwards elsewhere."
  }
}

run "the_health_check_of_the_alb_reaches_the_management_port" {
  command = plan

  assert {
    condition     = alltrue([for group in aws_lb_target_group.this : group.port == 8080 && group.target_type == "ip" && group.protocol == "HTTP"])
    error_message = "Requests reach another port than the one of the application."
  }

  assert {
    condition     = alltrue([for group in aws_lb_target_group.this : one(group.health_check).port == "8081" && one(group.health_check).matcher == "200"])
    error_message = "The health check does not ask the management port."
  }

  assert {
    condition = (
      one(aws_lb_target_group.this["app"].health_check).path == "/actuator/health/readiness" &&
      one(aws_lb_target_group.this["ops"].health_check).path == "/actuator/health"
    )
    error_message = "The health check asks another endpoint than the health of the service."
  }
}

run "the_management_port_is_open_to_the_load_balancers_alone" {
  command = plan

  assert {
    condition = (
      toset([for rule in aws_vpc_security_group_ingress_rule.balancer : rule.from_port if rule.security_group_id == var.security_group_ids["app"]]) == toset([8080, 8081]) &&
      alltrue([for rule in aws_vpc_security_group_ingress_rule.balancer : rule.referenced_security_group_id == var.security_group_ids["alb"] if rule.security_group_id == var.security_group_ids["app"]])
    )
    error_message = "app takes its ports from another source than the public ALB."
  }

  assert {
    condition = (
      toset([for rule in aws_vpc_security_group_ingress_rule.balancer : rule.from_port if rule.security_group_id == var.security_group_ids["ops"]]) == toset([8080, 8081]) &&
      alltrue([for rule in aws_vpc_security_group_ingress_rule.balancer : rule.referenced_security_group_id == var.security_group_ids["ops-alb"] if rule.security_group_id == var.security_group_ids["ops"]])
    )
    error_message = "ops takes its ports from another source than its internal ALB."
  }

  assert {
    condition = alltrue([
      for rule in aws_vpc_security_group_ingress_rule.balancer :
      rule.cidr_ipv4 == null && rule.cidr_ipv6 == null && rule.from_port == rule.to_port && contains([var.security_group_ids["app"], var.security_group_ids["ops"]], rule.security_group_id)
    ])
    error_message = "A service takes traffic from an address range or on a range of ports."
  }

  assert {
    condition = alltrue([
      for container in flatten([for definition in aws_ecs_task_definition.this : jsondecode(definition.container_definitions)]) :
      alltrue([for mapping in lookup(container, "portMappings", []) : contains([8080, 8081], mapping.containerPort)])
    ])
    error_message = "A container publishes another port."
  }
}

run "egress_is_a_list_of_destinations" {
  command = plan

  assert {
    condition = alltrue([
      for rule in concat(values(aws_vpc_security_group_egress_rule.balancer), [aws_vpc_security_group_egress_rule.smtp]) :
      rule.cidr_ipv4 == null && rule.cidr_ipv6 == null && rule.prefix_list_id == null && rule.referenced_security_group_id != null
    ])
    error_message = "A rule lets traffic out to an address range."
  }

  assert {
    condition = (
      aws_vpc_security_group_egress_rule.smtp.security_group_id == var.security_group_ids["worker"] &&
      aws_vpc_security_group_egress_rule.smtp.referenced_security_group_id == var.endpoint_security_group_id &&
      aws_vpc_security_group_egress_rule.smtp.from_port == 587 &&
      aws_vpc_security_group_egress_rule.smtp.to_port == 587
    )
    error_message = "worker does not reach SES on 587 through the interface endpoint alone."
  }

  assert {
    condition = (
      toset([for rule in aws_vpc_security_group_egress_rule.balancer : "${rule.security_group_id} ${rule.referenced_security_group_id} ${rule.from_port}"]) ==
      toset(flatten([for target in [["alb", "app"], ["ops-alb", "ops"]] : [for port in [8080, 8081] : "${var.security_group_ids[target[0]]} ${var.security_group_ids[target[1]]} ${port}"]]))
    )
    error_message = "A load balancer reaches another service or port than its own."
  }
}

run "ops_answers_on_the_internal_alb_to_the_vpn_alone" {
  command = plan

  assert {
    condition = (
      aws_lb_listener.ops.load_balancer_arn == var.ops_alb_arn &&
      aws_lb_listener.ops.protocol == "HTTPS" &&
      aws_lb_listener.ops.port == 443 &&
      aws_lb_listener.ops.ssl_policy == "ELBSecurityPolicy-TLS13-1-2-Res-2021-06" &&
      aws_lb_listener.ops.certificate_arn == var.ops_certificate_arn
    )
    error_message = "ops is not served over TLS on its internal ALB."
  }

  assert {
    condition     = one(aws_lb_listener.ops.default_action).type == "forward" && one(aws_lb_listener.ops.default_action).target_group_arn == aws_lb_target_group.this["ops"].arn
    error_message = "The internal ALB forwards elsewhere than to ops."
  }

  assert {
    condition = (
      [for rule in aws_vpc_security_group_ingress_rule.vpn : rule.cidr_ipv4] == ["10.100.0.0/22"] &&
      alltrue([for rule in aws_vpc_security_group_ingress_rule.vpn : rule.security_group_id == var.security_group_ids["ops-alb"] && rule.from_port == 443 && rule.to_port == 443])
    )
    error_message = "The internal ALB admits others than the VPN clients."
  }

  assert {
    condition     = one(aws_lb_target_group.this["ops"].stickiness).enabled && one(aws_lb_target_group.this["ops"].stickiness).type == "lb_cookie"
    error_message = "A browser moves between tasks of ops and loses its session."
  }

  assert {
    condition     = !one(aws_lb_target_group.this["app"].stickiness).enabled
    error_message = "app keeps a browser on one task."
  }
}

run "without_vpn_ranges_nobody_reaches_ops" {
  command = plan

  variables {
    ops_client_cidrs = []
  }

  assert {
    condition     = length(aws_vpc_security_group_ingress_rule.vpn) == 0
    error_message = "The internal ALB admits someone before the VPN exists."
  }
}

run "task_definitions_run_the_profile_without_a_debugger" {
  command = plan

  assert {
    condition     = toset(keys(aws_ecs_task_definition.this)) == toset(["app", "worker", "ops", "migrator", "recovery", "cli"])
    error_message = "A task definition is missing."
  }

  assert {
    condition = alltrue(flatten([
      for definition in aws_ecs_task_definition.this : [
        for container in jsondecode(definition.container_definitions) :
        lookup({ for variable in lookup(container, "environment", []) : variable.name => variable.value }, "SPRING_PROFILES_ACTIVE", "prod") == "prod"
      ]
    ]))
    error_message = "A container runs another profile than prod."
  }

  assert {
    condition = alltrue([
      for definition in aws_ecs_task_definition.this :
      contains([for variable in jsondecode(definition.container_definitions)[length(jsondecode(definition.container_definitions)) - 1].environment : variable.name], "SPRING_PROFILES_ACTIVE")
    ])
    error_message = "A service runs without a profile."
  }

  assert {
    condition = alltrue(flatten([
      for definition in aws_ecs_task_definition.this : [
        for container in jsondecode(definition.container_definitions) : [
          for variable in lookup(container, "environment", []) :
          !contains(["JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "JAVA_OPTS", "_JAVA_OPTIONS"], variable.name) && !can(regex("(?i)jdwp", variable.value))
        ]
      ]
    ]))
    error_message = "A container gets options of the JVM or a debugger."
  }

  assert {
    condition = alltrue(flatten([
      for definition in aws_ecs_task_definition.this : [for container in jsondecode(definition.container_definitions) : container.readonlyRootFilesystem]
    ]))
    error_message = "A container can write to its image."
  }

  assert {
    condition     = alltrue([for name, definition in aws_ecs_task_definition.this : endswith(jsondecode(definition.container_definitions)[0].image, ":1.4.0")])
    error_message = "A task runs another image than the release."
  }
}

run "staging_runs_its_own_profile" {
  command = plan

  variables {
    profile = "staging"
  }

  assert {
    condition = alltrue([
      for definition in aws_ecs_task_definition.this :
      contains(jsondecode(definition.container_definitions)[length(jsondecode(definition.container_definitions)) - 1].environment, { name = "SPRING_PROFILES_ACTIVE", value = "staging" })
    ])
    error_message = "A task of staging runs another profile."
  }
}

run "each_task_gets_its_database_role_and_settings" {
  command = plan

  assert {
    condition = (
      local.tasks.app.environment.DB_USERNAME == "app_runtime" &&
      local.tasks.app.environment.DB_URL == var.database.pooled_jdbc_url &&
      local.tasks.app.secrets.DB_PASSWORD == var.database.role_secret_arns["app_runtime"] &&
      local.tasks.app.environment.CLIENT_IP_HEADER == "CloudFront-Viewer-Address" &&
      local.tasks.app.environment.APP_VERSION == "1.4.0" &&
      local.tasks.app.secrets.VALKEY_PASSWORD == var.services.app.secrets.VALKEY_PASSWORD
    )
    error_message = "app does not get its role through PgBouncer, its version and the settings of the root."
  }

  assert {
    condition = (
      local.tasks.worker.environment.DB_USERNAME == "worker_runtime" &&
      local.tasks.worker.environment.PROVISIONER_DB_URL == var.database.provisioner_jdbc_url &&
      local.tasks.worker.environment.PROVISIONER_DB_TLS_CA == var.database.direct_tls_ca_pem &&
      local.tasks.worker.environment.PROVISIONER_DB_USERNAME == "provisioner" &&
      local.tasks.worker.secrets.PROVISIONER_DB_PASSWORD == var.database.role_secret_arns["provisioner"] &&
      local.tasks.worker.environment.TENANTS_BASE_DOMAIN == "idp.example.com"
    )
    error_message = "worker does not provision tenants directly on Postgres as provisioner."
  }

  assert {
    condition = (
      local.tasks.migrator.environment.DB_USERNAME == "idp_owner" &&
      local.tasks.migrator.environment.DB_URL == var.database.direct_jdbc_url &&
      local.tasks.migrator.environment.DB_TLS_CA == var.database.direct_tls_ca_pem
    )
    error_message = "migrator does not reach Postgres directly as idp_owner."
  }

  assert {
    condition = alltrue([
      for name in ["ops", "cli"] :
      local.tasks[name].environment.DB_USERNAME == "ops_runtime" && local.tasks[name].secrets.DB_PASSWORD == var.database.role_secret_arns["ops_runtime"]
    ])
    error_message = "ops or the CLI uses another role than ops_runtime."
  }

  assert {
    condition     = alltrue([for task in local.tasks : task.environment.KMS_NEW_KEYS == "aws-kms" && task.environment.KMS_AWS_REGION == "eu-central-1"])
    error_message = "A task keeps new keys outside KMS of its region."
  }

  assert {
    condition     = jsondecode(aws_ecs_task_definition.this["cli"].container_definitions)[0].command == ["cli"] && !contains(keys(local.tasks.cli.environment), "IDP_METADATA_FILE")
    error_message = "The CLI task does not start the CLI of ops."
  }
}

run "ops_reads_the_metadata_that_a_first_container_writes" {
  command = plan

  assert {
    condition = (
      jsondecode(aws_ecs_task_definition.this["ops"].container_definitions)[0].name == "idp-metadata" &&
      !jsondecode(aws_ecs_task_definition.this["ops"].container_definitions)[0].essential &&
      jsondecode(aws_ecs_task_definition.this["ops"].container_definitions)[0].secrets == [{ name = "IDP_METADATA", valueFrom = aws_secretsmanager_secret.idp_metadata.arn }]
    )
    error_message = "No container writes the metadata of ops-sso from its secret."
  }

  assert {
    condition = (
      jsondecode(aws_ecs_task_definition.this["ops"].container_definitions)[1].dependsOn == [{ containerName = "idp-metadata", condition = "SUCCESS" }] &&
      local.tasks.ops.environment.IDP_METADATA_FILE == "/run/idp/idp-metadata.xml"
    )
    error_message = "ops starts before its metadata exists."
  }
}

run "each_execution_role_reads_its_own_secrets" {
  command = plan

  assert {
    condition = one(data.aws_iam_policy_document.execution_secrets["worker"].statement).resources == toset([
      "arn:aws:secretsmanager:eu-central-1:111111111111:secret:idp-test/mail/smtp-Ij56Kl",
      "arn:aws:secretsmanager:eu-central-1:111111111111:secret:idp-test/db/worker_runtime-Bb22Bb",
      "arn:aws:secretsmanager:eu-central-1:111111111111:secret:idp-test/db/provisioner-Ee55Ee",
    ])
    error_message = "worker reads other secrets than mail and its two roles."
  }

  assert {
    condition = one(data.aws_iam_policy_document.execution_secrets["ops"].statement).resources == toset([
      "arn:aws:secretsmanager:eu-central-1:111111111111:secret:idp-test/db/ops_runtime-Cc33Cc",
      "arn:aws:secretsmanager:eu-central-1:111111111111:secret:idp-test/ops/idp-metadata-Md5Xyz",
    ])
    error_message = "ops reads other secrets than its role and the metadata."
  }

  assert {
    condition = alltrue([
      for name in ["cli", "migrator", "recovery"] :
      length(one(data.aws_iam_policy_document.execution_secrets[name].statement).resources) == 1
    ])
    error_message = "A one-off task reads more than the password of its role."
  }

  assert {
    condition     = !contains(one(data.aws_iam_policy_document.execution_secrets["ops"].statement).resources, var.services.app.secrets.VALKEY_PASSWORD)
    error_message = "ops reads a secret of app."
  }
}

run "services_run_in_the_private_subnets_without_exec" {
  command = plan

  assert {
    condition     = toset(keys(aws_ecs_service.this)) == toset(["app", "worker", "ops"])
    error_message = "migrator or the CLI runs as a service."
  }

  assert {
    condition = alltrue([
      for name, service in aws_ecs_service.this :
      !service.enable_execute_command && !one(service.network_configuration).assign_public_ip && one(service.network_configuration).security_groups == toset([var.security_group_ids[name]])
    ])
    error_message = "A service runs with ECS Exec, a public address or the security group of another."
  }

  assert {
    condition     = length(aws_ecs_service.this["worker"].load_balancer) == 0 && length(aws_ecs_service.this["app"].load_balancer) == 1
    error_message = "worker is behind a load balancer, or app is not."
  }

  assert {
    condition     = output.one_off_tasks.cli.network.securityGroups == [var.security_group_ids["ops"]] && output.one_off_tasks.migrator.network.securityGroups == [var.security_group_ids["migrator"]]
    error_message = "A one-off task runs with the security group of another."
  }
}

run "each_task_runs_as_its_own_task_role" {
  command = plan

  assert {
    condition     = alltrue([for name, definition in aws_ecs_task_definition.this : definition.task_role_arn == var.task_role_arns[name]])
    error_message = "A task runs as the role of another, or without one."
  }

  assert {
    condition     = aws_ecs_task_definition.this["cli"].task_role_arn != aws_ecs_task_definition.this["ops"].task_role_arn
    error_message = "The CLI runs as the role of ops, so the operator audit could not tell it from the service."
  }
}

run "only_worker_creates_tenant_keks_with_their_policy" {
  command = plan

  assert {
    condition     = local.tasks.worker.environment.KMS_AWS_KEY_POLICY == var.tenant_kek_policy
    error_message = "worker creates tenant KEKs without their key policy, and AWS would refuse them."
  }

  assert {
    condition     = [for name, task in local.tasks : name if contains(keys(task.environment), "KMS_AWS_KEY_POLICY")] == ["worker"]
    error_message = "Another task than worker gets the policy of tenant KEKs, though it creates none."
  }

  assert {
    condition     = local.tasks.worker.environment.KMS_PREVIOUS_KEK_RETENTION == "45d"
    error_message = "worker keeps a KEK replaced by a move for another time than the module says."
  }
}

run "the_recovery_run_is_migrator_applying_the_journal_again" {
  command = plan

  assert {
    condition = (
      local.tasks.recovery.image == "migrator" &&
      local.tasks.recovery.environment.REAPPLY_ERASURES == "true" &&
      local.tasks.recovery.environment.DB_USERNAME == "idp_owner" &&
      local.tasks.recovery.environment.DB_URL == var.database.direct_jdbc_url &&
      local.tasks.recovery.secrets.DB_PASSWORD == var.database.role_secret_arns["idp_owner"]
    )
    error_message = "The recovery run is not migrator applying the erasure journal again as idp_owner."
  }

  assert {
    condition     = [for name, task in local.tasks : name if lookup(task.environment, "REAPPLY_ERASURES", "false") != "false"] == ["recovery"]
    error_message = "A release of migrator applies the journal again, or with the rights it needs to."
  }

  assert {
    condition     = output.one_off_tasks.recovery.network.securityGroups == [var.security_group_ids["migrator"]] && !contains(keys(aws_ecs_service.this), "recovery")
    error_message = "The recovery run runs as a service or with another security group than migrator."
  }
}

run "a_release_of_migrator_refused_with_the_journal_switch" {
  command = plan

  variables {
    services = {
      app      = { environment = {}, secrets = {} }
      worker   = { environment = {}, secrets = {} }
      ops      = { environment = {}, secrets = {} }
      migrator = { environment = { REAPPLY_ERASURES = "true" }, secrets = {} }
    }
  }

  expect_failures = [aws_ecs_task_definition.this["migrator"]]
}

run "a_missing_task_role_refused" {
  command = plan

  variables {
    task_role_arns = {
      app      = "arn:aws:iam::111111111111:role/idp-test-app-task"
      worker   = "arn:aws:iam::111111111111:role/idp-test-worker-task"
      ops      = "arn:aws:iam::111111111111:role/idp-test-ops-task"
      cli      = "arn:aws:iam::111111111111:role/idp-test-cli-task"
      migrator = "arn:aws:iam::111111111111:role/idp-test-migrator-task"
    }
  }

  expect_failures = [var.task_role_arns]
}

run "a_shared_task_role_refused" {
  command = plan

  variables {
    task_role_arns = {
      app      = "arn:aws:iam::111111111111:role/idp-test-app-task"
      worker   = "arn:aws:iam::111111111111:role/idp-test-worker-task"
      ops      = "arn:aws:iam::111111111111:role/idp-test-ops-task"
      cli      = "arn:aws:iam::111111111111:role/idp-test-ops-task"
      migrator = "arn:aws:iam::111111111111:role/idp-test-migrator-task"
      recovery = "arn:aws:iam::111111111111:role/idp-test-recovery-task"
    }
  }

  expect_failures = [var.task_role_arns]
}

run "a_key_policy_that_is_no_policy_refused" {
  command = plan

  variables {
    tenant_kek_policy = "alias/tenant-policy"
  }

  expect_failures = [var.tenant_kek_policy]
}

run "a_fraction_of_a_day_refused_as_kek_retention" {
  command = plan

  variables {
    previous_kek_retention_days = 0.5
  }

  expect_failures = [var.previous_kek_retention_days]
}

run "the_local_profile_refused" {
  command = plan

  variables {
    profile = "local"
  }

  expect_failures = [var.profile]
}

run "a_profile_of_the_root_refused" {
  command = plan

  variables {
    services = {
      app      = { environment = { SPRING_PROFILES_ACTIVE = "local" }, secrets = {} }
      worker   = { environment = {}, secrets = {} }
      ops      = { environment = {}, secrets = {} }
      migrator = { environment = {}, secrets = {} }
    }
  }

  expect_failures = [var.services]
}

run "a_profile_in_another_spelling_refused" {
  command = plan

  variables {
    services = {
      app      = { environment = {}, secrets = {} }
      worker   = { environment = { "spring.profiles.include" = "local" }, secrets = {} }
      ops      = { environment = {}, secrets = {} }
      migrator = { environment = {}, secrets = {} }
    }
  }

  expect_failures = [var.services]
}

run "jdwp_refused" {
  command = plan

  variables {
    services = {
      app      = { environment = { JAVA_TOOL_OPTIONS = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005" }, secrets = {} }
      worker   = { environment = {}, secrets = {} }
      ops      = { environment = {}, secrets = {} }
      migrator = { environment = {}, secrets = {} }
    }
  }

  expect_failures = [var.services]
}

run "xdebug_refused" {
  command = plan

  variables {
    services = {
      app      = { environment = {}, secrets = {} }
      worker   = { environment = {}, secrets = {} }
      ops      = { environment = { JDK_JAVA_OPTIONS = "-Xdebug" }, secrets = {} }
      migrator = { environment = {}, secrets = {} }
    }
  }

  expect_failures = [var.services]
}

run "a_missing_service_refused" {
  command = plan

  variables {
    services = {
      app    = { environment = {}, secrets = {} }
      worker = { environment = {}, secrets = {} }
      ops    = { environment = {}, secrets = {} }
    }
  }

  expect_failures = [var.services]
}

run "a_secret_by_name_refused" {
  command = plan

  variables {
    services = {
      app      = { environment = {}, secrets = { VALKEY_PASSWORD = "idp-test/valkey/auth-token" } }
      worker   = { environment = {}, secrets = {} }
      ops      = { environment = {}, secrets = {} }
      migrator = { environment = {}, secrets = {} }
    }
  }

  expect_failures = [var.services]
}

run "the_whole_internet_refused_as_vpn" {
  command = plan

  variables {
    ops_client_cidrs = ["0.0.0.0/0"]
  }

  expect_failures = [var.ops_client_cidrs]
}

run "a_public_range_refused_as_vpn" {
  command = plan

  variables {
    ops_client_cidrs = ["203.0.113.0/24"]
  }

  expect_failures = [var.ops_client_cidrs]
}

run "a_range_by_a_host_address_refused" {
  command = plan

  variables {
    ops_client_cidrs = ["10.100.0.1/22"]
  }

  expect_failures = [var.ops_client_cidrs]
}

run "a_range_wider_than_the_private_block_refused" {
  command = plan

  variables {
    ops_client_cidrs = ["172.16.0.0/11"]
  }

  expect_failures = [var.ops_client_cidrs]
}

run "an_origin_header_with_a_wildcard_refused" {
  command = plan

  variables {
    origin_header_value = "Origin0Header0Of0The0Test0Distribution0*"
  }

  expect_failures = [var.origin_header_value]
}

run "a_database_url_without_verify_full_refused" {
  command = plan

  variables {
    database = {
      pooled_jdbc_url      = "jdbc:postgresql://pgbouncer.idp.internal:5432/idp?sslmode=require"
      pooled_tls_ca_pem    = "internal CA"
      direct_jdbc_url      = "jdbc:postgresql://idp-test.abc.eu-central-1.rds.amazonaws.com:5432/idp?sslmode=verify-full"
      provisioner_jdbc_url = "jdbc:postgresql://idp-test.abc.eu-central-1.rds.amazonaws.com:5432/idp?sslmode=verify-full"
      direct_tls_ca_pem    = "RDS root"
      role_secret_arns = {
        app_runtime    = "arn:aws:secretsmanager:eu-central-1:111111111111:secret:idp-test/db/app_runtime-Aa11Aa"
        worker_runtime = "arn:aws:secretsmanager:eu-central-1:111111111111:secret:idp-test/db/worker_runtime-Bb22Bb"
        ops_runtime    = "arn:aws:secretsmanager:eu-central-1:111111111111:secret:idp-test/db/ops_runtime-Cc33Cc"
        idp_owner      = "arn:aws:secretsmanager:eu-central-1:111111111111:secret:idp-test/db/idp_owner-Dd44Dd"
        provisioner    = "arn:aws:secretsmanager:eu-central-1:111111111111:secret:idp-test/db/provisioner-Ee55Ee"
      }
    }
  }

  expect_failures = [var.database]
}

run "a_release_that_is_no_image_tag_refused" {
  command = plan

  variables {
    release = "1.4.0 beta"
  }

  expect_failures = [var.release]
}
