param(
    [switch]$Check
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$sourceRoot = Join-Path $projectRoot 'apps\server\src\main\java'
$formatterVersion = '1.24.0'
$formatterCache = Join-Path ([System.IO.Path]::GetTempPath()) 'novel-agent-java-format'
$formatterJar = Join-Path $formatterCache "google-java-format-$formatterVersion-all-deps.jar"

if (-not (Test-Path -LiteralPath $formatterJar)) {
    New-Item -ItemType Directory -Path $formatterCache -Force | Out-Null
    $formatterUrl = "https://repo.maven.apache.org/maven2/com/google/googlejavaformat/google-java-format/$formatterVersion/google-java-format-$formatterVersion-all-deps.jar"
    Invoke-WebRequest -Uri $formatterUrl -OutFile $formatterJar
}

$sourceFiles = @(Get-ChildItem -LiteralPath $sourceRoot -Recurse -File |
    Where-Object {
        $_.Name -match '(Controller|Service)\.java$' -and
        $_.Directory.Name -in @('api', 'application')
    } |
    Sort-Object FullName |
    Select-Object -ExpandProperty FullName)

if ($sourceFiles.Count -eq 0) {
    throw "No Controller or Service source files found under $sourceRoot."
}

# Keep literal text, documentation and imports intact; only normalize Java layout.
$formatterArguments = @(
    '-jar', $formatterJar, '--aosp', '--skip-javadoc-formatting',
    '--skip-reflowing-long-strings', '--skip-sorting-imports', '--skip-removing-unused-imports'
)
if ($Check) {
    $formatterArguments += @('--dry-run', '--set-exit-if-changed')
}
else {
    $formatterArguments += '--replace'
}

& java @formatterArguments @sourceFiles
if ($LASTEXITCODE -ne 0) {
    throw "Java formatting check failed with exit code $LASTEXITCODE."
}

Write-Output "Checked $($sourceFiles.Count) Controller/Service files (check only: $Check)."
