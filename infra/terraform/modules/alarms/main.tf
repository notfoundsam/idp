data "aws_caller_identity" "current" {}

data "aws_partition" "current" {}

data "aws_region" "current" {}

locals {
  account   = data.aws_caller_identity.current.account_id
  partition = data.aws_partition.current.partition
  region    = data.aws_region.current.region

  # An alarm notifies a topic of its own region, and the metrics of a web ACL of CloudFront live in us-east-1 alone.
  regions = {
    home      = local.region
    us_east_1 = "us-east-1"
  }

  subscriptions = { for subscriber in var.notification_endpoints : "${subscriber.protocol}:${subscriber.endpoint}" => subscriber }
}

# CloudWatch publishes to an encrypted topic only with a key that lets it encrypt, which no key managed by AWS does.
data "aws_iam_policy_document" "topic_key" {
  for_each = local.regions

  statement {
    sid       = "Account"
    effect    = "Allow"
    actions   = ["kms:*"]
    resources = ["*"]

    principals {
      type        = "AWS"
      identifiers = ["arn:${local.partition}:iam::${local.account}:root"]
    }
  }

  statement {
    sid       = "AlarmsPublish"
    effect    = "Allow"
    actions   = ["kms:Decrypt", "kms:GenerateDataKey*"]
    resources = ["*"]

    principals {
      type        = "Service"
      identifiers = ["cloudwatch.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [local.account]
    }

    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["arn:${local.partition}:cloudwatch:${each.value}:${local.account}:alarm:*"]
    }
  }
}

resource "aws_kms_key" "topic" {
  description         = "Notifications of the alarms of ${var.name}"
  enable_key_rotation = true
  policy              = data.aws_iam_policy_document.topic_key["home"].json
}

resource "aws_kms_key" "topic_us_east_1" {
  provider = aws.us_east_1

  description         = "Notifications of the alarms of ${var.name} on the edge"
  enable_key_rotation = true
  policy              = data.aws_iam_policy_document.topic_key["us_east_1"].json
}

resource "aws_sns_topic" "alarms" {
  name              = "${var.name}-alarms"
  kms_master_key_id = aws_kms_key.topic.arn
}

resource "aws_sns_topic" "alarms_us_east_1" {
  provider = aws.us_east_1

  name              = "${var.name}-alarms"
  kms_master_key_id = aws_kms_key.topic_us_east_1.arn
}

resource "aws_sns_topic_subscription" "alarms" {
  for_each = local.subscriptions

  topic_arn = aws_sns_topic.alarms.arn
  protocol  = each.value.protocol
  endpoint  = each.value.endpoint
}

resource "aws_sns_topic_subscription" "alarms_us_east_1" {
  provider = aws.us_east_1
  for_each = local.subscriptions

  topic_arn = aws_sns_topic.alarms_us_east_1.arn
  protocol  = each.value.protocol
  endpoint  = each.value.endpoint
}
