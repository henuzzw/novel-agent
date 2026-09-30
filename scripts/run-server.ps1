param(
    [ValidateSet('api', 'worker', 'all')]
    [string]$Role = 'all'
)

$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root '.env.local'

if (-not (Test-Path -LiteralPath $envFile)) {
    throw "Missing $envFile. Copy .env.example to .env.local and fill in local credentials."
}

Get-Content -LiteralPath $envFile | ForEach-Object {
    $line = $_.Trim()
    if (-not $line -or $line.StartsWith('#')) {
        return
    }

    $parts = $line.Split('=', 2)
    if ($parts.Count -eq 2) {
        [Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1])
    }
}

$codexCommand = if ($env:CODEX_CLI_COMMAND) { $env:CODEX_CLI_COMMAND } else { 'codex.exe' }
$codexExecutable = Get-Command $codexCommand -CommandType Application -ErrorAction SilentlyContinue
if ($codexExecutable) {
    $env:CODEX_CLI_COMMAND = $codexExecutable.Source
}
elseif ($codexCommand -ieq 'codex.exe' -and $env:LOCALAPPDATA) {
    $codexBin = Join-Path $env:LOCALAPPDATA 'OpenAI\Codex\bin'
    if (Test-Path -LiteralPath $codexBin) {
        $desktopCodex = Get-ChildItem -LiteralPath $codexBin -Directory |
            ForEach-Object { Get-Item -LiteralPath (Join-Path $_.FullName 'codex.exe') -ErrorAction SilentlyContinue } |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
        if ($desktopCodex) {
            $env:CODEX_CLI_COMMAND = $desktopCodex.FullName
        }
    }
}

if (-not $env:CODEX_RUNTIME_DIRECTORY) {
    $env:CODEX_RUNTIME_DIRECTORY = Join-Path $root 'tmp\codex-runtime'
}

$env:APP_ROLE = $Role
Push-Location (Join-Path $root 'apps\server')
try {
    .\mvnw.cmd spring-boot:run
}
finally {
    Pop-Location
}
