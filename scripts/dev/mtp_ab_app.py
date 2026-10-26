#!/usr/bin/env python
"""对着 App 里正在跑的服务做投机解码 A/B（不绕过 App，所以 NPU 也能测）。

为什么不用 mtp_bench.py：那支脚本用 `run-as` 直接在沙箱里起 llama-server，
NPU 引擎在这条路上起不来——FastRPC 能力查询失败（err 114），查不到 DSP 版本就退回 v73，
在 v81 的 DSP 上必挂。App 自己的进程里同一个引擎是正常的，所以 NPU 只能借 App 的服务。

用法：
  1) 在 App 里把配置调好（引擎 / 模型 / 投机解码），启动服务
  2) python scripts/dev/mtp_ab_app.py --label mtp-on
  3) 改设置、重启服务，再跑 --label mtp-off
  4) python scripts/dev/mtp_ab_app.py --compare mtp-on mtp-off
"""
import argparse
import json
import os
import re
import statistics
import subprocess
import sys
import time
import urllib.request

SER = os.environ.get("SER", "adb-9c57c895-Kwx2M3._adb-tls-connect._tcp")
PKG = "com.localllm.server"

PROMPT_A = "Summarize the water cycle in detail."
PROMPT_B = "Explain why the sky appears blue, then describe how rainbows form."


def adb(*args, timeout=120):
    r = subprocess.run(["adb", "-s", SER, *args], capture_output=True, timeout=timeout)
    return r.stdout.decode("utf-8", "replace")


def http(path, body=None, timeout=900, port=18080):
    url = "http://127.0.0.1:%d%s" % (port, path)
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


def app_log_tail(n=400):
    return adb("shell", "run-as", PKG, "tail", "-n", str(n), "files/logs.txt")


def draft_lines(text, limit=6):
    out = []
    for line in text.splitlines():
        low = line.lower()
        if "draft" in low or "speculative" in low:
            out.append(line.strip()[:180])
    return out[-limit:]


def run(label, runs, gen, dev_port, local_port):
    subprocess.run(["adb", "-s", SER, "forward", "tcp:%d" % local_port, "tcp:%d" % dev_port],
                   capture_output=True)
    try:
        props = http("/props", port=local_port, timeout=15)
    except Exception as e:
        print("连不上 App 的服务（%s）。先在 App 里启动服务再跑。" % e)
        return None
    # 从 props 里记录当前引擎与模型，避免张冠李戴
    info = {
        "model": (props.get("model_path") or "").split("/")[-1],
        "n_ctx": props.get("default_generation_settings", {}).get("n_ctx")
                 or props.get("n_ctx"),
        "build": props.get("build_info"),
    }
    print("服务信息：", json.dumps(info, ensure_ascii=False))

    sample = http("/v1/chat/completions", {
        "messages": [{"role": "user", "content": "hi"}],
        "max_tokens": 1, "temperature": 0.0,
    }, port=local_port)
    keys = sorted((sample.get("timings") or {}).keys())
    print("timings 字段：", keys)

    dec, pre = [], []
    for i in range(runs):
        for p in (PROMPT_A, PROMPT_B):
            d = http("/v1/chat/completions", {
                "messages": [{"role": "user", "content": p}],
                "max_tokens": gen, "temperature": 0.0, "ignore_eos": True,
            }, port=local_port)
            t = d.get("timings") or {}
            dec.append(float(t.get("predicted_per_second") or 0))
            if t.get("prompt_per_second"):
                pre.append(float(t["prompt_per_second"]))
            print("  第%d轮 %-6.2f tok/s" % (i + 1, dec[-1]), flush=True)

    res = {
        "label": label,
        "info": info,
        "decode_median": round(statistics.median(dec), 2),
        "decode_raw": [round(v, 2) for v in dec],
        "prefill_median": round(statistics.median(pre), 1) if pre else None,
        "timing_keys": keys,
        "log_draft": draft_lines(app_log_tail()),
    }
    fn = "mtp_ab_%s.json" % label
    with open(fn, "w", encoding="utf-8") as f:
        json.dump(res, f, ensure_ascii=False, indent=2)
    print("\n%s：decode 中位 %.2f tok/s（%d 次），prefill 中位 %s"
          % (label, res["decode_median"], len(dec), res["prefill_median"]))
    if res["log_draft"]:
        print("日志里的投机解码行：")
        for l in res["log_draft"]:
            print("   " + l)
    print("已写 %s" % fn)
    return res


def compare(a, b):
    ra, rb = json.load(open("mtp_ab_%s.json" % a, encoding="utf-8")), \
             json.load(open("mtp_ab_%s.json" % b, encoding="utf-8"))
    print("模型：%s vs %s" % (ra["info"]["model"], rb["info"]["model"]))
    if ra["info"]["model"] != rb["info"]["model"]:
        print("!! 两次不是同一个模型，数字不可比")
    print("%-10s decode 中位 %8.2f   prefill 中位 %s"
          % (a, ra["decode_median"], ra["prefill_median"]))
    print("%-10s decode 中位 %8.2f   prefill 中位 %s"
          % (b, rb["decode_median"], rb["prefill_median"]))
    print("比值 %s / %s = %.2fx" % (a, b, ra["decode_median"] / (rb["decode_median"] or 1)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--label", default="run")
    ap.add_argument("--runs", type=int, default=3)
    ap.add_argument("--gen", type=int, default=128)
    ap.add_argument("--dev-port", type=int, default=8080)
    ap.add_argument("--local-port", type=int, default=18080)
    ap.add_argument("--compare", nargs=2, metavar=("A", "B"))
    a = ap.parse_args()
    if a.compare:
        compare(*a.compare)
        return
    run(a.label, a.runs, a.gen, a.dev_port, a.local_port)


if __name__ == "__main__":
    main()
