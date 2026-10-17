#!/usr/bin/env python
"""发一张合成图片，验证多模态（mmproj）链路是否正常。

用法: python scripts/dev/vision_probe.py [端口] [api-key]
图片：白底 + 蓝椭圆 + 红矩形（不依赖外部素材）。
"""
import base64
import io
import json
import sys
import urllib.request

from PIL import Image, ImageDraw

port = sys.argv[1] if len(sys.argv) > 1 else "18081"
key = sys.argv[2] if len(sys.argv) > 2 else ""

img = Image.new("RGB", (448, 448), "white")
d = ImageDraw.Draw(img)
d.ellipse((80, 120, 368, 300), fill=(30, 90, 200))
d.rectangle((120, 330, 330, 390), fill=(200, 30, 30))
buf = io.BytesIO()
img.save(buf, format="PNG")
b64 = base64.b64encode(buf.getvalue()).decode()

body = {
    "messages": [{
        "role": "user",
        "content": [
            {"type": "text", "text": "图片里有哪些形状？各是什么颜色？用一句话中文回答。"},
            {"type": "image_url", "image_url": {"url": "data:image/png;base64," + b64}},
        ],
    }],
    "max_tokens": 900,
    "temperature": 0.0,
}
req = urllib.request.Request(
    "http://127.0.0.1:%s/v1/chat/completions" % port,
    data=json.dumps(body).encode(),
    headers={"Content-Type": "application/json", **({"Authorization": "Bearer " + key} if key else {})})
try:
    with urllib.request.urlopen(req, timeout=900) as r:
        d = json.load(r)
    m = d["choices"][0]["message"]
    txt = (m.get("content") or "").strip()
    print("reply:", txt[:300] if txt else "(空：可能全花在思考上)")
    t = d.get("timings") or {}
    print("prompt_n:", t.get("prompt_n"), "| decode:",
          round(float(t.get("predicted_per_second") or 0), 1), "tok/s")
except urllib.error.HTTPError as e:
    print("HTTP", e.code, e.read().decode("utf-8", errors="replace")[:300])
except Exception as e:
    print("failed:", e)
