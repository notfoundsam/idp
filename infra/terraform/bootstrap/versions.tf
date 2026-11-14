terraform {
  required_version = "1.16.7"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "6.70.0"
    }
  }
}

provider "aws" {
  region              = var.region
  allowed_account_ids = [var.account_id]

  default_tags {
    tags = {
      Project     = "idp"
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}
