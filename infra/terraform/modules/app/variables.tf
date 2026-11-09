variable "name" {
  description = "Prefix of every name, such as idp-staging."
  type        = string
}

variable "profile" {
  description = "Spring profile of every task: prod, or staging, which also lets a load test set THROTTLE_ENABLED=false."
  type        = string

  # The local profile seeds fixed tenants and names a fixed operator without a login.
  validation {
    condition     = contains(["prod", "staging"], var.profile)
    error_message = "The profile is prod or staging, never local."
  }
}

variable "domain" {
  description = "Domain of the product, TENANTS_BASE_DOMAIN. ops answers at ops.<domain>."
  type        = string

  validation {
    condition     = can(regex("^([a-z0-9]([a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,}$", var.domain))
    error_message = "A lowercase DNS name such as idp.example.com."
  }
}

variable "release" {
  description = "Tag of the images of app, worker, ops and migrator, which app reports as APP_VERSION."
  type        = string

  validation {
    condition     = can(regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$", var.release))
    error_message = "An image tag: letters, digits, dots, underscores and dashes, at most 128."
  }
}

variable "min_client_version" {
  description = "MIN_CLIENT_VERSION of app. Empty accepts every frontend."
  type        = string
  default     = ""
}

variable "repository_urls" {
  description = "ECR repository of each image, by name."
  type        = map(string)

  validation {
    condition     = alltrue([for name in ["app", "worker", "ops", "migrator"] : contains(keys(var.repository_urls), name)])
    error_message = "Repositories for app, worker, ops and migrator are needed."
  }
}

variable "cluster_arn" {
  description = "ECS cluster of the services."
  type        = string
}

variable "vpc_id" {
  description = "VPC of the target groups."
  type        = string
}

variable "private_subnet_ids" {
  description = "Subnets of the tasks, without a route to the internet."
  type        = list(string)
}

variable "security_group_ids" {
  description = "Security groups from the network module, by workload. The module adds the rules of the load balancers and of SMTP."
  type        = map(string)

  validation {
    condition     = alltrue([for name in ["alb", "ops-alb", "app", "worker", "ops", "migrator"] : contains(keys(var.security_group_ids), name)])
    error_message = "Groups for alb, ops-alb, app, worker, ops and migrator are needed."
  }
}

variable "endpoint_security_group_id" {
  description = "Security group of the interface endpoints, where worker reaches SES on 587."
  type        = string
}

variable "services" {
  description = "Settings of app, worker, ops and migrator beside the database, the output services of the root: environment by value, secrets by ARN."
  type = map(object({
    environment = map(string)
    secrets     = map(string)
  }))

  validation {
    condition     = toset(keys(var.services)) == toset(["app", "worker", "ops", "migrator"])
    error_message = "Settings for app, worker, ops and migrator, and for nothing else."
  }

  # The module sets the profile itself, from var.profile.
  validation {
    condition = alltrue(flatten([
      for service in values(var.services) : [for name in concat(keys(service.environment), keys(service.secrets)) : !can(regex("(?i)^spring[._-]?profiles", name))]
    ]))
    error_message = "No service sets its Spring profiles, the module sets var.profile."
  }

  # JDWP would let anyone who reaches its port run code in the JVM.
  validation {
    condition = alltrue(flatten([
      for service in values(var.services) : [for value in values(service.environment) : !can(regex("(?i)jdwp|-Xdebug|-Xrunjdwp", value))]
    ]))
    error_message = "No service starts a debugger: JDWP, -Xdebug and -Xrunjdwp are refused."
  }

  validation {
    condition = alltrue(flatten([
      for service in values(var.services) : [for arn in values(service.secrets) : can(regex("^arn:aws[a-z-]*:secretsmanager:[a-z0-9-]+:[0-9]{12}:secret:", arn))]
    ]))
    error_message = "A secret is the ARN of a secret in Secrets Manager."
  }
}

variable "database" {
  description = "Connection settings of the database, the output database of the root."
  type = object({
    pooled_jdbc_url      = string
    pooled_tls_ca_pem    = string
    direct_jdbc_url      = string
    provisioner_jdbc_url = string
    direct_tls_ca_pem    = string
    role_secret_arns     = map(string)
  })

  validation {
    condition = alltrue([
      for url in [var.database.pooled_jdbc_url, var.database.direct_jdbc_url, var.database.provisioner_jdbc_url] :
      startswith(url, "jdbc:postgresql://") && strcontains(url, "sslmode=verify-full")
    ])
    error_message = "Every JDBC URL is Postgres with sslmode=verify-full."
  }

  validation {
    condition     = alltrue([for role in ["app_runtime", "worker_runtime", "ops_runtime", "idp_owner", "provisioner"] : contains(keys(var.database.role_secret_arns), role)])
    error_message = "Secrets for app_runtime, worker_runtime, ops_runtime, idp_owner and provisioner are needed."
  }
}

variable "task_role_arns" {
  description = "Task role of each task, the output task_role_arns of modules/iam."
  type        = map(string)

  validation {
    condition     = toset(keys(var.task_role_arns)) == toset(["app", "worker", "ops", "cli", "migrator", "recovery"])
    error_message = "Roles for app, worker, ops, cli, migrator and recovery, and for nothing else."
  }

  validation {
    condition     = length(distinct(values(var.task_role_arns))) == length(var.task_role_arns)
    error_message = "Each task has a role of its own."
  }
}

variable "tenant_kek_policy" {
  description = "Key policy that worker attaches to every tenant KEK it creates, the output tenant_kek_policy of modules/keys."
  type        = string

  validation {
    condition     = try(jsondecode(var.tenant_kek_policy).Version == "2012-10-17" && length(jsondecode(var.tenant_kek_policy).Statement) > 0, false)
    error_message = "A JSON policy of version 2012-10-17 with statements."
  }
}

variable "previous_kek_retention_days" {
  description = "Days worker keeps a KEK replaced by a move to another KMS: at least as long as a backup taken before the move."
  type        = number

  validation {
    condition     = var.previous_kek_retention_days >= 1 && floor(var.previous_kek_retention_days) == var.previous_kek_retention_days
    error_message = "A whole number of days."
  }
}

variable "https_listener_arn" {
  description = "HTTPS listener of the public ALB, from modules/edge."
  type        = string

  validation {
    condition     = can(regex("^arn:aws[a-z-]*:elasticloadbalancing:[a-z0-9-]+:[0-9]{12}:listener/app/", var.https_listener_arn))
    error_message = "The ARN of a listener of an application load balancer."
  }
}

variable "origin_header" {
  description = "Name of the header that CloudFront sends the public ALB, the output origin_header of modules/edge."
  type        = string

  validation {
    condition     = can(regex("^[A-Za-z][A-Za-z0-9-]*$", var.origin_header))
    error_message = "A header name."
  }
}

variable "origin_header_value" {
  description = "Secret value of that header, from modules/keys. Every rule that forwards to app requires it."
  type        = string
  sensitive   = true

  validation {
    condition     = can(regex("^[A-Za-z0-9]{32,128}$", var.origin_header_value))
    error_message = "Between 32 and 128 letters and digits."
  }
}

variable "ops_alb_arn" {
  description = "The internal ALB of ops, from the network module."
  type        = string

  validation {
    condition     = can(regex("^arn:aws[a-z-]*:elasticloadbalancing:[a-z0-9-]+:[0-9]{12}:loadbalancer/app/", var.ops_alb_arn))
    error_message = "The ARN of an application load balancer."
  }
}

variable "ops_certificate_arn" {
  description = "Certificate of ops.<domain> in the region of the ALB, the one of *.<domain> from modules/edge."
  type        = string

  validation {
    condition     = can(regex("^arn:aws[a-z-]*:acm:[a-z0-9-]+:[0-9]{12}:certificate/", var.ops_certificate_arn))
    error_message = "The ARN of an ACM certificate."
  }
}

variable "ops_client_cidrs" {
  description = "Address ranges of the VPN clients, the only ones that reach ops. Empty until the VPN exists."
  type        = list(string)
  default     = []

  validation {
    condition = alltrue([
      for cidr in var.ops_client_cidrs : try(cidrsubnet(cidr, 0, 0) == cidr && (
        (startswith(cidr, "10.") && tonumber(split("/", cidr)[1]) >= 8) ||
        (can(regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.", cidr)) && tonumber(split("/", cidr)[1]) >= 12) ||
        (startswith(cidr, "192.168.") && tonumber(split("/", cidr)[1]) >= 16)
      ), false)
    ])
    error_message = "Private IPv4 ranges of the VPN, written as their network address, such as 10.100.0.0/22."
  }
}

variable "desired_counts" {
  description = "Tasks of each service. ops keeps its sessions in memory, so its ALB keeps a browser on one task."
  type = object({
    app    = optional(number, 2)
    worker = optional(number, 2)
    ops    = optional(number, 1)
  })
  default = {}
}

variable "cpu_architecture" {
  description = "CPU architecture of the images."
  type        = string
  default     = "ARM64"
}

variable "log_retention_days" {
  description = "Days CloudWatch keeps the console lines of the services."
  type        = number
  default     = 90
}
