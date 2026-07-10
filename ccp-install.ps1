param(
    [string]$GatewayPluginsPath,
    [switch]$RemoveBackups
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$GradleProperties = Join-Path $ScriptDir "gradle.properties"

if (-not (Test-Path -LiteralPath $GradleProperties)) {
    throw "gradle.properties not found: $GradleProperties"
}

$PluginVersion = Select-String -LiteralPath $GradleProperties -Pattern '^pluginVersion\s*=\s*(.+)$' |
    ForEach-Object { $_.Matches[0].Groups[1].Value.Trim() } |
    Select-Object -First 1

if ([string]::IsNullOrWhiteSpace($PluginVersion)) {
    throw "pluginVersion not found in $GradleProperties"
}

$PluginZip = Join-Path $ScriptDir "build\distributions\devspaces-gateway-plugin-$PluginVersion.zip"

if (-not (Test-Path -LiteralPath $PluginZip)) {
    throw "Plugin ZIP not found: $PluginZip. Run ccp-build.sh first."
}

if ([string]::IsNullOrWhiteSpace($GatewayPluginsPath)) {
    $JetBrainsConfigPath = Join-Path $env:APPDATA "JetBrains"
    if (-not (Test-Path -LiteralPath $JetBrainsConfigPath)) {
        throw "JetBrains config directory not found: $JetBrainsConfigPath"
    }

    $LatestGatewayConfig = Get-ChildItem -LiteralPath $JetBrainsConfigPath -Directory -Filter "JetBrainsGateway*" |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if ($null -eq $LatestGatewayConfig) {
        throw "No JetBrainsGateway* config directory found in: $JetBrainsConfigPath"
    }

    $GatewayPluginsPath = Join-Path $LatestGatewayConfig.FullName "plugins"
    Write-Host "Using latest Gateway config: $($LatestGatewayConfig.FullName)"
}

if (-not (Test-Path -LiteralPath $GatewayPluginsPath)) {
    throw "Gateway plugins directory not found: $GatewayPluginsPath"
}

$OldPlugins = if ($RemoveBackups) {
    Get-ChildItem -LiteralPath $GatewayPluginsPath -Directory -Filter "devspaces-gateway-plugin*"
} else {
    Get-Item -LiteralPath (Join-Path $GatewayPluginsPath "devspaces-gateway-plugin") -ErrorAction SilentlyContinue
}

foreach ($OldPlugin in $OldPlugins) {
    Remove-Item -LiteralPath $OldPlugin.FullName -Recurse -Force
    Write-Host "Removed: $($OldPlugin.FullName)"
}

Expand-Archive -LiteralPath $PluginZip -DestinationPath $GatewayPluginsPath -Force

$InstalledPlugin = Join-Path $GatewayPluginsPath "devspaces-gateway-plugin"
if (-not (Test-Path -LiteralPath $InstalledPlugin)) {
    throw "Plugin was not installed as expected: $InstalledPlugin"
}

Write-Host "Installed: $PluginZip"
Write-Host "To:        $InstalledPlugin"
Write-Host "Restart JetBrains Gateway to load the plugin."
