---
name: package-rama-green
description: Provision and operate a private single-node Rama cluster on DigitalOcean using Green.
---

# Rama Package Skill

Use the bundled `green` launcher against a non-secret `colors.yml`.

```sh
./green build
./green create --dry-run
./green create
./green rama conductorReady
./green rama numSupervisors
./green delete
```

Read `references/configuration.md` before editing desired state. Put credentials
only in ignored `.envrc.private` as `COLORS_PAR_*`. Never export
`COLORS_PAR_PROFILE`, edit `.colors/`, weaken `compute-prevent-destroy`, or run
a real create/delete without authorization. The generated WireGuard client is
private local state. Rama service ports must remain inaccessible publicly.
