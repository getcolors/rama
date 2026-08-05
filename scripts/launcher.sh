#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
launcher="$root/skills/package-rama-green/green"
grep -q 'io.github.getcolors.rama.workflow/workflow' "$launcher"
grep -q 'io.github.getcolors.rama.operator/run' "$launcher"
[[ -L "$root/green" ]] && [[ $(readlink "$root/green") == skills/package-rama-green/green ]]
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
cp "$launcher" "$tmp/green"; chmod +x "$tmp/green"
sed "s#WORKDIR#.colors#" "$root/test/fixtures/colors.yml" > "$tmp/colors.yml"
(cd "$tmp" && RAMA_LIB_ROOT="$root" ./green build >/dev/null)
[[ -f "$tmp/.colors/rama-fixture/rama-infrastructure/main.tf" ]]
echo 'launcher: all checks passed'
