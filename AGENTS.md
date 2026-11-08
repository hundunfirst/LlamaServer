# AGENTS.md

给接手这个仓库的 AI 智能体看的入口说明。**先读这一份，再按需查下面列出的文档。**

## 项目是什么

Android 本地大模型推理 App「混沌智核」（HunDun AI），Kotlin + Jetpack Compose，包名 `com.localllm.server`。
内核算 llama.cpp（NPU / CPU 多引擎交叉编译），App 自带聊天、工具调用（文件 / shell / 网络 / MCP）、
定时任务、终端、模型管理。仓库：`git@github.com:hundunfirst/LlamaServer.git`。

## 铁律（改代码前必须先看，违反会出事）

1. **`versionCode` 只增不减，且已发布的号冻结**：`0.3.0=3 … 0.3.8=11、0.3.9=12`。任何要发版的改动，
   提交前先把 `versionCode`/`versionName` 加上去，否则装机覆盖不上、GitHub 上也会混。
2. **`targetSdk` 必须保持 28**。不是为了兼容老系统，而是 SELinux 域：`untrusted_app_34`（target≥34）
   被禁止访问容器/proot 需要的东西，`untrusted_app_28` 允许。改它等于把 Linux 容器功能废掉。
3. **不许改包名** `com.localllm.server`（改了用户数据全丢）。
4. **不许删模型文件**（约 35GB，在 `models/`，gitignored）。
5. Git 允许 commit + push，**不许 force push、不许改远程历史**。
6. 真机验收由用户做。**不要用 adb 驱动界面点击/截图去"验证功能"**，也不要跑用户的对话任务；
   你能做的是改代码、构建、装机，以及跑单测。

## 构建与验证（按耗时从短到长选）

```powershell
$env:JAVA_HOME="C:\Program Files\Java\jdk-23"      # 必须 JDK 23
gradlew.bat compileDebugKotlin      # 5 秒，只验语法（配置缓存已开）
gradlew.bat testDebugUnitTest       # 约 1 分钟，177 条单测
gradlew.bat installDebug            # 20-30 秒，构建并装到已连接的手机
```

- **不要**习惯性 `assembleDebug` 再 `adb install`：包有 200MB+，合并资产+签名很慢，`installDebug` 一步到位。
- 单元测试文件在 `app/src/test/java/com/localllm/server/`，纯逻辑都该有测试。
- 首次在新环境构建：引擎二进制和 rootfs **不在仓库里**（gitignored），要先跑
  `scripts/build-engine.ps1` 或看 CI 配置；见 `ENVIRONMENT.md`。

## 文档索引（比读代码快）

| 文件 | 什么时候读 |
|---|---|
| `ARCHITECTURE.md` | 想知道模块怎么分层、数据往哪流 |
| `ENVIRONMENT.md` | 新机器要搭环境、引擎怎么编译 |
| `TROUBLESHOOTING.md` | 真机上出现的怪问题（30KB，基本都记过） |
| `RELEASING.md` | 要发 GitHub Release |
| `NPU_ENGINE.md` / `UPGRADING_LLAMA.md` | 动引擎、升 llama.cpp 基线 |
| `HANDOFF.md` | **最全的交接说明（本地文件，gitignored）**：铁律、历史决策、踩过的坑、最近的重构记录。clone 下来没有这份是正常的，不代表不存在 |

## 代码约定

- **高内聚低耦合**：新功能进新文件，不要往巨型文件里加分支。当前 `ChatViewModel` 仍有 3300 行、
  `doStream` 约 1600 行（已撞过一次 JVM 方法体 64KB 上限），加逻辑前先考虑抽出函数/类。
- 注释写"为什么"，不写"是什么"。项目里大量注释解释的是**踩坑原因**（某个 API 在 Android 17 被删、
  某条命令在某个场景无效），改代码时别顺手删掉。
- 界面文案、注释、提交信息都用中文；提交信息格式 `type(scope): 中文描述`。
- 抽文件时注意：顶层 `private` 要改 `internal` 才能被同包新文件访问；搬走 UI 块后**必须补回调用点**，
  否则编译能过但界面是空白。

## 现在的地基状态

- 已发布：`v0.3.9`（`5cc591f` 之后的版本号未动）。
- 单测 177 条全绿；`ChatScreen` / `SettingsScreen` 已完成分组拆分（见 HANDOFF 的「代码拆分」一节）。
- 已知未完：`doStream` 的工具分发注册表化、附件 Store 抽取、定时任务队列抽取——方案都写在 HANDOFF 里。
