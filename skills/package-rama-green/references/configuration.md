# Configuration

`colors.yml` is a flat map. The worked deployment is `rama-digitalocean`.

- Compute: `provider-compute: digitalocean`, region, size, Ubuntu image, local
  SSH public-key path, VPC CIDR, SSH source CIDRs, and WireGuard source CIDRs.
- Rama: exact Rama ZIP URL/version, exact ZooKeeper URL/version, Java version,
  data directory, and supervisor port range.
- VPN: UDP port, network, server/client addresses, and client interface name.
- DNS: `provider-dns: cloudflare` or `null`/`false`/`no`; Cloudflare creates one
  DNS-only A record. Disabled DNS makes WireGuard use the public IP.
- Mail: `provider-smtp: resend` or `null`/`false`/`no`; enabled mode creates and
  verifies `notifications.<zone>` and configures the Resend SMTP relay.
- State: `provider-backend: local`, `s3`, or `r2`.
- License: `rama-license: false` by default. If true, provide the local path as
  `COLORS_PAR_RAMA_LICENSE_SOURCE_PATH`.

Credentials for enabled providers are `COLORS_PAR_DO_TOKEN`,
`COLORS_PAR_CLOUDFLARE_API_TOKEN`, `COLORS_PAR_RESEND_API_KEY`, and
`COLORS_PAR_RESEND_PASSWORD`. Never put their values in tracked files.

`compute-prevent-destroy: true` is mandatory committed desired state. For one
authorized deletion only, overlay it with
`COLORS_PAR_COMPUTE_PREVENT_DESTROY=false`.
