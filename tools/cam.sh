#!/usr/bin/env bash
# Emulator camera presets for emulator-5562 (keys posted to the emulator window, see tools/harness/emu.sh).
#   tools/cam.sh near   from the launch view: walk ~1.3 m towards the volume, rise a little, look down ~15 deg
H="$(cd "$(dirname "$0")" && pwd)/harness/emu.sh"
bash "$H" fast on >/dev/null; sleep 2
case "${1:-near}" in
  # measured 2026-10-23 with the 1.4x volume: walking speed ramps up, so overshoot then step back, rise, look down
  near) bash "$H" key W -HoldMs 1000; sleep 1; bash "$H" key E -HoldMs 300; sleep 1; bash "$H" drag 1080 900 1080 1020 -Button right
        sleep 1; bash "$H" fast on; sleep 2; bash "$H" key S -HoldMs 450; sleep 1; bash "$H" key E -HoldMs 250; sleep 1
        bash "$H" drag 1080 900 1080 1040 -Button right ;;
esac
