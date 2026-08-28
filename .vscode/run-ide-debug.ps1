$ErrorActionPreference = 'Stop'

function Resolve-JavaHome {
    foreach ($scope in @('Process', 'User', 'Machine')) {
        $value = [Environment]::GetEnvironmentVariable('JAVA_HOME', $scope)
        if ([string]::IsNullOrWhiteSpace($value)) {
            continue
        }
        if (Test-Path -LiteralPath (Join-Path $value 'bin\java.exe')) {
            return $value
        }
    }
    return $null
}

$javaHome = Resolve-JavaHome
if (-not $javaHome) {
    Write-Host 'ERROR: JAVA_HOME is not set and no JDK was found.'
    Write-Host 'Install JDK 21, set JAVA_HOME, and re-run this task.'
    exit 1
}

$env:JAVA_HOME = $javaHome
$env:Path = "$(Join-Path $javaHome 'bin');$env:Path"

$wrapper = Join-Path $PSScriptRoot '..\gradlew.bat'
if ($args.Count -eq 0) {
    & $wrapper ':datagrip-elasticsearch-rest-plugin:runDataGrip' '-PdebugIde=true' '--console=plain'
} else {
    & $wrapper @args
}
exit $LASTEXITCODE
