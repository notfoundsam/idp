variable "name" {
  description = "Prefix of the environment, such as idp-staging."
  type        = string
}

variable "deploy_role_name" {
  description = "Role that the release workflow assumes, created by infra/terraform/bootstrap."
  type        = string
}

variable "cluster_name" {
  description = "ECS cluster of the services."
  type        = string
}

variable "static_bucket" {
  description = "Bucket of the frontend files."
  type        = string
}

variable "repository_urls" {
  description = "ECR repository of each image, by image name."
  type        = map(string)
}

variable "repository_arns" {
  description = "ARN of each ECR repository, by image name."
  type        = map(string)
}

variable "infra_image_tag" {
  description = "Tag of the images of infra/, which a release pushes when the registry does not hold it yet."
  type        = string
}

variable "cpu_architecture" {
  description = "CPU architecture of the images, as ECS names it."
  type        = string
  default     = "ARM64"

  validation {
    condition     = contains(["ARM64", "X86_64"], var.cpu_architecture)
    error_message = "ECS runs ARM64 or X86_64."
  }
}

variable "services" {
  description = "Name of each ECS service that a release rolls out: app, worker and ops."
  type        = map(string)

  validation {
    condition     = toset(keys(var.services)) == toset(["app", "worker", "ops"])
    error_message = "A release rolls out app, worker and ops."
  }
}

variable "task_families" {
  description = "Task definition family of each task of modules/app."
  type        = map(string)
}

variable "one_off_tasks" {
  description = "Family and awsvpcConfiguration of the one-off runs of modules/app."
  type = map(object({
    task_definition = string
    network = object({
      subnets        = list(string)
      securityGroups = list(string)
      assignPublicIp = string
    })
  }))
}

variable "role_passwords_task" {
  description = "Task definition ARN and awsvpcConfiguration of the bootstrap step that sets the passwords of the runtime roles."
  type = object({
    task_definition = string
    network = object({
      subnets        = list(string)
      securityGroups = list(string)
      assignPublicIp = string
    })
  })
}

variable "task_role_arns" {
  description = "Task role of each task of modules/iam."
  type        = map(string)
}

variable "execution_role_arns" {
  description = "Execution role of each task of modules/app."
  type        = map(string)
}

variable "smoke_host" {
  description = "Host of the synthetic tenant that the smoke test of a release signs in to, set up once as docs/release.md describes."
  type        = string

  validation {
    condition     = can(regex("^([a-z0-9]([a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,}$", var.smoke_host))
    error_message = "A lowercase DNS name such as release-smoke.idp.example.com."
  }
}

variable "bootstrap_execution_role_arn" {
  description = "Execution role of the bootstrap tasks of modules/data."
  type        = string
}
