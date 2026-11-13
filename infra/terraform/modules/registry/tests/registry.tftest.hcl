mock_provider "aws" {}

variables {
  name = "idp-test"
}

run "every_image_has_a_repository_with_immutable_tags_scanned_on_push" {
  command = plan

  assert {
    condition     = toset([for repository in aws_ecr_repository.this : repository.name]) == toset([for image in ["app", "worker", "ops", "migrator", "pgbouncer", "db-bootstrap", "otel-collector"] : "idp-test/${image}"])
    error_message = "An image of a release has no repository, or a repository lies outside the environment."
  }

  assert {
    condition     = alltrue([for repository in aws_ecr_repository.this : repository.image_tag_mutability == "IMMUTABLE"])
    error_message = "A tag can be pushed again, and a revision would run another image than it was released with."
  }

  assert {
    condition     = alltrue([for repository in aws_ecr_repository.this : one(repository.image_scanning_configuration).scan_on_push])
    error_message = "A pushed image is not scanned for known vulnerabilities."
  }

  assert {
    condition     = alltrue([for repository in aws_ecr_repository.this : one(repository.encryption_configuration).encryption_type == "KMS"])
    error_message = "A repository is not encrypted with KMS."
  }
}

run "old_releases_expire_but_enough_stay_for_a_rollback" {
  command = plan

  assert {
    condition = alltrue([
      for policy in aws_ecr_lifecycle_policy.this : [for rule in jsondecode(policy.policy).rules : "${rule.selection.tagStatus} ${rule.selection.countType} ${rule.selection.countNumber}"] == [
        "untagged sinceImagePushed 7",
        "any imageCountMoreThan 100",
      ]
    ]) && length(aws_ecr_lifecycle_policy.this) == 7
    error_message = "A lifecycle policy removes the tagged images of the releases a rollback needs, or keeps the layers of failed pushes."
  }
}
