#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
for variant in r2 r2-keygen s3-keygen r2-mail-dns; do
  stage="$root/test/resources/golden/$variant/rama-fixture/rama-ansible"
  uv run --with ansible-core ansible-playbook --syntax-check -i "$stage/inventory.json" "$stage/main.yml" >/dev/null
  uv run --with ansible-core ansible-playbook --syntax-check -i "$stage/inventory.json" "$stage/cleanup.yml" >/dev/null
done
echo 'Ansible syntax: four managed/external and optional-service builds passed'
