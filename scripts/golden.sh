#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
for variant in r2 r2-keygen s3-keygen r2-mail-dns; do
fixture="$tmp/colors.yml"
sed "s#WORKDIR#$tmp/work#" "$root/test/fixtures/colors.yml" > "$fixture"
if [[ "$variant" == *-keygen ]]; then sed -i "/^digitalocean-ssh-authorized-keys:/d" "$fixture"; fi
if [[ "$variant" == s3-keygen ]]; then
  sed -i 's/provider-backend: r2/provider-backend: s3/' "$fixture"
  printf '\ns3-bucket: rama-state\ns3-region: us-east-1\n' >> "$fixture"
fi
if [[ "$variant" == r2-mail-dns ]]; then
  sed -i 's/provider-dns: false/provider-dns: cloudflare/;s/provider-smtp: false/provider-smtp: resend/' "$fixture"
fi
RAMA_LIB_ROOT="$root" "$root/green" build -f "$fixture" >/dev/null
actual="$tmp/work/rama-fixture"
golden="$root/test/resources/golden/$variant/rama-fixture"
# No rendered artefact may carry a real secret into a committed golden. Checked
# before --accept copies anything. POSIX grep on purpose: a missing binary
# inside `if` is simply false, so the guard must not depend on one that may be
# absent.
if grep -rEq 'client-key-data|client-certificate-data|BEGIN (RSA |EC |OPENSSH |DSA )?PRIVATE KEY|github_pat_|ghp_|gho_|ghu_|ghs_|ghr_' "$actual"; then
  echo 'golden: a credential-shaped value was rendered' >&2; exit 1
fi
if [[ ${1:-} == --accept ]]; then rm -rf "$golden"; mkdir -p "$(dirname "$golden")"; cp -a "$actual" "$golden"; rm -rf "$actual"; continue; fi
[[ -d "$golden" ]] || { echo 'golden missing; inspect build then run bb golden:accept' >&2; exit 1; }
diff -ru "$golden" "$actual"
rm -rf "$actual"
done
