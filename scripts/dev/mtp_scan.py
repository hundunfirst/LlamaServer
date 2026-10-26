#!/usr/bin/env python
"""扫手机上的 GGUF，找带 MTP（nextn 投机层）的模型。

只读每个文件的头部（默认 32MB，够覆盖 KV 段），不把模型拉下来。
用途：MTP 投机解码 A/B 需要「模型自带 MTP 层」；换引擎（如 htp）时还得是普通量化，
不能是 PQ2_0/PTQ1_0 那种只有 prism 认的包。

用法：
  python scripts/dev/mtp_scan.py                # 扫 files/models 下所有 .gguf
  python scripts/dev/mtp_scan.py a.gguf b.gguf  # 只扫指定文件
"""
import os
import struct
import subprocess
import sys

SER = os.environ.get("SER", "adb-9c57c895-Kwx2M3._adb-tls-connect._tcp")
PKG = "com.localllm.server"

FMT = {0: "B", 1: "b", 2: "H", 3: "h", 4: "I", 5: "i", 6: "f", 7: "?",
       10: "Q", 11: "q", 12: "d"}
SIZE = {0: 1, 1: 1, 2: 2, 3: 2, 4: 4, 5: 4, 6: 4, 7: 1, 10: 8, 11: 8, 12: 8}


class Buf:
    def __init__(self, data):
        self.d = data
        self.i = 0

    def take(self, n):
        if self.i + n > len(self.d):
            raise EOFError
        b = self.d[self.i:self.i + n]
        self.i += n
        return b

    def u32(self):
        return struct.unpack("<I", self.take(4))[0]

    def u64(self):
        return struct.unpack("<Q", self.take(8))[0]

    def s(self):
        return self.take(self.u64()).decode("utf-8", errors="replace")


def skip_val(b, t):
    """跳过值，返回可打印的摘要（字符串/标量给值，数组只给长度）"""
    if t == 8:
        return b.s()
    if t == 9:
        at = b.u32()
        n = b.u64()
        for _ in range(n):
            skip_val(b, at)
        return "[array %d]" % n
    fmt = FMT.get(t)
    if fmt is None:
        raise ValueError("unknown kv type %d" % t)
    return struct.unpack("<" + fmt, b.take(SIZE[t]))[0]


def fetch(name, head_mb=32):
    r = subprocess.run(["adb", "-s", SER, "exec-out", "run-as", PKG, "sh", "-c",
                        "head -c %d files/models/%s" % (head_mb * 1024 * 1024, name)],
                       capture_output=True)
    return r.stdout if r.stdout.startswith(b"GGUF") else b""


def scan(name):
    data = fetch(name)
    if not data:
        print("%-52s 读不到（不是 GGUF？）" % name)
        return None
    b = Buf(data)
    b.take(4)
    b.u32()
    b.u64()  # n_tensors
    n_kv = b.u64()
    kv = {}
    try:
        for _ in range(n_kv):
            k = b.s()
            kv[k] = skip_val(b, b.u32())
    except EOFError:
        pass
    arch = kv.get("general.architecture", "?")
    ft = kv.get("general.file_type", "?")
    mtp = {k: v for k, v in kv.items()
           if "nextn" in k.lower() or "mtp" in k.lower() or "draft" in k.lower()}
    ternary = int(ft) in (142, 143) if str(ft).isdigit() else False
    print("%-52s arch=%-10s file_type=%-5s kv=%d  %s%s" % (
        name, arch, ft, len(kv),
        ("MTP: " + ", ".join("%s=%s" % (k, v) for k, v in mtp.items())) if mtp else "无 MTP 键",
        "  [三元包，只有 prism 认]" if ternary else ""))
    return mtp


def main():
    names = sys.argv[1:]
    if not names:
        out = subprocess.run(["adb", "-s", SER, "shell", "run-as", PKG, "ls", "files/models"],
                             capture_output=True).stdout.decode("utf-8", "replace")
        names = [n.strip() for n in out.splitlines() if n.strip().endswith(".gguf")]
    for n in names:
        scan(n)


if __name__ == "__main__":
    main()
