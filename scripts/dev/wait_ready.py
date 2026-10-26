import json
import subprocess
import time
import urllib.request

SER = "adb-9c57c895-Kwx2M3._adb-tls-connect._tcp"
DEV_PORT = 8080
LOCAL = 18080

subprocess.run(["adb", "-s", SER, "forward", "tcp:%d" % LOCAL, "tcp:%d" % DEV_PORT],
               capture_output=True)

deadline = time.time() + 600
last = None
while time.time() < deadline:
    try:
        with urllib.request.urlopen("http://127.0.0.1:%d/health" % LOCAL, timeout=5) as r:
            body = r.read().decode("utf-8", "replace")[:120]
            print("ready:", body)
            break
    except Exception as e:
        code = getattr(e, "code", None)
        if code != last:
            print("%s 第 %ds" % (e, int(time.time() % 1000)))
            last = code
        time.sleep(3)
else:
    print("超时：服务没在最长时间内就绪")
