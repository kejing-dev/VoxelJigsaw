# 体素拼图 Voxel Jigsaw · agent notes

PICO OS 6 spatial app (Spatial SDK 6.1.9, Kotlin + SpatialUI/PicoTheme, no Material). Package `io.embodit.voxeljigsaw`.
Factory app `voxel-jigsaw`, skeleton A: one Volumetric DefaultWindowContainer in Shared Space, panels are AttachmentPanels
inside the volume (design package `spatial-app-factory/apps/voxel-jigsaw/design-package.json`, states S01–S18).

## Layout

- `tools/bake/bake_puzzles.js` — offline bake (node). Reuses the design sample's `vj-core.js`: 3 landmarks × 25/50 pieces,
  seeds from the design package, ambiguity gates. Writes `app/src/main/assets/puzzles/*.json` and `bake/ambiguity-report.json`.
- `domain/` — plain-JVM logic with unit tests: `Rot24` (the 24 axis rotations, turn keys, key paths), `Puzzle` (parse),
  `Rules` (release judgement, parking, hints; easy mode), `Mesher` (greedy voxel mesher), `SaveGame`.
- `scene/JigsawScene.kt` — all 3D (ECS entities, a 16 ms main-thread frame loop like the reference repo SpatialJanga):
  turntable, pieces, ghosts, outlines, sweep, attachment panel holders. `WORLD_SCALE = 1.4` scales the whole design.
- `ui/GameController.kt` — state machine S01–S18, saves/records, messages, step guide, panel layout.
- `ui/Panels.kt` — SpatialUI panels (home, top bar, turn keys, landmark card, display pill).
- `ui/DebugCommands.kt` — QA broadcast (debug builds).

## Build / run (emulator-5562, AVD PICO_6_1_Factory2)

```
tools/dev.sh build | install | relaunch | log [REGEX] | crash | shot OUT.png
node tools/bake/bake_puzzles.js          # only when the puzzles change
./gradlew.bat testDebugUnitTest           # JAVA_HOME = primer JDK 21 (see tools/dev.sh)
```

## Debug broadcast

```
adb -s emulator-5562 shell am broadcast -a io.embodit.voxeljigsaw.DEBUG -p io.embodit.voxeljigsaw --es cmd <name> [--es s '<args>']
tools/dev.sh cmd <name> '<args>'
```

`state <S01..S18>` sets up a design state. Others: `seed <landmark> <25|50> <placed>`, `solveNext`, `solve`,
`place <idx|next> <ok|wrongOrient|elsewhere>`, `hold <idx|next>`, `select <idx|next>`, `turn <y|x>`, `yaw <deg>`,
`hint`, `peek`, `menu`, `home`, `settings`, `display`, `card`, `count <25|50>`, `landmark <id>`, `start`,
`locale <zh|en|follow>`, `corruptSave`, `boundsCheck`, `stick <x>,<ms>`, `linger <on|off>` (timed effects 15× longer for
screenshots), `dump`, `resetAll`. Every command logs `CMD …`; state changes log `STATE Sxx …` (tag `VoxelJigsaw`).

## Screenshots

- `tools/cam.sh near` — frames the volume from the launch view (looking down at the turntable).
- `tools/qa_tour.sh [S01 …]` — every state → `spatial-app-factory/apps/voxel-jigsaw/build/states/<id>.png` + `tour.txt`
  (linger on, 2–3 frames, keeps the cleanest via `tools/bestshot.py`; the emulator often returns half-black/white frames).
- Real input: `tools/harness/emu.sh click|drag X Y …` (harness framebuffer = screenshot px × 0.84375). Verified:
  pinch-tap selects a piece, pinch-drag moves it (emulator gain ≈ 2× hand travel), drag on the table rim turns it to a 90° stop.

## Easy mode (default on, user feedback 2026-10-23)

"难度太高，需要每一块每一步的提示，点击提示高亮那一块，整体要大一点":
- the next piece in assembly order keeps a breathing gold frame and its gold ghost at the right place; top bar
  step line "第 n / 25 步 …"; tapping the step line or 提示 bobs and selects that piece (turn keys pop up);
- a piece released within 4 cm of its place snaps in and turns itself the right way;
- off in Settings → original design rules (2.5 cm, orientation must match; step line then shows the key presses ↻ ⤵).
- everything is 1.4× the design size (volume 1.4 × 0.84 × 0.7 m).

## Panels stay inside the volume (v0.1.3, headset feedback 2026-10-28 "voxel UI 被 crop 了")

The volume clips anything that sticks out. The tilted home panel hung below the turntable floor and past the front face,
so its bottom row was cut on the headset. Now the volume is the play space plus 8 cm under the floor and 8 cm in front
(`JigsawScene.PANEL_MIN / PANEL_MAX`, manifest 1.4 × 0.952 × 0.812 m), and `showPanel` moves each panel the least distance
that keeps its whole tilted rectangle 1.5 cm inside (log `PANEL fit …`). Panel sizes come from `PanelSizes` (dp × 0.00082 m);
`getVisualBounds` returns zeros for attachment panels, so the `panels` debug command cannot measure them.

Meshes are built in steps (`JigsawScene.ensureMeshes`): one SDK mesh per main-thread turn. Building a landmark in one go
blocked input ~10 s on the idle emulator and raised "isn't responding" (v0.1.2, ANR trace 2026-10-28 18:52).

## Known limits

- Thumbstick: `ControllerTrackingProvider` only reports in Full Space; this is a Shared Space volume. The listener and a
  horizontal-scroll fallback are wired but unverified on a device; `stick` debug command drives the same code path.
- `ObjectAudioComponent` not used: SoundPool with four synthesised wavs; mute key on the home panel and top bar.
- Mesh creation is slow while the emulator idles (~14 s per landmark at 2–4 fps; 0.4 s at 60 fps), but stepped, so input stays live.
