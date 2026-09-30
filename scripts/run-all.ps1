$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root '.env.local'
$web = Join-Path $root 'apps\web'

if (-not (Test-Path -LiteralPath $envFile)) {
    throw "Missing $envFile. Copy .env.example to .env.local and fill in local credentials."
}

if (-not (Test-Path -LiteralPath (Join-Path $web 'node_modules'))) {
    throw "Missing frontend dependencies. Run npm install in apps/web first."
}

Push-Location $web
try {
    npm run dev:all
    if ($LASTEXITCODE -ne 0) {
        throw "Frontend or backend exited with code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}
