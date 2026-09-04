<#!
.SYNOPSIS
Generates local JetBrains Marketplace signing material. Never commit its output.
#>
[CmdletBinding()]
param(
    [ValidateRange(1, [int]::MaxValue)]
    [int]$Days = 365,
    [string]$OutputDirectory = ".local/marketplace-signing",
    [string]$Subject,
    [string]$OpenSslConfig
)

$ErrorActionPreference = "Stop"

function Invoke-OpenSsl {
    param([string[]]$Arguments)

    & $script:OpenSsl.Source @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "OpenSSL failed with exit code $LASTEXITCODE."
    }
}

function Resolve-OpenSslConfig {
    param(
        [System.Management.Automation.CommandInfo]$Command,
        [string]$ConfiguredPath
    )

    $commandDirectory = Split-Path -Parent $Command.Source
    $candidates = @(
        $ConfiguredPath,
        $env:OPENSSL_CONF,
        (Join-Path $commandDirectory "openssl.cnf"),
        (Join-Path $commandDirectory "..\\ssl\\openssl.cnf"),
        (Join-Path $commandDirectory "..\\etc\\ssl\\openssl.cnf"),
        (Join-Path $PSScriptRoot "marketplace-signing-openssl.cnf"),
        "C:\\Program Files\\Git\\mingw64\\etc\\ssl\\openssl.cnf",
        "C:\\Program Files\\Git\\usr\\ssl\\openssl.cnf"
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }

    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    throw @"
OpenSSL configuration file was not found. Install a complete OpenSSL distribution,
or rerun with -OpenSslConfig <path-to-openssl.cnf>.
"@
}

$OpenSsl = Get-Command openssl -ErrorAction SilentlyContinue
if ($null -eq $OpenSsl) {
    throw "OpenSSL is required but was not found on PATH."
}
$env:OPENSSL_CONF = Resolve-OpenSslConfig -Command $OpenSsl -ConfiguredPath $OpenSslConfig

New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$encryptedKey = Join-Path $OutputDirectory "private_encrypted.pem"
$privateKey = Join-Path $OutputDirectory "private.pem"
$certificate = Join-Path $OutputDirectory "chain.crt"

$hasEncryptedKey = Test-Path -LiteralPath $encryptedKey
$hasPrivateKey = Test-Path -LiteralPath $privateKey
$hasCertificate = Test-Path -LiteralPath $certificate
if ($hasCertificate) {
    throw @"
Refusing to overwrite existing signing material in $OutputDirectory.
Move the existing files to secure storage before creating a different signing identity.
"@
}

if ($hasEncryptedKey) {
    if ($hasPrivateKey) {
        Write-Host "Resuming from existing private key: $privateKey"
    } else {
        Write-Host "Resuming from existing encrypted private key: $encryptedKey"
        Invoke-OpenSsl @("rsa", "-in", $encryptedKey, "-out", $privateKey)
    }
} elseif ($hasPrivateKey) {
    Write-Host "Resuming from existing private key: $privateKey"
} else {
    Invoke-OpenSsl @(
        "genpkey", "-aes-256-cbc", "-algorithm", "RSA", "-out", $encryptedKey,
        "-pkeyopt", "rsa_keygen_bits:4096"
    )
    Invoke-OpenSsl @("rsa", "-in", $encryptedKey, "-out", $privateKey)
}

$requestArguments = @(
    "req", "-key", $privateKey, "-new", "-x509", "-days", "$Days", "-out", $certificate
)
if (-not [string]::IsNullOrWhiteSpace($Subject)) {
    $requestArguments += @("-subj", $Subject)
}
Invoke-OpenSsl $requestArguments

Write-Host ""
Write-Host "Generated local signing files:"
Write-Host ""
Write-Host "  PRIVATE_KEY:       $privateKey"
Write-Host "  CERTIFICATE_CHAIN: $certificate"
Write-Host ""
Write-Host "The generated private.pem is unencrypted because the Marketplace signer uses"
Write-Host "the converted RSA key. Do not set PRIVATE_KEY_PASSWORD for this key. Preserve"
Write-Host "private_encrypted.pem and its passphrase as a secure offline backup."
Write-Host ""
Write-Host "For GitHub Actions, use single-line Base64 values (do not commit the output):"
Write-Host ""
Write-Host "  [Convert]::ToBase64String([IO.File]::ReadAllBytes('$privateKey'))"
Write-Host "  [Convert]::ToBase64String([IO.File]::ReadAllBytes('$certificate'))"
