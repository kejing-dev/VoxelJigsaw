"""Synthesises the four short effects (design budget.audio: snap / pick / wrong / done, <= 300 KB in all)
into app/src/main/res/raw as 22 kHz mono 16-bit WAV. Original sounds, no third-party samples.

    python tools/make_sounds.py
"""
import math
import os
import random
import struct
import wave

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
RAW = os.path.join(ROOT, "app", "src", "main", "res", "raw")
RATE = 22050


def write(name, samples):
    os.makedirs(RAW, exist_ok=True)
    peak = max(1e-6, max(abs(s) for s in samples))
    with wave.open(os.path.join(RAW, name + ".wav"), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(32767 * 0.85 * s / peak)) for s in samples))


def tone(freqs, dur, decay, attack=0.002, noise=0.0, seed=1):
    rnd = random.Random(seed)
    out = []
    for i in range(int(RATE * dur)):
        t = i / RATE
        env = min(1.0, t / attack) * math.exp(-t / decay)
        s = sum(a * math.sin(2 * math.pi * f * t) for f, a in freqs)
        s += noise * (rnd.random() * 2 - 1) * math.exp(-t / (decay * 0.3))
        out.append(s * env)
    return out


# snap: a short wooden "tock" with a click on top
write("snap", tone([(1180, 1.0), (2360, 0.35), (620, 0.5)], 0.12, 0.025, noise=0.6))
# pick: a soft high tick
write("pick", tone([(1650, 1.0), (3300, 0.2)], 0.06, 0.012, noise=0.2, seed=2))
# wrong: a muffled low thud
write("wrong", tone([(150, 1.0), (230, 0.5), (90, 0.4)], 0.22, 0.06, noise=0.15, seed=3))
# done: a bright major chord, arpeggiated
done = [0.0] * int(RATE * 1.3)
for k, f in enumerate([523.25, 659.25, 783.99, 1046.5]):
    part = tone([(f, 1.0), (f * 2, 0.25)], 1.3 - k * 0.08, 0.45)
    off = int(RATE * k * 0.08)
    for i, s in enumerate(part):
        if off + i < len(done):
            done[off + i] += s
write("done", done)
print("ok", sorted(os.listdir(RAW)))
