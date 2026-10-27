"""Contact sheet of screenshots: python tools/contact.py OUT.png a.png b.png ... (centre crop of the volume area)."""
import os
import sys
from PIL import Image, ImageDraw

out, files = sys.argv[1], sys.argv[2:]
crop = (520, 560, 2040, 2080) if os.environ.get("FULL") is None else None
tiles = []
for f in files:
    im = Image.open(f).convert("RGB")
    if crop:
        im = im.crop(crop)
    im = im.resize((560, 560))
    ImageDraw.Draw(im).text((10, 10), os.path.basename(f), fill=(255, 0, 0))
    tiles.append(im)
cols = min(3, len(tiles))
rows = (len(tiles) + cols - 1) // cols
sheet = Image.new("RGB", (cols * 560, rows * 560), (30, 30, 30))
for i, t in enumerate(tiles):
    sheet.paste(t, ((i % cols) * 560, (i // cols) * 560))
sheet.save(out)
