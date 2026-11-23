#!/usr/bin/env python3
"""Renders res/drawable-xhdpi/banner.png (the 320x180 dp TV launcher banner at 2x).
Needs Pillow. Run from the repository root: python3 tools/art.py"""
import math
from PIL import Image, ImageDraw, ImageFilter, ImageFont

W, H = 640, 360
img = Image.new("RGB", (W, H))
top, bottom = (0x0B, 0x4F, 0xA8), (0x4A, 0x9B, 0xE8)
d = ImageDraw.Draw(img)
for y in range(H):
    t = y / (H - 1)
    d.line([(0, y), (W, y)], fill=tuple(round(a + (b - a) * t) for a, b in zip(top, bottom)))

glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
g = ImageDraw.Draw(glow)
for r in range(170, 0, -4):
    a = round(110 * (1 - r / 170) ** 2)
    g.ellipse([132 - r, 128 - r, 132 + r, 128 + r], fill=(255, 240, 200, a))
img.paste(glow, (0, 0), glow)

d = ImageDraw.Draw(img)
d.ellipse([132 - 40, 128 - 40, 132 + 40, 128 + 40], fill=(0xFF, 0xC8, 0x3D))

cloud = Image.new("RGBA", (W, H), (0, 0, 0, 0))
c = ImageDraw.Draw(cloud)
for cx, cy, r in [(128, 196, 46), (186, 170, 62), (246, 200, 42)]:
    c.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(255, 255, 255, 255))
c.rounded_rectangle([84, 190, 288, 244], radius=27, fill=(255, 255, 255, 255))
shadow = cloud.filter(ImageFilter.GaussianBlur(10))
shadow_alpha = shadow.split()[3].point(lambda v: v * 0.25)
img.paste((0, 30, 80), (6, 10), shadow_alpha)
img.paste(cloud, (0, 0), cloud)

font = ImageFont.truetype("assets/fonts/selawkl.ttf", 70)
d = ImageDraw.Draw(img)
d.text((318, 188), "Weather", font=font, fill=(255, 255, 255), anchor="lm")
img.save("res/drawable-xhdpi/banner.png", optimize=True)
print("wrote res/drawable-xhdpi/banner.png")
