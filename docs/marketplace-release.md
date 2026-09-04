# JetBrains Marketplace release

This repository produces a signed plugin ZIP for Marketplace uploads. Do not
modify the signed ZIP after Gradle creates it.

## First release

The first Marketplace publication must be uploaded manually.

1. Confirm `pluginVersion` is `0.1.0` in `gradle.properties` and that
   `CHANGELOG.md` has the matching version section.
2. Run the normal CI checks: tests, `verifyPlugin`, and `buildPlugin`.
3. Review Plugin Verifier output for plugin descriptor, dependency,
   compatibility, and internal API issues.
4. Generate signing material and configure the required GitHub Actions secrets
   as described in [Signing certificate and secrets](#signing-certificate-and-secrets).
5. Run the **Marketplace Release** workflow with
   `publish_to_marketplace` left disabled. It runs tests and verifier checks,
   builds and signs the plugin, verifies the signature, and uploads the signed
   ZIP as an Actions artifact.
6. Download the signed ZIP and test it locally with **Settings → Plugins → ⚙ →
   Install Plugin from Disk...**.
7. In JetBrains Marketplace, create or complete the Vendor Profile, accept the
   Developer Agreement, and manually upload the signed ZIP.
8. Select Apache License 2.0, set the source code URL to
   `https://github.com/suhli/datagrip-es`, add tags and real screenshots, make
   the required visibility decision, complete the Trader/Non-trader declaration,
   and submit for review.

### Marketplace checklist

- [ ] JetBrains Account
- [ ] Marketplace Developer Agreement accepted
- [ ] Vendor profile created
- [ ] Vendor website
- [ ] Vendor email
- [ ] Trader / Non-trader declaration
- [ ] Apache License 2.0 selected
- [ ] Source code URL entered
- [ ] Tags selected
- [ ] Screenshots added
- [ ] Plugin visibility / Hidden decision made
- [ ] Signed ZIP uploaded manually
- [ ] Confirm no Privacy Policy is required because the plugin does not collect personal or telemetry data

### Screenshot guidance

Capture real plugin screens only. Use a consistent 1200×760-or-larger frame and
the same IDE theme. Remove passwords, API keys, private hosts, internal domains,
IP addresses, and personal information before uploading.

1. Data source configuration showing ES REST Data Source, URL, authentication,
   and TLS options.
2. Database Explorer showing indices and mapping fields.
3. An `.esrest` console with completion visible, for example:

   ```http
   GET /products/_search
   {
     "query": {
       "term": {
         "category.keyword": "books"
       }
     }
   }
   ```

4. A Result Grid showing hits or an aggregation result.

## Signing certificate and secrets

Generate local signing material with OpenSSL using the helper below. The output
directory is ignored by Git and the helper refuses to overwrite existing files.

```bash
scripts/generate-marketplace-signing-cert.sh
```

On Windows PowerShell, use the native helper instead:

```powershell
.\scripts\generate-marketplace-signing-cert.ps1
```

The PowerShell helper locates a usable `openssl.cnf` automatically and falls
back to the repository's minimal signing configuration. If you need to use a
specific OpenSSL configuration, pass its path explicitly:

```powershell
.\scripts\generate-marketplace-signing-cert.ps1 -OpenSslConfig "C:\path\to\openssl.cnf"
```

If a previous run stops after creating `private_encrypted.pem` or `private.pem`,
rerun the helper. It safely resumes without overwriting the existing key and
creates the missing files. If `chain.crt` already exists, it refuses to
overwrite the signing identity.

The helper creates `.local/marketplace-signing/` with:

- `private_encrypted.pem`: encrypted backup key; keep it and its passphrase in
  a secure offline backup.
- `private.pem`: the converted key used by the Marketplace ZIP Signer.
- `chain.crt`: the self-signed X.509 certificate chain.

OpenSSL asks for the key passphrase and certificate subject. Choose your own
identity; do not represent JetBrains, DataGrip, Elastic, or Elasticsearch B.V.
For example, `CN = ES REST Data Source Plugin Signing` and an actual
maintainer/vendor name are appropriate, but neither value is required by the
script.

### Password behavior

The helper follows the JetBrains conversion flow: `openssl rsa` decrypts
`private_encrypted.pem` to produce `private.pem`. That resulting key is
unencrypted, so `PRIVATE_KEY_PASSWORD` is not required and should be left
unset. Confirm this with:

```bash
openssl pkey -in .local/marketplace-signing/private.pem -noout
```

If OpenSSL asks for a password, the final key is encrypted: provide that key's
password as `PRIVATE_KEY_PASSWORD`. Never use an empty or invented password.

### GitHub Actions secrets

In GitHub, open **Repository → Settings → Secrets and variables → Actions →
New repository secret**. Configure these values:

- `PRIVATE_KEY`: Base64-encoded contents of `private.pem`.
- `CERTIFICATE_CHAIN`: Base64-encoded contents of `chain.crt`.
- `PRIVATE_KEY_PASSWORD`: only the password for an encrypted final
  `private.pem`, if applicable.
- `PUBLISH_TOKEN`: Marketplace token; only needed when enabling Marketplace
  publishing.

Base64 is recommended for `PRIVATE_KEY` and `CERTIFICATE_CHAIN`: the JetBrains
Gradle plugin detects and decodes it, avoiding multiline-secret and line-ending
problems. Do not Base64-encode `PRIVATE_KEY_PASSWORD` or `PUBLISH_TOKEN`.

On Linux or macOS:

```bash
base64 < .local/marketplace-signing/private.pem | tr -d '\n'
base64 < .local/marketplace-signing/chain.crt | tr -d '\n'
```

In PowerShell:

```powershell
[Convert]::ToBase64String(
    [IO.File]::ReadAllBytes(".local/marketplace-signing/private.pem")
)
[Convert]::ToBase64String(
    [IO.File]::ReadAllBytes(".local/marketplace-signing/chain.crt")
)
```

### Local signing verification

For the unencrypted `private.pem` generated by the helper, leave
`PRIVATE_KEY_PASSWORD` unset.

Linux or macOS:

```bash
export CERTIFICATE_CHAIN="$(cat .local/marketplace-signing/chain.crt)"
export CERTIFICATE_CHAIN_FILE="$PWD/.local/marketplace-signing/chain.crt"
export PRIVATE_KEY="$(cat .local/marketplace-signing/private.pem)"
unset PRIVATE_KEY_PASSWORD
./gradlew :plugin:signPlugin :plugin:verifyPluginSignature
```

Windows PowerShell:

```powershell
$env:CERTIFICATE_CHAIN = Get-Content ".local/marketplace-signing/chain.crt" -Raw
$env:CERTIFICATE_CHAIN_FILE = (Resolve-Path ".local/marketplace-signing/chain.crt")
$env:PRIVATE_KEY = Get-Content ".local/marketplace-signing/private.pem" -Raw
Remove-Item Env:PRIVATE_KEY_PASSWORD -ErrorAction SilentlyContinue
.\gradlew.bat :plugin:signPlugin :plugin:verifyPluginSignature
```

When using an encrypted final key, set `PRIVATE_KEY_PASSWORD` to that key's
real password before running the same commands.

### Rotation and backup

Keep `private.pem`, the original encrypted private key, and the relevant
passphrase in a secure long-term backup outside this repository. Do not rely on
GitHub Secrets as the only backup, and use the same signing identity for later
versions when possible. Treat a private-key leak as a security incident: rotate
the key immediately and never put private keys in issues, CI logs, or build
artifacts. Actions artifacts must contain only release outputs such as the
signed plugin ZIP.

## Later releases

1. Update `pluginVersion` in `gradle.properties` and add the matching
   `CHANGELOG.md` section.
2. Run CI and review Plugin Verifier results.
3. Run **Marketplace Release**. After the Marketplace listing has been created
   by the initial manual upload, enable `publish_to_marketplace` to run
   `publishPlugin`; this requires `PUBLISH_TOKEN` in addition to the signing
   secrets.
