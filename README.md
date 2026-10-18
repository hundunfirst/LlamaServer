# 混沌智核（HunDun AI · LlamaServer）

Android 手机上的本地 LLM 推理服务 APP：导入 GGUF 模型 → 后台拉起 llama-server →
在 `127.0.0.1:8080/v1` 暴露 OpenAI 兼容接口，供 Rikkahub 等 AI 客户端 / MCP 生态调用。

- 包名：`com.localllm.server`，中文名「混沌智核」，英文副名 HunDun AI
- 协议：MIT（llama.cpp 及其依赖均为 MIT / 公有领域）
- 目标设备：arm64-v8a，Android 12+（minSdk 31，targetSdk 28——原因见架构文档）

## 功能概览

**推理服务**：导入 GGUF → 后台前台服务拉起 llama-server → OpenAI 兼容接口；双引擎（CPU / OpenCL）按 `ngl` 自动选择；
模型级参数（上下文 / 输出上限 / mmproj 绑定）；KV 缓存量化（V 量化自动开 FA）；自动降级（上下文过大减半重试、显存不足降 ngl）；
删除模型有确认弹窗并先进回收站（7 天内可恢复，到期自动清理）。

**聊天**：多会话 / 云端渠道（任意 OpenAI 兼容端点，含电脑上的 llama.cpp）/ 思考卡 / 步骤时间线 / 每次回答用时 / 上下文占用与压缩 /
自动会话标题 / 助手指令预设（全局 + 会话级）/ 技能与技能链 / 任务清单卡片 / 阅读视图（拖选复制）。

**工具（模型可调用）**：`web_search`（Bing，可抓正文，来源可点）、`todo_write`、`memory_write`（本地长期记忆）、
`file_list/read/write/grep`（SAF 授权目录内）、`make_xlsx`、`make_pdf`、外部 MCP（HTTP，多服务器）。

**附件与隐私**：图片（多模态直读 / 本地 OCR 自动分流）、文本、PDF、docx、xlsx 文本抽取；附件 token 预算保护；
图片轮联网搜索三档（禁止 / 先问我 / 允许）；搜索词自动脱敏；全部推理与记忆仅在本机（云端渠道除外，由用户自行配置）。

## 免责声明（模型与生成内容）

本应用**不托管任何模型文件**。内置的「在线下载」只是一个指向第三方（HuggingFace 及其国内镜像 `hf-mirror.com`）的通用下载器，
模型的内容、许可证、合法性与安全性均由发布者负责，与本项目无关；本项目不审查、不背书，也不对下载后模型的行为或生成结果承担责任。

- **许可证**：各模型许可不同（Apache-2.0 / MIT / 自定义 / 需授权），使用、商用、再分发前请自行核对模型页的许可条款。
- **内容风险**：部分模型可能产生不准确、有偏见或不适内容；部分社区模型可能包含受限内容，使用与传播风险自负。
- **设备与流量**：模型动辄数 GB，注意蜂窝流量、存储空间与内存是否够用（上下文越大，KV 缓存越吃内存）。
- **合规**：请遵守你所在地区的法律法规以及模型发布方的使用条款；本软件按 MIT 协议「按原样」提供，不附带任何担保。

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
