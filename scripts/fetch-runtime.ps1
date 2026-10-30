# fetch-runtime.ps1 - 重建 Linux 运行环境资产（rootfs.zip / node-arm64.zip）进 assets/runtime/
# 用法: .\scripts\fetch-runtime.ps1
# 说明: 这两个 zip（共约 90 MB）不入库；换了新机器/清掉后跑一次本脚本即可。
#       来源与 DSH Go 同源：清华镜像 ubuntu-base（arm64）+ npmmirror node（arm64）。
#       重打成 zip 是因为 App 侧用 java.util.zip 解压（标准库，不引 tar/xz 依赖）。
#       另需 proot 4 库在 jniLibs（从 Termux 仓库取，见 dist/termux 与 LinuxRuntime.kt 注释）。

param(
    [string]$UbuntuVer = "24.04.5",
    [string]$NodeVer    = "24.22.1",
    [string]$Work       = "$PSScriptRoot\..\dist\runtime"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$assets = "$root\app\src\main\assets\runtime"
New-Item -ItemType Directory -Force -Path $Work, $assets | Out-Null

# ---- 1. rootfs：清华 ubuntu-base arm64 → zip ----
$rootfsTar = "$Work\ubuntu-base-$UbuntuVer-base-arm64.tar.gz"
if (-not (Test-Path $rootfsTar)) {
    Write-Host "== [download] ubuntu-base $UbuntuVer arm64 =="
    curl.exe -L --fail -o $rootfsTar "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/$UbuntuVer/release/ubuntu-base-$UbuntuVer-base-arm64.tar.gz"
}
$rootfsDir = "$Work\rootfs"
if (Test-Path $rootfsDir) { Remove-Item $rootfsDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $rootfsDir | Out-Null
tar -xzf $rootfsTar -C $rootfsDir
Compress-Archive -Path "$rootfsDir\*" -DestinationPath "$assets\rootfs.zip" -CompressionLevel Optimal
Write-Host "== [done] assets/runtime/rootfs.zip =="

# ---- 2. node：npmmirror arm64 tar.xz → usr/local 前缀 → zip ----
$nodeTar = "$Work\node-v$NodeVer-linux-arm64.tar.xz"
if (-not (Test-Path $nodeTar)) {
    Write-Host "== [download] node v$NodeVer arm64 =="
    curl.exe -L --fail -o $nodeTar "https://npmmirror.com/mirrors/node/v$NodeVer/node-v$NodeVer-linux-arm64.tar.xz"
}
$nodeDir = "$Work\node-v$NodeVer-linux-arm64"
if (Test-Path $nodeDir) { Remove-Item $nodeDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $nodeDir | Out-Null
tar -xJf $nodeTar -C $Work

# Compress-Archive 不能自定义条目前缀，这里手工建 zip（usr/local/ 前缀，App 解压进 rootfs 即得 /usr/local）
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
if (Test-Path "$assets\node-arm64.zip") { Remove-Item "$assets\node-arm64.zip" -Force }
$zip = [System.IO.Compression.ZipFile]::Open("$assets\node-arm64.zip", "Create")
try {
    Get-ChildItem -Recurse -File $nodeDir | ForEach-Object {
        $rel = $_.FullName.Substring($nodeDir.FullName.Length).TrimStart('\').Replace('\','/')
        $entry = $zip.CreateEntry("usr/local/$rel", [System.IO.Compression.CompressionLevel]::Optimal)
        $es = $entry.Open()
        try { $fs = [System.IO.File]::OpenRead($_.FullName); $fs.CopyTo($es) } finally { if ($fs) { $fs.Dispose() } }
        $es.Dispose()
    }
} finally {
    $zip.Dispose()
}
Write-Host "== [done] assets/runtime/node-arm64.zip =="
Get-ChildItem $assets | Select-Object Name, Length
