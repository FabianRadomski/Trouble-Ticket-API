#!/usr/bin/env bash
# Mints an RS256 JWT signed with .dev/jwt-private.pem (run scripts/dev-keys.sh first).
# Usage: scripts/dev-token.sh <tenant_id> [scopes] [ttl_seconds]
#   scripts/dev-token.sh tenant-a "tt:read tt:write"
#   curl -H "Authorization: Bearer $(scripts/dev-token.sh tenant-a)" localhost:8080/api/v1/troubleTicket
set -euo pipefail
cd "$(dirname "$0")/.."

tenant="${1:?usage: $0 <tenant_id> [scopes] [ttl_seconds]}"
scopes="${2:-tt:read tt:write}"
ttl="${3:-3600}"

# Values go into JSON unescaped, so only allow plain characters.
[[ "$tenant" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "invalid tenant_id: $tenant" >&2; exit 1; }
[[ "$scopes" =~ ^[A-Za-z0-9:._\ -]*$ ]] || { echo "invalid scopes: $scopes" >&2; exit 1; }
[[ "$ttl" =~ ^[0-9]+$ ]] || { echo "invalid ttl: $ttl" >&2; exit 1; }
[[ -f .dev/jwt-private.pem ]] || { echo "missing .dev/jwt-private.pem, run scripts/dev-keys.sh" >&2; exit 1; }

b64url() { openssl base64 -e -A | tr '+/' '-_' | tr -d '='; }

now=$(date +%s)
header='{"alg":"RS256","typ":"JWT"}'
payload=$(printf '{"sub":"dev-user","tenant_id":"%s","scope":"%s","iat":%d,"exp":%d}' \
  "$tenant" "$scopes" "$now" "$((now + ttl))")

signing_input="$(printf '%s' "$header" | b64url).$(printf '%s' "$payload" | b64url)"
signature=$(printf '%s' "$signing_input" | openssl dgst -sha256 -sign .dev/jwt-private.pem -binary | b64url)
echo "$signing_input.$signature"
