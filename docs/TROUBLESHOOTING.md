# 排障手册（TROUBLESHOOTING）

> 按"症状 → 根因 → 解法"组织。**修复已固化进代码的条目标注 [已根治]**，改动相关代码前先读它们。

## A. APP / 服务类

### #1 启动服务 5 秒后闪退 `ForegroundServiceDidNotStartInTimeException` [已根治]
- **根因**：`startForegroundService()` 后未及时 `startForeground()`。历史触发点有两个：状态收集器抢在 `onStartCommand` 前收到初始 STOPPED 执行了 `stopSelf()`；模型为空分支未先 startForeground。
- **解法（勿回退）**：`ServerService` 的 `startedForeground` 门闩 + 任何分支先 `startForeground`。

### #2 点「停止服务」闪退 `InterruptedIOException: read interrupted by close()` [已根治]
- **根因**：`destroy()` 关闭管道，读日志协程异常未捕获 → 崩进程（dropbox 曾记录 8 次）。
- **解法（勿回退）**：reader 协程 try/catch；`stop()` 先置 STOPPED 再 destroy（防读线程把状态改成 ERROR）。

### #3 启动报 `Permission denied (error=13)` 执行二进制
- **根因**：Android 高 targetSdk 的 SELinux 域（untrusted_app_34）禁止 exec app 数据文件。
- **解法（勿回退）**：`targetSdk = 28`（`build.gradle.kts` 有注释保护）。**提升 targetSdk 前必须先换掉 exec 方案**（JNI/fork 或进程内 llma_server）。

### #4 `CANNOT LINK EXECUTABLE: library "libOpenCL.so" not found`
- **根因**：`libggml-opencl.so` 的 NEEDED 在默认 linker 命名空间搜不到（/vendor 不在默认搜索路径）。
- **解法（勿回退）**：LD_LIBRARY_PATH 三段式：`files/native`（系统 libc++ 副本）`: /vendor/lib64` `: jniLibs`。顺序不能改——把 /vendor 放最前会用 vendor libc++ 遮蔽系统版，引出 #5。

### #5 `cannot locate symbol "__hash_memory" referenced by liblog.so`
- **根因**：vendor（VNDK）libc++ 与系统 libc++ 不同版本，系统库符号解析失败。
- **解法**：见 #4 的 `files/native/libc++.so`（系统 libc++ 副本，`BinaryInstaller` 自动复制）。

### #6 `ggml_opencl: platform IDs not available` / GPU 不工作
- 依次检查：
  1. 是否把**复制的** libOpenCL 放进 files/native 遮蔽了 vendor 原库（其内部绝对路径加载 Adreno 实现会失败）→ `BinaryInstaller` 已有清理逻辑；
  2. 验证 GPU 是否真工作：`adb shell run-as com.localllm.server grep -e kgsl -e adreno /proc/<llama-pid>/maps`（有映射 = 真在用 GPU）；
  3. 设备本身无 OpenCL（部分 Mali）→ 属正常降级：ngl=0 走 CPU 即可，**不会崩**（opencl 后端是按需加载的独立 .so）。

### #7 多模态启动报 `mismatch between text model (n_embd = X) and mmproj (n_embd = Y)`
- **根因**：mmproj 与主模型不同架构，配对错误。
- **解法**：模型页下方重新选配（或「不使用多模态」）。找对的 mmproj：与主模型**同一仓库/同一发布**的配套文件。

### #8 多模态返回乱码（如「邴宰wend…」）
- **根因**：聊天模板未生效。
- **解法**：`--jinja` 必须开启（`buildCommand` 已默认加）。若仍乱码 → 模型转换质量问题，换可靠量化源。

### #9 VLM 短答案尾部重复（「红色婉婉婉…」）
- 已知采样问题，非链路故障。临时：设置里投机解码改 `none`；或调客户端 temperature。

## B. 构建类

### #19 性能测量教训 + 双引擎（已定稿）[结论已修正]
- **教训（代价高昂）**：在手机上做 A/B 时，**同框运行多个 llama 实例会把测量彻底污染**（gen 从 110 掉到 20–40 都出现过）。唯一可信方法：`killall llama-server` → 静置 60s → 单实例 3 连测。
- **修正结论**：干净条件下，M0 旧构建、新 CPU 引擎性能同级（~100–120 tok/s）。此前"OpenCL 拖慢 CPU"的结论**证据被污染，已撤销**。
- **双引擎保留**（`engines/cpu` 与 `engines/opencl`，按 ngl 自动选择）——理由改为**兼容性**：无 OpenCL 的设备（部分 Mali）走 CPU 引擎可完整可用；有 OpenCL 的设备 ngl>0 走 GPU 引擎。
- CPU 引擎的 LD 路径**不含** `/vendor/lib64`（避免 VNDK 库遮蔽系统库）；OpenCL 引擎才附加 vendor 路径。
- **绑定地址规则**：局域网模式 `--host0.0.0.0`（必须！绑定具体 IP 会排除回环，手机本机客户端连不上）；状态页展示用真实 wlan IP。

### #21 GitHub 国内拉取慢/失败（镜像方案）[网络]
- **症状**：git clone / release 下载 / CMake FetchContent 限速（实测 ~31KB/s）或中断。
- **对策**：
  - 仓库克隆：`git clone https://ghfast.top/https://github.com/<org>/<repo>.git`（或 gh-proxy.com 等公共加速前缀；稳定性自行验证，勿用于可信发布链路）
  - Release 文件：同理在 URL 前加镜像前缀下载
  - CMake FetchContent（如 KleidiAI）：先用镜像手动下载源码包并解压，再 configure 时加
    `-DFETCHCONTENT_SOURCE_DIR_<NAME>=<本地解压目录>`，完全绕过在线拉取
  - 长期方案：依赖走 CI（GitHub Actions 境内访问国际网络正常），产物回传

### #10 OpenCL 后端编译 `UnicodeDecodeError: 'gbk'`
- **根因**：中文 Windows Python 默认 GBK 读内核源码。
- **解法**：构建前 `$env:PYTHONUTF8 = "1"`。

### #11 KleidiAI 等 `FetchContent` 下载失败
- **根因**：GitHub release CDN 限速/中断。
- **解法**：`-DGGML_CPU_KLEIDIAI=OFF`（性能损失有限，属可选项）；或代理/预下载依赖包。

## C. adb / 真机自动化类

### #12 `input tap` 报 `INJECT_EVENTS` SecurityException
- 开发者选项打开 **「USB 调试（安全设置）」**（需登录小米账号）。

### #13 `pm grant` 报 `GRANT_RUNTIME_PERMISSIONS` 拒绝
- 本机限制，shell 无法授通知权限。**无需处理**：targetSdk 28 下运行时权限安装即授予。

### #14 多显示器 `screencap` 截错屏
- 先 `adb shell dumpsys SurfaceFlinger --display-id`，用主屏 id：`screencap -d 4630946457447247251 -p ...`（本机主屏；重连后可能变）。

### #15 找控件坐标
```powershell
adb shell uiautomator dump /data/local/tmp/ui.xml
adb pull /data/local/tmp/ui.xml ui.xml
$raw = Get-Content ui.xml -Raw -Encoding UTF8    # 必须 UTF8，否则中文变乱码致 XML 解析失败
# 正则取 bounds 后，[int] 强转再算中心——直接字符串相加会变成 "120"+"306"="120306"
```

### #16 Windows 侧 PowerShell / curl 坑速查
| 坑 | 对策 |
|---|---|
| `curl` 是 Invoke-WebRequest 别名 | 一律 `curl.exe` |
| `-d '{json}'` 引号被吃成解析错误 | JSON 写入临时文件，`--data-binary "@file"` |
| PNG 尺寸 `-shl/-bor` 位运算解析错 | 用乘法：`[int]$b[16]*16777216+...` |
| adb 内嵌双引号在 Windows 被剥掉 | 设备端命令避免内层双引号：用 `grep -e A -e B` 替代 `grep "A\|B"` |
| `logcat -s Tag:` 报 invalid filter | 写全优先级：`-s ServerProcess:D` |
| 日志行莫名变少 | logd chatty 合并相似行；完整回显看启动时打印的完整命令 |
| 带中文的 `.ps1` 报「字符串缺少终止符」等诡异语法错 | PowerShell 5.1 按 GBK 解析无 BOM 的 UTF-8 脚本；**存成 UTF-8 with BOM**（`build-engine.ps1` 已按此保存，新写中文脚本记得同样处理） |

### #17 装完 APK 服务没了但进程还在
- `adb install` 不杀旧的 llama-server 子进程：`adb shell 'kill $(pidof llama-server)'`（单引号防 PowerShell 抢解析）。

### #18 真机写入 APP 私有目录
- 只能 `run-as`：`adb shell run-as com.localllm.server cp /data/local/tmp/xx /data/user/0/com.localllm.server/files/...`（调试版才可 run-as）。
- 修 SharedPreferences：先 `am force-stop`（防内存回写覆盖），再 run-as cp，再启动。

## D. 发布前检查（正式版）

- [ ] 16KB 页对齐（当前调试构建仅弹警告；正式版必须处理：NDK r28+ 或 `-Wl,-z,max-page-size=16384` 重链）
- [ ] release 签名 + 版本号
- [ ] `targetSdk=28` 的取舍复核（见 UPGRADING §二前提）
- [ ] 引擎产物清单与 readelf NEEDED 复核（UPGRADING §1.2）
- [ ] 体积：当前 debug ~65MB（引擎 17.5MB 未压缩入包）

### #20 sh 包装 ulimit 内存保险丝方案已撤销 [勿重蹈]
- 现象：ProcessBuilder 改为 `sh -c 'ulimit -v...; exec ...'` 后，llama-server 启动即 SIGABRT(code=134)，零输出。
- 同时实测该 ROM 的 sh 中 `ulimit -v` 设置后读回仍为 unlimited（空操作）。
- 结论：**保持直接 ProcessBuilder(cmd)**；内存防护靠「分配失败自动降级 +（聊天端）图片缩图 +（后续）JNI setrlimit」。
- 若未来重试此类方案，先在目标 ROM 用 `run-as ... sh -c 'ulimit -v 6000000; ulimit -v'` 验证有效性。

### #22 GitHub Actions CI 首跑三连坑 [CI]
1. **ninja 不是 Android SDK 包**：写进 setup-android action 的 `packages:` 会让 sdkmanager 报 `Failed to find package` 秒挂（repository2-3.xml 里没有任何 ninja 条目）。ninja 用 `apt install ninja-build`，或确认 runner 自带。
2. **`cmake --install` 参数必须是构建目录**（其中才有 `cmake_install.cmake`）；传 `CMAKE_INSTALL_PREFIX` 目录会报 `Not a file: .../cmake_install.cmake`。
3. **Windows 上提交的 `gradlew` 默认 100644 无执行位**，Linux runner 直接 `Permission denied`；用 `git update-index --chmod=+x gradlew` 修复后重提交。
- 修复 commit 见 `01246a4` 与 `db5ed3c`。

### #23 GitHub 国内访问时好时坏（DNS 正常但 TCP 层丢包）[网络]
- **现象**：`Resolve-DnsName` 解析 github.com 正常（如 20.205.243.166），但对 `github.com:443` 做 5 次 `TcpClient` 连接往往 3–4 次 5s 超时。
- **结论**：「DNS 无解」是错觉，根因在连接层间歇 RST/丢包，与解析无关。
- **缓解**：
  - `git push` 带重试循环（实践 6 次内必成功）
  - webfetch/API 调用用 `curl --retry-all-errors`
  - 或走镜像（见 #21）
- **不要为此改 DNS 或 hosts**——解析本来就对，改了没用还可能引入新问题。

### #22 GitHub Actions CI 三连坑（build.yml 首跑踩过）[CI]
- **坑 1 · packages 不能写 `ninja;1.12.1`**：Android SDK 仓库没有 ninja 包，`sdkmanager` 直接失败。ninja 用 `apt install ninja-build` 装，或确认 runner 自带。
- **坑 2 · `cmake --install` 参数**：必须传**构建目录**，不是 `CMAKE_INSTALL_PREFIX`；传错报 `Not a file: .../cmake_install.cmake`。
- **坑 3 · gradlew 可执行位**：Windows 上提交的 gradlew 默认 mode 100644，Linux runner 执行报 `permission denied`；`git update-index --chmod=+x gradlew` 后重新提交。
- 修复 commit 参考：`db5ed3c` / `01246a4`。

### #23 GitHub 国内连接间歇失败（连接层丢包，非 DNS）[网络]
- **现象**：DNS 解析正常（`Resolve-DnsName github.com` → 20.205.243.166），但 TCP 443 五次连接仅 1 次成功；`Invoke-WebRequest` 超时。
- **诊断**：`Resolve-DnsName github.com` 看解析；再用 TcpClient 循环测连通（`New-Object Net.Sockets.TcpClient` → `Connect('20.205.243.166', 443)`，多次统计成败）。DNS 正常但 TCP 大量失败 = 连接层丢包。
- **定性**：这是国内访问 GitHub **尽力而为的网络环境问题，不是仓库配置问题**（区别于 #21 的限速/慢）。
- **缓解**：挂代理后 git/push 正常；ghfast.top 等镜像**只用于 clone 拉取**（见 #21），push 和 Actions 页面必须走真实连接。
- **重试策略**：`git push` 失败后退避重试即可成功。

### #24 文件工具的目录授权限制（Android 11+）[APP]
- **现象**：`选择目录` 时无法选中「内部存储根 / Download / Android/data」。
- **原因**：系统不允许把受限目录本身授权给应用。
- **正确做法**：在内部存储根下新建专用文件夹（如 `LlamaWork`）并选中它；`Download` 下的**子目录**也可以。
- 相关：`file_write` 自动建子目录；拒绝 `..` 越界；单文件读写有大小上限。

### #25 三元量化（TQ/Q2_0）模型加载失败 [引擎]
- **现象**：加载 `*-Q2_0.gguf` 等三元量化报 `llama_model_loader: failed to load model`。
- **原因**：当前内置引擎版本较旧，不认识该类量化类型。
- **处理**：改用 `Q4_K_M / Q5_K_M / IQ4_XS` 等常规 GGUF；或按 `docs/UPGRADING_LLAMA.md` 升级引擎。
- 另：超低比特模型体积小但**计算量不变**，手机上未必更快（优化内核主要在 x86/Metal）。

### #26 OCR 数字/小数识别误差 [APP]
- **现象**：表格类截图里个别金额小数点丢失（如 `7177.50` → `717750`）。
- **原因**：ML Kit 对小字号数字的固有误差；已做「原图识别 + 坐标重建行 + 放大灰度预处理」降低概率。
- **建议**：关键金额人工核对；或改用「图片处理方式 = 多模态直读」让视觉模型读表格。

### #27 build-engine.ps1 在 configure 阶段 «假失败» [构建]
- **现象**：脚本刚打印 `== [configure] cpu ==` 就抛 `NativeCommandError: CMAKE_BUILD_TYPE=Release`，但手动执行同一条 cmake 命令 `$LASTEXITCODE=0` 成功。
- **原因**：cmake/ninja 把进度写 stderr，PowerShell 5.1 在 `$ErrorActionPreference="Stop"` 下把**原生 stderr 当终止错误**。
- **处理**：脚本已改为 `Continue`，成败一律用 `$LASTEXITCODE` 判定；新增原生调用必须自己补 `$LASTEXITCODE` 检查。
- **连带坑**：带中文注释的 `.ps1` **必须存 UTF-8 with BOM**。丢了 BOM 后 PS 5.1 按 GBK 解码，报「字符串缺少终止符 / 意外的标记」一串解析错误（不是脚本逻辑问题）。

### #28 16KB 页对齐漏掉 MODULE 库 [构建]
- **现象**：`libggml-cpu.so` / `libggml-opencl.so` 的 LOAD align 是 `0x1000`，而其他产物是 `0x4000`。
- **原因**：`GGML_BACKEND_DL=ON` 下后端被编成 **MODULE** 库（dlopen 插件），链接走 `CMAKE_MODULE_LINKER_FLAGS`，只设 EXE/SHARED 两个变量管不到。
- **处理**：configure 三个变量一起给 `-Wl,-z,max-page-size=16384`。复核命令：
  `llvm-readelf.exe -l <so> | findstr LOAD`，末列必须全是 `0x4000`。

