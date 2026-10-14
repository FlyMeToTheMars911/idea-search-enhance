<#
.SYNOPSIS
Launches an independent IDEA sandbox containing the built Search Enhance ZIP.
.DESCRIPTION
Creates fresh config, system, plugins and log directories on every invocation.
The installed IDE and the user's existing IDEA profile are never modified.
Use -PrepareOnly to inspect the sandbox without starting a GUI process.
.PARAMETER IdeaHome
Installation directory of the IDEA runtime used to launch the sandbox.
.PARAMETER PluginZip
Plugin distribution to unpack. Defaults to the local 1.0.2 distribution.
.PARAMETER ProjectPath
Optional project directory to open in the independent IDEA instance.
#>
[CmdletBinding()]
param(
    [string] $IdeaHome = 'D:\application\IntelliJ IDEA 2023.3.8',
    [string] $PluginZip,
    [string] $ProjectPath,
    [switch] $PrepareOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).ProviderPath
if ([string]::IsNullOrWhiteSpace($PluginZip)) {
    $PluginZip = Join-Path $projectRoot 'build\distributions\search-enhance-1.0.2.zip'
}
foreach ($requiredPath in @($IdeaHome, $PluginZip)) {
    if (-not (Test-Path -LiteralPath $requiredPath)) {
        throw "Required path does not exist: $requiredPath. Run scripts/build.ps1 first."
    }
}
$IdeaHome = (Resolve-Path -LiteralPath $IdeaHome).ProviderPath
$PluginZip = (Resolve-Path -LiteralPath $PluginZip).ProviderPath
$product = Get-Content -LiteralPath (Join-Path $IdeaHome 'product-info.json') `
    -Raw -Encoding UTF8 | ConvertFrom-Json
if ($product.buildNumber -ne '233.15619.7') {
    throw "This sandbox targets IDEA build 233.15619.7; found $($product.buildNumber)."
}
$launchConfiguration = $product.launch[0]
$launcher = Join-Path $IdeaHome $launchConfiguration.javaExecutablePath
if (-not (Test-Path -LiteralPath $launcher -PathType Leaf)) {
    throw "IDEA launcher does not exist: $launcher"
}
if (-not [string]::IsNullOrWhiteSpace($ProjectPath)) {
    $ProjectPath = (Resolve-Path -LiteralPath $ProjectPath).ProviderPath
}

$runName = 'run-{0}-{1}' -f (Get-Date -Format 'yyyyMMdd-HHmmss'),
    ([guid]::NewGuid().ToString('N').Substring(0, 8))
$sandboxRoot = Join-Path (Join-Path $projectRoot 'build\sandbox') $runName
$sandboxPaths = [ordered]@{
    'idea.config.path' = Join-Path $sandboxRoot 'config'
    'idea.system.path' = Join-Path $sandboxRoot 'system'
    'idea.plugins.path' = Join-Path $sandboxRoot 'plugins'
    'idea.log.path' = Join-Path $sandboxRoot 'log'
}
foreach ($directory in $sandboxPaths.Values) {
    $null = New-Item -ItemType Directory -Path $directory -Force
}

# Properties are ASCII with Unicode escapes, so non-ASCII Windows paths work
# regardless of which Java properties loader an IDEA launcher version uses.
$propertyLines = foreach ($entry in $sandboxPaths.GetEnumerator()) {
    $normalizedPath = $entry.Value.Replace('\', '/')
    $escapedPath = -join ($normalizedPath.ToCharArray() | ForEach-Object {
        if ([int] $_ -gt 127) { '\u{0:x4}' -f [int] $_ } else { [string] $_ }
    })
    '{0}={1}' -f $entry.Key, $escapedPath
}
$propertyLines += 'idea.paths.selector=SearchEnhanceSandbox'
$propertiesPath = Join-Path $sandboxRoot 'idea.properties'
[IO.File]::WriteAllLines($propertiesPath, [string[]] $propertyLines,
    (New-Object Text.UTF8Encoding($false)))

# Launch the SDK's Java entry point directly. On this installation the native
# launcher can return without starting the independent sandbox process.
$homeForward = $IdeaHome.Replace('\', '/')
$vmOptions = @('-Xms128m', '-Xmx1536m', '-Dfile.encoding=UTF-8', '-Djava.awt.headless=false')
$vmOptions += @($launchConfiguration.additionalJvmArguments |
    Where-Object { $_ -notmatch '^-Didea.paths.selector=|^-Dsplash=' } |
    ForEach-Object { $_.Replace('%IDE_HOME%', $homeForward) })
foreach ($entry in $sandboxPaths.GetEnumerator()) {
    $vmOptions += '-D{0}={1}' -f $entry.Key, $entry.Value.Replace('\', '/')
}
$vmOptions += @(
    '-Didea.paths.selector=SearchEnhanceSandbox', '-Dsplash=false',
    "-Didea.home.path=$homeForward",
    "-Didea.properties.file=$($propertiesPath.Replace('\', '/'))"
)
$vmOptionsPath = Join-Path $sandboxRoot 'sandbox.vmoptions'
[IO.File]::WriteAllLines($vmOptionsPath, [string[]] $vmOptions,
    (New-Object Text.UTF8Encoding($false)))
$bootClassPath = @($launchConfiguration.bootClassPathJarNames | ForEach-Object {
    $sdkJar = Join-Path (Join-Path $IdeaHome 'lib') $_
    if (-not (Test-Path -LiteralPath $sdkJar -PathType Leaf)) {
        throw "IDEA boot classpath JAR does not exist: $sdkJar"
    }
    $sdkJar
}) -join [IO.Path]::PathSeparator
$javaArguments = $vmOptions + @('-classpath', $bootClassPath, $launchConfiguration.mainClass)
if (-not [string]::IsNullOrWhiteSpace($ProjectPath)) {
    $javaArguments += $ProjectPath
}
# Quoting belongs in the Java argument file, avoiding the native command line
# length limit and preserving spaces and Unicode in SDK/project paths.
$argumentLines = @($javaArguments | ForEach-Object {
    '"' + $_.Replace('\', '/').Replace('"', '\"') + '"'
})
$argumentFile = Join-Path $sandboxRoot 'launch.args'
[IO.File]::WriteAllLines($argumentFile, [string[]] $argumentLines,
    (New-Object Text.UTF8Encoding($false)))

# This directory is new for every run; extraction cannot retain an old plugin.
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::ExtractToDirectory($PluginZip, $sandboxPaths['idea.plugins.path'])
Write-Host "Sandbox: $sandboxRoot"
Write-Host "Properties: $propertiesPath"
Write-Host "Java arguments: $argumentFile"
if ($PrepareOnly) {
    Write-Host 'Prepared only; IDEA was not started.'
    return
}

$previousProperties = [Environment]::GetEnvironmentVariable('IDEA_PROPERTIES', 'Process')
$previousVmOptions = [Environment]::GetEnvironmentVariable('IDEA_VM_OPTIONS', 'Process')
try {
    # Only this PowerShell process and its child inherit the temporary value.
    $env:IDEA_PROPERTIES = $propertiesPath
    $env:IDEA_VM_OPTIONS = $vmOptionsPath
    $process = Start-Process -FilePath $launcher -ArgumentList @('@"' + $argumentFile + '"') `
        -WorkingDirectory $IdeaHome -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $sandboxRoot 'launcher-stdout.txt') `
        -RedirectStandardError (Join-Path $sandboxRoot 'launcher-stderr.txt')
    Write-Host "Started independent IDEA Java process (PID $($process.Id))."
} finally {
    [Environment]::SetEnvironmentVariable('IDEA_PROPERTIES', $previousProperties, 'Process')
    [Environment]::SetEnvironmentVariable('IDEA_VM_OPTIONS', $previousVmOptions, 'Process')
}
