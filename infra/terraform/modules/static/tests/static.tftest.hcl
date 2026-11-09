mock_provider "aws" {}

variables {
  bucket                       = "idp-test-static"
  cloudfront_distribution_arns = ["arn:aws:cloudfront::111111111111:distribution/EDFDVBD6EXAMPLE"]
}

run "cloudfront_reads_objects_of_its_distribution_and_lists_nothing" {
  command = plan

  assert {
    condition = anytrue([
      for statement in data.aws_iam_policy_document.this.statement : (
        statement.sid == "CloudFrontListsNothing" &&
        statement.effect == "Deny" &&
        contains(statement.actions, "s3:ListBucket") &&
        contains(statement.actions, "s3:ListBucketVersions") &&
        one(statement.principals).identifiers == toset(["cloudfront.amazonaws.com"])
      )
    ])
    error_message = "CloudFront is not denied the listing of the bucket."
  }

  assert {
    condition = alltrue([
      for statement in data.aws_iam_policy_document.this.statement :
      statement.actions == toset(["s3:GetObject"]) && one(statement.condition).values == var.cloudfront_distribution_arns
      if statement.effect != "Deny"
    ])
    error_message = "The bucket allows more than reading objects by the distribution."
  }

  assert {
    condition     = aws_s3_bucket_public_access_block.this.block_public_policy && aws_s3_bucket_public_access_block.this.restrict_public_buckets
    error_message = "The bucket can become public."
  }
}

run "no_rule_takes_away_a_current_object_by_age" {
  command = plan

  assert {
    condition = alltrue([
      for rule in aws_s3_bucket_lifecycle_configuration.this.rule :
      length(rule.expiration) == 0 && length(rule.transition) == 0
    ])
    error_message = "A lifecycle rule expires or moves current objects, which would take the files of a release that is still open in a browser."
  }

  assert {
    condition = anytrue([
      for rule in aws_s3_bucket_lifecycle_configuration.this.rule :
      rule.status == "Enabled" && one(rule.noncurrent_version_expiration).noncurrent_days == 30
    ])
    error_message = "Overwritten and deleted files are kept forever."
  }

  assert {
    condition     = aws_s3_bucket_versioning.this.versioning_configuration[0].status == "Enabled"
    error_message = "A deleted or overwritten file cannot be restored."
  }
}

run "without_a_distribution_nothing_reads_the_bucket" {
  command = plan

  variables {
    cloudfront_distribution_arns = []
  }

  assert {
    condition     = alltrue([for statement in data.aws_iam_policy_document.this.statement : statement.effect == "Deny"])
    error_message = "The bucket allows a read without a distribution."
  }
}
