param([string]$Java = "java", [string]$Jar = "")
$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$credentialsPath = Join-Path $repo "data/companion-integration/credentials.json"
if (-not (Test-Path -LiteralPath $credentialsPath)) { throw "Generate local test credentials per docs/companion/INTEGRATION.md first." }
$credentials = Get-Content -LiteralPath $credentialsPath -Raw | ConvertFrom-Json
$env:JARVIS_INTEGRATION_PASSWORD = $credentials.password
$env:JARVIS_INTEGRATION_TOKEN = $credentials.token
try {
    if ($Jar) { & $Java "-Dloader.main=com.jarvis.integration.CompanionIntegrationServer" -cp $Jar org.springframework.boot.loader.launch.PropertiesLauncher }
    else {
        $classPath = Get-Content -LiteralPath (Join-Path $repo "data/companion-integration/runtime-classpath.txt") -Raw
        & $Java -cp $classPath com.jarvis.integration.CompanionIntegrationServer
    }
} finally { Remove-Item Env:JARVIS_INTEGRATION_PASSWORD,Env:JARVIS_INTEGRATION_TOKEN -ErrorAction SilentlyContinue }
