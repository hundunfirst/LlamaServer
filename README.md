# 本地推理服务（LlamaServer）

Android 手机上的本地 LLM 推理服务 APP：导入 GGUF 模型 → 后台拉起 llama-server →
在 `127.0.0.1:8080/v1` 暴露 OpenAI 兼容接口，供 Rikkahub 等 AI 客户端 / MCP 生态调用。

- 包名：`com.localllm.server`，中文名「本地推理服务」
- 协议：MIT（llama.cpp 及其依赖均为 MIT / 公有领域）
- 目标设备：arm64-v8a，Android 12+（minSdk 31，targetSdk 28——原因见架构文档）

## 文档索引（维护必读）

| 文档 | 内容 |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 分层架构、进程模型、关键设计决策及其原因 |
| [docs/ENVIRONMENT.md](docs/ENVIRONMENT.md) | 开发环境、依赖版本清单、完整构建命令 |
| [docs/UPGRADING_LLAMA.md](docs/UPGRADING_LLAMA.md) | **升级 llama.cpp 的操作手册与检查清单** |
| [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) | 历史坑点与排障手册（闪退、链接、adb 等） |

## 快速构建（三条命令）

```powershell
cd C:\Users\mingliang\XiaomiMiMoProjects\LlamaServer
# 1) 引擎（llama.cpp 原生部分，仅引擎变更时需要）
.\scripts\build-engine.ps1          # 见 ENVIRONMENT.md，等价的手动命令也在其中
# 2) APK
.\gradlew.bat assembleDebug
# 3) 装机
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## 仓库布局

```
LlamaServer/
├── app/                     # Android 工程（Kotlin + Compose）
│   └── src/main/
│       ├── jniLibs/arm64-v8a/   # llama 引擎 .so（strip 后约17MB，由引擎构建产物拷入）
│       ├── assets/bin/          # llama-server 可执行（约7KB 壳）
│       └── java/com/localllm/server/
├── llama.cpp/               # 引擎源码（浅克隆，独立 git，不随本仓库提交）
├── opencl-deps/             # OpenCL Headers + ICD Loader（独立 git）
├── models/                  # 本机开发用模型（不入库）
├── docs/                    # 本文档集
└── app-native/              # 引擎产物暂存（strip 后），再拷入 app/
```
