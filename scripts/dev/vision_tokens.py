#!/usr/bin/env python
"""同一模型下，不同图片的「图像 token 数 / 编码耗时」测量。

不吃界面：直接往 App 的 /v1/chat/completions 发带图请求，用返回的 timings 看
prompt_n（预填充 token）与 prompt_ms。max_tokens 设很小，隔离出「看图」这一段成本。

用法：
  python scripts/dev/vision_tokens.py            # 默认跑内置的几组图
  python scripts/dev/vision_tokens.py --runs 3
"""
import argparse
import base64
import io
import json
import os
import statistics
import subprocess
import time
import urllib.request

SER = os.environ.get("SER", "adb-9c57c895-Kwx2M3._adb-tls-connect._tcp")
LOCAL = 18080
DEV = 8080
PROMPT = "把图中出现的文字逐行抄下来。"


def http(path, body=None, timeout=900):
    req = urllib.request.Request(
        "http://127.0.0.1:%d%s" % (LOCAL, path),
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


def build_images(tmp):
    """造几组尺寸/内容不同的图，返回 [(标签, 路径, 宽, 高)]"""
    from PIL import Image, ImageDraw
    out = []

    def jpeg(img, name, q=85):
        p = os.path.join(tmp, name)
        img.convert("RGB").save(p, "JPEG", quality=q)
        out.append((name, p, img.width, img.height))
        return p

    def text_img(w, h, lines, size=14):
        img = Image.new("RGB", (w, h), "white")
        d = ImageDraw.Draw(img)
        # 用内置位图字体重复铺满，模拟「满屏文字」
        y = 4
        i = 0
        while y < h - size:
            d.text((4, y), lines[i % len(lines)], fill="black")
            y += size + 2
            i += 1
        return img

    def noise_img(w, h):
        img = Image.new("RGB", (w, h))
        px = img.load()
        for y in range(0, h, 2):
            for x in range(0, w, 2):
                v = (x * 7 + y * 13) % 256
                px[x, y] = (v, (v * 3) % 256, (v * 5) % 256)
                if x + 1 < w:
                    px[x + 1, y] = px[x, y]
                if y + 1 < h:
                    px[x, y + 1] = px[x, y]
        return img

    lines = ["Invoice-A20261025 total 1234.50 CNY", "订单号 A20261025 金额 1234.50 元",
             "line item 3 qty 2 price 617.25", "备注：请于 30 日内结清"]

    jpeg(text_img(512, 512, lines), "t512_text.jpg")
    jpeg(text_img(1024, 1024, lines), "t1024_text.jpg")
    jpeg(noise_img(1024, 1024), "n1024_noise.jpg")          # 同尺寸、无文字、无结构
    jpeg(text_img(2016, 1512, lines), "t2016x1512_text.jpg")  # 常见照片尺寸
    jpeg(text_img(1080, 8000, lines), "t1080x8000_long.jpg")  # 长截图
    jpeg(text_img(2000, 500, lines), "t2000x500_wide.jpg")    # 宽条幅
    return out


def ask(img_path):
    b64 = base64.b64encode(open(img_path, "rb").read()).decode()
    body = {
        "messages": [{"role": "user", "content": [
            {"type": "text", "text": PROMPT},
            {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64," + b64}},
        ]}],
        "max_tokens": 8, "temperature": 0.0, "ignore_eos": True,
    }
    d = http("/v1/chat/completions", body)
    return d.get("timings") or {}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", type=int, default=2)
    a = ap.parse_args()

    subprocess.run(["adb", "-s", SER, "forward", "tcp:%d" % LOCAL, "tcp:%d" % DEV],
                   capture_output=True)
    props = http("/props", timeout=20)
    print("模型：", (props.get("model_path") or "").split("/")[-1])
    print("视觉：", (props.get("modalities") or {}).get("vision"))
    n_ctx = (props.get("default_generation_settings") or {}).get("n_ctx")
    print("n_ctx：", n_ctx)

    tmp = os.path.join(os.getcwd(), ".vision_bench")
    os.makedirs(tmp, exist_ok=True)
    imgs = build_images(tmp)

    rows = []
    for name, path, w, h in imgs:
        pn, pm, err = [], [], None
        for _ in range(a.runs):
            try:
                t = ask(path)
                pn.append(int(t.get("prompt_n") or 0))
                pm.append(float(t.get("prompt_ms") or 0))
            except Exception as e:
                err = str(e)[:120]
                break
        kb = os.path.getsize(path) // 1024
        if err:
            print("%-22s %4dx%-5d %5dKB  失败：%s" % (name, w, h, kb, err))
            continue
        tok = statistics.median(pn)
        ms = statistics.median(pm)
        per_kpx = tok / (w * h / 1000.0)
        print("%-22s %4dx%-5d %5dKB  prompt %5d tok  %7.0f ms  %6.1f tok/kpx  %5.1f ms/kpx"
              % (name, w, h, kb, tok, ms, per_kpx, ms / (w * h / 1000.0)))
        rows.append({"img": name, "w": w, "h": h, "kb": kb, "tokens": tok, "ms": ms})

    with open("vision_tokens.json", "w", encoding="utf-8") as f:
        json.dump(rows, f, ensure_ascii=False, indent=2)
    print("\n明细已写 vision_tokens.json")


if __name__ == "__main__":
    main()
