"""生成一个最小合法 GGUF 头（无张量），用来验证 App 的"这是投影器不是主模型"识别。

用法: python scripts/dev/fake_gguf.py <输出路径> <architecture> [name]
例:  python scripts/dev/fake_gguf.py tmp-clip.gguf clip
"""
import struct
import sys

path = sys.argv[1]
arch = sys.argv[2] if len(sys.argv) > 2 else "clip"
name = sys.argv[3] if len(sys.argv) > 3 else "fake " + arch


def s(v: str) -> bytes:
    b = v.encode("utf-8")
    return struct.pack("<Q", len(b)) + b


kv = b""
kv += s("general.architecture") + struct.pack("<I", 8) + s(arch)
kv += s("general.name") + struct.pack("<I", 8) + s(name)
kv += s("general.size_label") + struct.pack("<I", 8) + s("test")

data = b"GGUF" + struct.pack("<I", 3) + struct.pack("<Q", 0) + struct.pack("<Q", 3) + kv
open(path, "wb").write(data)
print("wrote", path, len(data), "bytes, arch =", arch)
