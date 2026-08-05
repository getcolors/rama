#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
fixture="$tmp/colors.yml"
sed "s#WORKDIR#$tmp/work#" "$root/test/fixtures/colors.yml" > "$fixture"
RAMA_LIB_ROOT="$root" GREEN_LIB_ROOT="$root/../green" ONCE_LIB_ROOT="$root/../once" \
  "$root/green" build -f "$fixture" >/dev/null
actual="$tmp/work/rama-fixture"
golden="$root/test/resources/golden/local/rama-fixture"
if [[ ${1:-} == --accept ]]; then rm -rf "$golden"; mkdir -p "$(dirname "$golden")"; cp -a "$actual" "$golden"; exit 0; fi
[[ -d "$golden" ]] || { echo 'golden missing; inspect build then run bb golden:accept' >&2; exit 1; }
diff -ru "$golden" "$actual"
