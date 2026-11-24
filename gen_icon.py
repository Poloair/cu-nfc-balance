# -*- coding: utf-8 -*-
"""launcher 图标 v2：干净渐变底 + 白色非接触式(NFC)符号，极简构图"""
from PIL import Image, ImageDraw, ImageFilter

S = 768  # 超采样后缩到 192

# ---- 1. 对角渐变底：手动双线性插值（避免 resize 过冲发暗） ----
TL = (24, 201, 162)   # 左上 薄荷绿
TR = (20, 186, 178)   # 右上 青
BL = (30, 140, 206)   # 左下 天蓝
BR = (52, 116, 222)   # 右下 蓝
bg = Image.new("RGB", (S, S))
d = ImageDraw.Draw(bg)
for y in range(S):
    t = y / (S - 1)
    lc = tuple(int(TL[i] + (BL[i] - TL[i]) * t) for i in range(3))
    rc = tuple(int(TR[i] + (BR[i] - TR[i]) * t) for i in range(3))
    d.line([(0, y), (S, y)], fill=lc)
    for x in range(0, S, 8):  # 右侧往左覆盖，形成左->右的水平渐变
        u = x / (S - 1)
        c = tuple(int(lc[i] + (rc[i] - lc[i]) * u) for i in range(3))
        d.line([(S - 1 - x, y), (S - 1 - x, y)], fill=c)
bg = bg.convert("RGBA")

# ---- 2. 圆角方形裁切 ----
mask = Image.new("L", (S, S), 0)
ImageDraw.Draw(mask).rounded_rectangle([0, 0, S, S], radius=176, fill=255)
icon = Image.new("RGBA", (S, S), (0, 0, 0, 0))
icon.paste(bg, (0, 0), mask)

# ---- 3. 符号后的柔光，让白色符号从渐变里浮起来 ----
glow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
ImageDraw.Draw(glow).ellipse([210, 200, 580, 570], fill=(255, 255, 255, 70))
glow = glow.filter(ImageFilter.GaussianBlur(90))
icon = Image.alpha_composite(icon, glow)

# ---- 4. 非接触式符号：圆点 + 三道右开口弧（平头，干净利落） ----
sym = Image.new("RGBA", (S, S), (0, 0, 0, 0))
sd = ImageDraw.Draw(sym)
dx, dy = 292, 384
sd.ellipse([dx - 44, dy - 44, dx + 44, dy + 44], fill=(255, 255, 255, 255))
for radius, width, alpha in [(122, 42, 255), (196, 42, 232), (270, 42, 205)]:
    sd.arc([dx - radius, dy - radius, dx + radius, dy + radius],
           start=-46, end=46, fill=(255, 255, 255, alpha), width=width)

# 轻微柔化叠一层，做出发光边缘
soft = sym.filter(ImageFilter.GaussianBlur(6))
icon = Image.alpha_composite(icon, soft)
icon = Image.alpha_composite(icon, sym)

# ---- 5. 一道极轻的斜向玻璃反光 ----
sheen = Image.new("RGBA", (S, S), (0, 0, 0, 0))
shd = ImageDraw.Draw(sheen)
shd.polygon([(0, 0), (S, 0), (0, S * 0.5)], fill=(255, 255, 255, 24))
sheen = sheen.filter(ImageFilter.GaussianBlur(40))
icon = Image.alpha_composite(icon, sheen)

# ---- 6. 输出 ----
icon = icon.resize((192, 192), Image.LANCZOS)
icon.save(r"D:\Work\2026-11-24-15-32-50\cu-nfc-balance\res\mipmap-xxxhdpi\ic_launcher.png")
print("icon saved 192x192")
icon.resize((384, 384), Image.LANCZOS).save(r"D:\Work\2026-11-24-15-32-50\cu-nfc-balance\icon_preview.png")
print("preview saved")
