terraform {
  required_version = ">= 1.11"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.70.0"
    }
    http = {
      source  = "hashicorp/http"
      version = ">= 3.6.3"
    }
    tls = {
      source  = "hashicorp/tls"
      version = ">= 4.4.2"
    }
  }
}
