mock_provider "aws" {
  override_during = plan

  mock_data "aws_iam_policy_document" {
    defaults = {
      json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}"
    }
  }
}

override_data {
  target = data.aws_caller_identity.current
  values = {
    account_id = "111111111111"
  }
}

override_data {
  target = data.aws_partition.current
  values = {
    partition = "aws"
  }
}

override_data {
  target = data.aws_region.current
  values = {
    region = "eu-central-1"
  }
}

variables {
  name               = "idp-test"
  cluster_arn        = "arn:aws:ecs:eu-central-1:111111111111:cluster/idp-test"
  namespace_id       = "ns-0a1b2c3d4e5f6071"
  namespace          = "idp.internal"
  private_subnet_ids = ["subnet-0a1b2c3d4e5f60711", "subnet-0a1b2c3d4e5f60712"]
  security_group_ids = {
    "otel-collector" = "sg-0000000000000c0c0"
    app              = "sg-0000000000000a1b2"
    worker           = "sg-0000000000000a1b3"
    ops              = "sg-0000000000000a1b4"
    migrator         = "sg-0000000000000a1b5"
  }
  collector_image = "111111111111.dkr.ecr.eu-central-1.amazonaws.com/idp-test/otel-collector:1.0.0"
  collector_tls = {
    cert_pem       = "collector certificate"
    key_secret_arn = "arn:aws:secretsmanager:eu-central-1:111111111111:secret:idp-test/tls/otel-collector-Ab12Cd"
  }
}

# Statements are compared as "effect actions -> resources", with actions and resources sorted.
run "each_kind_of_line_is_kept_as_long_as_logging_md_says" {
  command = plan

  assert {
    condition = {
      for key, group in aws_cloudwatch_log_group.this : key => "${group.name} ${group.retention_in_days}"
      } == {
      logs      = "/idp-test/otlp 90"
      security  = "/idp-test/otlp/security 365"
      metrics   = "/idp-test/otlp/metrics 14"
      spans     = "aws/spans 14"
      collector = "/idp-test/otel-collector 90"
    }
    error_message = "A log group keeps its lines for another period than docs/security/logging.md: console and OTLP 90 days, idp.security a year, traces 14 days."
  }

  assert {
    condition     = alltrue([for group in aws_cloudwatch_log_group.this : group.deletion_protection_enabled])
    error_message = "A log group of telemetry can be deleted before its lines expire."
  }

  assert {
    condition = (
      output.log_groups.security == "/idp-test/otlp/security" &&
      output.metrics_namespace == "idp-test" &&
      output.endpoint == "https://otel-collector.idp.internal:4318"
    )
    error_message = "The outputs name other log groups, another namespace or an endpoint without TLS."
  }
}

run "the_collector_appends_to_its_log_groups_and_nothing_else" {
  command = plan

  assert {
    condition = (
      length([for statement in data.aws_iam_policy_document.task.statement : statement if statement.effect == "Allow"]) == 1 &&
      one([for statement in data.aws_iam_policy_document.task.statement : statement.actions if statement.effect == "Allow"]) == toset(["logs:CreateLogStream", "logs:PutLogEvents"]) &&
      one([for statement in data.aws_iam_policy_document.task.statement : statement.resources if statement.effect == "Allow"]) == toset(flatten([
        for group in ["/idp-test/otlp", "/idp-test/otlp/metrics", "/idp-test/otlp/security"] : [
          "arn:aws:logs:eu-central-1:111111111111:log-group:${group}",
          "arn:aws:logs:eu-central-1:111111111111:log-group:${group}:log-stream:*",
        ]
      ]))
    )
    error_message = "The collector may do more in CloudWatch Logs than append to the groups of OTLP."
  }

  assert {
    condition = alltrue([
      for action in ["logs:Delete*", "logs:PutRetentionPolicy", "logs:PutLogGroupDeletionProtection", "logs:Get*", "logs:FilterLogEvents", "logs:Start*", "logs:CreateExportTask", "logs:PutSubscriptionFilter"] :
      anytrue([for statement in data.aws_iam_policy_document.task.statement : statement.effect == "Deny" && contains(statement.actions, action) && statement.resources == toset(["*"])])
    ])
    error_message = "A later grant could let the collector delete, shorten, export or read the lines it writes."
  }

  assert {
    condition     = anytrue([for statement in data.aws_iam_policy_document.task.statement : statement.effect == "Deny" && contains(statement.actions, "xray:BatchGet*") && contains(statement.actions, "xray:GetTrace*")])
    error_message = "The collector may read the traces it sends."
  }

  assert {
    condition     = aws_iam_role_policy_attachment.xray.policy_arn == "arn:aws:iam::aws:policy/AWSXrayWriteOnlyPolicy"
    error_message = "The collector sends spans with more than the write-only policy of X-Ray."
  }

  assert {
    condition     = !anytrue([for statement in data.aws_iam_policy_document.task.statement : contains(statement.resources, "arn:aws:logs:eu-central-1:111111111111:log-group:aws/spans") if statement.effect == "Allow"])
    error_message = "The collector writes the spans past X-Ray."
  }

  assert {
    condition = (
      one([for statement in data.aws_iam_policy_document.execution.statement : statement if statement.effect == "Allow"]).resources == toset([var.collector_tls.key_secret_arn]) &&
      anytrue([for statement in data.aws_iam_policy_document.execution.statement : statement.effect == "Deny" && contains(statement.actions, "logs:Delete*")])
    )
    error_message = "The execution role of the collector reads another secret than its TLS key, or may change its console lines."
  }
}

run "only_x_ray_writes_the_spans" {
  command = plan

  assert {
    condition = (
      one(data.aws_iam_policy_document.spans.statement).actions == toset(["logs:PutLogEvents"]) &&
      one(data.aws_iam_policy_document.spans.statement).resources == toset(["arn:aws:logs:eu-central-1:111111111111:log-group:aws/spans:*"]) &&
      one(data.aws_iam_policy_document.spans.statement).principals == toset([{ type = "Service", identifiers = toset(["xray.amazonaws.com"]) }])
    )
    error_message = "The resource policy lets another service or another group than aws/spans be written."
  }

  assert {
    condition = toset([
      for condition in one(data.aws_iam_policy_document.spans.statement).condition : "${condition.test} ${condition.variable} ${join(",", condition.values)}"
      ]) == toset([
      "StringEquals aws:SourceAccount 111111111111",
      "ArnLike aws:SourceArn arn:aws:xray:eu-central-1:111111111111:*",
    ])
    error_message = "X-Ray of another account could write into the spans of this one."
  }

  assert {
    condition     = aws_xray_trace_segment_destination.this.destination == "CloudWatchLogs"
    error_message = "X-Ray keeps the spans in its own store, 30 days instead of the 14 of aws/spans."
  }
}

run "only_the_on_call_policy_reads_and_it_changes_nothing" {
  command = plan

  assert {
    condition = alltrue(flatten([
      for statement in data.aws_iam_policy_document.read.statement : [
        for action in statement.actions : statement.effect == "Allow" && can(regex("^(logs|xray|cloudwatch):(Describe|Get|List|Filter|BatchGet|StartQuery$|StartLiveTail$|StopQuery$)", action))
      ]
    ]))
    error_message = "The policy of the on-call engineers writes, changes or deletes something."
  }

  assert {
    condition = alltrue(flatten([
      for statement in data.aws_iam_policy_document.read.statement : [
        for resource in statement.resources : resource == "*" || startswith(resource, "arn:aws:logs:eu-central-1:111111111111:log-group:/idp-test/") || startswith(resource, "arn:aws:logs:eu-central-1:111111111111:log-group:aws/spans")
      ]
    ]))
    error_message = "The policy of the on-call engineers reads the logs of another environment."
  }

  assert {
    condition     = aws_iam_policy.read.name == "idp-test-telemetry-read"
    error_message = "The read policy has another name."
  }
}

run "the_services_reach_the_collector_over_otlp_alone" {
  command = plan

  assert {
    condition = (
      toset([for rule in aws_vpc_security_group_ingress_rule.collector : rule.referenced_security_group_id]) == toset([var.security_group_ids["app"], var.security_group_ids["worker"], var.security_group_ids["ops"]]) &&
      alltrue([for rule in aws_vpc_security_group_ingress_rule.collector : rule.security_group_id == var.security_group_ids["otel-collector"] && rule.from_port == 4318 && rule.to_port == 4318 && rule.cidr_ipv4 == null])
    )
    error_message = "The collector takes traffic from others than app, worker and ops, or on another port."
  }

  assert {
    condition = alltrue([
      for rule in aws_vpc_security_group_egress_rule.collector :
      rule.referenced_security_group_id == var.security_group_ids["otel-collector"] && rule.from_port == 4318 && rule.to_port == 4318 && rule.cidr_ipv4 == null && rule.prefix_list_id == null
    ]) && length(aws_vpc_security_group_egress_rule.collector) == 3
    error_message = "A service sends OTLP elsewhere than to the collector."
  }

  assert {
    condition = (
      !aws_ecs_service.collector.enable_execute_command &&
      !one(aws_ecs_service.collector.network_configuration).assign_public_ip &&
      one(aws_ecs_service.collector.network_configuration).security_groups == toset([var.security_group_ids["otel-collector"]]) &&
      one(aws_ecs_service.collector.network_configuration).subnets == toset(var.private_subnet_ids)
    )
    error_message = "The collector runs with ECS Exec, a public address or another security group."
  }
}

run "the_collector_serves_tls_with_a_read_only_root_and_writes_to_aws_over_https" {
  command = plan

  assert {
    condition = (
      jsondecode(aws_ecs_task_definition.collector.container_definitions)[0].readonlyRootFilesystem &&
      jsondecode(aws_ecs_task_definition.collector.container_definitions)[0].portMappings == [{ containerPort = 4318, protocol = "tcp" }] &&
      jsondecode(aws_ecs_task_definition.collector.container_definitions)[0].image == var.collector_image
    )
    error_message = "The collector can write to its image, publishes another port or runs another image."
  }

  assert {
    condition = (
      contains(jsondecode(aws_ecs_task_definition.collector.container_definitions)[0].environment, { name = "TLS_CERT", value = "collector certificate" }) &&
      jsondecode(aws_ecs_task_definition.collector.container_definitions)[0].secrets == [{ name = "TLS_KEY", valueFrom = var.collector_tls.key_secret_arn }]
    )
    error_message = "The collector does not serve the certificate of the internal CA with its key from the secret."
  }

  assert {
    condition = (
      local.collector_environment.LOGS_ENDPOINT == "https://logs.eu-central-1.amazonaws.com" &&
      local.collector_environment.TRACES_ENDPOINT == "https://xray.eu-central-1.amazonaws.com/v1/traces"
    )
    error_message = "The collector writes to AWS without TLS or to another region."
  }
}

run "a_missing_security_group_refused" {
  command = plan

  variables {
    security_group_ids = {
      app    = "sg-0000000000000a1b2"
      worker = "sg-0000000000000a1b3"
      ops    = "sg-0000000000000a1b4"
    }
  }

  expect_failures = [var.security_group_ids]
}

run "a_tls_key_by_name_refused" {
  command = plan

  variables {
    collector_tls = {
      cert_pem       = "collector certificate"
      key_secret_arn = "idp-test/tls/otel-collector"
    }
  }

  expect_failures = [var.collector_tls]
}
