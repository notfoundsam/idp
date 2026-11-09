locals {
  port    = 4318
  clients = toset(["app", "worker", "ops"])

  # The groups the collector writes. X-Ray writes aws/spans itself, and ECS writes the console of the collector.
  written = ["logs", "security", "metrics"]

  collector_environment = {
    AWS_REGION         = local.region
    LOGS_ENDPOINT      = "https://logs.${local.region}.amazonaws.com"
    TRACES_ENDPOINT    = "https://xray.${local.region}.amazonaws.com/v1/traces"
    LOG_GROUP          = aws_cloudwatch_log_group.this["logs"].name
    SECURITY_LOG_GROUP = aws_cloudwatch_log_group.this["security"].name
    METRICS_LOG_GROUP  = aws_cloudwatch_log_group.this["metrics"].name
    METRICS_NAMESPACE  = var.name
    TLS_CERT           = var.collector_tls.cert_pem
  }
}

resource "aws_service_discovery_service" "collector" {
  name = "otel-collector"

  dns_config {
    namespace_id   = var.namespace_id
    routing_policy = "MULTIVALUE"

    dns_records {
      type = "A"
      ttl  = 10
    }
  }
}

data "aws_iam_policy_document" "ecs_tasks" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [local.account]
    }

    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["arn:${data.aws_partition.current.partition}:ecs:${local.region}:${local.account}:*"]
    }
  }
}

resource "aws_iam_role" "execution" {
  name               = "${var.name}-otel-collector-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks.json
}

resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "execution" {
  statement {
    sid       = "TlsKey"
    effect    = "Allow"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [var.collector_tls.key_secret_arn]
  }

  statement {
    sid       = "NoLogReadOrChange"
    effect    = "Deny"
    actions   = local.log_tampering
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "execution" {
  name   = "execution"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.execution.json
}

# The collector appends to its groups and reads, changes or deletes nothing, whatever is granted later.
resource "aws_iam_role" "task" {
  name               = "${var.name}-otel-collector-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks.json
}

data "aws_iam_policy_document" "task" {
  statement {
    sid       = "AppendLogs"
    effect    = "Allow"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = flatten([for key in local.written : [local.log_group_arns[key], "${local.log_group_arns[key]}:log-stream:*"]])
  }

  statement {
    sid       = "NoLogReadOrChange"
    effect    = "Deny"
    actions   = local.log_tampering
    resources = ["*"]
  }

  statement {
    sid       = "NoTraceReadOrChange"
    effect    = "Deny"
    actions   = ["xray:BatchGet*", "xray:Delete*", "xray:GetTrace*", "xray:Put*Policy", "xray:PutEncryptionConfig", "xray:Update*"]
    resources = ["*"]
  }

  statement {
    sid       = "NoShell"
    effect    = "Deny"
    actions   = ["ecs:ExecuteCommand"]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "task" {
  name   = "task"
  role   = aws_iam_role.task.id
  policy = data.aws_iam_policy_document.task.json
}

# The OTLP endpoint of X-Ray, which only this AWS policy follows as its actions change.
resource "aws_iam_role_policy_attachment" "xray" {
  role       = aws_iam_role.task.name
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/AWSXrayWriteOnlyPolicy"
}

resource "aws_ecs_task_definition" "collector" {
  family                   = "${var.name}-otel-collector"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 256
  memory                   = 512
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = var.cpu_architecture
  }

  container_definitions = jsonencode([
    {
      name                   = "otel-collector"
      image                  = var.collector_image
      essential              = true
      readonlyRootFilesystem = true
      portMappings           = [{ containerPort = local.port, protocol = "tcp" }]
      environment            = [for key, value in local.collector_environment : { name = key, value = value }]
      secrets                = [{ name = "TLS_KEY", valueFrom = var.collector_tls.key_secret_arn }]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.this["collector"].name
          awslogs-region        = local.region
          awslogs-stream-prefix = "otel-collector"
        }
      }
    },
  ])
}

resource "aws_ecs_service" "collector" {
  name                               = "otel-collector"
  cluster                            = var.cluster_arn
  task_definition                    = aws_ecs_task_definition.collector.arn
  desired_count                      = var.collector_count
  launch_type                        = "FARGATE"
  platform_version                   = "1.4.0"
  enable_execute_command             = false
  propagate_tags                     = "SERVICE"
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [var.security_group_ids["otel-collector"]]
    assign_public_ip = false
  }

  service_registries {
    registry_arn = aws_service_discovery_service.collector.arn
  }
}

resource "aws_vpc_security_group_ingress_rule" "collector" {
  for_each = local.clients

  security_group_id            = var.security_group_ids["otel-collector"]
  description                  = "OTLP over HTTPS from ${each.key}"
  referenced_security_group_id = var.security_group_ids[each.key]
  ip_protocol                  = "tcp"
  from_port                    = local.port
  to_port                      = local.port
}

resource "aws_vpc_security_group_egress_rule" "collector" {
  for_each = local.clients

  security_group_id            = var.security_group_ids[each.key]
  description                  = "OTLP over HTTPS to the collector"
  referenced_security_group_id = var.security_group_ids["otel-collector"]
  ip_protocol                  = "tcp"
  from_port                    = local.port
  to_port                      = local.port
}
