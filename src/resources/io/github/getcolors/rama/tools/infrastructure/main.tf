terraform {
  required_providers {
    digitalocean = { source = "digitalocean/digitalocean", version = "~> 2.0" }
  }
}
provider "digitalocean" {}

data "digitalocean_ssh_keys" "operator" {
  filter {
    key    = "fingerprint"
    values = ["<{ digitalocean-ssh-key-fingerprint }>"]
  }
}

resource "digitalocean_vpc" "rama" {
  name     = "<{ digitalocean-name }>-vpc"
  region   = "<{ digitalocean-region }>"
  ip_range = "<{ digitalocean-vpc-cidr }>"
  lifecycle { prevent_destroy = <{ compute-prevent-destroy }> }
}

resource "digitalocean_droplet" "rama" {
  name     = "<{ digitalocean-name }>"
  region   = "<{ digitalocean-region }>"
  size     = "<{ digitalocean-size }>"
  image    = "<{ digitalocean-image }>"
  vpc_uuid = digitalocean_vpc.rama.id
  ssh_keys = [one(data.digitalocean_ssh_keys.operator.ssh_keys).id]
  lifecycle { prevent_destroy = <{ compute-prevent-destroy }> }
}

resource "digitalocean_firewall" "rama" {
  name        = "<{ digitalocean-name }>-firewall"
  droplet_ids = [digitalocean_droplet.rama.id]
  inbound_rule {
    protocol = "tcp"
    port_range = "22"
    source_addresses = <{ ssh-sources-hcl|safe }>
  }
  inbound_rule {
    protocol = "udp"
    port_range = "<{ wireguard-port }>"
    source_addresses = <{ wireguard-sources-hcl|safe }>
  }
  outbound_rule {
    protocol = "tcp"
    port_range = "1-65535"
    destination_addresses = ["0.0.0.0/0", "::/0"]
  }
  outbound_rule {
    protocol = "udp"
    port_range = "1-65535"
    destination_addresses = ["0.0.0.0/0", "::/0"]
  }
  outbound_rule {
    protocol = "icmp"
    destination_addresses = ["0.0.0.0/0", "::/0"]
  }
  lifecycle { prevent_destroy = <{ compute-prevent-destroy }> }
}

output "params" {
  value = { ip = digitalocean_droplet.rama.ipv4_address, user = "root", sudoer = "root", name = "<{ profile }>" }
}
