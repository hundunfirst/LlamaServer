# Build the shell-side helper into a standalone dex at app/src/main/assets/agent-shell.dex
#
# Why a separate dex: this class is loaded by `app_process` under the shell uid.
# Loading the APK's own dex only exposes classes.dex, so a helper class that lands
# in classes2.dex would not be found.
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts\build-agent-shell.ps1
# Re-run after editing AgentShell.java and commit the generated dex.
#
# Keep this file ASCII-only: Windows PowerShell 5.1 reads it as ANSI and a
# non-ASCII comment can break parsing.

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$src = Join-Path $root 'app\src\main\java\com\localllm\server\helper\AgentShell.java'
$outDir = Join-Path $root 'build\agent-shell'
$dexDir = Join-Path $root 'build\agent-shell-dex'
$assetsDir = Join-Path $root 'app\src\main\assets'
$dexOut = Join-Path $assetsDir 'agent-shell.dex'

$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$androidJar = Get-ChildItem (Join-Path $sdk 'platforms') -Directory |
    Sort-Object Name -Descending | Select-Object -First 1 |
    ForEach-Object { Join-Path $_.FullName 'android.jar' }
$d8 = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory |
    Sort-Object Name -Descending | Select-Object -First 1 |
    ForEach-Object { Join-Path $_.FullName 'd8.bat' }
if (-not (Test-Path $androidJar)) { throw "android.jar not found: $androidJar" }
if (-not (Test-Path $d8)) { throw "d8 not found: $d8" }

$javac = 'javac'
if ($env:JAVA_HOME) { $javac = Join-Path $env:JAVA_HOME 'bin\javac.exe' }

if (Test-Path $outDir) { Remove-Item $outDir -Recurse -Force }
if (Test-Path $dexDir) { Remove-Item $dexDir -Recurse -Force }
New-Item -ItemType Directory -Path $outDir | Out-Null
New-Item -ItemType Directory -Path $dexDir | Out-Null
New-Item -ItemType Directory -Path $assetsDir -Force | Out-Null

& $javac -source 8 -target 8 -nowarn -bootclasspath $androidJar -d $outDir $src
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

$classes = @(Get-ChildItem $outDir -Recurse -Filter *.class | ForEach-Object { $_.FullName })
# d8 --output takes a directory or a .zip/.jar; a .dex filename would be read as a directory
& $d8 --release --min-api 28 --lib $androidJar --output $dexDir @classes
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

$dexSrc = Join-Path $dexDir 'classes.dex'
if (-not (Test-Path $dexSrc)) { throw "d8 produced no classes.dex in $dexDir" }
Copy-Item $dexSrc $dexOut -Force

$size = (Get-Item $dexOut).Length
Write-Host "built $dexOut ($size bytes)"
