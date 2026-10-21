# -*- coding: utf-8 -*-
"""Altivane 应用图标生成器（v0.8.40）
输入 art/appicon.png（用户原图 800x800，青色纸飞机 + 荧光绿山峰 + 深灰底）
输出：
  res/drawable-xxxhdpi/ic_launcher_fg.png    432x432 透明底形状（adaptive 前景，安全区内）
  res/drawable-xxxhdpi/ic_launcher_mono.png  432x432 白色形状（MD3 themed icons 单色层）
  res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png        legacy 方形（原图整图缩放）
  res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher_round.png  legacy 圆形（背景色圆底 + 内容）
  打印背景色（写入 colors.xml ic_bg）
抠图：饱和度+明度阈值（深灰背景 S≈0 自动剔除），alpha 1px 羽化。
"""
from PIL import Image, ImageDraw, ImageFilter
from PIL.Image import Resampling
import os

ROOT = r"I:\dev\ride"
SRC = os.path.join(ROOT, "art", "appicon.png")
RES = os.path.join(ROOT, "app", "src", "main", "res")

src = Image.open(SRC).convert("RGB")
w, h = src.size
hsv = src.convert("HSV")
H, S, V = hsv.split()

# 形状 mask：S>0.28 且 V>0.28（背景深灰 S≈0 被剔除；青/绿高饱和保留）
mask = Image.eval(S, lambda v: 255 if v > 71 else 0)
maskV = Image.eval(V, lambda v: 255 if v > 71 else 0)
mask = Image.composite(mask, Image.new("L", mask.size, 0), mask.point(lambda v: 255 if v else 0))
mask = Image.eval(
    Image.merge("RGB", (mask, maskV, maskV)).convert("L"), lambda v: 255 if v > 0 else 0
)
# 简化：直接 AND 两个阈值
import PIL.ImageChops as C
mask = C.multiply(
    Image.eval(S, lambda v: 255 if v > 71 else 0),
    Image.eval(V, lambda v: 255 if v > 71 else 0),
)
# 去噪：中值滤波去孤立点（5x5 清 JPEG 噪散点）+ 1px 羽化边缘
mask = mask.filter(ImageFilter.MedianFilter(5)).filter(ImageFilter.MedianFilter(3)).filter(ImageFilter.GaussianBlur(0.8))

# 内容 bbox 与中心距（安全区缩放依据）
bbox = mask.getbbox()
print("content bbox:", bbox)
cx, cy = w / 2.0, h / 2.0
max_r = 0.0
px = mask.load()
for yy in range(0, h, 4):
    for xx in range(0, w, 4):
        if px[xx, yy] > 128:
            d = ((xx - cx) ** 2 + (yy - cy) ** 2) ** 0.5
            if d > max_r:
                max_r = d
print("content max radius(px):", max_r)

# ---- adaptive foreground / monochrome：432x432（108dp@xxxhdpi）----
# safe zone：中心 264px 圆全可见；取内容半径 456→缩放后 ≤150（safe 132 略超但标准圆 mask 144 内）
SIZE = 432
k = min(150.0 / max_r, 1.0)
new_side = int(round(w * k))
shape = src.convert("RGBA")
shape.putalpha(mask)
shape_s = shape.resize((new_side, new_side), Resampling.LANCZOS)

os.makedirs(os.path.join(RES, "drawable-xxxhdpi"), exist_ok=True)
fg = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
fg.alpha_composite(shape_s, ((SIZE - new_side) // 2, (SIZE - new_side) // 2))
fg.save(os.path.join(RES, "drawable-xxxhdpi", "ic_launcher_fg.png"))

mono_img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
white = Image.new("RGBA", shape_s.size, (255, 255, 255, 0))
white.putalpha(shape_s.split()[3])
mono_img.alpha_composite(white, ((SIZE - new_side) // 2, (SIZE - new_side) // 2))
mono_img.save(os.path.join(RES, "drawable-xxxhdpi", "ic_launcher_mono.png"))

# ---- legacy：背景色 + 原图整图 ----
corner = src.crop((0, 0, 24, 24)).resize((1, 1), Resampling.LANCZOS).getpixel((0, 0))
bg_hex = "#{:02X}{:02X}{:02X}".format(*corner)
print("bg color:", bg_hex)

DENS = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
full = Image.new("RGBA", (w, h), corner + (255,))
full.alpha_composite(shape, (0, 0))  # 原尺寸形状叠加（== 原图）
full = full.convert("RGB")
for dpi, s in DENS.items():
    d = os.path.join(RES, "mipmap-" + dpi)
    os.makedirs(d, exist_ok=True)
    sq = full.resize((s, s), Resampling.LANCZOS)
    sq.save(os.path.join(d, "ic_launcher.png"))
    # round：背景色圆底 + 内容，圆外透明
    rd = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    ImageDraw.Draw(rd).ellipse((0, 0, s - 1, s - 1), fill=corner)
    ck = s / float(w)
    content = shape.resize((int(w * ck), int(h * ck)), Resampling.LANCZOS)
    rd.alpha_composite(content, (0, 0))
    rd.save(os.path.join(d, "ic_launcher_round.png"))

print("done. k =", round(k, 3), "new_side =", new_side)
