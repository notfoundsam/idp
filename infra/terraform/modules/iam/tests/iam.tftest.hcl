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
  name                       = "idp-test"
  platform_kek_arn           = "arn:aws:kms:eu-central-1:111111111111:key/0b4f3c1e-7a52-4d9e-9a3c-5f0e2d8b6a41"
  erasure_journal_bucket_arn = "arn:aws:s3:::idp-test-erasures-111111111111"
}

# Statements are compared as "effect actions -> resources", with actions and resources sorted.
run "each_task_has_a_role_of_its_own_that_only_ecs_tasks_of_the_account_assume" {
  command = plan

  assert {
    condition     = toset(keys(aws_iam_role.task)) == toset(["app", "worker", "ops", "cli", "migrator", "recovery"]) && toset(keys(aws_iam_role_policy.task)) == toset(keys(aws_iam_role.task))
    error_message = "A task has no role or no policy of its own."
  }

  assert {
    condition     = length(distinct([for role in aws_iam_role.task : role.name])) == 6 && aws_iam_role.task["ops"].name == "idp-test-ops-task"
    error_message = "Two tasks share a role name."
  }

  assert {
    condition = (
      one(data.aws_iam_policy_document.ecs_tasks.statement).actions == toset(["sts:AssumeRole"]) &&
      one(data.aws_iam_policy_document.ecs_tasks.statement).principals == toset([{ type = "Service", identifiers = toset(["ecs-tasks.amazonaws.com"]) }])
    )
    error_message = "Somebody beside ECS tasks assumes a task role."
  }

  assert {
    condition = toset([
      for condition in one(data.aws_iam_policy_document.ecs_tasks.statement).condition : "${condition.test} ${condition.variable} ${join(",", condition.values)}"
      ]) == toset([
      "StringEquals aws:SourceAccount 111111111111",
      "ArnLike aws:SourceArn arn:aws:ecs:eu-central-1:111111111111:*",
    ])
    error_message = "ECS of another account or region could hand out a task role."
  }
}

run "ops_and_the_cli_only_encrypt_with_the_platform_kek" {
  command = plan

  assert {
    condition = alltrue([
      for name in ["ops", "cli"] : toset([
        for statement in data.aws_iam_policy_document.task[name].statement : "${statement.effect} ${join(",", sort(statement.actions))} -> ${join(",", sort(statement.resources))}"
        if anytrue([for action in statement.actions : startswith(action, "kms:")])
      ]) == toset(["Allow kms:Encrypt -> ${var.platform_kek_arn}"])
    ])
    error_message = "ops or the CLI may do more with a key than encrypt with the platform KEK: decrypt, re-encrypt or grant."
  }
}

run "ops_and_the_cli_read_no_secret_and_open_no_shell" {
  command = plan

  assert {
    condition = alltrue([
      for name in ["ops", "cli"] : contains([
        for statement in data.aws_iam_policy_document.task[name].statement : "${statement.effect} ${join(",", sort(statement.actions))} -> ${join(",", sort(statement.resources))}"
      ], "Deny secretsmanager:* -> *")
    ])
    error_message = "ops or the CLI may read a secret, the password of app_runtime among them."
  }

  assert {
    condition = alltrue([
      for name in keys(aws_iam_role.task) : contains([
        for statement in data.aws_iam_policy_document.task[name].statement : "${statement.effect} ${join(",", sort(statement.actions))} -> ${join(",", sort(statement.resources))}"
      ], "Deny ecs:ExecuteCommand -> *")
    ])
    error_message = "A task may open a shell in another one, app among them."
  }

  assert {
    condition = alltrue(flatten([
      for name in keys(aws_iam_role.task) : [
        for statement in data.aws_iam_policy_document.task[name].statement : [
          for action in statement.actions : !startswith(action, "ecs:") && !startswith(action, "secretsmanager:") && !startswith(action, "ssm")
        ] if statement.effect == "Allow"
      ]
    ]))
    error_message = "A task role reaches ECS, a secret or Systems Manager."
  }
}

run "no_task_reads_changes_or_deletes_the_stored_telemetry" {
  command = plan

  assert {
    condition = alltrue([
      for name in keys(aws_iam_role.task) : contains([
        for statement in data.aws_iam_policy_document.task[name].statement : "${statement.effect} ${join(",", sort(statement.actions))} -> ${join(",", sort(statement.resources))}"
      ], "Deny cloudwatch:*,logs:*,xray:* -> *")
    ])
    error_message = "A task may reach CloudWatch Logs, X-Ray or the metrics, so it could read or delete what it logged."
  }

  assert {
    condition = alltrue(flatten([
      for name in keys(aws_iam_role.task) : [
        for statement in data.aws_iam_policy_document.task[name].statement : [
          for action in statement.actions : !startswith(action, "logs:") && !startswith(action, "xray:") && !startswith(action, "cloudwatch:")
        ] if statement.effect == "Allow"
      ]
    ]))
    error_message = "A task role is granted a call of CloudWatch Logs, X-Ray or CloudWatch."
  }
}

run "only_the_cli_names_its_caller" {
  command = plan

  assert {
    condition     = contains(flatten([for statement in data.aws_iam_policy_document.task["cli"].statement : tolist(statement.actions) if statement.effect == "Allow"]), "sts:GetCallerIdentity")
    error_message = "The CLI does not name the operator who runs it."
  }

  assert {
    condition = alltrue([
      for name in ["app", "worker", "ops", "migrator", "recovery"] :
      !anytrue(flatten([for statement in data.aws_iam_policy_document.task[name].statement : [for action in statement.actions : startswith(action, "sts:")]]))
    ])
    error_message = "Another task than the CLI is granted a call of STS."
  }
}

run "iam_grants_no_destruction_and_no_wildcard" {
  command = plan

  assert {
    condition = alltrue(flatten([
      for name in keys(aws_iam_role.task) : [
        for statement in data.aws_iam_policy_document.task[name].statement : [for action in statement.actions : !strcontains(action, "*")]
        if statement.effect == "Allow"
      ]
    ]))
    error_message = "A task role is granted actions by a wildcard."
  }

  assert {
    condition = alltrue(flatten([
      for name in keys(aws_iam_role.task) : [
        for statement in data.aws_iam_policy_document.task[name].statement : [
          for action in statement.actions : !contains(["kms:ScheduleKeyDeletion", "kms:DisableKey", "kms:PutKeyPolicy", "kms:CreateGrant", "kms:ReEncryptFrom", "kms:ReEncryptTo"], action)
        ] if statement.effect == "Allow"
      ]
    ]))
    error_message = "IAM grants the deletion, a grant or a new policy of a key: ScheduleKeyDeletion comes only with the policy of a tenant KEK."
  }

  assert {
    condition = alltrue(flatten([
      for name in keys(aws_iam_role.task) : [
        for statement in data.aws_iam_policy_document.task[name].statement : [
          for action in statement.actions : !can(regex("(?i)^s3:(Delete|PutBucket|PutObjectRetention|PutObjectLegalHold|PutLifecycle|BypassGovernanceRetention)", action))
        ] if statement.effect == "Allow"
      ]
    ]))
    error_message = "A task may delete an entry of the erasure journal or loosen its lock."
  }

  assert {
    condition = [
      for name in sort(keys(aws_iam_role.task)) : name
      if anytrue([for statement in data.aws_iam_policy_document.task[name].statement : contains(statement.actions, "kms:CreateKey") && statement.effect == "Allow"])
    ] == ["worker"]
    error_message = "Another task than worker creates keys."
  }
}

run "worker_creates_tenant_keks_and_decrypts_the_platform_kek" {
  command = plan

  assert {
    condition = toset([
      for statement in data.aws_iam_policy_document.task["worker"].statement : "${statement.effect} ${join(",", sort(statement.actions))} -> ${join(",", sort(statement.resources))}"
      if anytrue([for action in statement.actions : startswith(action, "kms:")])
      ]) == toset([
      "Allow kms:CreateKey,kms:ListAliases -> *",
      "Allow kms:DescribeKey -> arn:aws:kms:eu-central-1:111111111111:key/*",
      "Allow kms:CreateAlias,kms:DeleteAlias -> arn:aws:kms:eu-central-1:111111111111:alias/tenant-*",
      "Allow kms:Decrypt -> ${var.platform_kek_arn}",
    ])
    error_message = "worker has other rights on keys in IAM than creating tenant KEKs, their aliases and decrypting with the platform KEK."
  }

  assert {
    condition = alltrue([
      for name in ["app", "ops", "cli", "migrator", "recovery"] :
      !anytrue([for statement in data.aws_iam_policy_document.task[name].statement : contains(statement.actions, "kms:Decrypt")])
    ])
    error_message = "Another task than worker decrypts with the platform KEK."
  }
}

run "the_erasure_journal_is_put_by_worker_and_listed_by_recovery" {
  command = plan

  assert {
    condition = {
      for name in keys(aws_iam_role.task) : name => join(" ", sort([
        for statement in data.aws_iam_policy_document.task[name].statement : "${statement.effect} ${join(",", sort(statement.actions))} -> ${join(",", sort(statement.resources))}"
        if anytrue([for action in statement.actions : startswith(action, "s3:")])
      ]))
      } == {
      app      = ""
      worker   = "Allow s3:PutObject -> arn:aws:s3:::idp-test-erasures-111111111111/*"
      ops      = ""
      cli      = ""
      migrator = ""
      recovery = "Allow s3:ListBucket -> arn:aws:s3:::idp-test-erasures-111111111111"
    }
    error_message = "Another task than worker writes the journal, or another than recovery lists it."
  }
}

run "a_release_of_migrator_and_app_hold_no_rights_in_iam" {
  command = plan

  assert {
    condition     = alltrue([for name in ["app", "migrator"] : alltrue([for statement in data.aws_iam_policy_document.task[name].statement : statement.effect == "Deny"])])
    error_message = "app or migrator gets a right in IAM: app decrypts through the policy of a tenant KEK, and a release needs no AWS call."
  }

  assert {
    condition = toset(flatten([
      for statement in data.aws_iam_policy_document.task["recovery"].statement : tolist(statement.actions) if statement.effect == "Allow"
    ])) == toset(["kms:ListAliases", "kms:DeleteAlias", "s3:ListBucket"])
    error_message = "The recovery run gets more in IAM than the aliases of tenant KEKs and the listing of the journal."
  }
}

run "the_key_policies_name_the_roles_of_the_tasks" {
  command = plan

  assert {
    condition     = local.role_arns == { for name, role in aws_iam_role.task : name => "arn:aws:iam::111111111111:role/${role.name}" }
    error_message = "A key policy names another role than the one the task runs as."
  }
}

# Each statement of a key policy reads "<sid>: <effect> <actions>[ except <not_actions>] by <principals> if <conditions>".
run "the_tenant_kek_policy_is_exactly_the_rights_of_the_services" {
  command = plan

  assert {
    condition = toset([
      for statement in data.aws_iam_policy_document.tenant_kek.statement : format("%s: %s %s%s by %s if %s",
        statement.sid,
        statement.effect,
        join(",", sort(statement.actions == null ? [] : tolist(statement.actions))),
        statement.not_actions == null ? "" : " except ${join(",", sort(tolist(statement.not_actions)))}",
        join(";", sort([for principal in statement.principals : "${principal.type}=${join(",", sort(tolist(principal.identifiers)))}"])),
        join(";", sort([for condition in statement.condition : "${condition.test} ${condition.variable} ${join(",", sort(condition.values))}"])),
      )
      ]) == toset([
      "Account: Allow kms:* by AWS=arn:aws:iam::111111111111:root if ",
      "AppDecrypts: Allow kms:Decrypt by *=* if ArnEquals aws:PrincipalArn arn:aws:iam::111111111111:role/idp-test-app-task",
      "WorkerManages: Allow kms:CreateAlias,kms:Decrypt,kms:DeleteAlias,kms:EnableKeyRotation,kms:Encrypt,kms:ScheduleKeyDeletion by *=* if ArnEquals aws:PrincipalArn arn:aws:iam::111111111111:role/idp-test-worker-task",
      "RecoveryDestroys: Allow kms:Decrypt,kms:DeleteAlias,kms:ScheduleKeyDeletion by *=* if ArnEquals aws:PrincipalArn arn:aws:iam::111111111111:role/idp-test-recovery-task",
      "NoOperator: Deny kms:* by *=* if ArnEquals aws:PrincipalArn arn:aws:iam::111111111111:role/idp-test-cli-task,arn:aws:iam::111111111111:role/idp-test-ops-task",
      "DecryptByServicesAlone: Deny kms:Decrypt by *=* if ArnNotEquals aws:PrincipalArn arn:aws:iam::111111111111:role/idp-test-app-task,arn:aws:iam::111111111111:role/idp-test-recovery-task,arn:aws:iam::111111111111:role/idp-test-worker-task",
      "DeletionByOffboardingAlone: Deny kms:ScheduleKeyDeletion by *=* if ArnNotEquals aws:PrincipalArn arn:aws:iam::111111111111:role/idp-test-recovery-task,arn:aws:iam::111111111111:role/idp-test-worker-task",
      "NoReEncryptOrGrants: Deny kms:CreateGrant,kms:ReEncrypt* by *=* if ",
    ])
    error_message = "The policy of a tenant KEK grants or denies something else than the rights of app, worker and the recovery run."
  }

  assert {
    condition     = output.tenant_kek_policy == data.aws_iam_policy_document.tenant_kek.json
    error_message = "worker gets another policy for tenant KEKs."
  }
}

run "the_operator_reaches_no_tenant_kek" {
  command = plan

  assert {
    condition = !anytrue(flatten([
      for statement in data.aws_iam_policy_document.tenant_kek.statement : [
        for condition in statement.condition : contains(condition.values, local.role_arns.ops) || contains(condition.values, local.role_arns.cli)
      ] if statement.effect == "Allow"
    ]))
    error_message = "A statement of a tenant KEK allows ops or the CLI something."
  }

  assert {
    condition = anytrue([
      for statement in data.aws_iam_policy_document.tenant_kek.statement :
      statement.effect == "Deny" && contains(statement.actions, "kms:*") && anytrue([
        for condition in statement.condition :
        condition.test == "ArnEquals" && condition.variable == "aws:PrincipalArn" && toset(condition.values) == toset([local.role_arns.ops, local.role_arns.cli])
      ])
    ])
    error_message = "The policy of a tenant KEK does not deny ops and the CLI every call: Decrypt, ReEncrypt and CreateGrant among them."
  }
}

run "only_offboarding_and_the_recovery_run_delete_a_tenant_kek" {
  command = plan

  assert {
    condition = anytrue([
      for statement in data.aws_iam_policy_document.tenant_kek.statement :
      statement.effect == "Deny" && statement.actions == toset(["kms:ScheduleKeyDeletion"]) && anytrue([
        for condition in statement.condition :
        condition.test == "ArnNotEquals" && condition.variable == "aws:PrincipalArn" && toset(condition.values) == toset([local.role_arns.worker, local.role_arns.recovery])
      ])
    ])
    error_message = "Somebody beside worker and the recovery run may schedule the deletion of a tenant KEK."
  }

  assert {
    condition = alltrue([
      for statement in data.aws_iam_policy_document.tenant_kek.statement :
      statement.effect == "Deny" || statement.sid == "Account" || (
        length(statement.principals) == 1 && one(statement.principals).type == "*" &&
        length(statement.condition) == 1 && one(statement.condition).test == "ArnEquals" && one(statement.condition).variable == "aws:PrincipalArn"
      )
    ])
    error_message = "A statement allows a tenant KEK to anybody, or to a principal that a new role of the same name would not match."
  }
}

run "only_the_services_decrypt_a_tenant_kek" {
  command = plan

  assert {
    condition = anytrue([
      for statement in data.aws_iam_policy_document.tenant_kek.statement :
      statement.effect == "Deny" && statement.actions == toset(["kms:Decrypt"]) && anytrue([
        for condition in statement.condition :
        condition.test == "ArnNotEquals" && condition.variable == "aws:PrincipalArn" && toset(condition.values) == toset([local.role_arns.app, local.role_arns.worker, local.role_arns.recovery])
      ])
    ])
    error_message = "Somebody beside app, worker and the recovery run may decrypt with a tenant KEK."
  }

  assert {
    condition = anytrue([
      for statement in data.aws_iam_policy_document.tenant_kek.statement :
      statement.effect == "Deny" && statement.actions == toset(["kms:ReEncrypt*", "kms:CreateGrant"]) && length(statement.condition) == 0
    ])
    error_message = "Somebody may re-encrypt under a tenant KEK or grant it."
  }
}

run "the_platform_kek_is_only_encrypted_by_the_operator_and_decrypted_by_worker" {
  command = plan

  assert {
    condition = toset([
      for statement in data.aws_iam_policy_document.platform_kek.statement : format("%s: %s %s%s by %s if %s",
        statement.sid,
        statement.effect,
        join(",", sort(statement.actions == null ? [] : tolist(statement.actions))),
        statement.not_actions == null ? "" : " except ${join(",", sort(tolist(statement.not_actions)))}",
        join(";", sort([for principal in statement.principals : "${principal.type}=${join(",", sort(tolist(principal.identifiers)))}"])),
        join(";", sort([for condition in statement.condition : "${condition.test} ${condition.variable} ${join(",", sort(condition.values))}"])),
      )
      ]) == toset([
      "Account: Allow kms:* by AWS=arn:aws:iam::111111111111:root if ",
      "OperatorEncryptsAlone: Deny  except kms:Encrypt by *=* if ArnEquals aws:PrincipalArn arn:aws:iam::111111111111:role/idp-test-cli-task,arn:aws:iam::111111111111:role/idp-test-ops-task",
      "DecryptByWorkerAlone: Deny kms:Decrypt by *=* if ArnNotEquals aws:PrincipalArn arn:aws:iam::111111111111:role/idp-test-worker-task",
      "NoReEncryptOrGrants: Deny kms:CreateGrant,kms:ReEncrypt* by *=* if ",
      "NoDeletionByServices: Deny kms:ScheduleKeyDeletion by *=* if ArnEquals aws:PrincipalArn arn:aws:iam::111111111111:role/idp-test-app-task,arn:aws:iam::111111111111:role/idp-test-cli-task,arn:aws:iam::111111111111:role/idp-test-migrator-task,arn:aws:iam::111111111111:role/idp-test-ops-task,arn:aws:iam::111111111111:role/idp-test-recovery-task,arn:aws:iam::111111111111:role/idp-test-worker-task",
    ])
    error_message = "The policy of the platform KEK grants or denies something else than encrypting by ops and the CLI and decrypting by worker."
  }

  assert {
    condition     = output.platform_kek_policy == data.aws_iam_policy_document.platform_kek.json
    error_message = "The platform KEK gets another policy."
  }

  assert {
    condition     = !anytrue([for statement in data.aws_iam_policy_document.platform_kek.statement : statement.effect == "Allow" && statement.sid != "Account"])
    error_message = "The policy of the platform KEK grants a service directly: the grants live in IAM, where they name this key alone."
  }
}

run "the_operator_only_encrypts_with_the_platform_kek" {
  command = plan

  assert {
    condition = anytrue([
      for statement in data.aws_iam_policy_document.platform_kek.statement :
      statement.effect == "Deny" && statement.not_actions == toset(["kms:Encrypt"]) && anytrue([
        for condition in statement.condition :
        condition.test == "ArnEquals" && condition.variable == "aws:PrincipalArn" && toset(condition.values) == toset([local.role_arns.ops, local.role_arns.cli])
      ])
    ])
    error_message = "ops or the CLI may decrypt, re-encrypt or grant the platform KEK."
  }
}

run "an_alias_refused_as_the_platform_kek" {
  command = plan

  variables {
    platform_kek_arn = "arn:aws:kms:eu-central-1:111111111111:alias/idp-test-platform"
  }

  expect_failures = [var.platform_kek_arn]
}

run "another_service_refused_as_the_journal" {
  command = plan

  variables {
    erasure_journal_bucket_arn = "arn:aws:dynamodb:eu-central-1:111111111111:table/erasures"
  }

  expect_failures = [var.erasure_journal_bucket_arn]
}
