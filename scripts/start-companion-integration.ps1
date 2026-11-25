param([string]$Java = "java", [string]$Jar = "", [ValidateRange(1,65535)][int]$Port = 18080)
$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
if (-not $Jar) { $Jar = Join-Path $repo "javac-output-test/jarvis-companion-integration/jarvis-companion-integration-2.24.0-SNAPSHOT.jar" }
if (-not (Test-Path -LiteralPath $Jar)) { throw "Build the separate NONPRODUCTION launcher first: mvn -Pcompanion-integration clean verify" }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $Jar).Path)
try {
    $reader = New-Object System.IO.StreamReader($archive.GetEntry('META-INF/MANIFEST.MF').Open())
    try { $manifest = $reader.ReadToEnd() } finally { $reader.Dispose() }
    if ($manifest -notmatch '(?m)^Start-Class: com\.jarvis\.integration\.CompanionIntegrationServer\s*$') { throw 'This is not the separate integration JAR; refusing to launch it with test credentials.' }
} finally { $archive.Dispose() }
$credentialsPath = Join-Path $repo "data/companion-integration/credentials.json"
if (-not (Test-Path -LiteralPath $credentialsPath)) { throw "Generate local test credentials per docs/companion/INTEGRATION.md first." }
$credentials = Get-Content -LiteralPath $credentialsPath -Raw | ConvertFrom-Json
$env:JARVIS_INTEGRATION_PASSWORD = $credentials.password
$env:JARVIS_INTEGRATION_TOKEN = $credentials.token
$env:JARVIS_INTEGRATION_HISTORY = Join-Path $repo "data/companion-integration/history.db"
$env:JARVIS_INTEGRATION_PORT = "$Port"
try {
    # The production Core JAR deliberately cannot launch the mock server anymore.
    & $Java -jar $Jar
    if ($LASTEXITCODE -ne 0) { throw "Integration process exited with code $LASTEXITCODE" }
} finally { Remove-Item Env:JARVIS_INTEGRATION_PASSWORD,Env:JARVIS_INTEGRATION_TOKEN,Env:JARVIS_INTEGRATION_HISTORY,Env:JARVIS_INTEGRATION_PORT -ErrorAction SilentlyContinue }
