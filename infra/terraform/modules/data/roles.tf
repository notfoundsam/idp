locals {
  runtime_roles = ["app_runtime", "worker_runtime", "ops_runtime"]
  roles         = concat(["idp_owner", "provisioner"], local.runtime_roles)
}

locals {
  bootstrap_environment = [
    { name = "PGHOST", value = aws_db_instance.this.address },
    { name = "PGPORT", value = tostring(aws_db_instance.this.port) },
    { name = "PGDATABASE", value = aws_db_instance.this.db_name },
    { name = "DB_TLS_CA", value = local.rds_root_pem },
  ]

  # The owner step runs once as the master user, before the first migration. The role step runs as idp_owner after every
  # migration that adds a role and after every change of a password.
  bootstrap_tasks = {
    owner = {
      command = ["/usr/local/lib/idp/bootstrap-owner.sh"]
      user    = var.master_username
      secrets = {
        PGPASSWORD           = "${aws_db_instance.this.master_user_secret[0].secret_arn}:password::"
        IDP_OWNER_PASSWORD   = var.role_secret_arns["idp_owner"]
        PROVISIONER_PASSWORD = var.role_secret_arns["provisioner"]
      }
    }
    role-passwords = {
      command = concat(["/usr/local/lib/idp/set-role-passwords.sh"], local.runtime_roles)
      user    = "idp_owner"
      secrets = merge(
        { PGPASSWORD = var.role_secret_arns["idp_owner"] },
        { for role in local.runtime_roles : "${upper(role)}_PASSWORD" => var.role_secret_arns[role] },
      )
    }
  }
}

resource "aws_ecs_task_definition" "bootstrap" {
  for_each = local.bootstrap_tasks

  family                   = "${var.name}-db-${each.key}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 256
  memory                   = 512
  execution_role_arn       = aws_iam_role.execution["db-bootstrap"].arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }

  volume {
    name = "run"
  }

  container_definitions = jsonencode([
    {
      name                   = "db-bootstrap"
      image                  = var.db_bootstrap_image
      essential              = true
      command                = each.value.command
      readonlyRootFilesystem = true
      mountPoints            = [{ sourceVolume = "run", containerPath = "/run/idp" }]
      environment            = concat(local.bootstrap_environment, [{ name = "PGUSER", value = each.value.user }])
      secrets                = [for name, arn in each.value.secrets : { name = name, valueFrom = arn }]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.this["db-bootstrap"].name
          awslogs-region        = data.aws_region.current.region
          awslogs-stream-prefix = each.key
        }
      }
    },
  ])
}
