# MoE 专家按需读（比内存大的 MoE 模型怎么跑）

## 问题

35B-A3B（`MiniCPM-V-4_7-35B-A3B-mix_q3gud_im.gguf`，14.44 GB）在 15.44 GB 内存的机器上：
纯 CPU 能跑但只有 **0.2 tok/s**，NPU 起不来，而且会把系统内存吃穿、**OOM 直接把手机带进重启**。

## 根因：默认 mmap 会整份预读

`llama-model.cpp:1876` 里是 `ml.init_mappings(true, ...)` —— 那个 `true` 是 **prefetch，硬编码**。
配合 `llama-model-loader.cpp:1430`：

```cpp
const size_t prefetch_size = prefetch && use_mmap ? -1 : 0;   // -1 = 整个文件
```

于是默认 mmap 会对**整个 14.4 GB 文件**发 `POSIX_MADV_WILLNEED`，等于要求把全部权重拉进页缓存。
在这台机器上必然换页抖动，随后 OOM。

## 方案：让专家张量走 lazy（上游已有机制，缺的是入口）

上游本来就有 `lazy_read`：被标记 `TENSOR_READ_LAZY` 的张量**不进预读**，只留在 mmap 映射里，
并改用 `POSIX_MADV_RANDOM`，由内核按缺页取。原来只给个别架构的 per-layer embedding 用。

`scripts/patches/0001-lazy-expert-tensors.patch`（约 40 行）加了一个入口：
环境变量 `LLAMA_LAZY_TENSORS` 给一串**子串**，张量名命中就强制 lazy，不受
`--lazy-mode` 与 4 GiB 阈值限制。

App 侧（`ServerProcessManager`）在**模型是 MoE 且体积 > 物理内存 50%** 时自动设置
`LLAMA_LAZY_TENSORS=exps`，同时：

- 内存闸让路（`!lazyExperts` 才按 85% 拒绝）——否则这个能力永远用不上；
- **上下文钳到 8192**（`LAZY_MAX_CTX`）：权重已占满内存，KV 再大照样 OOM。

## 实测（同机，35B-A3B Q4_K，ctx 2048）

| | 默认 mmap | 按需读 |
|---|---|---|
| 生成 | 0.2 tok/s，最终系统重启 | **1.3–2.7 tok/s，稳定** |
| 内存 | 吃穿 → OOM | `MemAvailable` 全程 9.4→10.0 GB，swap 不动 |
| 输出 | — | 正确（问茶文化起源，史实与条理都对） |
| lazy 覆盖 | 0 | **120 个张量（40 层 × 3），约 13.2 GB，占模型 92%** |

日志确认方式：

```
parse_forced_patterns: LLAMA_LAZY_TENSORS 生效，1 条模式强制按需读
add: tensor blk.0.ffn_up_exps.weight (size = 110 MiB) lazy read enabled
```

## 和竞品的差距

竞品宣称同一模型在 12 GB 手机上 5–6 tok/s，我们 1.3–2.7。原因基本可以确定：
它用 O_DIRECT 的 64 MB 对齐大块读，我们走内核 4 KB 缺页，随机读放大严重。

## 调参铁律（实测得出，别再重复试）

### 1. ngl 越高，解码越慢——混合卸载在这类模型上是负收益

35B-A3B（14.44 GB）+ NPU 引擎，同一提示，只改 ngl：

| ngl | 预填充 | 解码 |
|---|---|---|
| 99 | 280 s | **0.8** tok/s |
| 20 | 252 s | 1.9 tok/s |
| 1 | 227 s | **2.5** tok/s |

原因：lazy 的专家张量被固定在 **CPU buffer** 上（`lazy_read::buft()` 返回 CPU buffer type），
所以 ngl 越高，计算图被切得越碎——注意力/dense 在 DSP、专家在 CPU，
**每个 token 的每一层都要跨一次 fastRPC 边界**，往返开销直接吃掉 NPU 的算力优势。
预填充几乎不随 ngl 变（227–280 s），因为瓶颈是从闪存取 13.2 GB 专家，不是算力。

**结论**：这类「大到必须拆开算」的模型，最优配置是 ngl=1 或直接关掉 NPU，别开高。

### 2. NPU 只在「模型整体装得下」时才划算

| 模型 | 后端 | 预填充 | 解码 |
|---|---|---|---|
| 12B | **NPU** | **3 s** | ~4 tok/s |
| 12B | CPU | 180 s | 2.5–3 tok/s |
| 0.8B | NPU / CPU | 577 / 50 tok/s | 46 / 36 tok/s |

12B 能整体进 DSP/页缓存，预填充快 60 倍；一旦模型必须拆开（35B），跨边界开销把收益吃光。
**判断标准不是「模型多大」，而是「能不能整体装下」。**

### 3. 预填充的代价每次启动只付一次

llama-server 把上一次的 KV 留在 slot 里，同前缀直接复用（实测相同提示重发 0.18 s、
只换末尾一句 0.47 s）。**停止会话不影响缓存，重启服务才丢**。
所以「首字要等 227 秒」只发生在每次启动服务后的第一条消息，之后的追问是秒级。

## 下一步优化方向

- **专家级整块预读已经实现**（`scripts/patches/0002-moe-expert-prefetch.patch`）：在 `mul_mat_id`
  里对当前路由选中的专家发 `MADV_WILLNEED`，单次上限 512 MB 防止冲掉页缓存。
  **但效果尚未测出**——对照实验跑到一半超时（不预取那次光 prefill 就 494 秒）。
  上机后如果 35B 的预填充仍是分钟级，说明 4 KB 缺页不是唯一瓶颈，得往 O_DIRECT 对齐大块读走。
- 真正的天花板是「必须从闪存读 13.2 GB 专家」。不解小模型或加重量化，35B 的交互体验不可能好。

## 构建

`llama.cpp/` 是不入库的独立克隆，**重新克隆后补丁会丢**。`scripts/build-engine.ps1` 已加钩子：
构建前遍历 `scripts/patches/*.patch`，已应用的跳过（`git apply --check --reverse` 判定），
未应用的自动 `git apply`。

当前状态（`BinaryInstaller.VERSION = 8`，改动引擎后必须升这个值，否则已装设备不会重新释放）：

- **cpu / opencl**：`scripts/build-engine.ps1` 本地交叉编译，构建前自动应用两个补丁。
- **htp（NPU）**：Docker 构建（`llama.cpp/scripts/snapdragon/build.py --target adb`，不用 `--push`），
  构建完再跑 `scripts/assemble-htp-engine.ps1` 组装进 `assets/engines/htp`（挑必需库 + strip，
  跳过 DSP 侧的 `libggml-htp-*.so`）。**必须带 `libggml-opencl.so`**：它是整条链接链的硬依赖，
  少了会 `CANNOT LINK EXECUTABLE`；而它自己 NEEDED `libOpenCL.so`，所以运行时 htp 引擎的
  `LD_LIBRARY_PATH` 要带 `/vendor/lib64`。
- **prism**：另一条 fork，不应用这两个补丁；三元模型也跑不了 35B 这个量级。
