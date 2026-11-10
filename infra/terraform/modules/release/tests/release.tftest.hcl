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

override_data {
  target = data.aws_caller_identity.current
  values = {
    account_id = "111111111111"
  }
}

override_resource {
  target          = aws_ssm_parameter.release
  override_during = plan
  values = {
    arn = "arn:aws:ssm:eu-central-1:111111111111:parameter/idp-test/release"
  }
}

override_resource {
  target          = aws_secretsmanager_secret.smoke_login
  override_during = plan
  values = {
    arn = "arn:aws:secretsmanager:eu-central-1:111111111111:secret:idp-test/release/smoke-login-AbCdEf"
  }
}

variables {
  name             = "idp-test"
  smoke_host       = "release-smoke.idp.example.com"
  deploy_role_name = "idp-test-github-deploy"
  cluster_name     = "idp-test"
  static_bucket    = "idp-test-static-111111111111"
  repository_urls = {
    app      = "111111111111.dkr.ecr.eu-central-1.amazonaws.com/idp-test/app"
    worker   = "111111111111.dkr.ecr.eu-central-1.amazonaws.com/idp-test/worker"
    ops      = "111111111111.dkr.ecr.eu-central-1.amazonaws.com/idp-test/ops"
    migrator = "111111111111.dkr.ecr.eu-central-1.amazonaws.com/idp-test/migrator"
  }
  repository_arns = {
    app      = "arn:aws:ecr:eu-central-1:111111111111:repository/idp-test/app"
    worker   = "arn:aws:ecr:eu-central-1:111111111111:repository/idp-test/worker"
    ops      = "arn:aws:ecr:eu-central-1:111111111111:repository/idp-test/ops"
    migrator = "arn:aws:ecr:eu-central-1:111111111111:repository/idp-test/migrator"
  }
  infra_image_tag = "0.1.0"
  services        = { app = "app", worker = "worker", ops = "ops" }
  task_families   = { for name in ["app", "worker", "ops", "cli", "migrator", "recovery"] : name => "idp-test-${name}" }
  one_off_tasks = {
    for name in ["migrator", "recovery", "cli"] : name => {
      task_definition = "idp-test-${name}"
      network = {
        subnets        = ["subnet-0a1b2c3d4e5f60711"]
        securityGroups = [name == "cli" ? "sg-0000000000000a1b4" : "sg-0000000000000a1b5"]
        assignPublicIp = "DISABLED"
      }
    }
  }
  role_passwords_task = {
    task_definition = "arn:aws:ecs:eu-central-1:111111111111:task-definition/idp-test-db-role-passwords:3"
    network = {
      subnets        = ["subnet-0a1b2c3d4e5f60711"]
      securityGroups = ["sg-0000000000000a1b6"]
      assignPublicIp = "DISABLED"
    }
  }
  task_role_arns      = { for name in ["app", "worker", "ops", "cli", "migrator", "recovery"] : name => "arn:aws:iam::111111111111:role/idp-test-${name}-task" }
  execution_role_arns = { for name in ["app", "worker", "ops", "cli", "migrator", "recovery"] : name => "arn:aws:iam::111111111111:role/idp-test-${name}-execution" }

  bootstrap_execution_role_arn = "arn:aws:iam::111111111111:role/idp-test-db-bootstrap-execution"
}

# Statements are compared as "effect actions -> resources", with actions and resources sorted.
run "the_release_reads_its_settings_from_one_parameter" {
  command = plan

  assert {
    condition     = aws_ssm_parameter.release.name == "/idp-test/release" && aws_ssm_parameter.release.type == "String"
    error_message = "The settings of a release live elsewhere than /<name>/release."
  }

  assert {
    condition = jsondecode(aws_ssm_parameter.release.value) == jsondecode(jsonencode({
      region          = "eu-central-1"
      cluster         = "idp-test"
      bucket          = "idp-test-static-111111111111"
      platform        = "linux/arm64"
      repositories    = var.repository_urls
      infra_image_tag = "0.1.0"
      services        = { for name in ["app", "worker", "ops"] : name => { service = name, family = "idp-test-${name}" } }
      tasks = {
        migrator       = { family = "idp-test-migrator", network = var.one_off_tasks.migrator.network }
        cli            = { family = "idp-test-cli", network = var.one_off_tasks.cli.network }
        role-passwords = { family = "idp-test-db-role-passwords", network = var.role_passwords_task.network }
      }
      smoke = {
        host   = "release-smoke.idp.example.com"
        secret = "arn:aws:secretsmanager:eu-central-1:111111111111:secret:idp-test/release/smoke-login-AbCdEf"
      }
      alarms = { prefix = "idp-test-", regions = ["eu-central-1", "us-east-1"] }
    }))
    error_message = "The settings miss what infra/deploy reads, or name the recovery run."
  }

  assert {
    condition     = aws_iam_role_policy.deploy.role == "idp-test-github-deploy" && aws_iam_role_policy.deploy.name == "release"
    error_message = "The rights of a release reach another role than deploy."
  }
}

run "the_release_rolls_out_and_runs_its_own_tasks_alone" {
  command = plan

  assert {
    condition = toset([
      for statement in data.aws_iam_policy_document.deploy.statement : "${join(",", sort(statement.actions))} -> ${join(",", sort(statement.resources))}"
      if anytrue([for action in statement.actions : startswith(action, "ecs:")])
      ]) == toset([
      "ecs:DescribeTaskDefinition,ecs:RegisterTaskDefinition -> *",
      "ecs:TagResource -> ${join(",", sort([for name in ["app", "cli", "migrator", "ops", "worker"] : "arn:aws:ecs:eu-central-1:111111111111:task-definition/idp-test-${name}:*"]))}",
      "ecs:DescribeServices,ecs:UpdateService -> arn:aws:ecs:eu-central-1:111111111111:service/idp-test/app,arn:aws:ecs:eu-central-1:111111111111:service/idp-test/ops,arn:aws:ecs:eu-central-1:111111111111:service/idp-test/worker",
      "ecs:RunTask -> arn:aws:ecs:eu-central-1:111111111111:task-definition/idp-test-cli:*,arn:aws:ecs:eu-central-1:111111111111:task-definition/idp-test-db-role-passwords:*,arn:aws:ecs:eu-central-1:111111111111:task-definition/idp-test-migrator:*",
      "ecs:DescribeTasks -> arn:aws:ecs:eu-central-1:111111111111:task/idp-test/*",
    ])
    error_message = "A release may update another service, run another task, such as the recovery run, or tag another revision."
  }

  assert {
    condition = alltrue([
      for statement in data.aws_iam_policy_document.deploy.statement : (
        one(statement.condition).variable == "ecs:cluster" && toset(one(statement.condition).values) == toset(["arn:aws:ecs:eu-central-1:111111111111:cluster/idp-test"])
      ) if contains(statement.actions, "ecs:RunTask")
    ])
    error_message = "A release may run its tasks in another cluster."
  }

  assert {
    condition = alltrue([
      for statement in data.aws_iam_policy_document.deploy.statement : (
        one(statement.condition).variable == "ecs:CreateAction" && toset(one(statement.condition).values) == toset(["RegisterTaskDefinition"])
      ) if contains(statement.actions, "ecs:TagResource")
    ])
    error_message = "A release may retag a revision after registering it."
  }

  assert {
    condition     = !anytrue([for statement in data.aws_iam_policy_document.deploy.statement : contains(statement.actions, "ecs:DeregisterTaskDefinition") || contains(statement.actions, "ecs:ExecuteCommand")])
    error_message = "A release may deregister a revision that a rollback needs, or open a shell in a task."
  }
}

run "the_release_passes_the_roles_of_its_tasks_to_ecs_alone" {
  command = plan

  assert {
    condition = [
      for statement in data.aws_iam_policy_document.deploy.statement : statement.resources if contains(statement.actions, "iam:PassRole")
      ] == [toset(concat(
        [for name in ["app", "worker", "ops", "migrator", "cli"] : "arn:aws:iam::111111111111:role/idp-test-${name}-task"],
        [for name in ["app", "worker", "ops", "migrator", "cli"] : "arn:aws:iam::111111111111:role/idp-test-${name}-execution"],
        ["arn:aws:iam::111111111111:role/idp-test-db-bootstrap-execution"],
    ))]
    error_message = "A release may pass another role, such as that of the recovery run, deploy or terraform."
  }

  assert {
    condition = alltrue([
      for statement in data.aws_iam_policy_document.deploy.statement : (
        one(statement.condition).variable == "iam:PassedToService" && toset(one(statement.condition).values) == toset(["ecs-tasks.amazonaws.com"])
      ) if contains(statement.actions, "iam:PassRole")
    ])
    error_message = "A release may pass a role to another service than ECS tasks."
  }

  assert {
    condition = alltrue(flatten([
      for statement in data.aws_iam_policy_document.deploy.statement : [for resource in statement.resources : resource != "*"]
      if !contains(statement.actions, "ecr:GetAuthorizationToken") && !contains(statement.actions, "ecs:RegisterTaskDefinition")
    ]))
    error_message = "A right of the release beside the ECR login and the revisions reaches every resource."
  }
}

run "the_release_pushes_images_writes_the_static_files_and_reads_its_settings" {
  command = plan

  assert {
    condition = toset([
      for statement in data.aws_iam_policy_document.deploy.statement : "${join(",", sort(statement.actions))} -> ${join(",", sort(statement.resources))}"
      if !anytrue([for action in statement.actions : startswith(action, "ecs:") || startswith(action, "iam:")])
      ]) == toset([
      "ssm:GetParameter -> arn:aws:ssm:eu-central-1:111111111111:parameter/idp-test/release",
      "ecr:GetAuthorizationToken -> *",
      "ecr:BatchCheckLayerAvailability,ecr:BatchGetImage,ecr:CompleteLayerUpload,ecr:DescribeImages,ecr:InitiateLayerUpload,ecr:PutImage,ecr:UploadLayerPart -> ${join(",", sort(values(var.repository_arns)))}",
      "s3:ListBucket -> arn:aws:s3:::idp-test-static-111111111111",
      "s3:DeleteObject,s3:GetObject,s3:PutObject -> arn:aws:s3:::idp-test-static-111111111111/*",
      "secretsmanager:GetSecretValue -> arn:aws:secretsmanager:eu-central-1:111111111111:secret:idp-test/release/smoke-login-AbCdEf",
      "cloudwatch:DescribeAlarms -> arn:aws:cloudwatch:*:111111111111:alarm:idp-test-*",
    ])
    error_message = "A release reaches another parameter, repository, bucket, secret or alarm, or may change more than the files of the frontend."
  }
}

run "the_smoke_test_reads_one_secret_that_a_person_writes" {
  command = plan

  assert {
    condition     = aws_secretsmanager_secret.smoke_login.name == "idp-test/release/smoke-login"
    error_message = "The login of the synthetic user lives elsewhere than docs/release.md says."
  }

  assert {
    condition     = !anytrue([for statement in data.aws_iam_policy_document.deploy.statement : anytrue([for action in statement.actions : startswith(action, "secretsmanager:") && action != "secretsmanager:GetSecretValue"])])
    error_message = "A release may change or list secrets, where it only reads the login of the synthetic user."
  }
}

run "a_smoke_host_that_is_no_dns_name_refused" {
  command = plan

  variables {
    smoke_host = "https://release-smoke.idp.example.com"
  }

  expect_failures = [var.smoke_host]
}

run "x86_images_build_for_amd64" {
  command = plan

  variables {
    cpu_architecture = "X86_64"
  }

  assert {
    condition     = jsondecode(aws_ssm_parameter.release.value).platform == "linux/amd64"
    error_message = "The images would be built for another architecture than ECS runs."
  }
}

run "a_release_without_one_of_the_services_refused" {
  command = plan

  variables {
    services = { app = "app", worker = "worker" }
  }

  expect_failures = [var.services]
}
