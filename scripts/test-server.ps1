param(
    [switch]$Database,
    [string]$Tests = ''
)

$root = Split-Path -Parent $PSScriptRoot
if ($Database) {
    $envFile = Join-Path $root '.env.local'
    if (-not (Test-Path -LiteralPath $envFile)) { throw 'Database tests require the existing local configuration.' }
    Get-Content -LiteralPath $envFile | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith('#')) {
            $parts = $line.Split('=', 2)
            if ($parts.Count -eq 2) { [Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1], 'Process') }
        }
    }
    $env:NOVEL_AUTOMATION_DB_TEST = 'true'
}

$arguments = @('-q', 'test')
if ($Tests) { $arguments += "-Dtest=$Tests" }
Push-Location (Join-Path $root 'apps/server')
try {
    & .\mvnw.cmd @arguments
    exit $LASTEXITCODE
}
finally { Pop-Location }
