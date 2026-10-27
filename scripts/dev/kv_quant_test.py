#!/usr/bin/env python
"""验证「量化 KV 在各引擎上能不能起」：同一模型、同一组 -ctk/-ctv，只换引擎。

背景：NPU(htp) 上设 --cache-type-k q4_0 会 ggml_abort：
  pre-allocated tensor (cache_k_l3 (view)) in a buffer (HTP0)
  that cannot run the operation (SET_ROWS)
这里跑 CPU 引擎做对照，确认是引擎能力问题而不是参数写错。

用法：python scripts/dev/kv_quant_test.py [--engine cpu] [--k q4_0] [--v q8_0]
"""
import argparse
import os
import subprocess
import time

SER = os.environ.get("SER", "adb-9c57c895-Kwx2M3._adb-tls-connect._tcp")
PKG = "com.localllm.server"
REMOTE = "/data/user/0/%s/files" % PKG
MODEL = "Qwen3.5-4B-Uncensored-HauhauCS-Aggressive-Q4_K_M.gguf"


def adb(*a, timeout=180):
    return subprocess.run(["adb", "-s", SER, *a], capture_output=True,
                          timeout=timeout).stdout.decode("utf-8", "replace")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--engine", default="cpu")
    ap.add_argument("--k", default="q4_0")
    ap.add_argument("--v", default="q8_0")
    ap.add_argument("--ctx", type=int, default=2048)
    ap.add_argument("--wait", type=int, default=150)
    a = ap.parse_args()

    adb("shell", "run-as", PKG, "mkdir", "-p", REMOTE + "/bench")
    log = REMOTE + "/bench/kvquant_%s.log" % a.engine
    adb("shell", "run-as", PKG, "sh", "-c", "rm -f " + log)
    adb("shell", "run-as", PKG, "pkill", "-f", "llama-server")

    cmd = ("cd {r}/engines/{e} && LD_LIBRARY_PATH=.:/vendor/lib64 nohup ./llama-server "
           "-m {r}/models/{m} -c {c} -t 6 -ngl 0 --cache-type-k {k} --cache-type-v {v} -fa on "
           "--host 127.0.0.1 --port 18087 > {r}/bench/kvquant_{e}.log 2>&1 </dev/null &"
           ).format(r=REMOTE, e=a.engine, m=MODEL, c=a.ctx, k=a.k, v=a.v)
    subprocess.Popen(["adb", "-s", SER, "shell", "run-as", PKG, "sh", "-c", cmd],
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    body = ""
    deadline = time.time() + a.wait
    while time.time() < deadline:
        time.sleep(3)
        body = adb("shell", "run-as", PKG, "cat", log)
        low = body.lower()
        if ("model loaded" in low or "listening on" in low
                or "set_rows" in low or "ggml_abort" in low or "exiting" in low):
            break

    keys = ("cache", "loaded", "listening", "abort", "error", "set_rows", "exiting")
    lines = [l for l in body.splitlines() if any(k in l.lower() for k in keys)]
    print("引擎 %s  -ctk %s -ctv %s" % (a.engine, a.k, a.v))
    print("\n".join(lines[:14]) if lines else "(没有匹配行，日志长度 %d)" % len(body))
    adb("shell", "run-as", PKG, "pkill", "-f", "llama-server")


if __name__ == "__main__":
    main()
