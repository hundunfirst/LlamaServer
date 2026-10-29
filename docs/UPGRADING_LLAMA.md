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

## 三、当前引擎结构存档（v0.8.0 基线，commit `8642541b2`）

> v0.7.0 → v0.8.0 结构未变（`llama-server` 仍是 ~7KB 壳）。两点差异：
> cpu 构建会多产出 `llama-cli` 与 `libllama-cli-impl.so`（App 不用，已从 assets 剔除，见 §六）；
> htp 的 DSP skel 多了 `libggml-htp-v85.so`（v0.8 工具链新增，本机设备是 v81，暂用不到但保留）。
> 旧基线 620d2225c 的比对结论：产物集合**完全一致**（cpu 8 个 / opencl 9 个），仅体积变化。

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

## 六、升级实测记录（v0.7.0，2026-10-17）

**路径**：`3fb4767` → tag `v0.7.0`（`620d2225c`），中间 40 个提交，产物集合未变。
对本机（Adreno 840）相关的修复：`opencl: fix OOB tail access for adreno noshuffle gemv kernels`（#30421）、
`opencl: apply flash attn softcap before mask`（#30546）、server 侧 SWA 复用阈值与 slot 状态落盘修复。

A/B 方法：同一台机器、同一模型（MiniCPM-V-4.7-1B-F16，ctx 131072，mmproj 挂载），
把旧引擎从已发布的 v0.3.3 APK 里取出替换进 `files/engines/cpu` 复测，`ignore_eos` 固定生成 128 token。
提示词约 1023 token。

| 项目 | 旧引擎 3fb4767 | 新引擎 v0.7.0 |
|---|---|---|
| CPU prefill | 84.5 tok/s | 88.8 tok/s |
| CPU decode | 32.2 tok/s | 31.6 tok/s |
| GPU prefill（ngl=99） | 未测 | 543 tok/s |
| GPU decode（ngl=99） | 未测 | 27.1 tok/s |

结论：CPU 解码在噪声范围内持平（±2%），prefill 略升；GPU 的 prefill 约为 CPU 的 6 倍，
decode 反而低于 CPU——与设置页「0 = 纯 CPU 更快」的提示一致，本次无需改默认值。

功能回归：mmproj 视觉请求（红色圆形）识别正确；起停 3 轮无 crash、进程树干净；
opencl 引擎 `libggml-opencl.so` 正常加载（进程内 231 处 `/dev/kgsl-3d0` 映射）。

## 七、升级实测记录（v0.8.0，2026-10-28 起）

**动机**：新下载的 `LFM3-1.7B-Uncensored-AD-Q4_K_M.gguf` 报
`unknown model architecture: 'lfm3'`——v0.8.0 的 release notes 第一条就是 **LFM3 家族**
（LFM3-1.7B 文本 / LFM3-VL-2B 视觉）。

**路径**：tag `v0.7.0`（`620d2225c`）→ tag `v0.8.0`（`8642541b2`）。两个自研补丁
（`scripts/patches/0001` LAZY_TENSORS、`0002` MADV_WILLNEED）在 v0.8.0 上**直接应用干净**，
没有冲突——这是本轮最大的不确定项，实测落空。

### htp 的工具链必须跟着升

用 v0.7 镜像编 v0.8.0 源码，DSP 内核直接编不过：

```
ggml-hexagon/htp/hvx-copy.h:324: error: call to undeclared function 'Q6_Vsf_equals_Vw'
ggml-hexagon/htp/hvx-exp.h:227: error: call to undeclared function 'Q6_Vh_equals_Vhf'
```

上游那句 `hexagon: update to snapdragon-toolchain v0.8 and enable hexagon v85` 是硬性要求。
**国内拉 ghcr 的办法**：`ghcr.nju.edu.cn` 镜像 3 秒可达，直连 ghcr 慢；拉完打规范 tag 再用：

```powershell
docker pull ghcr.nju.edu.cn/snapdragon-toolchain/arm64-android:v0.8
docker tag  ghcr.nju.edu.cn/snapdragon-toolchain/arm64-android:v0.8 `
            ghcr.io/snapdragon-toolchain/arm64-android:v0.8
```

### 产物集合的变化（§1.2 核对清单要跟上）

- **cpu**：多出 `llama-cli` + `libllama-cli-impl.so`（~210 KB，App 不用，已剔除）。
  build.py 的拷贝是「bin 目录全搬」，所以每轮升级后都要确认这两个没有回流。
- **htp**：多出 `libggml-htp-v85.so`（871 KB）。`assemble-htp-engine.ps1` 已支持
  `-InstallDir` 指定构建目录，并把 v85 skel 设为可选（老构建没有就不报错）。
- 两者结构不变：`llama-server` 仍是 ~7KB 壳，NEEDED 闭包与 16KB 对齐（LOAD align `0x4000`）复核过。

**版本号**：`BinaryInstaller.VERSION` 8 → 10（9 是中间只升 prism 那次）。

