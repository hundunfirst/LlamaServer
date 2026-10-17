# fetch-prism-engine.ps1 — 拉取 PrismML fork 的 Android arm64 引擎，瘦身后放进 assets/engines/prism
# 用法: .\scripts\fetch-prism-engine.ps1
# 说明: 这套引擎支持 PQ2_0 / PTQ1_0 等 PrismML 打包（上游 llama.cpp 不认），只有纯 CPU 模式会用到；
#       assets/engines/ 不入库，所以在别的机器上构建前需要先跑一次本脚本。
param(
    [string]$Release = "prism-b10777-e3de715",
    [string]$Mirror  = "https://gh-proxy.com/"
)

$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot
$work = "$root\dist\prism-engine"
$tar  = "$work\android-arm64.tar.gz"
$dest = "$root\app\src\main\assets\engines\prism"
$ndk  = "$env:LOCALAPPDATA\Android\Sdk\ndk\27.3.13750724"
$strip = "$ndk\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strip.exe"

# 只需要这些：server 壳 + 全部 llama/ggml 运行库 + 两个 CPU 变体（运行时 dlopen 选最优）
$keep = @(
    "llama-server",
    "libllama-server-impl.so", "libllama-common.so", "libllama.so", "libmtmd.so",
    "libggml.so", "libggml-base.so",
    "libggml-cpu-android_armv8.0_1.so",
    "libggml-cpu-android_armv9.2_2.so"
)

New-Item -ItemType Directory -Force $work | Out-Null
if (-not (Test-Path $tar)) {
    $url = "$Mirror" + "https://github.com/PrismML-Eng/llama.cpp/releases/download/$Release/llama-$Release-bin-android-arm64.tar.gz"
    Write-Host "== [download] $url =="
    & curl.exe -L --fail -o $tar $url
    if ($LASTEXITCODE -ne 0) { throw "下载失败（GitHub 直连很慢，换 -Mirror 前缀再试）" }
}

$srcDir = "$work\extract"
if (Test-Path $srcDir) { Remove-Item $srcDir -Recurse -Force }
New-Item -ItemType Directory -Force $srcDir | Out-Null
& tar -xzf $tar -C $srcDir
if ($LASTEXITCODE -ne 0) { throw "解压失败: $tar" }

$inner = Get-ChildItem $srcDir -Directory | Select-Object -First 1
if (-not $inner) { throw "压缩包里没有目录" }

if (Test-Path $dest) { Remove-Item "$dest\*" -Force }
else { New-Item -ItemType Directory -Force $dest | Out-Null }

foreach ($f in $keep) {
    $p = Join-Path $inner.FullName $f
    if (-not (Test-Path $p)) { Write-Host "  [skip] 缺少 $f"; continue }
    Copy-Item $p $dest -Force
}
Get-ChildItem $dest -Filter *.so | ForEach-Object { & $strip --strip-unneeded $_.FullName }

$mb = [math]::Round(((Get-ChildItem $dest | Measure-Object Length -Sum).Sum) / 1MB, 1)
Write-Host "== [done] assets/engines/prism: $mb MB, $((Get-ChildItem $dest).Count) 个文件 =="
Write-Host "注意: 换引擎后要改 BinaryInstaller.VERSION 才会在已装设备上重新释放。"
