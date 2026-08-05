# rama

A Green Package Skill for a private single-node Rama cluster on DigitalOcean.
It provisions a Droplet, DigitalOcean firewall, WireGuard, ZooKeeper, Rama
Conductor and Supervisor, optional Cloudflare DNS and Resend mail, and a local
Rama CLI configured for the VPN.

```sh
./green build
./green create --dry-run
./green create
./green rama conductorReady
./green rama numSupervisors
./green delete
```

Install with `npx skills add getcolors/rama`, then copy
`.agents/skills/package-rama-green/green` to the deployment root. Credentials
are `COLORS_PAR_*` exports in `.envrc.private`; never set `COLORS_PAR_PROFILE`.
See `skills/package-rama-green/references/configuration.md`.

## Development

```sh
bb test
bb golden
./scripts/launcher.sh
```
