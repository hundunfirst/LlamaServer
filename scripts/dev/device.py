#!/usr/bin/env python
"""璁惧璋冭瘯灏忓伐鍏凤紙鐪熸満楠岃瘉鐢級銆?
鐢ㄦ硶:
  python scripts/dev/device.py ui [姝ｅ垯]              # dump 褰撳墠鐣岄潰骞跺垪鍑烘枃鏈妭鐐?  python scripts/dev/device.py tap X Y                 # 鐐逛竴涓?  python scripts/dev/device.py prefs K=V [K=V ...]     # 鏀?shared_prefs锛堝厛 force-stop锛?      鏀寔 selected_model / ctx_size / ngl / port / 甯冨皵閿紙true/false锛?  python scripts/dev/device.py bench [PORT] [妯″瀷鍚峕   # 瀵硅繍琛屼腑鐨勬湇鍔¤窇 128 token 鍩哄噯
  python scripts/dev/device.py server [--start|--stop] # 璧峰仠鏈嶅姟锛堣蛋 UI锛岄渶瑕佽В閿侊級
  python scripts/dev/device.py props [PORT]            # 鐪?/props

绔彛榛樿 18081锛坅db forward 鍒版墜鏈?8080锛夈€係ER 鍙敤鐜鍙橀噺瑕嗙洊銆?"""
import json
import os
import re
import subprocess
import sys
import urllib.request

SER = os.environ.get("SER", "9c57c895")
PKG = "com.localllm.server"
PREFS = "shared_prefs/llama_server_settings.xml"
PORT = os.environ.get("PORT", "18081")


def adb(*args, binary=False):
    r = subprocess.run(["adb", "-s", SER, *args], capture_output=True)
    return r.stdout if binary else r.stdout.decode("utf-8", errors="replace")


def cmd_ui(pat="."):
    subprocess.run(["adb", "-s", SER, "shell", "uiautomator", "dump", "/data/local/tmp/ui.xml"],
                   capture_output=True)
    subprocess.run(["adb", "-s", SER, "pull", "/data/local/tmp/ui.xml", "ui.xml"],
                   capture_output=True)
    data = open("ui.xml", encoding="utf-8", errors="replace").read()
    for n in re.findall(r"<node[^>]*>", data):
        t = re.search(r'\btext="([^"]*)"', n)
        d = re.search(r'content-desc="([^"]*)"', n)
        b = re.search(r'bounds="([^"]*)"', n)
        text = (t.group(1) if t else "") or (d.group(1) if d else "")
        if text and (pat == "." or re.search(pat, text)):
            print(f"{text[:60]!r:64} bounds={b.group(1) if b else ''}")


def cmd_tap(x, y):
    adb("shell", "input", "tap", str(x), str(y))


def cmd_prefs(pairs):
    import xml.etree.ElementTree as ET
    kv = dict(p.split("=", 1) for p in pairs)
    adb("shell", "am force-stop", PKG)
    out = adb("exec-out", "run-as", PKG, "cat", PREFS, binary=True).decode("utf-8", errors="replace")
    for k, v in kv.items():
        if k == "selected_model":
            v = "/data/user/0/%s/files/models/%s" % (PKG, v)
            out = re.sub(r'(<string name="selected_model">)[^<]*(</string>)',
                         lambda m: m.group(1) + v + m.group(2), out)
        elif k in ("ctx_size", "ngl", "port"):
            out = re.sub(r'(<int name="%s" value=")\d+(")' % k,
                         lambda m: m.group(1) + v + m.group(2), out)
        elif re.search(r'<string name="%s">' % k, out):
            out = re.sub(r'(<string name="%s">)[^<]*(</string>)' % k,
                         lambda m: m.group(1) + v + m.group(2), out)
        elif v in ("true", "false"):
            if re.search(r'<boolean name="%s" value="[^"]*"' % k, out):
                out = re.sub(r'(<boolean name="%s" value=")[^"]*(")' % k,
                             lambda m: m.group(1) + v + m.group(2), out)
            else:
                out = out.replace("</map>", '    <boolean name="%s" value="%s" />\n</map>' % (k, v))
        else:
            raise SystemExit("涓嶆敮鎸佺殑閿? %s=%s" % (k, v))
    ET.fromstring(out.encode("utf-8"))
    open("tmp_prefs_new.xml", "w", encoding="utf-8", newline="").write(out)
    adb("push", "tmp_prefs_new.xml", "/data/local/tmp/newprefs.xml")
    adb("shell", 'cat /data/local/tmp/newprefs.xml | run-as %s sh -c "cat > %s"' % (PKG, PREFS))
    print("prefs:", kv)


def cmd_props(port=None):
    port = port or PORT
    with urllib.request.urlopen("http://127.0.0.1:%s/props" % port, timeout=10) as r:
        d = json.load(r)
    print("build:", d.get("build_info"))
    print("model:", (d.get("model_path") or "").split("/")[-1])
    print("n_ctx:", d.get("default_generation_settings", {}).get("n_ctx"))


def cmd_bench(port=None):
    port = port or PORT
    body = {"messages": [{"role": "user", "content": "Summarize the water cycle."}],
            "max_tokens": 128, "n_predict": 128, "ignore_eos": True, "temperature": 0.0}
    req = urllib.request.Request("http://127.0.0.1:%s/v1/chat/completions" % port,
                                 data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=900) as r:
        d = json.load(r)
    t = d.get("timings") or {}
    print("decode %.1f tok/s | prefill %.1f tok/s | %s tok"
          % (float(t.get("predicted_per_second") or 0),
             float(t.get("prompt_per_second") or 0), t.get("predicted_n")))


def cmd_engine_help(mode="prism", pats=("spec",)):
    inner = "cd files/engines/%s && LD_LIBRARY_PATH=. ./llama-server --help 2>&1" % mode
    out = adb("shell", 'run-as %s sh -c "%s"' % (PKG, inner))
    for line in out.splitlines():
        s = line.strip()
        if s and any(p in s for p in pats):
            print(s[:170])


def cmd_server(action):
    if action == "--stop":
        adb("shell", "input", "tap", "294", "2428")   # 鐘舵€侀〉
        cmd_ui("鍋滄鏈嶅姟")
        return
    adb("shell", "am", "start", "-n", "%s/.MainActivity" % PKG)
    subprocess.run(["powershell", "-NoProfile", "-Command", "Start-Sleep -Seconds 5"])
    cmd_tap(294, 2428)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(0)
    op = sys.argv[1]
    args = sys.argv[2:]
    if op == "ui":
        cmd_ui(args[0] if args else ".")
    elif op == "tap":
        cmd_tap(args[0], args[1])
    elif op == "prefs":
        cmd_prefs(args)
    elif op == "props":
        cmd_props(args[0] if args else None)
    elif op == "bench":
        cmd_bench(args[0] if args else None)
    elif op == "help":
        cmd_engine_help(args[0] if args else "prism", tuple(args[1:]) or ("spec",))
    elif op == "server":
        cmd_server(args[0] if args else "--start")
    else:
        print(__doc__)
