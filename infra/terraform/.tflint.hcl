config {
  call_module_type = "local"
}

plugin "terraform" {
  enabled = true
  preset  = "all"
}

# A root pins the exact version of every provider its modules use, also of those it calls none of itself.
rule "terraform_unused_required_providers" {
  enabled = false
}

plugin "aws" {
  enabled = true
  version = "0.50.0"
  source  = "github.com/terraform-linters/tflint-ruleset-aws"
}
