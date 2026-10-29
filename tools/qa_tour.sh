#!/usr/bin/env bash
# Screenshot tour of the design states (design states[].qaCapture), emulator-5562 only.
#   tools/qa_tour.sh [S02 S05 ...]      default: every state
# Shots -> ${OUT:-../spatial-app-factory/apps/voxel-jigsaw/build/states}/<id>.png, log lines -> tour.txt there.
# Each state is set up by the debug broadcast `state <id>` (app/src/main/java/io/embodit/voxeljigsaw/ui/DebugCommands.kt).
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${OUT:-$ROOT/../spatial-app-factory/apps/voxel-jigsaw/build/states}"
mkdir -p "$OUT"
DEV=tools/dev.sh
cd "$ROOT"
wait_for() { case "$1" in S01) echo 0;; S04) echo 1;; S15) echo 9;; S16) echo 12;; S08|S09|S10|S11|S12) echo 1;; *) echo 5;; esac; }
states="${*:-S01 S02 S03 S04 S05 S06 S07 S08 S09 S10 S11 S12 S13 S14 S15 S16 S17 S18}"
bash $DEV cmd linger on >/dev/null   # hints, peek, messages and the seam flash stay 15x longer while shooting
for s in $states; do
  bash tools/harness/emu.sh fast on >/dev/null 2>&1
  before="$(bash $DEV log 'CMD seed ready' 9999 | wc -l)"
  bash $DEV cmd state $s >/dev/null
  # seeded states build their meshes in steps first (v0.1.4): wait for "CMD seed ready", at most 40 s
  case "$s" in S01|S02|S04|S17) ;; *) for _ in $(seq 1 40); do [ "$(bash $DEV log 'CMD seed ready' 9999 | wc -l)" -gt "$before" ] && break; sleep 1; done ;; esac
  sleep "$(wait_for $s)"
  n=3; case "$s" in S01|S04|S08) n=2;; esac
  # emulator console screenshots (emu.sh shot): pico-cli capture often returns half-rendered frames on this AVD
  for i in $(seq 1 $n); do bash tools/harness/emu.sh shot cand$i >/dev/null 2>&1; done
  python -I tools/bestshot.py "$OUT/$s.png" $(for i in $(seq 1 $n); do echo "$ROOT/.scratch/shots/harness/cand$i.png"; done) >/dev/null
  line="$(bash $DEV log 'STATE S[0-9]+' 1 | tail -1 | cut -c1-200)"
  echo "$s  $line" | tee -a "$OUT/tour.txt"
done
bash $DEV cmd linger off >/dev/null
