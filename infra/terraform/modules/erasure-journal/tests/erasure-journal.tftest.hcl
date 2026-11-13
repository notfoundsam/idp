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

override_resource {
  target          = aws_s3_bucket.this
  override_during = plan
  values = {
    id  = "idp-test-erasure-journal"
    arn = "arn:aws:s3:::idp-test-erasure-journal"
  }
}

variables {
  bucket                = "idp-test-erasure-journal"
  backup_retention_days = 30
}

run "entries_are_locked_in_compliance_mode_past_every_backup" {
  command = plan

  assert {
    condition     = aws_s3_bucket.this.object_lock_enabled
    error_message = "Object Lock is turned on when the bucket is created."
  }

  assert {
    condition     = aws_s3_bucket_versioning.this.versioning_configuration[0].status == "Enabled"
    error_message = "Object Lock needs versioning."
  }

  assert {
    condition     = one(aws_s3_bucket_object_lock_configuration.this.rule[0].default_retention).mode == "COMPLIANCE"
    error_message = "No one shortens the retention of an entry."
  }

  assert {
    condition     = one(aws_s3_bucket_object_lock_configuration.this.rule[0].default_retention).days == 45
    error_message = "An entry outlives the backups by the default margin of 15 days."
  }

  assert {
    condition     = output.retention_days == 45
    error_message = "The output tells the retention of the lock."
  }

  assert {
    condition     = one([for rule in aws_s3_bucket_lifecycle_configuration.this.rule : rule.expiration[0].days if rule.id == "expired-entries"]) == 45
    error_message = "The lifecycle expires an entry only when its lock ends."
  }
}

run "the_margin_adds_to_the_backups" {
  command = plan

  variables {
    backup_retention_days = 35
    margin_days           = 10
  }

  assert {
    condition     = one(aws_s3_bucket_object_lock_configuration.this.rule[0].default_retention).days == 45
    error_message = "The retention is the days of backups plus the margin."
  }
}

run "the_bucket_is_private_and_owned_by_the_account" {
  command = plan

  assert {
    condition = alltrue([
      aws_s3_bucket_public_access_block.this.block_public_acls,
      aws_s3_bucket_public_access_block.this.block_public_policy,
      aws_s3_bucket_public_access_block.this.ignore_public_acls,
      aws_s3_bucket_public_access_block.this.restrict_public_buckets,
    ])
    error_message = "Every public access is blocked."
  }

  assert {
    condition     = aws_s3_bucket_ownership_controls.this.rule[0].object_ownership == "BucketOwnerEnforced"
    error_message = "ACLs are off."
  }

  assert {
    condition     = one(aws_s3_bucket_server_side_encryption_configuration.this.rule).apply_server_side_encryption_by_default[0].sse_algorithm == "AES256"
    error_message = "Entries are encrypted at rest."
  }
}

run "the_policy_refuses_plain_http_and_every_delete" {
  command = plan

  assert {
    condition = one([
      for statement in data.aws_iam_policy_document.this.statement : statement.actions
      if statement.sid == "NoDeletes" && statement.effect == "Deny"
    ]) == toset(["s3:DeleteObject", "s3:DeleteObjectVersion"])
    error_message = "No one deletes an entry or one of its versions."
  }

  assert {
    condition = toset(one([
      for statement in data.aws_iam_policy_document.this.statement : one(statement.condition).values
      if statement.sid == "TlsOnly" && statement.effect == "Deny"
    ])) == toset(["false"])
    error_message = "A request without TLS is refused."
  }
}

run "less_than_a_day_of_backups_refused" {
  command = plan

  variables {
    backup_retention_days = 0
  }

  expect_failures = [var.backup_retention_days]
}

run "a_margin_below_a_week_refused" {
  command = plan

  variables {
    margin_days = 6
  }

  expect_failures = [var.margin_days]
}
