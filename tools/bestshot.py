"""Keeps the cleanest of several emulator frames: python tools/bestshot.py OUT.png a.png b.png ...
The emulator sometimes returns a frame whose lower part is still black; the frame with the fewest
pure-black pixels wins. Prints the black share of each candidate."""
import shutil
import sys
from PIL import Image

out, cands = sys.argv[1], sys.argv[2:]
scores = []
for c in cands:
    im = Image.open(c).convert("L").resize((256, 256))
    px = list(im.tobytes())
    scores.append((sum(1 for p in px if p < 6) / len(px), c))
scores.sort()
shutil.copyfile(scores[0][1], out)
print(" ".join(f"{s:.2f}" for s, _ in scores))
