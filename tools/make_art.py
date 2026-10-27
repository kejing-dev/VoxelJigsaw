"""Voxel Jigsaw art, all drawn here from data (no third-party images):

  1. Home-card thumbnails: an isometric render of each landmark from the baked puzzle files
     -> app/src/main/res/drawable-nodpi/thumb_<id>.png
  2. The app icon: one shape list drawn three ways - the adaptive-icon vector foreground
     (res/drawable/ic_launcher_foreground.xml on @color/ic_launcher_background #C7861A), the PICO 3D icon
     layers (icon_3d_layer_0/1 + sdf), and a 512 PNG export.

    python tools/make_art.py [icon512.png]

Icon: a cream three-tier voxel tower on gilded gold; its top-right block has lifted off and floats,
tilted, above a dashed gap - "rebuild the landmark block by block".
"""
import json
import math
import os
import sys
from PIL import Image, ImageDraw

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
RES = os.path.join(ROOT, "app", "src", "main", "res")
PUZ = os.path.join(ROOT, "app", "src", "main", "assets", "puzzles")

# ---------------------------------------------------------------------------------------------
# thumbnails


def hex_rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def shade(c, k):
    return tuple(max(0, min(255, int(v * k))) for v in c) + (255,)


def thumbnail(lid, w=360, h=220):
    p = json.load(open(os.path.join(PUZ, lid + "-25.json"), encoding="utf-8"))
    pal = [hex_rgb(c) for c in p["palette"]]
    vox = {}
    for piece in p["pieces"]:
        for x, y, z, c in piece["vox"]:
            vox[(x, y, z)] = c
    cs, sn = math.cos(math.radians(30)), math.sin(math.radians(30))

    def proj(x, y, z, s):
        return ((x - z) * cs * s, (x + z) * sn * s - y * s)

    pts = [proj(x + dx, y + dy, z + dz, 1) for (x, y, z) in vox for dx in (0, 1) for dy in (0, 1) for dz in (0, 1)]
    minx = min(q[0] for q in pts); maxx = max(q[0] for q in pts)
    miny = min(q[1] for q in pts); maxy = max(q[1] for q in pts)
    s = min((w - 24) / (maxx - minx), (h - 20) / (maxy - miny))
    ox = w / 2 - (minx + maxx) / 2 * s
    oy = h / 2 - (miny + maxy) / 2 * s
    im = Image.new("RGBA", (w * 2, h * 2), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)

    def P(x, y, z):
        a, b = proj(x, y, z, s)
        return ((a + ox) * 2, (b + oy) * 2)

    for (x, y, z) in sorted(vox, key=lambda k: (k[0] + k[2], k[1])):
        c = pal[vox[(x, y, z)]]
        if (x, y + 1, z) not in vox:
            d.polygon([P(x, y + 1, z), P(x + 1, y + 1, z), P(x + 1, y + 1, z + 1), P(x, y + 1, z + 1)], fill=shade(c, 1.08))
        if (x + 1, y, z) not in vox:
            d.polygon([P(x + 1, y, z), P(x + 1, y + 1, z), P(x + 1, y + 1, z + 1), P(x + 1, y, z + 1)], fill=shade(c, 0.78))
        if (x, y, z + 1) not in vox:
            d.polygon([P(x, y, z + 1), P(x + 1, y, z + 1), P(x + 1, y + 1, z + 1), P(x, y + 1, z + 1)], fill=shade(c, 0.92))
    return im.resize((w, h), Image.LANCZOS)


# ---------------------------------------------------------------------------------------------
# icon (108-unit adaptive-icon viewport; safe zone 21..87)

CREAM = "#FFF6E3"
CREAM_SIDE = "#F3DFB8"
SEAM = "#9A5E0C"
GOLD_BG = "#C7861A"
TILE = 6.0


def icon_shapes():
    sh = []
    tiers = [(30, 66, 78, 78), (36, 54, 72, 66), (42, 42, 66, 54)]
    for (x0, y0, x1, y1) in tiers:
        sh.append(("rect", x0, y0, x1, y1, CREAM))
        sh.append(("rect", x0, y1 - 2.2, x1, y1, CREAM_SIDE))
    # the gap in the top tier where the block came from
    gx0, gy0, gx1, gy1 = 60, 42, 66, 48
    sh.append(("rect", gx0, gy0, gx1, gy1, GOLD_BG))
    # voxel seams
    for (x0, y0, x1, y1) in tiers:
        x = x0 + TILE
        while x < x1 - 0.1:
            sh.append(("rect", x - 0.5, y0, x + 0.5, y1, SEAM))
            x += TILE
        sh.append(("rect", x0, y0 + TILE - 0.5, x1, y0 + TILE + 0.5, SEAM))
    sh.append(("rect", gx0, gy0, gx1, gy1, GOLD_BG))
    # dashed outline of the gap
    for i in range(3):
        sh.append(("rect", gx0 + i * 2.2, gy0 - 0.5, gx0 + i * 2.2 + 1.2, gy0 + 0.5, "#FFFFFF"))
        sh.append(("rect", gx1 - 0.5, gy0 + i * 2.2, gx1 + 0.5, gy0 + i * 2.2 + 1.2, "#FFFFFF"))
    # finial
    sh.append(("rect", 51, 34, 57, 42, CREAM))
    sh.append(("rect", 53, 29, 55, 34, CREAM))
    # the floating block, tilted 22 degrees, above and right of the gap
    cx, cy, half, ang = 74.5, 30.5, 6.0, math.radians(22)

    def rot(px, py):
        return (cx + px * math.cos(ang) - py * math.sin(ang), cy + px * math.sin(ang) + py * math.cos(ang))

    sh.append(("poly", [rot(-half, -half), rot(half, -half), rot(half, half), rot(-half, half)], CREAM))
    sh.append(("poly", [rot(-half, half - 2.2), rot(half, half - 2.2), rot(half, half), rot(-half, half)], CREAM_SIDE))
    # motion ticks from the gap to the block
    for k, (ax, ay) in enumerate([(64.5, 39.5), (66.8, 37.2)]):
        sh.append(("poly", [(ax, ay), (ax + 1.0, ay - 1.0), (ax + 1.8, ay - 0.2), (ax + 0.8, ay + 0.8)], "#FFFFFF"))
    return sh


def vector_xml(shapes):
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           "<!-- Launcher foreground (108 x 108, inside the 66 dp safe zone): a cream voxel tower whose top-right block",
           "     floats off above a dashed gap. Generated by tools/make_art.py with the 3D icon layers and the 512 export. -->",
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    android:width="108dp"', '    android:height="108dp"',
           '    android:viewportWidth="108"', '    android:viewportHeight="108">']
    for s in shapes:
        if s[0] == "rect":
            _, x0, y0, x1, y1, c = s
            d = f"M{x0:.2f},{y0:.2f}H{x1:.2f}V{y1:.2f}H{x0:.2f}Z"
        else:
            _, pts, c = s
            d = "M" + "L".join(f"{x:.2f},{y:.2f}" for x, y in pts) + "Z"
        out.append(f'    <path android:fillColor="#FF{c.lstrip("#")}" android:pathData="{d}" />')
    out.append("</vector>")
    return "\n".join(out) + "\n"


def render(shapes, size, bg):
    k = size / 108.0
    im = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    if bg:
        d.rounded_rectangle((size * 0.04, size * 0.04, size * 0.96, size * 0.96), radius=size * 0.21, fill=GOLD_BG)
    for s in shapes:
        if s[0] == "rect":
            _, x0, y0, x1, y1, c = s
            d.rectangle((x0 * k, y0 * k, x1 * k, y1 * k), fill=c)
        else:
            _, pts, c = s
            d.polygon([(x * k, y * k) for x, y in pts], fill=c)
    return im


def alpha_white(im):
    w = Image.new("RGBA", im.size, (255, 255, 255, 0))
    w.putalpha(im.split()[3])
    return w


def main():
    nodpi = os.path.join(RES, "drawable-nodpi")
    os.makedirs(nodpi, exist_ok=True)
    for lid in ("qinian", "bigben", "taj"):
        thumbnail(lid).save(os.path.join(nodpi, f"thumb_{lid}.png"))
    shapes = icon_shapes()
    draw = os.path.join(RES, "drawable")
    open(os.path.join(draw, "ic_launcher_foreground.xml"), "w", encoding="utf-8").write(vector_xml(shapes))
    tile = render([], 1024, True)
    front = render(shapes, 1024, False)
    tile.save(os.path.join(draw, "icon_3d_layer_0.png"))
    front.save(os.path.join(draw, "icon_3d_layer_1.png"))
    alpha_white(tile).save(os.path.join(draw, "icon_3d_sdf_0.png"))
    alpha_white(front).save(os.path.join(draw, "icon_3d_sdf_1.png"))
    if len(sys.argv) > 1:
        full = Image.new("RGBA", (1024, 1024), GOLD_BG)
        Image.alpha_composite(full, front).resize((512, 512), Image.LANCZOS).save(sys.argv[1])
    print("art written")


if __name__ == "__main__":
    main()
