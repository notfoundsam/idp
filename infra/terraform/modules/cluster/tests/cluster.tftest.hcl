mock_provider "aws" {}

variables {
  name   = "idp-test"
  vpc_id = "vpc-0a1b2c3d4e5f60718"
}

run "the_cluster_runs_fargate_alone_with_container_insights" {
  command = plan

  assert {
    condition     = aws_ecs_cluster.this.name == "idp-test" && [for setting in aws_ecs_cluster.this.setting : "${setting.name}=${setting.value}"] == ["containerInsights=enabled"]
    error_message = "The cluster has another name, or ECS keeps no metrics of its tasks."
  }

  assert {
    condition = (
      aws_ecs_cluster_capacity_providers.this.capacity_providers == toset(["FARGATE"]) &&
      [for strategy in aws_ecs_cluster_capacity_providers.this.default_capacity_provider_strategy : strategy.capacity_provider] == ["FARGATE"]
    )
    error_message = "A task may run on EC2 instances or on Fargate Spot, which stops it at any time."
  }
}

run "tasks_find_each_other_in_a_private_namespace_of_the_vpc" {
  command = plan

  assert {
    condition     = aws_service_discovery_private_dns_namespace.this.name == "idp.internal" && aws_service_discovery_private_dns_namespace.this.vpc == "vpc-0a1b2c3d4e5f60718"
    error_message = "The internal certificates name hosts of another zone, or the zone resolves outside the VPC."
  }
}
