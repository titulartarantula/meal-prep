from PIL import Image, ImageDraw, ImageFont
from svgelements import Path

GREEN = (0x2E, 0x7D, 0x32)
D = ("M8.1,13.34l2.83,-2.83L3.91,3.5c-1.56,1.56 -1.56,4.09 0,5.66l4.19,4.18zM14.88,11.53c1.53,0.71 3.68,0.21 5.27,-1.38 "
     "1.91,-1.91 2.28,-4.65 0.81,-6.12 -1.46,-1.46 -4.2,-1.1 -6.12,0.81 -1.59,1.59 -2.09,3.74 -1.38,5.27L3.7,19.87l1.41,1.41"
     "L12,14.41l6.88,6.88 1.41,-1.41L13.41,13l1.47,-1.47z")

def glyph_polys(scale, ox, oy):
    """Polygons for the 24x24 glyph, scaled and offset (its 0..24 box)."""
    polys = []
    for sp in Path(D).as_subpaths():
        sp = Path(sp)
        pts = []
        for seg in sp:
            n = 2 if type(seg).__name__ in ("Line", "Close", "Move") else 64
            for i in range(n + 1):
                p = seg.point(i / n)
                if p is not None:
                    pts.append((ox + p[0] * scale, oy + p[1] * scale))
        polys.append(pts)
    return polys

def draw_glyph(img, size_px, cx, cy, ss):
    d = ImageDraw.Draw(img)
    s = size_px / 24 * ss
    for poly in glyph_polys(s, cx * ss - 12 * s, cy * ss - 12 * s):
        d.polygon(poly, fill=(255, 255, 255))

SS = 4
# Icon 512x512, full-bleed green, glyph ~58% of the side, opaque RGB (32-bit when saved as RGBA)
big = Image.new("RGB", (512 * SS, 512 * SS), GREEN)
draw_glyph(big, 300, 256, 256, SS)
icon = big.resize((512, 512), Image.LANCZOS).convert("RGBA")
icon.save("/tmp/playassets/icon-512.png", optimize=True)

# Feature graphic 1024x500
W, H = 1024, 500
fg = Image.new("RGB", (W * SS, H * SS), GREEN)
draw_glyph(fg, 190, 230, 250, SS)
d = ImageDraw.Draw(fg)
bold = ImageFont.truetype("/usr/share/fonts/opentype/urw-base35/NimbusSans-Bold.otf", 96 * SS)
reg = ImageFont.truetype("/usr/share/fonts/opentype/urw-base35/NimbusSans-Regular.otf", 38 * SS)
d.text((390 * SS, 250 * SS), "Meal Prep", font=bold, fill=(255, 255, 255), anchor="ls")
d.text((392 * SS, 310 * SS), "Plan the week's dinners together", font=reg, fill=(0xE8, 0xF5, 0xE9), anchor="ls")
fg.resize((W, H), Image.LANCZOS).save("/tmp/playassets/feature-1024x500.png", optimize=True)
