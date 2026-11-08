locals {
  containers = {
    for name, task in local.tasks : name => {
      name                   = name
      image                  = "${var.repository_urls[task.image]}:${var.release}"
      essential              = true
      command                = task.command
      readonlyRootFilesystem = true
      mountPoints            = [{ sourceVolume = "run", containerPath = local.run_dir }]
      environment            = [for key, value in task.environment : { name = key, value = value }]
      secrets                = [for key, arn in task.secrets : { name = key, valueFrom = arn }]
      portMappings = contains(local.balanced, name) ? [
        { containerPort = local.http_port, protocol = "tcp" },
        { containerPort = local.management_port, protocol = "tcp" },
      ] : []
      # worker has no load balancer to replace a task that stopped being ready.
      healthCheck = name == "worker" ? {
        command     = ["CMD", "curl", "-fs", "http://localhost:${local.management_port}/actuator/health/readiness"]
        interval    = 15
        timeout     = 5
        retries     = 3
        startPeriod = 60
      } : null
      dependsOn = name == "ops" ? [{ containerName = "idp-metadata", condition = "SUCCESS" }] : null
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.this[name].name
          awslogs-region        = data.aws_region.current.region
          awslogs-stream-prefix = name
        }
      }
    }
  }

  # ECS hands the metadata over as a variable, and ops reads a file, so a container of the same image writes it first.
  idp_metadata_container = {
    name                   = "idp-metadata"
    image                  = "${var.repository_urls["ops"]}:${var.release}"
    essential              = false
    entryPoint             = ["sh", "-c"]
    command                = ["printf '%s\\n' \"$IDP_METADATA\" > ${local.metadata_file}"]
    readonlyRootFilesystem = true
    mountPoints            = [{ sourceVolume = "run", containerPath = local.run_dir }]
    secrets                = [{ name = "IDP_METADATA", valueFrom = aws_secretsmanager_secret.idp_metadata.arn }]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.this["ops"].name
        awslogs-region        = data.aws_region.current.region
        awslogs-stream-prefix = "idp-metadata"
      }
    }
  }
}

resource "aws_ecs_task_definition" "this" {
  for_each = local.tasks

  family                   = "${var.name}-${each.key}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = each.value.cpu
  memory                   = each.value.memory
  execution_role_arn       = aws_iam_role.execution[each.key].arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = var.cpu_architecture
  }

  # The root is read-only, and the image fills this volume, the temporary directory of the JVM.
  volume {
    name = "run"
  }

  container_definitions = jsonencode(concat(
    each.key == "ops" ? [local.idp_metadata_container] : [],
    [{ for key, value in local.containers[each.key] : key => value if value != null }],
  ))

  lifecycle {
    precondition {
      condition     = each.value.environment["SPRING_PROFILES_ACTIVE"] == var.profile && contains(["prod", "staging"], var.profile)
      error_message = "${each.key} runs another profile than prod or staging."
    }

    precondition {
      condition     = alltrue([for value in values(each.value.environment) : !can(regex("(?i)jdwp|-Xdebug|-Xrunjdwp", value))])
      error_message = "${each.key} starts a debugger."
    }
  }
}

resource "aws_ecs_service" "this" {
  for_each = local.services

  name                               = each.key
  cluster                            = var.cluster_arn
  task_definition                    = aws_ecs_task_definition.this[each.key].arn
  desired_count                      = var.desired_counts[each.key]
  launch_type                        = "FARGATE"
  platform_version                   = "1.4.0"
  enable_execute_command             = false
  propagate_tags                     = "SERVICE"
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  health_check_grace_period_seconds  = contains(local.balanced, each.key) ? 60 : null

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [var.security_group_ids[local.security_group[each.key]]]
    assign_public_ip = false
  }

  dynamic "load_balancer" {
    for_each = [for name in [each.key] : aws_lb_target_group.this[name].arn if contains(local.balanced, name)]

    content {
      target_group_arn = load_balancer.value
      container_name   = each.key
      container_port   = local.http_port
    }
  }

  # ECS registers targets only in a group that a listener forwards to.
  depends_on = [aws_lb_listener_rule.app, aws_lb_listener.ops]
}
