<#
.SYNOPSIS
Runs the test-only application starter in a fresh IDEA 2023.3.8 sandbox.
.DESCRIPTION
Compiles only src/integrationTest against the built production ZIP. The test
plugin is installed only below build/smoke and never enters the production ZIP.
The independent command initializes Swing offscreen, writes reports and exits.
.PARAMETER IdeaHome
IDEA 2023.3.8 installation directory.
.PARAMETER PluginZip
Production plugin distribution; run scripts/build.ps1 before this command.
.PARAMETER TimeoutSeconds
Maximum lifetime of this script's own isolated test process.
#>
[CmdletBinding()]
param(
    [string] $IdeaHome = 'D:\application\IntelliJ IDEA 2023.3.8',
    [string] $PluginZip,
    [int] $TimeoutSeconds = 180
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).ProviderPath
$IdeaHome = (Resolve-Path -LiteralPath $IdeaHome).ProviderPath
$product = Get-Content -LiteralPath (Join-Path $IdeaHome 'product-info.json') -Raw -Encoding UTF8 |
    ConvertFrom-Json
if ($product.buildNumber -ne '233.15619.7') {
    throw "This integration starter targets IDEA build 233.15619.7; found $($product.buildNumber)."
}
if ([string]::IsNullOrWhiteSpace($PluginZip)) {
    $PluginZip = Join-Path $projectRoot 'build\distributions\search-enhance-1.0.2.zip'
}
$PluginZip = (Resolve-Path -LiteralPath $PluginZip).ProviderPath
$javac = Join-Path $IdeaHome 'jbr\bin\javac.exe'
$launcher = Join-Path $IdeaHome 'jbr\bin\java.exe'
foreach ($tool in @($javac, $launcher)) {
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) {
        throw "Required integration tool missing: $tool"
    }
}
$runName = 'smoke-{0}-{1}' -f (Get-Date -Format 'yyyyMMdd-HHmmss'),
    ([guid]::NewGuid().ToString('N').Substring(0, 8))
$runRoot = Join-Path (Join-Path $projectRoot 'build\smoke') $runName
$reportRoot = Join-Path (Join-Path $projectRoot 'build\reports') $runName
$classes = Join-Path $runRoot 'test-classes'
$plugins = Join-Path $runRoot 'plugins'
$testPluginLibrary = Join-Path $plugins 'search-enhance-smoke\lib'
$paths = [ordered]@{
    'idea.config.path' = Join-Path $runRoot 'config'
    'idea.system.path' = Join-Path $runRoot 'system'
    'idea.plugins.path' = $plugins
    'idea.log.path' = Join-Path $runRoot 'log'
    'search.enhance.smoke.report' = $reportRoot
}
foreach ($directory in @($classes, $testPluginLibrary, $reportRoot) + @($paths.Values)) {
    $null = New-Item -ItemType Directory -Path $directory -Force
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::ExtractToDirectory($PluginZip, $plugins)
$productionJar = Join-Path $plugins 'search-enhance\lib\search-enhance.jar'
if (-not (Test-Path -LiteralPath $productionJar -PathType Leaf)) {
    throw "Production plugin JAR was not found inside $PluginZip"
}
$sourceRoot = Join-Path $projectRoot 'src\integrationTest\java'
$classPath = $productionJar + [IO.Path]::PathSeparator + (Join-Path $IdeaHome 'lib\*')
foreach ($source in Get-ChildItem -LiteralPath $sourceRoot -Filter '*.java' -File -Recurse) {
    & $javac --release 17 -encoding UTF-8 -proc:none -classpath $classPath `
        -sourcepath $sourceRoot -d $classes $source.FullName
    if ($LASTEXITCODE -ne 0) {
        throw "Integration source compilation failed: $($source.FullName)"
    }
}
$testJar = Join-Path $testPluginLibrary 'search-enhance-smoke.jar'
Copy-Item -LiteralPath (Join-Path $projectRoot 'src\integrationTest\resources\META-INF') `
    -Destination (Join-Path $classes 'META-INF') -Recurse
[IO.Compression.ZipFile]::CreateFromDirectory(
    $classes, $testJar, [IO.Compression.CompressionLevel]::Optimal, $false)
$propertyLines = foreach ($entry in $paths.GetEnumerator()) {
    $normalized = $entry.Value.Replace('\', '/')
    $escaped = -join ($normalized.ToCharArray() | ForEach-Object {
        if ([int] $_ -gt 127) { '\u{0:x4}' -f [int] $_ } else { [string] $_ }
    })
    '{0}={1}' -f $entry.Key, $escaped
}
$propertyLines += @(
    'idea.paths.selector=SearchEnhanceSmoke',
    'idea.initially.ask.config=false',
    'idea.accept.eua=true',
    'jb.consents.confirmation.enabled=false',
    'idea.suppress.statistics.report=true'
)
$propertiesPath = Join-Path $runRoot 'idea.properties'
[IO.File]::WriteAllLines($propertiesPath, [string[]] $propertyLines, [Text.UTF8Encoding]::new($false))
$vmOptionsPath = Join-Path $runRoot 'smoke.vmoptions'
$vmOptions = @('-Xms128m', '-Xmx1536m', '-ea', '-Dfile.encoding=UTF-8', '-Djava.awt.headless=false')
$homeForward = $IdeaHome.Replace('\', '/')
$vmOptions += @($product.launch[0].additionalJvmArguments |
    Where-Object { $_ -notmatch '^-Didea.paths.selector=|^-Dsplash=' } |
    ForEach-Object { $_.Replace('%IDE_HOME%', $homeForward) })
$vmOptions += @($paths.GetEnumerator() | ForEach-Object {
    '-D{0}={1}' -f $_.Key, $_.Value.Replace('\', '/')
})
$vmOptions += @('-Didea.paths.selector=SearchEnhanceSmoke', '-Dsplash=false',
    "-Didea.home.path=$homeForward", "-Didea.properties.file=$($propertiesPath.Replace('\', '/'))")
$bootClassPath = @($product.launch[0].bootClassPathJarNames | ForEach-Object {
    Join-Path (Join-Path $IdeaHome 'lib') $_
}) -join [IO.Path]::PathSeparator
$javaArguments = $vmOptions + @('-classpath', $bootClassPath, $product.launch[0].mainClass, 'searchEnhanceSmoke')
# Java's argument-file parser handles each quoted argument itself. Forward
# slashes keep Windows path backslashes from becoming argument-file escapes.
$argumentLines = @($javaArguments | ForEach-Object {
    '"' + $_.Replace('\', '/').Replace('"', '\"') + '"'
})
[IO.File]::WriteAllLines($vmOptionsPath, [string[]] $argumentLines, [Text.UTF8Encoding]::new($false))
$stdout = Join-Path $runRoot 'launcher-stdout.txt'
$stderr = Join-Path $runRoot 'launcher-stderr.txt'
$previousProperties = [Environment]::GetEnvironmentVariable('IDEA_PROPERTIES', 'Process')
$previousVmOptions = [Environment]::GetEnvironmentVariable('IDEA_VM_OPTIONS', 'Process')
try {
    $env:IDEA_PROPERTIES = $propertiesPath
    $env:IDEA_VM_OPTIONS = $vmOptionsPath
    $process = Start-Process -FilePath $launcher -ArgumentList @('@"' + $vmOptionsPath + '"') `
        -WorkingDirectory $IdeaHome -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr
} finally {
    [Environment]::SetEnvironmentVariable('IDEA_PROPERTIES', $previousProperties, 'Process')
    [Environment]::SetEnvironmentVariable('IDEA_VM_OPTIONS', $previousVmOptions, 'Process')
}
Write-Host "Started isolated integration process PID $($process.Id)."
Write-Host "Sandbox: $runRoot"
Write-Host "Reports: $reportRoot"
$deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
while (-not $process.HasExited -and [DateTime]::UtcNow -lt $deadline) {
    Start-Sleep -Milliseconds 250
    $process.Refresh()
}
if (-not $process.HasExited) {
    # This Process object belongs to the instance launched above, never the
    # user's running IDE. Timeout cleanup is confined to this one process.
    $process.Kill()
    $process.WaitForExit()
    throw "Integration test timed out after $TimeoutSeconds seconds. Inspect $runRoot"
}
$resultPath = Join-Path $reportRoot 'smoke-result.txt'
if (-not (Test-Path -LiteralPath $resultPath -PathType Leaf)) {
    throw "IDEA exited ($($process.ExitCode)) without a smoke report. Inspect $runRoot"
}
$result = (Get-Content -LiteralPath $resultPath -Raw -Encoding UTF8).Trim()
Write-Host "Integration result: $result"
Write-Host "Report: $(Join-Path $reportRoot 'smoke-report.md')"
if ($result -ne 'PASS') {
    throw "Integration checks failed. Inspect $reportRoot"
}
