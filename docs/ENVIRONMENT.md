# 开发环境与依赖版本（ENVIRONMENT）

> 升级任何一项后，**同步更新本文档对应行**——它是环境的唯一事实源。

## 一、宿主机

| 项 | 版本/路径 | 备注 |
|---|---|---|
| OS | Windows（中文区域） | **GBK 是多个坑的根源**，见 TROUBLESHOOTING |
| JDK | Java 23（系统 PATH 的 `java`） | Gradle 9 + AGP 9 需 17+ |
| C/C++ 主机编译器 | **无**（无 VS/MinGW/Clang） | 故 Vulkan 构建（需主机工具链）暂不可行，见 UPGRADING 文末 backlog |
| Python | 3.14 `C:\Users\mingliang\AppData\Local\Programs\Python\Python314` | OpenCL 后端代码生成必需；**构建必须设 `PYTHONUTF8=1`** |
| Git | 2.56 | GitHub git 协议可达；**release CDN 限速 ~31KB/s** |

## 二、Android 工具链

| 项 | 版本 |
|---|---|
| Android SDK | `C:\Users\mingliang\AppData\Local\Android\Sdk` |
| platforms | android-35、android-36 |
| build-tools | 35.0.0、36.0.0 |
| **NDK** | **27.3.13750724**（另有 27.0.12077973，构建用 27.3） |
| CMake + Ninja | SDK 自带 3.22.1：`Sdk\cmake\3.22.1\bin\{cmake,ninja}.exe`（不在 PATH，脚本内用全路径） |
| glslc（Vulkan 备用） | `Sdk\ndk\27.3.13750724\shader-tools\windows-x86_64\glslc.exe` |

## 三、Gradle / AGP（对齐 AuroraMusic 的成熟配方）

| 项 | 版本 |
|---|---|
| Gradle（wrapper） | 9.1.0 |
| AGP | 9.0.1（内置 Kotlin，**没有** kotlin-android 插件，只加 compose 插件） |
| Kotlin Compose 插件 | 2.3.20 |
| Compose BOM | 2026.03.01 |
| compileSdk / minSdk / **targetSdk** | 36 / 31 / **28（不可轻动，见 ARCHITECTURE）** |
| JVM target | 17 |

## 四、引擎依赖（llama.cpp 生态）

| 依赖 | 版本/commit | 获取方式 |
|---|---|---|
| **llama.cpp** | **620d2225c**（v0.7.0，2026-10-15，ggml 0.27.0） | 浅克隆于 `llama.cpp/`，已 checkout 到 tag `v0.7.0` |
| OpenCL-Headers | 30bc20a | 浅克隆于 `opencl-deps/OpenCL-Headers` |
| OpenCL-ICD-Loader | 5192c84 | 浅克隆于 `opencl-deps/OpenCL-ICD-Loader`，NDK 交叉编译产出 `libOpenCL.so`（仅链接用，不进 APK） |
| 引擎产物 | strip 后：cpu 14.7MB（8 个文件）/ opencl 18.2MB（9 个文件） | 见下方构建命令，`scripts/build-engine.ps1` 同步产出到 `app/src/main/assets/engines/{cpu,opencl}` |

> llama.cpp 无独立版本号，**以 commit 为准**；升级后回填此表。

## 五、引擎构建命令（权威副本）

### 5.1 OpenCL 版（当前 APK 使用的产物）

```powershell
$env:PYTHONUTF8 = "1"    # 必须！否则中文 Windows 下 GBK 解码崩溃
$cmake = "C:\Users\mingliang\AppData\Local\Android\Sdk\cmake\3.22.1\bin\cmake.exe"
$ninja = "C:\Users\mingliang\AppData\Local\Android\Sdk\cmake\3.22.1\bin\ninja.exe"
$ndk   = "C:\Users\mingliang\AppData\Local\Android\Sdk\ndk\27.3.13750724"
$root  = "C:\Users\mingliang\XiaomiMiMoProjects\LlamaServer"
$oc    = "$root\opencl-deps"
$align = "-Wl,-z,max-page-size=16384"   # 16KB 页对齐：EXE/SHARED/MODULE 三个都要给（见 TROUBLESHOOTING #28）

# (a) 若 OpenCL ICD 尚未构建：
& $cmake -S "$oc\OpenCL-ICD-Loader" -B "$oc\icd-build" -G Ninja `
  "-DCMAKE_MAKE_PROGRAM=$ninja" -DCMAKE_BUILD_TYPE=Release `
  "-DCMAKE_TOOLCHAIN_FILE=$ndk\build\cmake\android.toolchain.cmake" `
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 `
  -DOPENCL_ICD_LOADER_HEADERS_DIR="$oc\OpenCL-Headers" `
  "-DCMAKE_INSTALL_PREFIX=$oc\icd-install"
& $cmake --build "$oc\icd-build"
& $cmake --install "$oc\icd-build"

# (b) 引擎主体：
& $cmake -S "$root\llama.cpp" -B "$root\llama.cpp\build-android-opencl" -G Ninja `
  "-DCMAKE_MAKE_PROGRAM=$ninja" -DCMAKE_BUILD_TYPE=Release `
  "-DCMAKE_TOOLCHAIN_FILE=$ndk\build\cmake\android.toolchain.cmake" `
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 `
  -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_LLAMAFILE=OFF -DLLAMA_OPENSSL=OFF `
  -DGGML_CPU_KLEIDIAI=OFF -DGGML_OPENCL=ON `
  "-DCMAKE_EXE_LINKER_FLAGS=$align" "-DCMAKE_SHARED_LINKER_FLAGS=$align" "-DCMAKE_MODULE_LINKER_FLAGS=$align" `
  "-DOpenCL_INCLUDE_DIR=$oc\OpenCL-Headers" `
  "-DOpenCL_LIBRARY=$oc\icd-install\lib\libOpenCL.so" `
  "-DPython3_EXECUTABLE=C:\Users\mingliang\AppData\Local\Programs\Python\Python314\python.exe"
& $ninja -C "$root\llama.cpp\build-android-opencl" llama-server
```

### 5.2 产物入包（strip → 拷贝）

当前运行时布局是 `assets/engines/{cpu,opencl}/`（由 `BinaryInstaller` 首启释放到 `files/engines/{mode}/`，按 ngl 自动选）。
`scripts/build-engine.ps1` 已包含下面全部步骤，手改时照抄：

```powershell
$strip = "$ndk\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strip.exe"
foreach ($mode in @("cpu","opencl")) {
  $src = "$root\llama.cpp\build-android-$mode\bin"
  Get-ChildItem $src -Filter *.so | ForEach-Object { & $strip --strip-unneeded $_.FullName }
  $dest = "$root\app\src\main\assets\engines\$mode"
  Remove-Item "$dest\*" -Force -ErrorAction SilentlyContinue
  Copy-Item "$src\*" $dest -Force
  Remove-Item "$dest\libggml-cpu-android_armv8.6*","$dest\libggml-cpu-android_armv9*" -Force -ErrorAction SilentlyContinue
}
```

改完引擎必须**同时**把 `core/BinaryInstaller.kt` 的 `VERSION` 加一，否则已装设备不会重新释放引擎。

### 5.3 APK

```powershell
cd $root
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## 六、参考机型（开发与验收基线）

| 项 | 值 |
|---|---|
| 机型 | 小米 17 Pro Max（2509FPN0BC），16GB + 512GB |
| 系统 | **Android 17 / API 37**（HyperOS OS4.0.0.44） |
| SoC GPU | 骁龙旗舰，**Adreno 840**（OpenCL 验证平台） |
| 开发选项 | 无线调试已开；**「USB 调试（安全设置）」已开**（否则 adb 无法注入点击） |

## 七、数据源约定

- 模型下载一律走 **https://hf-mirror.com/**（HuggingFace 官方站国内不可用）
- 真机模型预置：`adb push` 到 `/data/local/tmp` → `adb shell run-as com.localllm.server cp ...`（APP 私有目录只能这样写入）
- 真机自动化：点击用 `uiautomator dump` 取坐标 + `input tap`（详见 TROUBLESHOOTING #11–13）
