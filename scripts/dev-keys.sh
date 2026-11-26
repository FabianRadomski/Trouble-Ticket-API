#!/usr/bin/env bash
# Generates the local RS256 keypair for manual testing (README §1) into .dev/ (gitignored).
# The API only ever reads .dev/jwt-public.pem; scripts/dev-token.sh signs with the private key.
# Usage: scripts/dev-keys.sh [--force]
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ -f .dev/jwt-private.pem && "${1:-}" != "--force" ]]; then
  echo "Keys already exist in .dev/ (use --force to replace them)."
  exit 0
fi

mkdir -p .dev
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out .dev/jwt-private.pem 2>/dev/null
openssl pkey -in .dev/jwt-private.pem -pubout -out .dev/jwt-public.pem
chmod 600 .dev/jwt-private.pem
# Readable by the non-root user in the api container (mounted read-only by docker-compose.yml).
chmod 644 .dev/jwt-public.pem
echo "Wrote .dev/jwt-private.pem and .dev/jwt-public.pem"
