param([string]$Java = "java", [string]$Jar = "")
$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$credentialsPath = Join-Path $repo "data/companion-integration/credentials.json"
if (-not (Test-Path -LiteralPath $credentialsPath)) { throw "Generate local test credentials per docs/companion/INTEGRATION.md first." }
$credentials = Get-Content -LiteralPath $credentialsPath -Raw | ConvertFrom-Json
$env:JARVIS_INTEGRATION_PASSWORD = $credentials.password
$env:JARVIS_INTEGRATION_TOKEN = $credentials.token
$env:JARVIS_INTEGRATION_HISTORY = Join-Path $repo "data/companion-integration/history.db"
try {
    if ($Jar) { & $Java "-Dloader.main=com.jarvis.integration.CompanionIntegrationServer" -cp $Jar org.springframework.boot.loader.launch.PropertiesLauncher }
    else {
        $classPath = Get-Content -LiteralPath (Join-Path $repo "data/companion-integration/runtime-classpath.txt") -Raw
        & $Java -cp $classPath com.jarvis.integration.CompanionIntegrationServer
    }
    if ($LASTEXITCODE -ne 0) { throw "Integration process exited with code $LASTEXITCODE" }
} finally { Remove-Item Env:JARVIS_INTEGRATION_PASSWORD,Env:JARVIS_INTEGRATION_TOKEN,Env:JARVIS_INTEGRATION_HISTORY -ErrorAction SilentlyContinue }
