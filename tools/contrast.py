"""Text contrast on real screenshot pixels (factory rule D13): python tools/contrast.py <png> name:x0,y0,x1,y1 ...

For each box (framebuffer pixels, tight around one piece of text and its own background) the text and background
colours are taken as the 3rd and 97th luminance percentiles of the box; prints the WCAG contrast ratio.
"""
import sys

from PIL import Image


def lum(c):
    def ch(v):
        v = v / 255.0
        return v / 12.92 if v <= 0.03928 else ((v + 0.055) / 1.055) ** 2.4
    r, g, b = c
    return 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b)


im = Image.open(sys.argv[1]).convert("RGB")
for spec in sys.argv[2:]:
    name, box = spec.split(":")
    x0, y0, x1, y1 = (int(v) for v in box.split(","))
    px = list(im.crop((x0, y0, x1, y1)).getdata())
    px.sort(key=lum)
    dark, light = px[int(len(px) * 0.03)], px[int(len(px) * 0.97)]
    ratio = (lum(light) + 0.05) / (lum(dark) + 0.05)
    print("%-22s %5.2f:1  dark=#%02x%02x%02x light=#%02x%02x%02x" % (name, ratio, *dark, *light))
