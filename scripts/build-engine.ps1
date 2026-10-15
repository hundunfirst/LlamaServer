# build-engine.ps1 — 一键构建双引擎（CPU + OpenCL）并按运行时布局入包
# 用法: .\scripts\build-engine.ps1
# 说明: 见 docs/ENVIRONMENT.md §五；16KB 页对齐已在链接参数中（发布硬性要求）
param()

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

# 中文 Windows 下 Python 代码生成必须 UTF-8（GBK 会崩）
$env:PYTHONUTF8 = "1"

$cmake   = "$env:LOCALAPPDATA\Android\Sdk\cmake\3.22.1\bin\cmake.exe"
$ninja   = "$env:LOCALAPPDATA\Android\Sdk\cmake\3.22.1\bin\ninja.exe"
$ndk     = "$env:LOCALAPPDATA\Android\Sdk\ndk\27.3.13750724"
$strip   = "$ndk\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strip.exe"
$oc      = "$root\opencl-deps"
$py      = "$env:LOCALAPPDATA\Programs\Python\Python314\python.exe"
$align   = "-Wl,-z,max-page-size=16384"

foreach ($p in @($cmake, $ninja, $strip, $py, "$root\llama.cpp\CMakeLists.txt")) {
    if (-not (Test-Path $p)) { throw "缺少: $p （对照 docs/ENVIRONMENT.md 修正）" }
}

function Build-Engine {
    param([string]$Name, [string[]]$CmakeArgs)
    $build = "$root\llama.cpp\build-android-$Name"
    Write-Host "== [configure] $Name =="
    & $cmake -S "$root\llama.cpp" -B $build -G Ninja `
        "-DCMAKE_MAKE_PROGRAM=$ninja" -DCMAKE_BUILD_TYPE=Release `
        "-DCMAKE_TOOLCHAIN_FILE=$ndk\build\cmake\android.toolchain.cmake" `
        -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 `
        -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_LLAMAFILE=OFF -DLLAMA_OPENSSL=OFF `
        -DGGML_CPU_KLEIDIAI=OFF `
        "-DCMAKE_EXE_LINKER_FLAGS=$align" "-DCMAKE_SHARED_LINKER_FLAGS=$align" `
        @CmakeArgs | Select-Object -Last 3
    if ($LASTEXITCODE -ne 0) { throw "configure failed: $Name" }
    Write-Host "== [build] $Name =="
    & $ninja -C $build llama-server
    if ($LASTEXITCODE -ne 0) { throw "build failed: $Name" }

    $src = "$build\bin"
    Get-ChildItem $src -Filter *.so | ForEach-Object { & $strip --strip-unneeded $_.FullName }
    $dest = "$root\app\src\main\assets\engines\$Name"
    if (Test-Path $dest) { Remove-Item "$dest\*" -Force -ErrorAction SilentlyContinue }
    else { New-Item -ItemType Directory -Force $dest | Out-Null }
    Copy-Item "$src\*" $dest -Force
    # 与现网一致：不打包多指令集变体（armv8.6/armv9 在目标机无增益，见会话记录）
    Remove-Item "$dest\libggml-cpu-android_armv8.6*" -Force -ErrorAction SilentlyContinue
    Remove-Item "$dest\libggml-cpu-android_armv9*" -Force -ErrorAction SilentlyContinue
    Write-Host "== [done] $Name -> assets/engines/$Name ($((Get-ChildItem $dest).Count) files) =="
}

# CPU 引擎（无 OpenCL，纯 CPU 路径 + Mali 等无 OpenCL 机型可用）
Build-Engine -Name "cpu" -CmakeArgs @("-DGGML_OPENCL=OFF", "-DGGML_BACKEND_DL=OFF", "-DGGML_CPU_ALL_VARIANTS=OFF")

# OpenCL 引擎（GPU 路径，ngl>0 时使用）
if (-not (Test-Path "$oc\icd-install\lib\libOpenCL.so")) {
    Write-Host "== [icd] 构建 OpenCL ICD Loader =="
    & $cmake -S "$oc\OpenCL-ICD-Loader" -B "$oc\icd-build" -G Ninja `
        "-DCMAKE_MAKE_PROGRAM=$ninja" -DCMAKE_BUILD_TYPE=Release `
        "-DCMAKE_TOOLCHAIN_FILE=$ndk\build\cmake\android.toolchain.cmake" `
        -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 `
        -DOPENCL_ICD_LOADER_HEADERS_DIR="$oc\OpenCL-Headers" `
        "-DCMAKE_INSTALL_PREFIX=$oc\icd-install" | Out-Null
    & $cmake --build "$oc\icd-build" | Out-Null
    & $cmake --install "$oc\icd-build" | Out-Null
}
Build-Engine -Name "opencl" -CmakeArgs @(
    "-DGGML_OPENCL=ON", "-DGGML_BACKEND_DL=ON", "-DGGML_CPU_ALL_VARIANTS=OFF",
    "-DOpenCL_INCLUDE_DIR=$oc\OpenCL-Headers",
    "-DOpenCL_LIBRARY=$oc\icd-install\lib\libOpenCL.so",
    "-DPython3_EXECUTABLE=$py"
)

Write-Host ""
Write-Host "完成。下一步: .\gradlew.bat assembleDebug （或 assembleRelease）"
Write-Host "注意: BinaryInstaller 版本号不变时，已安装设备需卸载重装或改 VERSION 才会重释放引擎。"
