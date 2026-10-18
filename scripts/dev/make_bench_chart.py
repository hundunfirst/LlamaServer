"""用 PIL 画一张实测对比图（无需 matplotlib，中文用系统字体）。

用法: python scripts/dev/make_bench_chart.py <输出 png>
数据来源：docs/evals/bonsai-review-and-guide.md（2026-10-18 本机实测）
"""
import sys
from PIL import Image, ImageDraw, ImageFont

OUT = sys.argv[1] if len(sys.argv) > 1 else "tmp_bench_chart.png"

W, H = 1500, 1000
BG = (255, 255, 255)
INK = (32, 36, 48)
MUTED = (120, 128, 144)
PHONE = (76, 121, 202)      # 手机
PC = (214, 118, 60)         # 电脑
BAR_BG = (244, 246, 250)
GRID = (228, 232, 240)

FONT_CANDIDATES = [
    r"C:\Windows\Fonts\msyh.ttc",
    r"C:\Windows\Fonts\msyhbd.ttc",
    r"C:\Windows\Fonts\simhei.ttf",
]


def font(size, bold=False):
    for p in FONT_CANDIDATES:
        try:
            return ImageFont.truetype(p, size)
        except Exception:
            continue
    return ImageFont.load_default()


img = Image.new("RGB", (W, H), BG)
d = ImageDraw.Draw(img)

f_title = font(38, True)
f_sub = font(20)
f_label = font(24)
f_value = font(24, True)
f_note = font(19)

d.text((80, 60), "本地跑 Bonsai 三元量化模型：实测 decode 速度", font=f_title, fill=INK)
d.text((80, 116), "单位 tok/s（每秒生成 token 数）。同一台机器、同一模型，仅更换 llama.cpp 引擎/参数。", font=f_sub, fill=MUTED)

rows = [
    ("手机 · 4B 三元量化（官方引擎 Q2_0）", 9.0, PHONE, "为什么会觉得慢"),
    ("手机 · 4B 普通模型 Q4_K_M（对照）", 13.5, PHONE, "普通 4B 也就这个水平"),
    ("手机 · 4B 三元量化（PrismML 分支 PQ2_0）", 18.4, PHONE, "换引擎后快一倍"),
    ("手机 · 0.5B 普通模型 Q4_K_M（分支）", 99.9, PHONE, "小模型就是快"),
    ("电脑 · 9B 三元量化（无投机）", 40.0, PC, ""),
    ("电脑 · 9B 三元量化 + 内置 MTP", 55.0, PC, "投机解码 +36%"),
    ("电脑 · 4B 三元量化（CUDA 全量上卡）", 63.9, PC, ""),
]

top = 200
row_h = 96
label_w = 660
bar_x = 80 + label_w
bar_max = W - bar_x - 190
vmax = 100.0

for i, (label, val, color, note) in enumerate(rows):
    y = top + i * row_h
    d.rectangle([80, y, W - 80, y + row_h - 18], fill=BAR_BG)
    d.text((100, y + 12), label, font=f_label, fill=INK)
    if note:
        d.text((100, y + 46), note, font=f_note, fill=MUTED)
    w = int(bar_max * val / vmax)
    d.rectangle([bar_x, y + 10, bar_x + w, y + row_h - 28], fill=color)
    d.text((bar_x + w + 14, y + 20), f"{val:.1f}", font=f_value, fill=color)

# 底部说明
d.line([80, H - 118, W - 80, H - 118], fill=GRID, width=2)
d.text((80, H - 100),
       "设备：小米 17 Pro Max（骁龙 8 Elite 系 / Adreno 840 / 16GB / Android 17，纯 CPU，-t 6）",
       font=f_note, fill=MUTED)
d.text((80, H - 74),
       "　　　笔记本（Ryzen 7 6800H / RTX 2050 4GB / 15.2GB / Windows 11，CUDA，-ngl 99，-t 8）",
       font=f_note, fill=MUTED)
d.text((80, H - 44),
       "热态单次实测，误差约 ±5%。三元量化省内存不省算力，别期待它跑出小模型的速度。",
       font=f_note, fill=INK)

img.save(OUT)
print("saved", OUT, img.size)
