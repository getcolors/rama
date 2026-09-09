# CLAUDE.md

## What this is

`rama` is a Green-only Package Skill for one private, single-node Rama cluster
on DigitalOcean. It provisions a deployment-owned VPC and Droplet, a restrictive
cloud firewall, WireGuard, ZooKeeper, Rama Conductor and Supervisor services,
optional Cloudflare DNS and Resend mail, acceptance checks, and a local
VPN-aware `green rama` adapter. The first consumer is
`../rama-digitalocean`.

The repository ships `package-rama-green` and its `green` launcher payload.
The root `./green` is a symlink to that payload. The package depends on Green
and reuses ONCE's Resend registration and verification stages. colors-compute
owns provider compute, shared/node state, firewall, and SSH key lifecycle; DNS,
WireGuard, machine convergence, acceptance, and the Rama
adapter are package-owned.

## Commands

```sh
bb test
bb golden
./scripts/launcher.sh
./green build
./green create --dry-run
./green create                 # only with explicit authorization
./green rama conductorReady
./green rama numSupervisors
./green delete                 # guarded and destructive
```

Never run a real create or delete without explicit authorization. Never read or
edit `.colors/`; it is generated and contains private WireGuard material. Never
read `.envrc.private`.

## Desired state and credentials

`colors.yml` is a flat, non-secret map. The supported deployment is one
DigitalOcean node with exact Rama, ZooKeeper, and Java versions. Cloudflare DNS
and Resend SMTP are independently optional. A commercial Rama license is also
optional: desired state contains only `rama-license: true`; the local source
path arrives through `COLORS_PAR_RAMA_LICENSE_SOURCE_PATH`.

All credentials use `COLORS_PAR_*` and must never render. Build and dry-run are
credential-free. Never export `COLORS_PAR_PROFILE`; the package refuses it
because profile identifies local work and remote state. Keep
`compute-prevent-destroy: true` committed and lift it only for one authorized
delete with `COLORS_PAR_COMPUTE_PREVENT_DESTROY=false`.

## Network and operational boundary

SSH admits only configured administrative CIDRs. WireGuard UDP may admit roaming
clients, but Rama, ZooKeeper, and supervisor ports bind to the VPN and must never
be exposed publicly. Cloudflare creates a DNS-only record; proxying is not a
valid Rama transport.

The `green rama ...` adapter downloads and caches the exact configured Rama
release, uses the generated VPN-aware `rama.yaml`, and invokes Rama's bundled
Python CLI locally. Generated WireGuard client configuration is retained private
state, not a distributable artifact.

## Package and deployment coupling

The deployment launcher is a copy, not a symlink. Package pins are managed only
by `bb pin` after a clean pushed commit; never invent or hand-edit `rama-sha`.
After repinning, update consumers by installing/updating the skill and re-copying
the payload:

```sh
npx skills update -p -y
cp .agents/skills/package-rama-green/green green
```

A change spanning Green, ONCE, Rama, and a deployment is a separate commit in
each repository, pushed upstream first. For local cross-boundary development,
use `GREEN_LIB_ROOT`, `ONCE_LIB_ROOT`, `COLORS_COMPUTE_LIB_ROOT`, or `RAMA_LIB_ROOT` rather than editing a
SHA.

`bb golden` protects rendered infrastructure, stage/state names, the ONCE reuse
surface, and the no-rendered-secret boundary. Read every golden diff; never run
`bb golden:accept` merely to make the check pass.

## Documentation

`index.html` is the standalone package manual and carries two analytics tags:
GA4 measurement ID `G-4VKP1WY4QJ`, whose explicit `page_title` must exactly
equal the decoded HTML `<title>`, and the self-hosted Rybbit snippet
`<script src="https://rybbit.getcolors.ai/api/script.js" data-site-id="9fb9c41a6d49" defer></script>`,
which shares one site ID across every repository page because
`getcolors.github.io/<repo>/` paths already encode the repository. Keep both
tags together. Keep the manual, README, skill instructions, configuration
reference, and consumer documentation aligned with behavior.

## Git

Work on the current branch. Do not commit or push unless explicitly asked.

Compute requests a singleton with a created private network and only SSH/admin
and WireGuard UDP public ingress. Keep provider resources in colors-compute and
consume its normalized inventory; never infer a missing deployment from a failed
state read. Remote R2/S3 shared and node states replace the legacy monolith,
which requires explicit migration. Application cleanup must succeed before SSH
config removal and compute destruction. The library destroys owned keys last.
The optional `digitalocean-ssh-authorized-keys` public file selects an externally
owned account key; omission selects managed keygen. Build never reads that file.
