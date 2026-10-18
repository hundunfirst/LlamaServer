#!/usr/bin/env python
"""投机解码（MTP 等）真机 A/B 基准。

在手机上直接起 llama-server（绕过 App），交替跑多组配置：每轮重启服务并交换顺序，
抵消 CPU 热衰减造成的整体下滑；同轮还会记录 CPU/电池温度当噪声证据。

用法:
  python scripts/dev/mtp_bench.py --engine prism --runs 6 \
      --configs "none@Ternary-Bonsai-2-4B-PQ2_0.gguf;none;draft-mtp:3;draft-mtp:8"

  配置写法  type[:nmax][@模型文件名]   没写 @ 就用 --model
  --ctx 8192   --threads 6   --port 18085   --gen 64   --long（加一个长提示 measurement）
"""
import argparse
import json
import os
import re
import statistics
import subprocess
import time
import urllib.request

SER = os.environ.get("SER", "adb-9c57c895-Kwx2M3._adb-tls-connect._tcp")
PKG = "com.localllm.server"
REMOTE = "/data/user/0/%s/files" % PKG
PORT = 18085

PROMPT_A = "Summarize the water cycle in detail."
PROMPT_B = "Explain why the sky appears blue, then describe how rainbows form."
LONG_SEED = (
    "Photosynthesis is the process by which green plants, algae and some bacteria "
    "convert light energy into chemical energy stored in glucose. Chlorophyll in the "
    "thylakoid membranes absorbs photons, exciting electrons that travel down an "
    "electron transport chain and pump protons into the lumen. The resulting "
    "proton gradient drives ATP synthase, while the electrons eventually reduce NADP+ "
    "to NADPH. In the stroma, the Calvin cycle uses ATP and NADPH to fix carbon "
    "dioxide into three-carbon sugars, catalyzed by rubisco. "
)


def adb(*args, binary=False, timeout=180):
    r = subprocess.run(["adb", "-s", SER, *args], capture_output=True, timeout=timeout)
    return r.stdout if binary else r.stdout.decode("utf-8", errors="replace")


def rapp(*args):
    """在 App 沙箱内跑一条简单命令（参数逐个传，不做 shell 拼接）。"""
    return adb("shell", "run-as", PKG, *args)


def server_pids():
    out = rapp("pgrep", "-f", "llama-server")
    return [l.strip() for l in out.split() if l.strip().isdigit()]


def kill_server():
    rapp("pkill", "-f", "llama-server")
    for _ in range(30):
        if not server_pids():
            return
        time.sleep(0.5)


def tail_log(logname, n=25):
    return rapp("tail", "-n", str(n), "%s/bench/%s" % (REMOTE, logname))


def temps():
    out = adb("shell", "dumpsys thermalservice")
    cpu = [float(m) for m in re.findall(r"mValue=([\d.]+), mType=0", out)]
    bat = re.findall(r"mValue=([\d.]+), mType=2", out)
    return (round(max(cpu), 1) if cpu else None,
            round(float(bat[0]), 1) if bat else None)


def http_json(path, body=None, timeout=900):
    url = "http://127.0.0.1:%d%s" % (PORT, path)
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data,
                                headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


def start_server(engine, model, spec, nmax, ctx, threads, fa="on", env="", ngl=0, extra=""):
    kill_server()
    tag = "%s_%s_%s" % (re.sub(r"[^\w.-]", "_", model.replace(".gguf", ""))[:24],
                        spec, nmax if nmax else "def")
    logname = "bench_%s.log" % re.sub(r"[^\w.-]", "_", tag)
    rapp("mkdir", "-p", "%s/bench" % REMOTE)
    cmd = ("cd %s/engines/%s && LD_LIBRARY_PATH=.:/vendor/lib64 %snohup setsid ./llama-server "
           "-m %s/models/%s --host 127.0.0.1 --port %d -t %d -ngl %d -c %d"
           % (REMOTE, engine, (env + " ") if env else "", REMOTE, model, PORT, threads, ngl, ctx))
    cmd += (" -fa off" if fa == "off" else " -fa on")
    if spec and spec != "none":
        cmd += " --spec-type %s" % spec
        if nmax:
            cmd += " --spec-draft-n-max %d" % nmax
    if extra:
        cmd += " " + extra
    cmd += " > %s/bench/%s 2>&1 </dev/null &" % (REMOTE, logname)
    t0 = time.time()
    # 后台服务会一直占住 pty，这条 adb 连接不能等它返回
    subprocess.Popen(["adb", "-s", SER, "shell", 'run-as %s sh -c "%s"' % (PKG, cmd)],
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    deadline = t0 + 300
    while time.time() < deadline:
        time.sleep(2)
        try:
            http_json("/health", timeout=5)
            return logname, time.time() - t0
        except Exception:
            if not server_pids():
                raise SystemExit("服务启动即退出：\n" + tail_log(logname))
    raise SystemExit("等待 /health 超时：\n" + tail_log(logname))


def ask(text, max_tokens, lora_scale=None):
    body = {"messages": [{"role": "user", "content": text}],
            "max_tokens": max_tokens, "temperature": 0.0, "ignore_eos": True}
    if lora_scale is not None:
        # 需要启动时带 --lora-init-without-apply，强度才能按请求调
        body["lora"] = [{"id": 0, "scale": lora_scale}]
    d = http_json("/v1/chat/completions", body)
    return d.get("timings") or {}, ((d.get("choices") or [{}])[0].get("message") or {}).get("content") or ""


def draft_lines(logname):
    log = rapp("cat", "%s/bench/%s" % (REMOTE, logname))
    out = []
    for line in log.splitlines():
        low = line.lower()
        if "draft acceptance" in low or "speculative decoding enabled" in low:
            out.append(line.strip()[:160])
    return out


def parse_cfg(c, default_model):
    model = default_model
    if "@" in c:
        c, model = c.split("@", 1)
    spec, _, nmax = c.partition(":")
    nmax = int(nmax) if nmax.strip() else 0
    label = "%s%s%s" % (spec.strip(), (":%d" % nmax) if nmax else "",
                        ("@" + model if model != default_model else ""))
    return label, spec.strip(), nmax, model


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="Ternary-Bonsai-2-4B-PQ2_0-MTP-Q8_0.gguf")
    ap.add_argument("--engine", default="prism")
    ap.add_argument("--configs", default="none;draft-mtp:3")
    ap.add_argument("--runs", type=int, default=6)
    ap.add_argument("--ctx", type=int, default=8192)
    ap.add_argument("--threads", type=int, default=6)
    ap.add_argument("--port", type=int, default=18085)
    ap.add_argument("--gen", type=int, default=64)
    ap.add_argument("--ngl", type=int, default=0)
    ap.add_argument("--long", action="store_true")
    ap.add_argument("--fa", default="on", choices=["on", "off"])
    ap.add_argument("--env", default="", help="给 llama-server 加环境变量，如 LLAMA_MTP_CHAIN=1")
    ap.add_argument("--extra", default="", help="原样追加给 llama-server 的参数，如 --lora x.gguf")
    ap.add_argument("--lora-scale", type=float, default=None, help="按请求下发 lora 强度（需 --extra 带 --lora-init-without-apply）")
    ap.add_argument("--show", type=int, default=0, help="打印回答前 N 个字符")
    a = ap.parse_args()

    global PORT
    PORT = a.port
    subprocess.run(["adb", "-s", SER, "forward", "tcp:%d" % PORT, "tcp:%d" % PORT],
                   capture_output=True)
    adb("shell", "am force-stop %s" % PKG)
    long_prompt = ("Read the following passage and then write a 200-word explanation of "
                   "how it connects to plant growth.\n\n" + LONG_SEED * 2)

    cfgs = [parse_cfg(c, a.model) for c in a.configs.split(";") if c.strip()]
    samples = {label: {"dec": [], "prefill": [], "load": [], "acc": [], "draft_t": []}
               for label, _, _, _ in cfgs}
    for r in range(a.runs):
        order = cfgs if r % 2 == 0 else list(reversed(cfgs))
        t_cpu, t_bat = temps()
        print("-- 轮%d  CPU %s°C  电池 %s°C" % (r + 1, t_cpu, t_bat), flush=True)
        for label, spec, nmax, model in order:
            logname, load_s = start_server(a.engine, model, spec, nmax, a.ctx, a.threads,
                                           a.fa, a.env, a.ngl, a.extra)
            decs = []
            for p in (PROMPT_A, PROMPT_B):
                t, content = ask(p, a.gen, a.lora_scale)
                decs.append(float(t.get("predicted_per_second") or 0))
                if a.show:
                    print("     回答：%s" % content[:a.show].replace("\n", " "), flush=True)
            line = "   %-34s 起 %4.1fs | decode %6.2f / %6.2f" % (label, load_s, decs[0], decs[1])
            s = samples[label]
            if a.long:
                tl, _ = ask(long_prompt, a.gen, a.lora_scale)
                s["prefill"].append(float(tl.get("prompt_per_second") or 0))
                decs.append(float(tl.get("predicted_per_second") or 0))
                line += " / 长 %6.2f | prefill %5.1f (%d tok)" % (
                    decs[2], float(tl.get("prompt_per_second") or 0),
                    int(tl.get("prompt_n") or 0))
                if any("draft" in k.lower() for k in tl):
                    s["draft_t"].append({k: v for k, v in tl.items() if "draft" in k.lower()})
            print(line, flush=True)
            s["dec"].append(statistics.mean(decs))
            s["load"].append(load_s)
            s["acc"] = draft_lines(logname)
    kill_server()

    rows = {}
    for label, s in samples.items():
        rows[label] = {
            "decode_median": round(statistics.median(s["dec"]), 2),
            "raw": [round(v, 2) for v in s["dec"]],
            "prefill_median": round(statistics.median(s["prefill"]), 1) if s["prefill"] else None,
            "load_median": round(statistics.median(s["load"]), 1),
            "draft_timings": s["draft_t"],
            "log_lines": s["acc"][-4:],
        }

    print("\n%-36s %10s %9s %8s" % ("config", "decode", "prefill", "load s"))
    for label, r in rows.items():
        print("%-36s %10.2f %9s %8.1f"
              % (label, r["decode_median"],
                 "-" if r["prefill_median"] is None else r["prefill_median"], r["load_median"]))
    base = rows.get("none")
    if base:
        print("\n相对 none（同模型）的 decode：")
        for label, r in rows.items():
            if label == "none":
                continue
            print("  %-32s %.2fx" % (label, r["decode_median"] / (base["decode_median"] or 1)))
    for label, r in rows.items():
        if r["log_lines"]:
            print("\n%s 日志：" % label)
            for line in r["log_lines"]:
                print("   " + line)
    with open("mtp_bench_result.json", "w", encoding="utf-8") as f:
        json.dump(rows, f, ensure_ascii=False, indent=2)
    print("\n明细已写 mtp_bench_result.json")


if __name__ == "__main__":
    main()
