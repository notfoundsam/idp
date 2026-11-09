variable "name" {
  description = "Prefix of every name, such as idp-staging."
  type        = string
}

variable "trail_bucket" {
  description = "Name of the bucket where CloudTrail delivers the management events of the account."
  type        = string
}

variable "trail_retention_days" {
  description = "Days the trail keeps an event, in its log group and its bucket."
  type        = number
  default     = 365

  validation {
    condition     = var.trail_retention_days >= 365
    error_message = "An investigation needs the actions of the last year."
  }
}

variable "metrics_namespace" {
  description = "CloudWatch namespace of the metrics idp.* of the services, from modules/telemetry."
  type        = string
}

variable "security_log_group" {
  description = "Log group of the lines of idp.security, from modules/telemetry. The refusals of Throttle are counted there."
  type        = string
}

variable "task_role_arns" {
  description = "Task role of each task from modules/iam. Only app, worker and recovery may decrypt with a KMS key."
  type        = map(string)

  validation {
    condition     = alltrue([for task in ["app", "worker", "recovery"] : contains(keys(var.task_role_arns), task)])
    error_message = "The roles of app, worker and recovery are needed."
  }

  validation {
    condition     = alltrue([for arn in values(var.task_role_arns) : can(regex("^arn:aws[a-z-]*:iam::[0-9]{12}:role/[A-Za-z0-9+=,.@_-]+$", arn))])
    error_message = "CloudTrail names a task by the ARN of its role without a path, so each value is one."
  }
}

variable "log_tampering_actions" {
  description = "Calls of CloudWatch Logs that read, change or delete stored lines, from modules/telemetry. The role of the trail denies them."
  type        = list(string)
}

variable "waf" {
  description = "Metric names of the web ACL of modules/edge and of its rate-based rules, whose blocks CloudWatch counts in us-east-1."
  type = object({
    web_acl = string
    rules   = list(string)
  })

  validation {
    condition     = length(var.waf.rules) > 0
    error_message = "The web ACL has rules that block."
  }
}

variable "notification_endpoints" {
  description = "Who the alarms notify: e-mail addresses or HTTPS URLs of the on-call engineers. Each one confirms its subscription."
  type = list(object({
    protocol = string
    endpoint = string
  }))
  default = []

  validation {
    condition = alltrue([
      for subscriber in var.notification_endpoints :
      (subscriber.protocol == "email" && can(regex("^[^@\\s]+@[^@\\s]+$", subscriber.endpoint))) ||
      (subscriber.protocol == "https" && startswith(subscriber.endpoint, "https://"))
    ])
    error_message = "An endpoint is an e-mail address or an HTTPS URL."
  }
}
