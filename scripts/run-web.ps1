$root = Split-Path -Parent $PSScriptRoot
Push-Location (Join-Path $root 'apps\web')
try {
    npm run dev
}
finally {
    Pop-Location
}

