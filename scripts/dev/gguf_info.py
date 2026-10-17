#!/usr/bin/env python
"""读 GGUF 头，判断一个文件到底是主模型还是附属件（不用把整个文件拉下来）。

用法:
  python scripts/dev/gguf_info.py <手机上的文件名> [--head-mb 8]
  python scripts/dev/gguf_info.py --local <本地路径>

判据（来自 GGUF 元数据，比文件名可靠）:
  general.type          model / mmproj / adapter / imatrix
  general.architecture  正常架构(qwen3/qwen35/llama…) = 主模型；
                        dflash / dspark = 投机草稿；clip = 视觉编码器
  n_tensors + 张量类型分布  主模型有 token_embd/output 和完整层张量
"""
import collections
import os
import struct
import subprocess
import sys

SER = os.environ.get("SER", "9c57c895")
PKG = "com.localllm.server"

FMT = {0: "B", 1: "b", 2: "H", 3: "h", 4: "I", 5: "i", 6: "f", 7: "?", 10: "Q", 11: "q", 12: "d"}
SIZE = {0: 1, 1: 1, 2: 2, 3: 2, 4: 4, 5: 4, 6: 4, 7: 1, 10: 8, 11: 8, 12: 8}
TYPES = {0: "F32", 1: "F16", 2: "Q4_0", 3: "Q4_1", 6: "Q5_0", 7: "Q5_1", 8: "Q8_0", 9: "Q8_1",
         10: "Q2_K", 11: "Q3_K", 12: "Q4_K", 13: "Q5_K", 14: "Q6_K", 15: "Q8_K", 16: "IQ2_XXS",
         17: "IQ2_XS", 18: "IQ3_XXS", 19: "IQ1_S", 20: "IQ4_NL", 21: "IQ3_S", 22: "IQ2_S",
         23: "IQ4_XS", 26: "I32", 28: "F64", 29: "IQ1_M", 30: "BF16", 34: "TQ1_0", 35: "TQ2_0",
         39: "MXFP4", 40: "NVFP4", 41: "Q1_0", 42: "Q2_0", 142: "PQ2_0"}


class R:
    def __init__(self, b):
        self.b, self.i = b, 0

    def take(self, n):
        if self.i + n > len(self.b):
            raise EOFError("head 不够大，加大 --head-mb")
        v = self.b[self.i:self.i + n]
        self.i += n
        return v

    def u32(self):
        return struct.unpack("<I", self.take(4))[0]

    def u64(self):
        return struct.unpack("<Q", self.take(8))[0]

    def s(self):
        return self.take(self.u64()).decode("utf-8", errors="replace")


def fetch(name, head_mb):
    n = head_mb * 1024 * 1024
    r = subprocess.run(["adb", "-s", SER, "exec-out", "run-as", PKG, "sh", "-c",
                        "head -c %d files/models/%s" % (n, name)], capture_output=True)
    if not r.stdout.startswith(b"GGUF"):
        r = subprocess.run(["adb", "-s", SER, "exec-out", "run-as", PKG, "sh", "-c",
                            "head -c %d files/%s" % (n, name if "/" in name else "projectors/" + name)], capture_output=True)
    if not r.stdout.startswith(b"GGUF"):
        print("  读不到文件（不是 GGUF 或路径不对）:", name)
    return r.stdout


def analyze(data, label):
    print("=" * 68)
    print(label)
    if data[:4] != b"GGUF":
        print("  不是 GGUF（magic=%r）" % data[:4])
        return
    r = R(data)
    r.take(4)
    ver, n_t, n_kv = r.u32(), r.u64(), r.u64()
    meta = {}
    try:
        for _ in range(n_kv):
            k = r.s()
            t = r.u32()
            if t == 8:
                meta[k] = r.s()
            elif t == 9:
                et = r.u32()
                n = r.u64()
                if et == 8:
                    for i in range(n):
                        v = r.s()
                        if i == 0:
                            meta[k] = "<str array n=%d: %s...>" % (n, v[:24])
                else:
                    for _ in range(n):
                        r.take(SIZE[et])
                    meta[k] = "<array n=%d>" % n
            elif t in FMT:
                meta[k] = struct.unpack("<" + FMT[t], r.take(SIZE[t]))[0]
            else:
                meta[k] = "<unknown %d>" % t
    except EOFError as e:
        print("  (元数据没读完：%s)" % e)

    keys = ("general.type", "general.architecture", "general.name", "general.size_label",
            "general.quantization_version")
    print("  gguf=%d n_tensors=%d n_kv=%d" % (ver, n_t, n_kv))
    for k in keys:
        if k in meta:
            print("  %-24s %s" % (k, str(meta[k])[:70]))

    hist = collections.Counter()
    try:
        for _ in range(n_t):
            r.s()
            nd = r.u32()
            for _ in range(nd):
                r.u64()
            t = r.u32()
            r.u64()
            hist[TYPES.get(t, "type%d" % t)] += 1
        print("  张量类型分布        %s" % dict(hist))
    except EOFError:
        print("  张量列表超出 head 范围（不影响 arch/type 判断）")

    gt = str(meta.get("general.type", "")).lower()
    arch = str(meta.get("general.architecture", "")).lower()
    print("  → 判断：", end="")
    if gt in ("mmproj",) or arch in ("clip",):
        print("多模态投影器（配 --mmproj，不能单独当主模型）")
    elif gt in ("adapter", "lora"):
        print("LoRA 适配器（配主模型用 --lora）")
    elif gt in ("imatrix",):
        print("重要性矩阵（量化用料，不是模型）")
    elif arch in ("dflash", "dspark", "eagle3", "draft", "mtp"):
        print("投机解码草稿/侧车（--spec-type 用，不能当主模型）")
    else:
        print("主模型（arch=%s，可直接 -m 加载）" % (arch or "未知"))


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(0)
    if sys.argv[1] == "--local":
        p = sys.argv[2]
        analyze(open(p, "rb").read(int(sys.argv[3]) * 1024 * 1024 if len(sys.argv) > 3 else 8 << 20),
                p)
    else:
        mb = 8
        if "--head-mb" in sys.argv:
            mb = int(sys.argv[sys.argv.index("--head-mb") + 1])
        name = sys.argv[1]
        analyze(fetch(name, mb), name)
