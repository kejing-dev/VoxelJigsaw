#!/usr/bin/env node
/*
 * Offline puzzle bake. Reads the three landmark voxel models from vj-core.js (the same core the design sample uses),
 * cuts each into two levels (headset feedback 2026-10-28: "块数少一点 5-10 块一关，10-20 块第二关"), lays the pieces out
 * in the two wings so that no two overlap from the user's eye (profile D17: "pieces 要更分散一点…容易遮挡看不清，也选不中"),
 * checks the gates, and writes:
 *   app/src/main/assets/puzzles/<landmark>-<n>.json   pieces, homes, allowed / scatter orientations, wing spots
 *   app/src/main/assets/puzzles/rots.json             the 24 axis rotations (Kotlin Rot24 is checked against it)
 *   app/src/main/assets/puzzles/levels.json           piece count of level 1 and 2 per landmark
 *   bake/ambiguity-report.json                        per set: gates, projected gaps; per piece: size, orientation, wing spot
 * Any set that misses a gate fails the bake (exit 1). Run: node tools/bake/bake_puzzles.js
 */
"use strict";
const fs = require("fs");
const path = require("path");
const Core = require("./vj-core.js");

const ROOT = path.resolve(__dirname, "..", "..");
const OUT = path.join(ROOT, "app", "src", "main", "assets", "puzzles");
const REPORT = path.join(ROOT, "bake");
const VOX = 0.012;
const VOX_OF = { taj: +(process.env.TAJ_VOX || 0.011) };
let vox = VOX; // voxel size of the set being baked

// level sets: piece count and longest-edge cap (voxels). The Taj's minarets are 13 voxels tall and only join the rest
// through the plinth, so it needs a couple more pieces than the others to keep every piece within the cap.
const SETS = [
  { id: "qinian", level: 1, n: 8, cap: 14, seed: 11 },
  { id: "qinian", level: 2, n: 16, cap: 11, seed: 23 },
  { id: "bigben", level: 1, n: 8, cap: 12, seed: 31 },
  { id: "bigben", level: 2, n: 16, cap: 10, seed: 41 },
  { id: "taj", level: 1, n: 10, cap: 14, seed: 53 },
  { id: "taj", level: 2, n: 16, cap: 12, seed: 61 },
];

// ---- wing layout (design metres, volume frame: origin on the volume floor centre, +Y up, +Z towards the user) ----
// Kotlin JigsawScene.VOL_MIN / VOL_MAX must match WING_X_MAX + margin.
const EYE = [0, 0.45, 1.2]; // design eye (design package spatialLayout)
const GAP = 0.03; // between projected boxes, design metres (x 1.4 on the headset = 4.2 cm)
const WING_X = [0.215, +(process.env.WX || 0.585)]; // |x| of a wing: clear of the turntable and the landmark, inside the volume (±0.6)
const WING_Y = [0.02, +(process.env.WY || 0.64)];
const WING_Z = 0.03; // front face plane of the pieces in the wings
// the top bar hangs at z 0.04 (in the wing plane), y 0.50..0.645, |x| < 0.2: pieces under it stay below this height
const TOPBAR = { xMax: 0.245, yMin: 0.485 };

const rotExt = (R, e) => Core.rot(R, e).map(Math.abs);

/** A box (centre c, half h) seen from EYE, projected onto the plane z = 0: [x0, y0, x1, y1]. */
function project(c, h) {
  let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
  for (const sx of [-1, 1]) for (const sy of [-1, 1]) for (const sz of [-1, 1]) {
    const p = [c[0] + sx * h[0], c[1] + sy * h[1], c[2] + sz * h[2]];
    const t = EYE[2] / (EYE[2] - p[2]);
    const x = EYE[0] + (p[0] - EYE[0]) * t, y = EYE[1] + (p[1] - EYE[1]) * t;
    x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y);
  }
  return [x0, y0, x1, y1];
}
const boxGap = (a, b) => Math.max(b[0] - a[2], a[0] - b[2], b[1] - a[3], a[1] - b[3]);

/**
 * Scatter orientation of each piece: a wrong one (not in allowed), as compact as possible seen from the front (the
 * projected width x height, depth counted at 0.4 for the perspective), tipped over when that costs little.
 */
function chooseScatter(p) {
  const bad = Core.ROTS.map((_, i) => i).filter((i) => p.allowed.indexOf(i) < 0);
  const cost = (i) => {
    const e = rotExt(Core.ROTS[i], p.ext).map((v) => v * vox);
    return (e[0] + 0.4 * e[2] + GAP) * (e[1] + 0.4 * e[2] + GAP);
  };
  const best = Math.min(...bad.map(cost));
  const near = bad.filter((i) => cost(i) <= best * 1.08);
  const tipped = near.filter((i) => Core.ROTS[i][1][1] !== 1);
  const pick = (tipped.length ? tipped : near).sort((a, b) => cost(a) - cost(b))[0];
  return pick === undefined ? 0 : pick;
}

/**
 * Lays [pieces] out in the two wings as rows (shelves) of front-facing boxes: rows spread up the wing, the pieces of a
 * row spread across it, rows that reach under the top bar start outside it. Tries a few orderings and keeps the one
 * with the widest smallest projected gap. Returns null when nothing fits.
 */
function layout(pieces) {
  const items = pieces.map((p) => {
    const e = rotExt(Core.ROTS[p.scatterRot], p.ext).map((v) => v * vox);
    return { p, w: e[0], h: e[1], d: e[2] };
  });
  const effW = (it) => it.w + it.d * (WING_X[1] / EYE[2]);
  const effH = (it) => it.h + it.d * 0.35;
  const orders = [
    (a, b) => b.h - a.h || b.w - a.w,
    (a, b) => effW(b) * effH(b) - effW(a) * effH(a),
    (a, b) => b.w - a.w || b.h - a.h,
  ];
  let best = null;
  for (const ord of orders) for (const balance of ["area", "count"]) for (const vSpread of [1, 0.5]) {
    const res = tryLayout(items, ord, balance, vSpread, effW, effH);
    if (res && (!best || res.minGap > best.minGap)) best = { ...res, pos: items.map((it) => it.pos.slice()) };
  }
  if (!best) return null;
  items.forEach((it, i) => { it.p.wing = best.pos[i].map((v) => +v.toFixed(5)); it.p.wingSize = [it.w, it.h, it.d].map((v) => +v.toFixed(4)); });
  return best;
}

function tryLayout(items, ord, balance, vSpread, effW, effH) {
  const wings = [[], []], load = [0, 0];
  items.slice().sort(ord).forEach((it) => {
    const k = load[0] <= load[1] ? 0 : 1;
    wings[k].push(it); load[k] += balance === "area" ? (effW(it) + GAP) * (effH(it) + GAP) : 1;
  });
  for (let k = 0; k < 2; k++) {
    const sign = k === 0 ? -1 : 1;
    const list = wings[k].slice().sort(ord);
    // rows from the bottom; a row's inner edge moves out past the top bar when the row reaches under it
    const rows = [];
    let y = WING_Y[0];
    let row = null;
    const inner = (y0, h) => (y0 + h > TOPBAR.yMin ? Math.max(WING_X[0], TOPBAR.xMax) : WING_X[0]);
    for (const it of list) {
      if (row) {
        const h = Math.max(row.h, effH(it));
        if (row.w + GAP + effW(it) <= WING_X[1] - inner(row.y, h)) { row.items.push(it); row.w += GAP + effW(it); row.h = h; continue; }
        y = row.y + row.h + GAP;
      }
      row = { y, items: [it], w: effW(it), h: effH(it) };
      if (row.w > WING_X[1] - inner(y, row.h)) return null;
      rows.push(row);
    }
    const top = rows.length ? rows[rows.length - 1].y + rows[rows.length - 1].h : WING_Y[0];
    if (top > WING_Y[1]) return null;
    // spread the rows over the free height (vSpread of it), then the pieces of each row across its width
    const extra = rows.length > 1 ? Math.min(((WING_Y[1] - top) * vSpread) / (rows.length - 1), 0.06) : 0;
    rows.forEach((r, i) => {
      r.y += extra * i;
      const x0 = inner(r.y, r.h);
      const spare = WING_X[1] - x0 - r.w;
      const hgap = r.items.length > 1 ? GAP + Math.min(spare / (r.items.length - 1), 0.08) : 0;
      const total = r.w + (hgap - GAP) * (r.items.length - 1);
      let x = x0 + Math.max(0, (WING_X[1] - x0 - total) / 2);
      for (const it of r.items) {
        it.pos = [sign * (x + it.w / 2), r.y + it.h / 2, WING_Z - it.d / 2];
        x += effW(it) + hgap;
      }
    });
  }
  const boxes = items.map((it) => project(it.pos, [it.w / 2, it.h / 2, it.d / 2]));
  let minGap = Infinity;
  for (let i = 0; i < items.length; i++) for (let j = i + 1; j < items.length; j++) minGap = Math.min(minGap, boxGap(boxes[i], boxes[j]));
  const minSide = Math.min(...boxes.map((b) => Math.min(Math.abs(b[0]), Math.abs(b[2]))));
  return { minGap, minSide };
}

function segOf(id, v) {
  if (id === "qinian") return v.y <= 2 ? 0 : (v.y <= 7 ? 1 : 2);
  if (id === "bigben") return v.y <= 13 ? 0 : (v.y <= 17 ? 1 : 2);
  const mx = Math.abs(v.x + 0.5), mz = Math.abs(v.z + 0.5);
  if (v.y <= 2 || ((mx === 7.5 || mx === 8.5) && (mz === 7.5 || mz === 8.5))) return 0;
  return v.y <= 11 ? 1 : 2;
}

function normKey(vox, R) {
  const pts = vox.map((v) => ({ p: Core.rot(R, [2 * v.x + 1, 2 * v.y + 1, 2 * v.z + 1]), c: v.c }));
  const mn = [Infinity, Infinity, Infinity];
  pts.forEach((q) => { for (let a = 0; a < 3; a++) mn[a] = Math.min(mn[a], q.p[a]); });
  return pts.map((q) => ((q.p[0] - mn[0]) / 2) + "," + ((q.p[1] - mn[1]) / 2) + "," + ((q.p[2] - mn[2]) / 2) + "|" + q.c).sort().join(";");
}

/** Cuts set [s] trying seeds until the count, size and layout gates pass; the best try otherwise. */
function makeSet(s) {
  const model = Core.buildModel(s.id);
  let best = null;
  for (let t = 0; t < 80; t++) {
    const seed = s.seed + t * 101;
    const pieces = Core._cut(model, s.n, seed, s.cap);
    if (pieces.length !== s.n) continue;
    const a = Core._analyse(model, pieces, seed);
    pieces.forEach((p) => { p.scatterRot = chooseScatter(p); p.tipped = Core.ROTS[p.scatterRot][1][1] !== 1; });
    const lay = layout(pieces);
    const st = a.stats;
    st.tipped = pieces.filter((p) => p.tipped).length;
    st.wrongAtStart = pieces.filter((p) => p.allowed.indexOf(p.scatterRot) < 0).length;
    const ok = !!lay && lay.minGap >= GAP - 1e-4 && lay.minSide >= 0.17 && st.minSize >= 8 && st.maxEdge <= s.cap && st.wrongAtStart === s.n && st.tipped >= 0.5 * s.n;
    const score = (lay ? lay.minGap * 100 : -100) + st.withinBand - 3 * st.slabs;
    if (!best || (ok && !best.ok) || (ok === best.ok && score > best.score)) best = { model, pieces, order: a.order, stats: st, lay, seed, ok, score };
    if (ok && st.slabs === 0 && lay.minGap >= GAP + 0.005) break;
  }
  return best;
}

fs.mkdirSync(OUT, { recursive: true });
fs.mkdirSync(REPORT, { recursive: true });
for (const f of fs.readdirSync(OUT)) if (/^[a-z]+-\d+\.json$/.test(f)) fs.unlinkSync(path.join(OUT, f));
fs.writeFileSync(path.join(OUT, "rots.json"), JSON.stringify(Core.ROTS));

const levels = {};
const report = { generatedBy: "tools/bake/bake_puzzles.js", gates: `n exact, wrongAtStart = n, tipped >= 50%, maxEdge <= cap, projected gap >= ${GAP} m from eye ${EYE}`, sets: {} };
let failed = false;
for (const s of SETS) {
  const key = `${s.id}-${s.n}`;
  (levels[s.id] = levels[s.id] || [])[s.level - 1] = s.n;
  vox = VOX_OF[s.id] || VOX;
  const P = makeSet(s);
  if (!P) { console.error(`${key}: no cut with ${s.n} pieces`); failed = true; continue; }
  const st = P.stats, id = s.id, n = s.n;
  const palette = [];
  const ci = (c) => { let i = palette.indexOf(c); if (i < 0) { palette.push(c); i = palette.length - 1; } return i; };
  let radius = 0, top = 0;
  P.model.list.forEach((v) => { radius = Math.max(radius, Math.hypot(v.x + 0.5, v.z + 0.5) + 0.7); top = Math.max(top, v.y + 1); });
  const layerRadius = new Array(top).fill(0);
  P.model.list.forEach((v) => {
    for (const dx of [0, 1]) for (const dz of [0, 1]) layerRadius[v.y] = Math.max(layerRadius[v.y], Math.hypot(v.x + dx, v.z + dz));
  });
  const keys = P.pieces.map((p) => Core.ROTS.map((M) => normKey(p.vox, M)));
  const pieces = P.pieces.map((p) => {
    const segCount = [0, 0, 0];
    p.vox.forEach((v) => segCount[segOf(id, v)]++);
    const equiv = [];
    P.pieces.forEach((q) => {
      if (q.idx === p.idx || q.vox.length !== p.vox.length) return;
      const target = keys[q.idx][0];
      const rots = [];
      keys[p.idx].forEach((k, i) => { if (k === target) rots.push(i); });
      if (rots.length) equiv.push({ idx: q.idx, rots });
    });
    return {
      idx: p.idx, home: p.home, min: p.min, max: p.max,
      vox: p.vox.map((v) => [v.x, v.y, v.z, ci(v.c)]),
      allowed: p.allowed, scatterRot: p.scatterRot, tipped: p.tipped, nearFits: p.nearFits,
      wing: p.wing, seg: segCount.indexOf(Math.max(...segCount)), equiv,
    };
  });
  const segCount = [0, 0, 0];
  pieces.forEach((p) => segCount[p.seg]++);
  const ok = P.ok && segCount.every((c) => c > 0);
  if (!ok) failed = true;
  const out = { id, n, level: s.level, seedHint: s.seed, seed: P.seed, voxel: vox, palette, top, radius: +radius.toFixed(3), layerRadius: layerRadius.map((r) => +r.toFixed(3)), order: P.order, segCount, pieces };
  fs.writeFileSync(path.join(OUT, `${key}.json`), JSON.stringify(out));
  const gap = P.lay ? P.lay.minGap : NaN;
  report.sets[key] = {
    ok, level: s.level, seed: P.seed, cap: s.cap, stats: st, segCount, minProjectedGapM: +gap.toFixed(4),
    tippedPct: Math.round((100 * st.tipped) / n), nearAmbigPct: Math.round((100 * st.nearAmbig) / n),
    pieces: P.pieces.map((p) => ({ idx: p.idx, voxels: p.vox.length, ext: p.ext, scatterRot: p.scatterRot, tipped: p.tipped, wing: p.wing, wingSize: p.wingSize })),
  };
  console.log(`${key}: ${ok ? "OK " : "FAIL"} L${s.level} seed=${P.seed} voxels=${st.voxels} size ${st.minSize}-${st.maxSize} maxEdge=${st.maxEdge}/${s.cap} tipped=${report.sets[key].tippedPct}% wrongAtStart=${st.wrongAtStart}/${n} gap=${(gap * 100).toFixed(1)}cm side=${P.lay ? P.lay.minSide.toFixed(3) : "-"} segs=${segCount}`);
}
fs.writeFileSync(path.join(OUT, "levels.json"), JSON.stringify(levels));
fs.writeFileSync(path.join(REPORT, "ambiguity-report.json"), JSON.stringify(report, null, 1));
if (failed) { console.error("BAKE FAILED: a set misses a gate"); process.exit(1); }
console.log("bake ok ->", OUT);
