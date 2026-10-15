# 升级 llama.cpp 操作手册（UPGRADING_LLAMA）

**升级动机通常只有两类**：支持新的模型架构 / 修复 bug、跟进性能特性。
升级 = 重编引擎 + 拷产物 + 重打 APK，**不需要改 APP 代码**（除非下面"可能需要改代码"清单命中）。

## 一、标准流程

```powershell
$root = "C:\Users\mingliang\XiaomiMiMoProjects\LlamaServer"
cd "$root\llama.cpp"
git fetch origin            # 用 commit/tag 精确锁定，避免裸用漂移的 master
git checkout <目标commit>   # 例如 v0.26.x 的某个 tag，或验证过的 commit
```

### 1. 重编引擎

按 [ENVIRONMENT.md §5](ENVIRONMENT.md) 的命令执行（或 `.\scripts\build-engine.ps1`）。
三条铁律：

- **`$env:PYTHONUTF8 = "1"` 必须先设**（OpenCL 内核代码生成在中文 Windows 用 GBK 读文件会崩）
- Configure 阶段若在 `FetchContent`（KleidiAI 等）网络失败 → 保持 `-DGGML_CPU_KLEIDIAI=OFF`
- 配置成功后**只编目标**：`ninja llama-server`（别全量，省一半时间）

### 2. 核对产物集合（易漏！）

```powershell
Get-ChildItem "$root\llama.cpp\build-android-opencl\bin" | Select-Object Name
# 与 app/src/main/jniLibs/arm64-v8a/ 现有清单比对：
# - 新增多余 .so？→ 必须一起拷入 jniLibs（少拷 = 运行时 CANNOT LINK）
# - 文件消失/改名？→ 从 jniLibs 删除旧的，否则死代码进包
# - llama-server 不再是几 KB 的壳？→ main.cpp 结构可能变了，回读本手册 §三
```

再用 readelf 复核依赖闭环（新增依赖库会在这里现形）：

```powershell
$readelf = "$ndk\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe"
& $readelf -d "$src\llama-server" | Select-String NEEDED
# 每个 NEEDED 都必须能在 jniLibs 或 LD 三段路径里找到
```

### 3. strip + 拷入 + 打包装机

即 ENVIRONMENT §5.2 + §5.3。

### 4. 回归检查清单（全部通过才算升级完成）

| # | 检查 | 方法 | 通过标准 |
|---|---|---|---|
| 1 | CPU 基线 | 设置 ngl=0 启动 → 任意 OpenAI 请求 | 正常出字，记录 tok/s 与升级前对比 |
| 2 | exec 链 | 启动服务 | 无 `CANNOT LINK` / `Permission denied`（看日志页） |
| 3 | GPU | 设置 ngl=99 重启 | `run-as ... grep -e kgsl -e adreno /proc/<pid>/maps` 有映射；速度不低于 CPU |
| 4 | 多模态 | 选配 mmproj 重启 → 发图请求 | 正确理解图片内容 |
| 5 | **停止×3** | 连续启停 3 轮 | crash 缓冲无 localllm 记录、进程树干净 |
| 6 | Rikkahub | 客户端实际对话 | 正常 |
| 7 | 新参数 | `llama-server --help` 与升级前 diff | 见 §二 |

## 二、可能需要改 APP 代码的信号

llama.cpp 的**公共契约是 CLI 参数和 OpenAI API**，出现以下任一情况才动 Kotlin：

| 信号 | 要改的地方 |
|---|---|
| `--jinja`、`--mmproj`、`--cache-type-k`、`--spec-type`、`--api-key` 等被移除/改名 | `ServerProcessManager.buildCommand()` |
| 新增想暴露给用户的参数 | `AppSettings` + `SettingsScreen` + `buildCommand` 三处（模式照抄现有字段） |
| `/v1/chat/completions` 行为变化（响应结构、流格式） | APP 不解析响应体，**通常无需改**；改的是客户端侧 |
| `bin/llama-server` 结构变化（不再依赖 `libllama-server-impl.so`、新增大 .so） | §1.2 产物清单 + 本文档 §三 + 拷贝脚本 |
| 构建系统新增依赖（新 FetchContent、新主机工具需求） | ENVIRONMENT.md §四/§五 |

**不需要改**：进程管理、FGS、SELinux/LD 策略、模型目录、UI——这些与 llama 版本无关。

## 三、当前引擎结构存档（3fb4767 基线）

```
bin/llama-server              ~7KB 壳，main.cpp 仅一行：return llama_server(argc, argv);
bin/libllama-server-impl.so   服务端全部实现（server.cpp/server-http/.../server-mcp）
bin/libllama-common.so        参数解析、chat 模板等公共层
bin/libllama.so  libmtmd.so   推理核心 / 多模态
bin/libggml*.so               ggml 基础 + CPU 后端
bin/libggml-opencl.so         OpenCL 后端（运行时按需 dlopen，非链接依赖）
```

升级后若此结构变化，**以 readelf NEEDED 为准**更新本节。

## 四、注意事项（历史教训浓缩）

1. **锁 commit，不锁 master**：llama.cpp 日变更，未验证的 master 进包等于裸奔
2. **`.so` 集合变化是最常见的静默炸弹**——症状是真机 `CANNOT LINK EXECUTABLE`，对策全在 §1.2
3. **spec/投机参数是变动最频繁的区域**（`--spec-type` 枚举值历史上多次增删），升级后重点 diff 这段 help
4. 大版本若改变默认行为（如 ctx、slot 分配），回归时把设置页参数复位到默认再测
5. 升级完成后**回填两处版本号**：ENVIRONMENT §四（llama commit、OpenCL deps commit 若也动了）
6. 发布正式版前额外：16KB 页对齐（NDK r28+ 或 `-Wl,-z,max-page-size=16384`）、release 签名、混淆开关、体积复核

## 五、Backlog：Vulkan 后端（可选）

- 已具备：NDK 自带 `glslc`；缺：主机 C++ 编译器（构建 `vulkan-shaders-gen` 需要）
- 路径：下载 w64devkit（GitHub release 国内限速 ~31KB/s，需耐心或代理）→ configure 加 `-DGGML_VULKAN=ON` 并在 PATH 前置 w64devkit/bin 与 glslc 目录 → 产物多一个 `libggml-vulkan.so` 按 §1.2 入包
- 收益：Mali 等无 OpenCL 机型的 GPU 加速（当前这些机型走 CPU，可用但无加速）
