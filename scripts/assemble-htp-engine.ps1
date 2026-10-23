# assemble-htp-engine.ps1 - 把 Docker 构建产物组装成 App 的 htp 引擎目录
# 用法: .\scripts\assemble-htp-engine.ps1
# 前提: 先跑过
#   cd llama.cpp; python scripts\snapdragon\build.py --target adb
#   （产物在 llama.cpp\pkg-adb\llama.cpp\{bin,lib}）
#
# 为什么要这一步而不是直接拷：
#   1. 只挑运行时真正需要的库；多带的每一个都会进 APK。
#   2. 必须 strip，否则一个引擎 155 MB。
#   3. 归一到一个扁平目录（和 cpu/opencl/prism 一致），走 LD_LIBRARY_PATH=引擎目录。

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$src  = "$root\llama.cpp\pkg-adb\llama.cpp"
$dst  = "$root\app\src\main\assets\engines\htp"
$strip = "$env:LOCALAPPDATA\Android\Sdk\ndk\27.3.13750724\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strip.exe"

if (-not (Test-Path "$src\bin\llama-server")) { throw "缺少构建产物: $src\bin\llama-server（先跑 build.py）" }
if (-not (Test-Path $strip)) { throw "缺少 llvm-strip: $strip" }

# libggml-opencl.so 必须带：llama-server / libllama / libggml / libmtmd 都 DT_NEEDED 它，
# 缺了会在 exec 阶段直接 "CANNOT LINK EXECUTABLE"。它自己又 NEEDED libOpenCL.so，
# 所以运行时 htp 引擎的 LD_LIBRARY_PATH 也要带 /vendor/lib64。
$libs = @(
    "libggml.so", "libggml-base.so", "libggml-cpu.so", "libggml-hexagon.so", "libggml-opencl.so",
    "libggml-htp-v73.so", "libggml-htp-v75.so", "libggml-htp-v79.so", "libggml-htp-v81.so",
    "libllama.so", "libllama-common.so", "libllama-server-impl.so", "libmtmd.so"
)

New-Item -ItemType Directory -Force -Path $dst | Out-Null
Remove-Item "$dst\*" -Force -ErrorAction SilentlyContinue

Copy-Item "$src\bin\llama-server" "$dst\llama-server" -Force
foreach ($l in $libs) {
    if (-not (Test-Path "$src\lib\$l")) { throw "构建产物里缺少 $l" }
    Copy-Item "$src\lib\$l" "$dst\$l" -Force
}

# strip 主机端 ELF；DSP skel（libggml-htp-*.so）是 Hexagon 侧二进制，不能碰
Get-ChildItem $dst -File | Where-Object { $_.Name -like "*.so" -and $_.Name -notlike "libggml-htp-*" } | ForEach-Object {
    & $strip --strip-unneeded $_.FullName
}

$files = Get-ChildItem $dst -File
Write-Host "== [done] htp -> assets/engines/htp（$($files.Count) 个文件，$([math]::Round(($files | Measure-Object Length -Sum).Sum/1MB,1)) MB）=="
Write-Host "注意: 引擎内容变了要升 BinaryInstaller.VERSION，否则已安装设备不会重新释放。"
