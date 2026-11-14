terraform {
  required_version = "1.16.7"

  # The rest comes from backend.hcl, see infra/terraform/README.md.
  backend "s3" {
    key          = "production/terraform.tfstate"
    use_lockfile = true
    encrypt      = true
  }

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "6.70.0"
    }
    http = {
      source  = "hashicorp/http"
      version = "3.6.3"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "4.4.2"
    }
  }
}

provider "aws" {
  region              = var.region
  allowed_account_ids = [var.account_id]

  default_tags {
    tags = {
      Project     = "idp"
      Environment = local.environment
      ManagedBy   = "terraform"
    }
  }
}

# CloudFront takes certificates and web ACLs only from us-east-1.
provider "aws" {
  alias               = "us_east_1"
  region              = "us-east-1"
  allowed_account_ids = [var.account_id]

  default_tags {
    tags = {
      Project     = "idp"
      Environment = local.environment
      ManagedBy   = "terraform"
    }
  }
}
