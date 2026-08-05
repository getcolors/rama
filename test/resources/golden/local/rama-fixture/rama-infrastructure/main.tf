terraform {
  required_providers {
    digitalocean = { source = "digitalocean/digitalocean", version = "~> 2.0" }
  }
}
provider "digitalocean" {}

data "digitalocean_ssh_keys" "operator" {
  filter {
    key    = "fingerprint"
    values = ["c8:24:b0:7f:94:28:37:5a:23:d6:02:8b:b0:00:d7:7a"]
  }
}

resource "digitalocean_vpc" "rama" {
  name     = "rama-fixture-vpc"
  region   = "ams3"
  ip_range = "10.24.0.0/20"
  lifecycle { prevent_destroy = true }
}

resource "digitalocean_droplet" "rama" {
  name     = "rama-fixture"
  region   = "ams3"
  size     = "s-8vcpu-16gb"
  image    = "ubuntu-24-04-x64"
  vpc_uuid = digitalocean_vpc.rama.id
  ssh_keys = [one(data.digitalocean_ssh_keys.operator.ssh_keys).id]
  lifecycle { prevent_destroy = true }
}

resource "digitalocean_firewall" "rama" {
  name        = "rama-fixture-firewall"
  droplet_ids = [digitalocean_droplet.rama.id]
  inbound_rule {
    protocol = "tcp"
    port_range = "22"
    source_addresses = ["192.0.2.1/32"]
  }
  inbound_rule {
    protocol = "udp"
    port_range = "51820"
    source_addresses = ["0.0.0.0/0", "::/0"]
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
  lifecycle { prevent_destroy = true }
}

output "params" {
  value = { ip = digitalocean_droplet.rama.ipv4_address, user = "root", sudoer = "root", name = "rama-fixture" }
}
