resource "aws_service_discovery_service" "pgbouncer" {
  name = "pgbouncer"

  dns_config {
    namespace_id   = var.namespace_id
    routing_policy = "MULTIVALUE"

    dns_records {
      type = "A"
      ttl  = 10
    }
  }
}

resource "aws_ecs_task_definition" "pgbouncer" {
  family                   = "${var.name}-pgbouncer"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 256
  memory                   = 512
  execution_role_arn       = aws_iam_role.execution["pgbouncer"].arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = var.cpu_architecture
  }

  volume {
    name = "run"
  }

  container_definitions = jsonencode([
    {
      name                   = "pgbouncer"
      image                  = var.pgbouncer_image
      essential              = true
      readonlyRootFilesystem = true
      portMappings           = [{ containerPort = 5432, protocol = "tcp" }]
      mountPoints            = [{ sourceVolume = "run", containerPath = "/run/idp" }]
      environment = [
        { name = "DB_HOST", value = aws_db_instance.this.address },
        { name = "DB_PORT", value = tostring(aws_db_instance.this.port) },
        { name = "DB_NAME", value = aws_db_instance.this.db_name },
        { name = "DEFAULT_POOL_SIZE", value = tostring(var.pgbouncer_pool_size) },
        { name = "MAX_CLIENT_CONN", value = tostring(var.pgbouncer_max_clients) },
        { name = "CLIENT_TLS_CERT", value = var.pgbouncer_tls.cert_pem },
        { name = "SERVER_TLS_CA", value = data.http.rds_ca.response_body },
      ]
      secrets = concat(
        [{ name = "CLIENT_TLS_KEY", valueFrom = var.pgbouncer_tls.key_secret_arn }],
        [for role in local.runtime_roles : { name = "${upper(role)}_PASSWORD", valueFrom = aws_secretsmanager_secret.role[role].arn }],
      )
      healthCheck = {
        command     = ["CMD", "pg_isready", "-h", "127.0.0.1", "-p", "5432", "-q"]
        interval    = 10
        timeout     = 5
        retries     = 3
        startPeriod = 10
      }
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.this["pgbouncer"].name
          awslogs-region        = data.aws_region.current.region
          awslogs-stream-prefix = "pgbouncer"
        }
      }
    },
  ])
}

resource "aws_ecs_service" "pgbouncer" {
  name                               = "pgbouncer"
  cluster                            = var.cluster_arn
  task_definition                    = aws_ecs_task_definition.pgbouncer.arn
  desired_count                      = var.pgbouncer_count
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
    security_groups  = [var.security_group_ids["pgbouncer"]]
    assign_public_ip = false
  }

  service_registries {
    registry_arn = aws_service_discovery_service.pgbouncer.arn
  }
}
