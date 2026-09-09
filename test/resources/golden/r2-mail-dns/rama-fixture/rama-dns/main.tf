terraform {
  required_providers {
    cloudflare = { source = "cloudflare/cloudflare", version = "~> 5.0" }
  }
}
provider "cloudflare" {}
data "cloudflare_zone" "domains" {
  for_each = toset(["example.com"])
  filter = { name = each.value }
}
