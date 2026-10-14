<#
.SYNOPSIS
Builds and tests Search Enhance offline against an existing IntelliJ IDEA SDK.
.DESCRIPTION
Uses Java 17 and the IDE's local lib directory. Each Java source is passed to
javac separately; javac may resolve dependent sources through -sourcepath.
Every invocation gets a fresh staging directory to avoid stale class files.
No Maven, IDE download, or changes to the installed IDE are needed.
.PARAMETER IdeaHome
Installation directory of IntelliJ IDEA 2023.3.8 (build 233.15619.7).
.PARAMETER JavaHome
Optional complete JDK location. Tries the IDE runtime, local JDK 17, JAVA_HOME,
then the javac on PATH. A runtime without jar.exe is skipped automatically.
.PARAMETER SkipTests
Skips the standalone query regression runner. Normal builds should run tests.
#>
[CmdletBinding()]
param(
    [string] $IdeaHome = 'D:\application\IntelliJ IDEA 2023.3.8',
    [string] $JavaHome,
    [switch] $SkipTests
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-AbsoluteDirectory {
    param([string] $Path, [string] $Label)
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
        throw "$Label directory does not exist: $Path"
    }
    return (Resolve-Path -LiteralPath $Path).ProviderPath
}

function Invoke-NativeChecked {
    param([string] $Executable, [string[]] $NativeArguments)
    & $Executable @NativeArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Native command failed with exit code ${LASTEXITCODE}: $Executable"
    }
}

function Compile-SourcesIndividually {
    param(
        [string] $SourceDirectory,
        [string] $Destination,
        [string] $ClassPath,
        [string] $SourcePath
    )
    $sources = @(Get-ChildItem -LiteralPath $SourceDirectory -Filter '*.java' -File -Recurse |
        Sort-Object FullName)
    if ($sources.Count -eq 0) {
        throw "No Java sources found in $SourceDirectory"
    }
    foreach ($source in $sources) {
        Write-Host ('Checking {0}' -f $source.FullName.Substring($projectRoot.Length + 1))
        $compilerArguments = @(
            '-encoding', 'UTF-8', '--release', '17', '-proc:none',
            '-classpath', $ClassPath, '-sourcepath', $SourcePath,
            '-d', $Destination, $source.FullName
        )
        Invoke-NativeChecked -Executable $javac -NativeArguments $compilerArguments
    }
}

$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).ProviderPath
$IdeaHome = Get-AbsoluteDirectory -Path $IdeaHome -Label 'IDEA SDK'
$productInfoPath = Join-Path $IdeaHome 'product-info.json'
if (-not (Test-Path -LiteralPath $productInfoPath -PathType Leaf)) {
    throw "IDEA SDK product-info.json not found: $productInfoPath"
}
$productInfo = Get-Content -LiteralPath $productInfoPath -Raw -Encoding UTF8 | ConvertFrom-Json
$expectedBuild = '233.15619.7'
if ($productInfo.buildNumber -ne $expectedBuild) {
    throw "Expected IDEA 2023.3.8 build $expectedBuild; found $($productInfo.buildNumber)."
}
if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    $jdkCandidates = @((Join-Path $IdeaHome 'jbr'), 'D:\application\sdk\jdk17')
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $jdkCandidates += $env:JAVA_HOME
    }
    $javacCommand = Get-Command 'javac.exe' -ErrorAction SilentlyContinue
    if ($null -ne $javacCommand) {
        $jdkCandidates += Split-Path -Parent (Split-Path -Parent $javacCommand.Source)
    }
    foreach ($candidate in $jdkCandidates) {
        $missingTools = @('javac.exe', 'java.exe', 'jar.exe') | Where-Object {
            -not (Test-Path -LiteralPath (Join-Path $candidate "bin\$_") -PathType Leaf)
        }
        if (@($missingTools).Count -eq 0) {
            $JavaHome = $candidate
            break
        }
    }
    if ([string]::IsNullOrWhiteSpace($JavaHome)) {
        throw 'A complete JDK 17 is required. Supply -JavaHome with java, javac and jar.'
    }
}
$JavaHome = Get-AbsoluteDirectory -Path $JavaHome -Label 'JDK'
$javac = Join-Path $JavaHome 'bin\javac.exe'
$java = Join-Path $JavaHome 'bin\java.exe'
$jar = Join-Path $JavaHome 'bin\jar.exe'
foreach ($executable in @($javac, $java, $jar)) {
    if (-not (Test-Path -LiteralPath $executable -PathType Leaf)) {
        throw "Required JDK executable not found: $executable"
    }
}
$ideaLibraryDirectory = Join-Path $IdeaHome 'lib'
if (@(Get-ChildItem -LiteralPath $ideaLibraryDirectory -Filter '*.jar' -File).Count -eq 0) {
    throw "IDEA SDK JAR files not found in $ideaLibraryDirectory"
}

$sourceRoot = Join-Path $projectRoot 'src\main\java'
$resourceRoot = Join-Path $projectRoot 'src\main\resources'
$descriptorPath = Join-Path $resourceRoot 'META-INF\plugin.xml'
if (-not (Test-Path -LiteralPath $descriptorPath -PathType Leaf)) {
    throw "Plugin descriptor not found: $descriptorPath"
}
[xml] $pluginDescriptor = Get-Content -LiteralPath $descriptorPath -Raw -Encoding UTF8
$pluginVersion = [string] $pluginDescriptor.'idea-plugin'.version
if ($pluginVersion -notmatch '^\d+\.\d+\.\d+(?:[-.][a-zA-Z0-9]+)*$') {
    throw "Invalid or missing literal plugin version in $descriptorPath"
}

# Unique directories make interrupted and consecutive builds independent.
$buildRoot = Join-Path $projectRoot 'build'
$runName = 'offline-{0}-{1}' -f (Get-Date -Format 'yyyyMMdd-HHmmss'),
    ([guid]::NewGuid().ToString('N').Substring(0, 8))
$runRoot = Join-Path $buildRoot $runName
$classes = Join-Path $runRoot 'classes'
$testClasses = Join-Path $runRoot 'test-classes'
$stagingRoot = Join-Path $runRoot 'stage'
$pluginLibrary = Join-Path $stagingRoot 'search-enhance\lib'
$distributionDirectory = Join-Path $buildRoot 'distributions'
foreach ($directory in @($classes, $testClasses, $pluginLibrary, $distributionDirectory)) {
    $null = New-Item -ItemType Directory -Path $directory -Force
}

Write-Host "SDK: $($productInfo.name) $($productInfo.version) ($($productInfo.buildNumber))"
Invoke-NativeChecked -Executable $javac -NativeArguments @('-version')
$sdkClassPath = Join-Path $ideaLibraryDirectory '*'
$mainClassPath = $classes + [IO.Path]::PathSeparator + $sdkClassPath
Compile-SourcesIndividually -SourceDirectory $sourceRoot -Destination $classes `
    -ClassPath $mainClassPath -SourcePath $sourceRoot

if (-not $SkipTests) {
    $testSourceRoot = Join-Path $projectRoot 'src\test\java'
    $testClassPath = $testClasses + [IO.Path]::PathSeparator + $mainClassPath
    $testSourcePath = $testSourceRoot + [IO.Path]::PathSeparator + $sourceRoot
    Compile-SourcesIndividually -SourceDirectory $testSourceRoot -Destination $testClasses `
        -ClassPath $testClassPath -SourcePath $testSourcePath
    Write-Host 'Running query regression tests...'
    Invoke-NativeChecked -Executable $java -NativeArguments @(
        '-ea', '-Dfile.encoding=UTF-8', '-classpath', $testClassPath,
        'com.zhoucb.searchenhance.query.QueryTestRunner'
    )
}

$pluginJar = Join-Path $pluginLibrary 'search-enhance.jar'
Invoke-NativeChecked -Executable $jar -NativeArguments @(
    '--create', '--file', $pluginJar, '-C', $classes, '.', '-C', $resourceRoot, '.'
)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archiveName = "search-enhance-$pluginVersion.zip"
$temporaryArchive = Join-Path $runRoot $archiveName
[IO.Compression.ZipFile]::CreateFromDirectory(
    $stagingRoot, $temporaryArchive, [IO.Compression.CompressionLevel]::Optimal, $false
)
$distributionPath = Join-Path $distributionDirectory $archiveName
Copy-Item -LiteralPath $temporaryArchive -Destination $distributionPath -Force
$archiveInfo = Get-Item -LiteralPath $distributionPath
$archiveHash = (Get-FileHash -LiteralPath $distributionPath -Algorithm SHA256).Hash
Write-Host "Plugin ZIP: $distributionPath ($($archiveInfo.Length) bytes)"
Write-Host "SHA256: $archiveHash"
Write-Host "Build files: $runRoot"
