#!/usr/bin/env bash
# Emulator camera presets for emulator-5560 (keys posted to the emulator window, see tools/harness/emu.sh).
#   tools/cam.sh near   from the launch view: walk ~1.3 m towards the volume, rise a little, look down ~15 deg
H="$(cd "$(dirname "$0")" && pwd)/harness/emu.sh"
bash "$H" fast on >/dev/null; sleep 2
case "${1:-near}" in
  # measured 2026-10-22: walking speed ramps up, so the overshoot-then-back sequence is what frames the volume
  near) bash "$H" key W -HoldMs 1400; sleep 1; bash "$H" key E -HoldMs 300; sleep 1; bash "$H" drag 1080 900 1080 1060 -Button right
        sleep 1; bash "$H" fast on; sleep 2; bash "$H" key S -HoldMs 600; sleep 1; bash "$H" drag 1080 900 1080 1000 -Button right ;;
esac
