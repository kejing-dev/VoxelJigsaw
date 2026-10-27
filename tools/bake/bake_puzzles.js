#!/usr/bin/env node
/*
 * Offline puzzle bake (design cutting.where). Reads the three landmark voxel models from vj-core.js (the same
 * core the design sample uses), cuts each into 25 and 50 pieces with the seeds from the design package, checks
 * the three ambiguity gates and the wing packing, and writes:
 *   app/src/main/assets/puzzles/<landmark>-<n>.json   pieces, homes, allowed / scatter orientations, wing spots
 *   app/src/main/assets/puzzles/rots.json             the 24 axis rotations (Kotlin Rot24 is checked against it)
 *   bake/ambiguity-report.json                        per piece: tipped, near-ambiguity, equivalence, wing spot
 * Any set that misses a gate fails the bake (exit 1). Run: node tools/bake/bake_puzzles.js
 */
"use strict";
const fs = require("fs");
const path = require("path");
const Core = require("./vj-core.js");

const ROOT = path.resolve(__dirname, "..", "..");
const OUT = path.join(ROOT, "app", "src", "main", "assets", "puzzles");
const REPORT = path.join(ROOT, "bake");
const SEEDS = { "qinian-25": 3946, "qinian-50": 7, "bigben-25": 108, "bigben-50": 7, "taj-25": 7, "taj-50": 7 };
const VOX = 0.012;

// segment label of a voxel (design rules.facts: three segments per landmark, piece = majority of its voxels)
function segOf(id, v) {
  if (id === "qinian") return v.y <= 2 ? 0 : (v.y <= 7 ? 1 : 2);
  if (id === "bigben") return v.y <= 13 ? 0 : (v.y <= 17 ? 1 : 2);
  const mx = Math.abs(v.x + 0.5), mz = Math.abs(v.z + 0.5);
  if (v.y <= 2 || ((mx === 7.5 || mx === 8.5) && (mz === 7.5 || mz === 8.5))) return 0;
  return v.y <= 11 ? 1 : 2;
}

// wing spot of a packed piece, in volume metres (sample wingPos: left x -40..-15 vox, right 15..40, y from 5, back raised 3.3)
function wingPos(slot) {
  const left = slot.bin % 2 === 0, back = slot.bin >= 2;
  const x = left ? -40 + slot.x + slot.w / 2 : 15 + slot.x + slot.w / 2;
  const y = 5 + slot.y + slot.h / 2 + (back ? 3.3 : 0);
  const z = back ? -6.7 : 8.3;
  return [x * VOX, y * VOX, z * VOX].map((n) => +n.toFixed(5));
}

function normKey(vox, R) {
  const pts = vox.map((v) => ({ p: Core.rot(R, [2 * v.x + 1, 2 * v.y + 1, 2 * v.z + 1]), c: v.c }));
  const mn = [Infinity, Infinity, Infinity];
  pts.forEach((q) => { for (let a = 0; a < 3; a++) mn[a] = Math.min(mn[a], q.p[a]); });
  return pts.map((q) => ((q.p[0] - mn[0]) / 2) + "," + ((q.p[1] - mn[1]) / 2) + "," + ((q.p[2] - mn[2]) / 2) + "|" + q.c).sort().join(";");
}

fs.mkdirSync(OUT, { recursive: true });
fs.mkdirSync(REPORT, { recursive: true });
fs.writeFileSync(path.join(OUT, "rots.json"), JSON.stringify(Core.ROTS));

const report = { generatedBy: "tools/bake/bake_puzzles.js", gates: "wrongAtStart = n, tipped >= 60%, nearAmbig >= 30%, packFail = 0, maxEdge <= cap", sets: {} };
let failed = false;
for (const key of Object.keys(SEEDS)) {
  const [id, nStr] = key.split("-");
  const n = +nStr;
  const P = Core.makePuzzle(id, n, SEEDS[key]);
  const s = P.stats;
  const palette = [];
  const ci = (c) => { let i = palette.indexOf(c); if (i < 0) { palette.push(c); i = palette.length - 1; } return i; };
  let radius = 0, top = 0;
  P.model.list.forEach((v) => { radius = Math.max(radius, Math.hypot(v.x + 0.5, v.z + 0.5) + 0.7); top = Math.max(top, v.y + 1); });
  // per-layer outer radius (voxel corners), for pushing a piece just outside the landmark ("parking", spatialLayout.release)
  const layerRadius = new Array(top).fill(0);
  P.model.list.forEach((v) => {
    for (const dx of [0, 1]) for (const dz of [0, 1]) layerRadius[v.y] = Math.max(layerRadius[v.y], Math.hypot(v.x + dx, v.z + dz));
  });
  const keys = P.pieces.map((p) => Core.ROTS.map((M) => normKey(p.vox, M)));
  const pieces = P.pieces.map((p) => {
    const segCount = [0, 0, 0];
    p.vox.forEach((v) => segCount[segOf(id, v)]++);
    // equivalent pieces: the rotations that make this piece look exactly like another one at that one's home
    const equiv = [];
    P.pieces.forEach((q) => {
      if (q.idx === p.idx || q.vox.length !== p.vox.length) return;
      const target = keys[q.idx][0];
      const rots = [];
      keys[p.idx].forEach((k, i) => { if (k === target) rots.push(i); });
      if (rots.length) equiv.push({ idx: q.idx, rots });
    });
    return {
      idx: p.idx,
      home: p.home,
      min: p.min,
      max: p.max,
      vox: p.vox.map((v) => [v.x, v.y, v.z, ci(v.c)]),
      allowed: p.allowed,
      scatterRot: p.scatterRot,
      tipped: p.tipped,
      nearFits: p.nearFits,
      wing: wingPos(p.slot),
      wingSlot: p.slot,
      seg: segCount.indexOf(Math.max(...segCount)),
      equiv,
    };
  });
  const segCount = [0, 0, 0];
  pieces.forEach((p) => segCount[p.seg]++);
  const ok = P.ok && s.wrongAtStart === n && s.tipped >= 0.6 * n && s.nearAmbig >= 0.3 * n && s.packFail === 0 && segCount.every((c) => c > 0);
  if (!ok) failed = true;
  const out = { id, n, seedHint: SEEDS[key], seed: P.seed, voxel: VOX, palette, top, radius: +radius.toFixed(3), layerRadius: layerRadius.map((r) => +r.toFixed(3)), order: P.order, segCount, pieces };
  fs.writeFileSync(path.join(OUT, `${key}.json`), JSON.stringify(out));
  report.sets[key] = {
    ok, seed: P.seed, stats: s, segCount,
    tippedPct: Math.round((100 * s.tipped) / n), nearAmbigPct: Math.round((100 * s.nearAmbig) / n),
    equivPieces: pieces.filter((p) => p.equiv.length).length,
    pieces: pieces.map((p) => ({ idx: p.idx, voxels: p.vox.length, ext: [p.max[0] - p.min[0], p.max[1] - p.min[1], p.max[2] - p.min[2]], tipped: p.tipped, nearFits: p.nearFits, allowed: p.allowed, scatterRot: p.scatterRot, seg: p.seg, equiv: p.equiv.map((e) => e.idx), wing: p.wing })),
  };
  console.log(`${key}: ${ok ? "OK " : "FAIL"} seed=${P.seed} pieces=${s.pieces} voxels=${s.voxels} size ${s.minSize}-${s.maxSize} maxEdge=${s.maxEdge}/${s.cap} tipped=${report.sets[key].tippedPct}% nearAmbig=${report.sets[key].nearAmbigPct}% wrongAtStart=${s.wrongAtStart}/${n} packFail=${s.packFail} segs=${segCount}`);
}
fs.writeFileSync(path.join(REPORT, "ambiguity-report.json"), JSON.stringify(report, null, 1));
if (failed) { console.error("BAKE FAILED: a set misses a gate"); process.exit(1); }
console.log("bake ok ->", OUT);
