terraform {
  required_version = ">= 1.7.0"

  required_providers {
    browsercloud = {
      source  = "sshiong/browsercloud"
      version = "~> 0.1"
    }
  }
}

variable "browsercloud_token" {
  type      = string
  sensitive = true
}

provider "browsercloud" {
  endpoint = "https://browsercloud.example.com"
  token    = var.browsercloud_token
}

resource "browsercloud_group" "production" {
  name                         = "Production"
  description                  = "Production browser environments"
  color                        = "#26D9C7"
  default_on_maximum_reached   = "PAUSE_AGENT"
  default_allow_migration      = true
  default_allow_hibernate      = true
  session_ids                  = []
}

resource "browsercloud_tag" "compliance" {
  name        = "compliance"
  description = "Compliance-controlled environments"
  color       = "#F59E0B"
  session_ids = []
}

data "browsercloud_workspace_settings" "current" {}

output "effective_resource_policy" {
  value = data.browsercloud_workspace_settings.current.resource_policy_mode
}
