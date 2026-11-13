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
  target          = aws_sesv2_email_identity.domain
  override_during = plan
  values = {
    arn = "arn:aws:ses:eu-central-1:111111111111:identity/idp.example.com"
  }
}

variables {
  name   = "idp-test"
  domain = "idp.example.com"
  from   = "no-reply@idp.example.com"
}

run "ses_sends_for_the_domain_with_its_own_dkim_keys" {
  command = plan

  assert {
    condition     = aws_sesv2_email_identity.domain.email_identity == "idp.example.com"
    error_message = "The identity of SES is not the domain of the environment, so worker sends nothing."
  }

  assert {
    condition     = one(aws_sesv2_email_identity.domain.dkim_signing_attributes).next_signing_key_length == "RSA_2048_BIT"
    error_message = "SES signs with a DKIM key shorter than 2048 bits."
  }
}

run "the_smtp_user_sends_from_one_address_of_the_identity_alone" {
  command = plan

  assert {
    condition = [
      for statement in data.aws_iam_policy_document.smtp.statement :
      "${join(",", sort(statement.actions))} -> ${join(",", sort(statement.resources))} if ${one(statement.condition).variable} = ${join(",", one(statement.condition).values)}"
    ] == ["ses:SendRawEmail -> arn:aws:ses:eu-central-1:111111111111:identity/idp.example.com if ses:FromAddress = no-reply@idp.example.com"]
    error_message = "The SMTP user may do more than send, send for another identity, or from another address."
  }

  assert {
    condition     = output.host == "email-smtp.eu-central-1.amazonaws.com" && output.port == 587
    error_message = "worker sends elsewhere than the SMTP endpoint of SES with STARTTLS."
  }

  assert {
    condition     = aws_secretsmanager_secret.smtp.name == "idp-test/mail/smtp" && aws_secretsmanager_secret.smtp.recovery_window_in_days == 7
    error_message = "The SMTP credentials live elsewhere than infra/terraform/README.md says."
  }
}

run "a_new_credentials_version_replaces_the_access_key" {
  command = plan

  assert {
    condition     = terraform_data.credentials_version.input == 1
    error_message = "The access key is not tied to credentials_version."
  }
}

run "a_sender_outside_the_domain_refused" {
  command = plan

  variables {
    from = "no-reply@other.example.com"
  }

  expect_failures = [var.from]
}

run "a_sender_that_is_no_address_refused" {
  command = plan

  variables {
    from = "idp.example.com"
  }

  expect_failures = [var.from]
}
