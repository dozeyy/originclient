#!/usr/bin/env bash
# Stores Mojang's public keys where the Worker reads them (D1 `meta`, row
# 'mojang_keys'). The Worker can't fetch them itself: Mojang answers every
# request from Cloudflare Workers with 403, but GitHub's runners get through.
# Run from services/cosmetics with CLOUDFLARE_API_TOKEN + CLOUDFLARE_ACCOUNT_ID
# set, by .github/workflows/cosmetics-service.yml (every deploy) and
# .github/workflows/mojang-keys.yml (weekly). Contract: docs/SOCIAL.md ->
# Identity v2. Fails (and writes nothing) unless both key lists came back
# non-empty, so a Mojang hiccup never wipes the keys already stored.
set -euo pipefail
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

curl -sS --fail --max-time 30 --retry 3 --retry-all-errors \
  -H 'User-Agent: OriginClient-CI/1.0 (+https://originclient.org)' \
  -o "$tmp/publickeys.json" https://api.minecraftservices.com/publickeys

# Keep only the two lists the service checks with, as plain base64 strings
# (base64 has no quote characters, so the JSON is safe inside the SQL string).
python3 - "$tmp/publickeys.json" > "$tmp/keys.sql" <<'PY'
import json, re, sys, time
d = json.load(open(sys.argv[1]))
def keys(name):
    out = []
    for k in d.get(name) or []:
        b = k.get('publicKey') if isinstance(k, dict) else k
        if isinstance(b, str) and re.fullmatch(r'[A-Za-z0-9+/=]+', b):
            out.append(b)
    return out
v = {'profilePropertyKeys': keys('profilePropertyKeys'),
     'playerCertificateKeys': keys('playerCertificateKeys'),
     'at': int(time.time() * 1000)}
if not v['profilePropertyKeys'] or not v['playerCertificateKeys']:
    sys.exit('Mojang returned no profilePropertyKeys/playerCertificateKeys; keeping the stored keys.')
print(f"profile keys: {len(v['profilePropertyKeys'])}, certificate keys: {len(v['playerCertificateKeys'])}", file=sys.stderr)
print("INSERT OR REPLACE INTO meta (k, v) VALUES ('mojang_keys', '%s');" % json.dumps(v, separators=(',', ':')))
PY

npx --yes wrangler@4 d1 execute origin-cosmetics --remote --command "$(cat "$tmp/keys.sql")"
echo "Stored Mojang's public keys in D1."
