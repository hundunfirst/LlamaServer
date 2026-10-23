# Hexagon NPU 引擎：构建与接入

高通 SoC 专用。走 DSP（Hexagon/HTP）做推理，实测比 GPU/CPU 快一个数量级。
上游 llama.cpp 自带 `ggml-hexagon` 后端（`GGML_HEXAGON` 默认 OFF），我们把它编成第四套引擎 `htp`。

## 实测性能（本机 SM8850 / Adreno 840，2026-10-23）

`llama-bench`，Qwen3.5-0.8B-Q4_K_M：

| 测试 | 数值 |
|---|---|
| pp128（预填充） | **2490.96 ± 19.40 t/s** |
| tg64（解码） | **69.34 ± 0.63 t/s** |

### 同机 A/B（0.8B-Q4_K_M、ctx 8192、同一 prompt、同一 OpenAI 接口）

| | CPU 引擎 | NPU 引擎 | 倍数 |
|---|---|---|---|
| 预填充（834 token） | 16.68 s → **50 tok/s** | 1.45 s → **577 tok/s** | **≈11.5×** |
| 解码（256 token） | 7.15 s → **35.8 tok/s** | **45.8–46.2 tok/s** | ≈1.3× |

结论要分开说，别混：

- **NPU 的强项是预填充（约 11.5 倍）**，因为那是矩阵乘密集、可大规模并行的部分。
  对 agent 式用法（长系统提示 + 工具 schema + 长期记忆）收益最大——省的就是首字延迟。
- **解码只快约 1.3 倍**。0.8B 这种小模型的 decode 主要受内存带宽限制，6 线程 CPU 已经能到 36 tok/s，
  NPU 拿不到数量级提升。所以「开了 NPU 但感觉出字没快多少」是正常的，不是没生效。
- 口径提醒：`llama-bench` 报的 `pp128 2491 / tg64 69.3` 是引擎内核口径；走 OpenAI 接口会再折掉
  模板、采样、每 token 序列化与更大 ctx，落到上表的 577 / 46。**比性能时必须同口径。**
- 之前文档里「解码快 8 倍」的说法是错的：那个 8–9 tok/s 来自竞品 App 上 35B-A3B MoE，模型完全不同，不可比。

### 上下文大小对解码的影响（实测，别再用「小模型更快」的直觉）

同一个 0.8B-Q4_K_M、同一 NPU 引擎、只改 `-c`：

| ctx | 解码 |
|---|---|
| 8192 | 45.8–46.2 tok/s |
| 32768 | 33.7 / 39.4 tok/s（均值 ≈36.5） |

**上下文翻 4 倍，解码掉约 22%。** 因为 NPU 侧 KV cache 随 `-c` 一起放大，超出 DSP 可用内存后
注意力部分会退到 CPU。真机案例：`qwen2.5-0.5b` 在 ctx 32768 下只有 28.7–30.1 tok/s，
换成 0.8B 在 ctx 8192 反而有 46——**看起来「小模型更慢」，其实是被上下文吃掉了**。

同 ctx（32K）下 0.5B 仍比 0.8B 慢约 17%：每 token 的 DSP 调度/fastRPC 固定开销占比更高，
加上 qwen2.5 这类老架构在 HTP 上算子覆盖不如 qwen3.5。**结论：想快就压 ctx，别指望换更小的模型。**

### Q4_0 vs Q4_K_M（同引擎、ctx 8192、同 prompt、同接口，0.8B）

| | 解码 | 预填充（834 token） |
|---|---|---|
| Qwen3.5-0.8B-**Q4_0** | 44.5 / 43.4 tok/s | **2175 tok/s** |
| Qwen3.5-0.8B-**Q4_K_M** | 43.0 / 40.7 tok/s | 825 tok/s |

- **解码几乎无差别**（44 vs 42，噪声内）。所以「换了 Q4_0 感觉没变快」是对的——看到的就是解码速度。
- **预填充 Q4_0 快约 2.6 倍**，这才是「NPU 对 Q4_0 有原生 kernel」的实际体现，收益落在**首字延迟**上。
- 竞品手册说「Q4_K_M 会掉到 CPU 速度」是夸张：实测 825 tok/s 仍是纯 CPU（50 tok/s）的 16 倍，
  只是不如 Q4_0 的原生路径。**长提示/首字敏感选 Q4_0；纯看吐字速度两者都行（Q4_0 体积还小 5%）。**
- 别被「越跑越快」迷惑：App 的 tok/s 是**累计平均**（总 token / 总耗时），生成越长，
  开头那段预填充被摊得越薄，数字自然往上爬——不是真的在加速。

启动日志关键行（确认真的走了 NPU）：

```
ggml-hex: Loading driver libcdsprpc.so
ggml-hex: using CDSP domain: instance-id 0 id 1000 name 'nsp1000'
ggml-hex: Hexagon backend (experimental) : allocating new registry : ndev 1
ggml-hex: Hexagon Arch version v81, DMA64 enabled
ggml-hex: device 0: HTP0 (phys=0, virt=0, domain=nsp1000:1000)
```

**注意：8 Elite Gen 5（SM8850 / Adreno 840）报的是 HTP v81，不是 v79。**
竞品 PocketOrca 的兼容性矩阵把这一档写成 v79 且标注未测，是错的。

## 构建（Docker 路线，宿主机 x86_64 Windows 可用）

前置：Docker Desktop（WSL2 后端）、`adb` 在 PATH 里。

```powershell
# 1. 拉工具链镜像（数 GB；该镜像含 linux/amd64，x86_64 宿主原生运行，不走 qemu）
docker pull ghcr.io/snapdragon-toolchain/arm64-android:v0.7

# 2. 构建 + 推到手机（产物同时留在 llama.cpp\pkg-adb\llama.cpp）
cd llama.cpp
$env:PATH += ";C:\Users\mingliang\AppData\Local\Android\Sdk\platform-tools"
python scripts\snapdragon\build.py --target "adb:<序列号>" --push
```

- `--hex-only --hex-arch v81` 只编译 Hexagon 相关库，**不含 llama-server**，不能当引擎用。
- 宿主机是 AMD64 时不要用 `setup-sdk.py` 装裁剪版 SDK：GitHub 上那些包全是 `-arm64-wos`
  （Windows-on-ARM 宿主），装了也跑不了。要原生构建只能装高通官网的完整 SDK（需账号）。
- 产物布局：`pkg-adb\llama.cpp\{bin,lib}`。`bin/llama-server` 只有 ~10KB，是加载
  `lib/libllama-server-impl.so` 的启动器。

## 组装成 App 的引擎目录

`app/src/main/assets/engines/htp/` 是**扁平**的（和 cpu/opencl 一致，不放 `bin`/`lib` 子目录），
需要这些文件：

```
llama-server
libggml.so  libggml-base.so  libggml-cpu.so  libggml-hexagon.so
libggml-htp-v73.so  libggml-htp-v75.so  libggml-htp-v79.so  libggml-htp-v81.so
libllama.so  libllama-common.so  libllama-server-impl.so  libmtmd.so
```

两条硬性注意：

1. **`libggml-opencl.so` 必须带**。Docker 预设默认打开了 OpenCL，`llama-server`、`libllama.so`、
   `libggml.so`、`libmtmd.so` 全都 `DT_NEEDED` 它；剔掉的话 exec 阶段就报
   `CANNOT LINK EXECUTABLE: library "libggml-opencl.so" not found`，连 `main()` 都进不去。
   而它自己又 `NEEDED libOpenCL.so`，所以 htp 引擎的 `LD_LIBRARY_PATH` 也要带 `/vendor/lib64`
   （与 GPU 引擎同样处理）。用 `llvm-readelf -d` 核对完整依赖闭包，别凭猜。
2. **必须 strip**。CMake 出来的库是全符号的，14 个文件共 158 MB；用 NDK 的
   `llvm-strip --strip-unneeded` 处理（**跳过 `libggml-htp-*.so`**，那是 DSP 侧二进制），
   体积降到 20.7 MB。APK 增约 9 MB。

引擎内容有变动时记得**升 `BinaryInstaller.VERSION`**，否则 `files/engines/<mode>/` 里的旧副本
不会重装（标记相同就直接复用），表现为「改了 assets 但行为没变」。

## 两个运行时坑（真机踩过）

- **DSP 会话是独占的**。一个进程占着 HTP0 时，第二个进程会失败：
  `ggml-hex: HTP0 failed to open session ... error 0x80000406`，
  上层只看到 `failed to create context`。所以**残留的 llama-server（比如 App 进程被杀但子进程还在）
  会让 NPU 起不来**，而 CPU/GPU 引擎不会。排查时先 `ps -A | grep llama-server` 清干净。
- **unsupported op 会静默回退到 CPU**。日志里是
  `warmup: list of unsupported ops (backend=HTP0): MUL_MAT: type = f32, ne = [...]` ——
  这时 NPU 确实在用（`backend=HTP0`），但那些 f32 矩阵乘走 CPU，速度会掉到接近纯 CPU。
  实测 4B-Q4_K_M 挂 BF16 mmproj + 64K ctx 只有 7.3 tok/s；0.8B-Q4_K_M（ctx 4096、无 mmproj）
  是 69 t/s。**mmproj 是 f32/BF16 算子的主要来源，纯文本优先别挂**。

## App 侧接线（已实现）

- `BinaryInstaller.MODE_HTP` + `modeFor(useGpu, prism, npu)`：NPU 优先级最高。
- `AppSettings.npuEnabled`（`npu_engine`）→ 设置页「Hexagon NPU（高通专用）」开关。
- 开启后强制 `ngl=99`，并给 `buildServerArgs` 传 `npuDevice = "HTP0"` → 追加 `--device HTP0`。
- **`ADSP_LIBRARY_PATH` 必须设成引擎目录**（和 `LD_LIBRARY_PATH` 同值）。DSP 侧的 skel 由
  fastRPC 在 CDSP 上加载，找的是 `ADSP_LIBRARY_PATH`；漏了它只报「找不到设备」，很难联想到此处。
- NPU 下**不做 ngl 减半降级**：Hexagon 没有「少卸载几层」的退路。

## 加载模式（`-lm`）的真相：别拿 dio 当「专家流式」

App 设置里已暴露 `-lm`（自动 / dio / mmap / 锁内存）。机制在 `llama-model-loader.cpp:560`：

```cpp
use_mmap      = (load_mode == MMAP || MMAP_MLOCK || AUTO);
use_direct_io = (load_mode == DIRECT_IO);
```

- **只有 auto / mmap / mmap+mlock 才用 mmap**。模型**大于**物理内存时，只有这条路能跑
  （权重留在文件映射里按需换页）——代价就是 35B 那种 14.4 GB / 15.4 GB 的组合会疯狂换页，
  实测 0.2 tok/s，且可能把系统拖进 OOM 重启。
- **`dio` 会关掉 mmap**，把权重读进已分配内存。所以它**不是**让大模型跑起来的办法，
  对「模型大于内存」的场景是**更糟**的选择（直接内存不足）。它适用的场景只有一个：
  模型装得下、但系统太忙导致 mmap 抖动严重时，绕开 page cache 可能更快。
- **竞品那种「35B-A3B 跑在 12 GB 手机上」不是这个开关**。他们 README 说的 O_DIRECT
  专家流式是按专家按需读的自研 native 加载器（所以其 Java 配置里找不到任何相关参数）。
  想复刻要改 `llama-model-loader` 的权重加载与 MoE 层的 buffer 管理，是独立项目。

配套保护：`ServerProcessManager` 有内存闸，模型超过物理内存 85% 直接拒绝启动（中文提示），
所以「选 dio + 35B」这种组合会先被拦下，不会真的把机器搞崩。

## 已知限制

- 只吃 Q4_0 / IQ4_NL / Q4_K 这类量化；PrismML 三元打包（PQ2_0 / PTQ1_0）用不了，
  启动前的引擎×打包门控会拦下并提示关掉 NPU。
- 适合 ≤9B；35B 级 MoE 走不了 NPU。
- 上游标注该后端为 experimental。
