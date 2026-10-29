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

### #25 三元 / 1-bit 量化模型加载失败（Bonsai 家族）[引擎]
- **现象**：加载 `*-Q2_0.gguf` 等超低比特模型报 `llama_model_loader: failed to load model` 或 `dflash requires ctx_other to be set`。
- **先分清是哪种文件**（Bonsai 2 有三种打包，只有一种能用本引擎）：

  | 文件 | 张量类型 | 能否用本引擎（v0.7.0） |
  |---|---|---|
  | `*-Q2_0.gguf`（官方 group-64） | 42 `Q2_0` | **能**，实测可加载并正常出字 |
  | `*-Q1_0.gguf`（Bonsai 1 的 1-bit） | 41 `Q1_0` | **能**（ARM 有 NEON kernel），但很慢 |
  | `*-PQ2_0.gguf` | 142 `PQ2_0` | **不能**，PrismML fork 专属 |
  | `*-PTQ1_0.gguf` / `*-MTP-Q8_0.gguf` | fork 专属打包 | **不能**，需要 fork |
  | `*-DFlash2-*.gguf` / `*-dspark-*.gguf` | arch = `dflash` / `dspark` | **不能当主模型**：它们是投机解码的草稿侧车 |

- **诊断方法**：读 GGUF 头看 `general.architecture` 与张量类型（本仓库会话里用过的小脚本思路：解析 magic `GGUF` → n_kv → KV → tensor infos）。
  报 `dflash requires ctx_other to be set` 就说明加载的是草稿模型；报 `failed to load model` 且类型是 142 就是 fork 打包。
- **处理**：只用 `*-Q2_0.gguf`；草稿模型要配主模型走 `--spec-type draft-dflash` / `draft-dspark`，本 App 还没有对应开关。
- **上下文**：Bonsai 2 声明 262144，但 Bonsai 1 的 8B 训练上下文只有 65536；把 App 上下文设到 131072 会触发
  `n_ctx_seq > n_ctx_train` 并因内存 fitting 失败起不来 → 降到 32768 可正常加载。
- **速度预期**（本机小米 17 Pro Max，CPU）：Ternary-Bonsai-2-9B-Q2_0 约 5 tok/s、Bonsai-8B（1-bit）约 6.6 tok/s；
  超低比特只省内存，**解码不会更快**。
- 另：Bonsai 2 是 thinking 模型，`max_tokens` 给小了会全花在思考上、正文为空（官方建议 ≥ 数 k）。

### #25b 用 PrismML fork 引擎解锁 PQ2_0（实测可行，2026-10-18）

- **产物**：他们 release `prism-b10777-e3de715` 里有 `...-bin-android-arm64.tar.gz`（74MB），**不用自己交叉编译**。
- **结构兼容**：解压是平铺目录（`llama-server` 壳 + `libllama*` / `libggml*` / `libmtmd`），
  与 `assets/engines/{cpu,opencl}` 的布局一致；`llvm-strip` 后 **19.5MB / 9 个文件**（未瘦身 202MB）。
- **对齐/依赖**：全部 `LOAD align=0x4000`（16KB 满足发布要求），NEEDED 只有系统库。
- **实测（本机 CPU、`-t 6`）**：Bonsai 4B **PQ2_0** decode **18.4 tok/s**（上游引擎同模型 Q2_0 只有 9.0）；
  普通 Qwen3.5-4B Q4_K_M 在 fork 上 15.4 tok/s，比上游 13.5 略快 → 老模型兼容没问题。
- **注意**：该 Android 包是**纯 CPU 构建**（没有 `libggml-opencl.so`），所以只能替换 `engines/cpu`，
  GPU 路径继续用上游 `engines/opencl`；`libggml-cpu` 是按 `libggml-cpu-android_armvX_Y.so` 分变体的，
  两个变体（armv8.0 / armv9.2）都要带上，它靠 dlopen 扫描选最优。
- **进包前还要回归**：mmproj 视觉、KV 量化（`-fa`）、`--spec-type`（MTP/DFlash）、长上下文、停止；
  fork 版本线是 `0.2.0-dev (build 10777)`，与上游 v0.7.0 不同线，双线维护成本要算进去。
- **回归结果（2026-10-18，App 内开关打开，普通模型）**：
  | 项目 | 结果 |
  |---|---|
  | 普通模型加载/生成 | 通过；0.5B **99.9 tok/s**（上游同机 79.7）、Qwen3.5-4B 15.4（上游 13.5）→ 反而更快 |
  | 视觉 mmproj | 通过；Qwen2.5-VL-3B + 配套投影器正确识别"蓝圆 + 红矩形" |
  | KV 量化 + `-fa` | 通过；`--cache-type-k/v q8_0` + 自动 `-fa on`，0.5B 107 tok/s |
  | 投机 `--spec-type ngram-simple` | 通过；带 `--spec-draft-n-max 32` 正常起 |
  | 投机 `--spec-type draft-mtp` | 普通模型报 `model doesn't contain MTP layers`；带 nextn 层的模型能起，但**实测更慢**（见 #25d） |


### #25c App 会读 GGUF 头判定文件类型（2026-10-18）

- **动机**：文件名不可靠——`MTP` 既可能是"主模型自带 MTP 头"的能力标签，也可能是独立侧车；
  投影器也是 `.gguf`，混进模型目录就能被选成主模型，然后启动才报底层 loader 错。
- **实现**：`core/GgufInfo.kt` 读头部前 512KB（碰到词表大数组提前收工），按
  `general.type` + `general.architecture` 判成 `MAIN / MMPROJ / DRAFT / ADAPTER / IMATRIX`，
  结果按「路径+大小+mtime」缓存。
- **三处生效**：① 模型页只在"放错类别"时标红（对话区里出现草稿/投影器、投影区里出现主模型）；
  ② 下载完成时核对所选类别，选错就不自动选中并写明原因；③ 启动前拦截，
  报「不是主模型：这是投机解码的草稿/侧车文件（architecture=dflash）」而不是让 llama-server 抛 loader 错。
- **验证**：造了一个 156 字节的最小 GGUF（arch=clip）放进模型目录，模型页正确标红、
  启动被拦截（用户真机确认）。

### #25d 模型自带 MTP 层：CPU 上是负收益，Hexagon NPU 上反过来是正收益

> 标题原为「在这台机器上是负收益（2026-10-18）」。2026-10-25 补的 NPU 对照推翻了「一律更慢」这个说法，
> 见下面的「NPU 复测」。结论按引擎分开：**CPU / OpenCL 关掉，Hexagon NPU 开着**。

- **判据（别信文件名）**：KV 元数据里 `<arch>.nextn_predict_layers > 0` 就说明模型自带 MTP 头，
  llama.cpp 建 MTP 上下文时只看这个。`Ternary-Bonsai-2-4B-PQ2_0-MTP-Q8_0.gguf` 是
  `qwen35.nextn_predict_layers = 1`（block_count 33 而不是 32，多出来的 `blk.32.nextn.*` 就是 MTP 层）。
  实测 `Qwen3.5-4B-UD-Q5_K_XL.gguf` 也带这一层。
- **这个键排在词表大数组之后**（本机这两个模型 KV 段约 10.5MB），只读头 512KB 读不到。
  `core/GgufInfo.kt` 的 `hasMtpHead()` 单独读 24MB 并按字节搜键名（不在字节流里逐条走 KV，
  词表那种几十万条字符串数组很容易踩类型对齐坑），结果带缓存；只在启动前对选中的那个文件调一次。
- **App 策略（0.3.6）**：检测到就**只记一行日志、默认不开**；想强开在设置里填 `spec_type=draft-mtp`
  （模型不带 nextn 层时自动忽略）。三个引擎都接受这个参数，所以代码里不按引擎分支。
  引擎真拒绝时（`failed to create MTP context`）会自动去掉参数重试一次，加载不中断；
  无关错误（如 opencl 装三元包的 `invalid ggml type 142`）不会被误判成 MTP 问题。
- **A/B 实测（同机交替多轮取中位数，`-t 8 -fa off`，全程 CPU 95.8°C 降频）**：

  | 配置 | Bonsai 4B PQ2_0-MTP | Qwen3.5-4B Q5_K_XL |
  |---|---|---|
  | 不带投机 | 12.48 tok/s | 6.99 tok/s |
  | `draft-mtp` 默认（n-max 3） | 9.27（**0.74x**） | 5.09（**0.73x**） |
  | `draft-mtp` n-max 1 | 10.92（0.95x） | — |
  | `draft-mtp` n-max 8 | 5.99（0.52x） | — |

  草稿接受率 0.63–0.90、平均猜中 1.9–3.3 个 token，**问题不在 MTP 头**。
- **根因**：这台机器的批量前向几乎不省时间——prefill 只有 23 tok/s，而单 token 解码 12.5 tok/s，
  即"验证 4 个候选"≈"顺序跑 3.2 个 token"的成本，投机的前提（验证比顺序便宜）不成立。
  n-max 越大越亏（0.95 → 0.74 → 0.52）正是这条的推论。
- **三元包上还有一个前提**：`PQ2_0`（type 142）只有 prism 能解，上游 opencl 引擎直接
  `invalid ggml type 142`，所以 GPU 路径对这个模型没有 MTP 可谈。
- **NPU 复测（2026-10-25，推翻了「一律更慢」）**：同一模型同一参数，只切 `--spec-type`：

  | 配置（2B Q6_K / htp / `-ngl 99 --device HTP0` / ctx 16384 / `-t 6`） | decode 中位 | 样本 |
  |---|---|---|
  | `--spec-type draft-mtp`（会话 1） | 31.43 tok/s | 31.29 / 27.45 / 31.57 / 33.85 / 28.20 / 35.04 |
  | `--spec-type draft-mtp`（会话 2） | 32.15 tok/s | 29.83 / 32.15 / 30.38 / 32.16 / 29.18 / 33.13 / 33.46 / 35.04 |
  | 不带 `--spec-type`（会话 1） | 29.70 tok/s | 30.09 / 29.75 / 29.54 / 29.68 / 29.71 / 29.55 |
  | 不带 `--spec-type`（会话 2） | 29.33 tok/s | 29.27–29.62 |

  **约 +8%（MTP 开），且关掉那一侧稳到 ±1%**，跨会话不漂。CPU 那次的 −25% 没有复现。
  机理推测（未验证）：NPU 上单 token 解码受带宽/延迟支配，把「一次验证多个候选」交给 DSP 反而摊薄了固定开销；
  而 CPU 上前向本身便宜，批量验证省不下时间。
  **别跨引擎比 tok/s**：这里是 2B Q6_K，CPU 那组是 4B PQ2_0/Q5_K_XL，绝对数字不可比，只有同配置内的比值有意义。
- **NPU 上怎么复跑**：`mtp_bench.py` 走 `run-as` 直起引擎，**htp 在这条路上起不来**——
  日志首行 `FastRPC capability query failed (err 114)`，查不到 DSP 版本就退回加载 `libggml-htp-v73.so`，
  在 v81 的 DSP 上 `failed to open session (0x72)`。App 自己的进程里同一个引擎正常，
  差别在进程上下文不在环境变量。所以 NPU 只能借 App 的服务测：
  `scripts/dev/mtp_ab_app.py --label mtp-on --runs 3`（先 `wait_ready.py` 等服务就绪），
  换配置靠改设置 + 重启服务，两组结果用 `--compare mtp-on mtp-off` 对比。
- **仍缺的一项**：这轮的 `timings` 里没有 draft 字段、日志也不打接受率，所以 NPU 上的
  草稿接受率没拿到（CPU 那次是 0.63–0.90）。要看它得给引擎加更详细的 verbosity。

### #25e 三元包能挂 LoRA 适配器（实测，2026-10-18）

- **背景**：AtomicChat 给 Bonsai 2 全家出了一套 rank-1「反拒答方向」适配器
  （`AtomicChat/Ternary-Bonsai-2-{2B,4B,9B,27B}-Uncensored-LoRA-GGUF`，1.2 / 2.4 / 3.2 / 9.7 MB）。
  基础包保持字节不变，方向只在计算图里以两个额外 matmul 生效，强度可按请求调。
- **前提**：只有 PrismML fork 能开这些三元包（上游 opencl 引擎报
  `invalid ggml type 142` / `unsupported prism.hadamard.version`）。本仓库内置的 prism 引擎
  `prism-b10777-e3de715` 正是适配器作者验证过的构建。
- **实测（本机，prism + `Ternary-Bonsai-2-2B-PQ2_0.gguf`）**：
  | 项目 | 结果 |
  |---|---|
  | 加载 | 通过；`--lora <path> --lora-init-without-apply` 后 `GET /lora-adapters` 返回 `id 0 / scale 1.0` |
  | 按请求调强度 | 通过；请求体带 `"lora":[{"id":0,"scale":1}]` 正常返回 |
  | 代价 | decode 24.74 → 26.89 tok/s（`-t 8 -fa off`，2 轮，差异在噪声内）；加载慢约 1s |
  | 错路径 | 立即失败 `failed to load lora adapter`，不会静默忽略 |
- **注意**：作者只在 **PTQ1_0** 包上测过（2B/4B/9B 官方包就是 PTQ1_0）；我们手上的 **PQ2_0 / MTP**
  包作者未测。上面只证明「机制通、速度无损失」，**PQ2_0 上的效果（拒答率）没有量过**。
  作者自述该适配器是 research-grade（2B 方向残留约 1e-4）。
- **App 已支持（0.3.6）**：导入时选「LoRA 适配器」类型落到 `files/adapters/`；在模型页该模型的「参数」
  弹窗里选适配器 + 强度（默认 1，0 = 等效关闭），启动时自动追加
  `--lora-scaled <路径>:<强度>`（适配器文件不存在就不带，避免起不来）。
  适配器被删/移走时绑定自动清理（`AppSettings.dropLoraIfMissing`）。真机验收：2B PQ2_0 + 该适配器，
  日志出现 `LoRA 适配器：…（强度 1.0）`，`GET /lora-adapters` 返回 `id 0 / scale 1.0`，decode 36 tok/s。
  适配器同样进回收站、可恢复。
- 复测：`scripts/dev/mtp_bench.py --extra "--lora <设备路径> --lora-init-without-apply" --lora-scale 1`

### #25f Flash Attention 设成「关」会让解码速度腰斩（实测，2026-10-19）

- **现象**：`qwen2.5-0.5b-instruct-q4_k_m` 解码掉到 30 出头，与记忆中「稳定 55」差一截。
  这台机器常年 95°C 降频，绝对速度本来就在飘，所以第一反应容易误判成热降频。
- **根因**：设置里 Flash Attention 被设成「关」，启动命令里出现 `-fa off`：

  ```
  llama-server ... -c 32768 --jinja -t 8 --mmproj ... -fa off
  ```

  「关」是唯一会显式追加该参数的状态；留空 = 「自动」，根本不传这个参数
  （`ServerProcessManager.kt:541`）。只有量化 V 缓存会强制改回 on 并留日志（同文件 534-540）。
- **实测（本机，同模型、同上下文）**：

  | FA 档位 | decode |
  |---|---|
  | 关（`-fa off`） | 30 tok/s |
  | 自动（不传参） | **58 tok/s** |

  约 1.9×，代价接近一半。
- **为什么伤害这么大**：解码是内存带宽瓶颈。FA 关闭后注意力走非融合路径，KV 读取量被放大，
  上下文越长越明显——当时 0.5B 的模型级 ctx 是 32768，2B 是 65536。
- **别靠界面猜，直接读真实命令**：
  `adb shell run-as com.localllm.server cat /proc/<llama-server pid>/cmdline`。
  本例就是靠这条确认的（界面显示与实际生效参数不一定一致）。
- **记数字时注意可比性**：`scripts/dev/mtp_bench.py` 默认 `-fa on`
  （`--fa on|off`，没有 auto 档），而 App 侧可能是 off，两者不能混着比。
  #25e 里标注 `-fa off` 的 24.74 / 26.89 与脚本默认档不同源，不要当作同一基线。
- **结论**：除排障外不要设「关」。0.5B 那段 32768 上下文对它也偏大，压到 8192 还能再快一点。

### #25g KV 缓存量化：省内存但不省时间，NPU 上还会直接崩（2026-10-27）
- **NPU（htp）上不能用**：把 KV 设成 `-ctk q4_0 -ctv q8_0` 后服务点启动就退出（code=134），日志是
  `pre-allocated tensor (cache_k_l3 (view)) in a buffer (HTP0) that cannot run the operation (SET_ROWS)`
  → `ggml_abort`。htp 后端没实现量化 KV 所需的 `SET_ROWS`；「减 ctx / 减 ngl / 去投机」那套降级对它无效。
  App 现在会在启动前把这种组合**自动改回 f16** 并落一行中文日志（`KvCache.quantized()`）。
- **CPU 上是负收益（用户真机观察，2026-10-27，尚未做 A/B 量化）**：量化 KV 的收益是**显存/内存**，
  不是速度——attention 每次都要把 K/V 解量化回可算的格式，CPU 上这笔开销盖过了省下的带宽。
  想省内存又要快，优先**调小上下文**（KV 与 ctx 线性，131k→32k 是最大的一刀）、
  其次不挂用不到的 mmproj；不要指望 KV 量化提速。
- **谁适合开**：显存/内存真的不够（例如把 ctx 撑到 64k 以上又必须留在 GPU/NPU 上）时才值得，
  且只在 CPU/OpenCL 引擎下有意义。

### #26 OCR 数字/小数识别误差 [APP]
- **现象**：表格类截图里个别金额小数点丢失（如 `7177.50` → `717750`）。
- **原因**：ML Kit 对小字号数字的固有误差；已做「原图识别 + 坐标重建行 + 放大灰度预处理」降低概率。
- **建议**：关键金额人工核对；或给模型挂上 mmproj（视觉模型直读图片），让它读表格原文——
  纯文本模型只能走 `ocr_image` 工具，误差就是 ML Kit 的上限。

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


### #29 本地长时间推理 + 快充 → 触发热保护被系统清后台 [设备]
- **现象**：本地模型跑多轮工具循环（例如代码任务里 7 轮生成 + 反复加载沙箱页面）中途会话突然中断，
  日志停在 `工具回传完成，第 N 轮继续生成…`，**没有崩溃堆栈、也没有 OOM 记录**，
  `ps` 里 `llama-server` 消失；温度 `CPU 91–96°C`、电池 40°C。
- **原因**：**同时快充**。推理本身已把 CPU 顶到 95°C 附近，充电的额外热量让它越过保护线，
  MIUI 主动清后台降温。事件日志里能看到 `am_kill … SwipeUpClean`，
  连带 `com.google.android.webview:sandboxed_process0` 也一起被杀。
- **判据**：`adb logcat -b events -d -t 3000 | grep -E "am_kill|am_proc_died"`；
  `adb shell dumpsys thermalservice | grep CPU`。
  如果是内存不足，会看到 `lmkd` / `lowmemorykiller`，两者要分清。
- **处理**：跑长任务时不要充电；或用电脑端模型（不受此限制）。
  本地代码类任务可把上下文压到 16384 以内，缩短单轮时间。
- **注意**：这不是 App 的 bug，也不必为此收紧轮数上限——正常温度下 8 轮没问题。