# T2 设备体检 — 进展

## §1 做了什么
- 新建 `core/HealthCheck.kt`：`object HealthCheck`，提供 `data class Item(level, title, detail)`、`enum class Level { OK, WARN, FAIL, INFO }`、`suspend fun run(context: Context): List<Item>`（内部 `withContext(Dispatchers.IO)`）。
- 实现 10 类全静态检查（不启动推理服务）：
  1. 系统（厂商/型号/Android 版本+SDK/安全补丁）→ INFO
  2. ABI（`Build.SUPPORTED_ABIS` 必须含 arm64-v8a，否则 FAIL）
  3. CPU（`availableProcessors()` 核数）→ INFO
  4. 内存（totalMem/availMem；avail<2GB WARN、<1GB FAIL）+ 与 `AppSettings.selectedModelPath` 指向文件大小对比（>可用 85% FAIL、>70% WARN；文件不存在则跳过对比项）
  5. 存储（`dataDir` 分区 StatFs 剩余空间 <5GB WARN；`filesDir/models` 总大小 vs 剩余空间，剩余<模型总量 WARN）
  6. 引擎（`filesDir/engines/{cpu,opencl}` 下 llama-server 与 `libggml-cpu*.so`；`AppSettings.ngl>0` 时加查 `libggml-opencl*.so`；缺失/为空 FAIL；逐文件读 4 字节魔数非 `\x7fELF` 或零长 FAIL；16KB p_align 解析按约定跳过）
  7. 服务（`ServerProcessManager.state.value` 状态 + 是否已选模型；未选模型 WARN）
  8. 网络（`hf-mirror.com:443` TCP 连通，`Socket.connect` 5s 超时；失败 WARN——项目中无现成 TcpClient 类，用 `java.net.Socket` 等效实现，无新依赖）
  9. 电池（粘性广播 `ACTION_BATTERY_CHANGED` 读电量/温度(×0.1°C)/是否充电；温度>42°C WARN）
  10. 云端渠道（`AppSettings.chatCloudEnabled` 开启时检查 url/key/model 是否填齐，缺项 WARN；未启用 → INFO）
- 每个检查项单独 `safe(...)/safeList(...)` 包裹，单项异常只降级为「未能完成检查」INFO，不影响其余项。
- `StatusScreen.kt` 集成：标题下新增「设备体检」OutlinedButton，点击后按钮内转 `CircularProgressIndicator` + 「体检中…」，协程调用 `HealthCheck.run(context)`；结果用 `AlertDialog`（text 内 `LazyColumn`，`heightIn(max=360.dp)`）展示——每项 8dp 色点（OK `#43A047` 绿 / WARN `#FB8C00` 橙 / FAIL `colorScheme.error` 红 / INFO `outline` 灰）+ title 加粗 + detail 小字；顶部总结行「共X项：Y通过 Z警告 W失败」；底部「复制报告」（TextButton）将纯文本报告写剪贴板（`LocalClipboardManager`），「关闭」为 confirmButton。

## §2 改动文件与关键行
- **新建** `app/src/main/java/com/localllm/server/core/HealthCheck.kt`（约 260 行）
  - `run()`：L30 起，`withContext(Dispatchers.IO)` + 10 项 `safe/safeList` 聚合
  - 各检查：`systemInfo/abi/cpu/memory/storage/engine/service/network/battery/cloud`
  - `isElf()`：FileInputStream 读 4 字节魔数校验
  - `fmtBytes/dirSize` 工具函数
- **修改** `app/src/main/java/com/localllm/server/ui/StatusScreen.kt`
  - 头部 import 区：新增 LazyColumn/AlertDialog/CircularProgressIndicator/TextButton/背景与圆形等 Compose import + `com.localllm.server.core.HealthCheck` + `kotlinx.coroutines.launch`
  - 函数体状态声明（约 L48-51）：`checking` / `healthItems`
  - 「设备体检」按钮行（约 L66-91）：跑协程、按钮内进度圈
  - 结果 AlertDialog（约 L186-246）：总结行 + LazyColumn 色点列表 + 复制报告/关闭

## §3 编译结果
- 命令：`gradlew.bat -p <项目> :app:compileDebugKotlin --no-daemon`（JAVA_HOME=jdk-23, ANDROID_HOME=Sdk）
- 最终结果：**BUILD FAILED，唯一 error 来自禁改文件** `ui/ChatViewModel.kt:142 Unresolved reference 'mcpHttpUrl'`（并行任务持有；已按约定等待 30s 重试共 4 次，均未消除——该文件在禁改清单，不能代修）。
- **我负责的 HealthCheck.kt 与 StatusScreen.kt 零 error**：多轮编译中 Kotlin 分析器跨文件报错（曾在同一轮同时报 McpHttpClient/DeviceCheckup/StatusScreen 多文件错误），而最终两轮只报 ChatViewModel 一个错，证明我两文件通过了完整词法/语法/类型检查。
- 过程中修复的我方错误：`"$okCount通过"` 中文被并入标识符 → 改 `"${okCount}通过"`。
- **并发冲突记录**（重要）：
  1. StatusScreen.kt 在本任务期间被另一并行任务**两次**整体覆盖（版本 A 残留 `}e` 语法错误；版本 B 引用其 `DeviceCheckupDialog`，来自 `ui/DeviceCheckup.kt`——疑似另一路「设备体检」重复实现）。我按本任务授权两次重写回本规格实现（按钮跑 `HealthCheck.run` + AlertDialog 结果展示）。**最后写入时间约 19:56，若再次被覆盖需重跑。**
  2. `core/HealthCheck.kt` 曾被某并行进程整体删除一次；已按原内容重建。
  3. 该 UI 冲突需上层协调：`DeviceCheckup.kt` 目前成为无引用死代码（若其编译错误复发会拖累整包编译，但那是该任务的问题）。
- 未 git commit（按要求）。

## §4 留给真机验证的事项
- 网络项：真机上验证 hf-mirror.com:443 5s 超时行为（含断网/仅蜂窝场景）。
- 电池项：充电中/高温（>42°C）场景下 WARN 是否触发；`registerReceiver(null, ...)` 粘性广播在目标 ROM 上的读数。
- 内存对比阈值（85%/70%）与存储阈值（5GB）在真实模型（数 GB GGUF）下的告警是否合理。
- 引擎项：首次未启动服务时 engines 目录不存在会报 FAIL（附「首次启动自动释放」提示文案），确认文案可接受；ngl>0 时 opencl 引擎文件校验。
- 16KB 段对齐（ELF p_align≥0x4000）检查按规格跳过——若真机 Android 15+ 出现 dlopen 失败再补。
- 测速类检查（推理速度）规格要求跳过，等真机后另任务补充。
- 「复制报告」写入剪贴板后在外部 App 粘贴验证格式。
- StatusScreen 布局：体检按钮与结果 Dialog 在小屏设备上的滚动表现。
