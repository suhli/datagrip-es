#!/usr/bin/env bash
# Generates local JetBrains Marketplace signing material. Never commit its output.
set -euo pipefail

output_dir=".local/marketplace-signing"
days="365"

usage() {
  cat <<'EOF'
Usage: scripts/generate-marketplace-signing-cert.sh [--output-dir DIRECTORY] [--days DAYS]

Generates an encrypted 4096-bit RSA backup key, the Marketplace signer key,
and a self-signed X.509 certificate. OpenSSL prompts for the key passphrase and
certificate identity; do not use a JetBrains, DataGrip, Elastic, or
Elasticsearch B.V. identity.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --output-dir)
      output_dir="${2:?missing directory after --output-dir}"
      shift 2
      ;;
    --days)
      days="${2:?missing number after --days}"
      if [[ ! "$days" =~ ^[1-9][0-9]*$ ]]; then
        echo "--days must be a positive integer" >&2
        exit 2
      fi
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

command -v openssl >/dev/null 2>&1 || {
  echo "OpenSSL is required but was not found on PATH." >&2
  exit 1
}

for file in private_encrypted.pem private.pem chain.crt; do
  if [[ -e "$output_dir/$file" ]]; then
    echo "Refusing to overwrite existing signing material: $output_dir/$file" >&2
    exit 1
  fi
done

umask 077
mkdir -p "$output_dir"

openssl genpkey \
  -aes-256-cbc \
  -algorithm RSA \
  -out "$output_dir/private_encrypted.pem" \
  -pkeyopt rsa_keygen_bits:4096

openssl rsa \
  -in "$output_dir/private_encrypted.pem" \
  -out "$output_dir/private.pem"

openssl req \
  -key "$output_dir/private.pem" \
  -new \
  -x509 \
  -days "$days" \
  -out "$output_dir/chain.crt"

chmod 600 "$output_dir/private_encrypted.pem" "$output_dir/private.pem"
chmod 644 "$output_dir/chain.crt"

cat <<EOF

Generated local signing files:

  PRIVATE_KEY:       $output_dir/private.pem
  CERTIFICATE_CHAIN: $output_dir/chain.crt

The generated private.pem is unencrypted because the Marketplace signer uses
the converted RSA key. Do not set PRIVATE_KEY_PASSWORD for this key. Preserve
private_encrypted.pem and its passphrase as a secure offline backup.

For GitHub Actions, use single-line Base64 values (do not commit the output):

  base64 < "$output_dir/private.pem" | tr -d '\\n'
  base64 < "$output_dir/chain.crt" | tr -d '\\n'

EOF
