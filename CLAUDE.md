# CLAUDE.md

`rama` is a Green-only Package Skill for one private Rama node on DigitalOcean.
It depends on Green and reuses ONCE's Resend registration/verification stages.
The package owns DigitalOcean infrastructure, DNS-only records, WireGuard,
Rama/ZooKeeper provisioning, acceptance, and the local `green rama` adapter.

Run `bb test`, `bb golden`, `./scripts/launcher.sh`, `./green build`, and
`./green create --dry-run`. Never read `.envrc.private` or `.colors/`. Never
run create/delete without authorization. Keep `compute-prevent-destroy: true`;
only override it for an authorized deletion. Credentials use `COLORS_PAR_*` and
must never render. Never set `COLORS_PAR_PROFILE`.

Pins are managed only by `bb pin` after a clean pushed commit. Deployment
launchers are copies and must be synchronized after repinning. Do not commit or
push without explicit authorization.
