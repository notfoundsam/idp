locals {
  trail_name = "${var.name}-management"
  # Known before the trail and the group exist, so the policies and the filters show in the plan.
  trail_arn           = "arn:${local.partition}:cloudtrail:${local.region}:${local.account}:trail/${local.trail_name}"
  trail_log_group     = "/${var.name}/cloudtrail"
  trail_log_group_arn = "arn:${local.partition}:logs:${local.region}:${local.account}:log-group:${local.trail_log_group}"
  trail_bucket_arn    = "arn:${local.partition}:s3:::${var.trail_bucket}"
}

resource "aws_s3_bucket" "trail" {
  bucket = var.trail_bucket
}

resource "aws_s3_bucket_ownership_controls" "trail" {
  bucket = aws_s3_bucket.trail.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_public_access_block" "trail" {
  bucket                  = aws_s3_bucket.trail.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "trail" {
  bucket = aws_s3_bucket.trail.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# An overwritten file stays as a version, and the digests of the trail show which one CloudTrail delivered.
resource "aws_s3_bucket_versioning" "trail" {
  bucket = aws_s3_bucket.trail.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "trail" {
  bucket = aws_s3_bucket.trail.id

  rule {
    id     = "expired-events"
    status = "Enabled"

    filter {}

    expiration {
      days = var.trail_retention_days
    }

    noncurrent_version_expiration {
      noncurrent_days = var.trail_retention_days
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }

  depends_on = [aws_s3_bucket_versioning.trail]
}

data "aws_iam_policy_document" "trail_bucket" {
  statement {
    sid       = "CloudTrailChecksAcl"
    effect    = "Allow"
    actions   = ["s3:GetBucketAcl"]
    resources = [local.trail_bucket_arn]

    principals {
      type        = "Service"
      identifiers = ["cloudtrail.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceArn"
      values   = [local.trail_arn]
    }
  }

  statement {
    sid       = "CloudTrailWrites"
    effect    = "Allow"
    actions   = ["s3:PutObject"]
    resources = ["${local.trail_bucket_arn}/AWSLogs/${local.account}/*"]

    principals {
      type        = "Service"
      identifiers = ["cloudtrail.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceArn"
      values   = [local.trail_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "s3:x-amz-acl"
      values   = ["bucket-owner-full-control"]
    }
  }

  statement {
    sid       = "TlsOnly"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [local.trail_bucket_arn, "${local.trail_bucket_arn}/*"]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }

  # Only the lifecycle removes an event, which no bucket policy stops.
  statement {
    sid       = "NoDeletes"
    effect    = "Deny"
    actions   = ["s3:DeleteObject", "s3:DeleteObjectVersion"]
    resources = ["${local.trail_bucket_arn}/*"]

    principals {
      type        = "*"
      identifiers = ["*"]
    }
  }
}

resource "aws_s3_bucket_policy" "trail" {
  bucket = aws_s3_bucket.trail.id
  policy = data.aws_iam_policy_document.trail_bucket.json

  depends_on = [aws_s3_bucket_public_access_block.trail]
}

# The metric filters read the trail here. Deletion protection keeps it as the groups of modules/telemetry are kept.
resource "aws_cloudwatch_log_group" "trail" {
  name                        = local.trail_log_group
  retention_in_days           = var.trail_retention_days
  deletion_protection_enabled = true

  lifecycle {
    postcondition {
      condition     = trimsuffix(self.arn, ":*") == local.trail_log_group_arn
      error_message = "The policies name another ARN than the one of the log group of the trail."
    }
  }
}

data "aws_iam_policy_document" "trail_assume" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["cloudtrail.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceArn"
      values   = [local.trail_arn]
    }
  }
}

resource "aws_iam_role" "trail" {
  name               = "${var.name}-cloudtrail"
  assume_role_policy = data.aws_iam_policy_document.trail_assume.json
}

data "aws_iam_policy_document" "trail" {
  statement {
    sid       = "AppendEvents"
    effect    = "Allow"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${local.trail_log_group_arn}:log-stream:*"]
  }

  statement {
    sid       = "NoTampering"
    effect    = "Deny"
    actions   = var.log_tampering_actions
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "trail" {
  name   = "append-to-log-group"
  role   = aws_iam_role.trail.id
  policy = data.aws_iam_policy_document.trail.json
}

# The free copy of the management events of every region, KMS calls included, which the KMS alarms need.
resource "aws_cloudtrail" "this" {
  name                          = local.trail_name
  s3_bucket_name                = aws_s3_bucket.trail.bucket
  is_multi_region_trail         = true
  include_global_service_events = true
  enable_log_file_validation    = true
  enable_logging                = true
  cloud_watch_logs_group_arn    = "${local.trail_log_group_arn}:*"
  cloud_watch_logs_role_arn     = aws_iam_role.trail.arn

  event_selector {
    read_write_type           = "All"
    include_management_events = true
  }

  depends_on = [aws_s3_bucket_policy.trail, aws_cloudwatch_log_group.trail, aws_iam_role_policy.trail]

  lifecycle {
    postcondition {
      condition     = self.arn == local.trail_arn
      error_message = "The policies name another ARN than the one of the trail."
    }
  }
}
