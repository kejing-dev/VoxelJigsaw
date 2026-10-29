/* 体素拼图 · 样稿内核：地标体素模型 + 切块 + 朝向歧义统计（浏览器与 node 共用） */
(function (root) {
"use strict";

function rng(seed) {
  return function () {
    seed |= 0; seed = seed + 0x6D2B79F5 | 0;
    var t = Math.imul(seed ^ seed >>> 15, 1 | seed);
    t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t;
    return ((t ^ t >>> 14) >>> 0) / 4294967296;
  };
}

/* ---------- 3 座地标（体素格，x/z 以 0 为中心，y 从 0 向上） ---------- */
var MODELS = {
  qinian: function (put) {
    var C = { m: "#E9E4D8", m2: "#CFC6B3", r: "#B5302A", r2: "#7E2620", g: "#D9A441", t: "#2E6B6A", b: "#2F5FC4", b2: "#24479A" };
    for (var x = -10; x < 10; x++) for (var z = -10; z < 10; z++) {
      var r = Math.hypot(x + .5, z + .5), a = Math.atan2(z + .5, x + .5);
      var sec = Math.floor((a + Math.PI) / (2 * Math.PI) * 16) % 16;
      if (r <= 9.6) put(x, 0, z, r > 8.7 ? C.m2 : C.m);
      if (r <= 8.4) put(x, 1, z, r > 7.6 ? C.m2 : C.m);
      if (r <= 7.2) put(x, 2, z, C.m);
      for (var y = 3; y <= 6; y++) if (r <= 5.6) put(x, y, z, r > 4.7 ? ((y >= 4 && y <= 5 && sec % 2 === 0) ? C.g : C.r) : C.r2);
      if (r <= 5.8) put(x, 7, z, r > 4.8 && sec % 2 === 0 ? C.g : C.t);
      if (r <= 7.4) put(x, 8, z, r > 6.6 ? C.b2 : C.b);
      if (r <= 6.6) put(x, 9, z, C.b);
      if (r <= 4.6) put(x, 10, z, r > 3.7 && sec % 2 === 0 ? C.g : C.r);
      if (r <= 4.8) put(x, 11, z, C.t);
      if (r <= 5.8) put(x, 12, z, r > 5.0 ? C.b2 : C.b);
      if (r <= 5.0) put(x, 13, z, C.b);
      if (r <= 3.4) put(x, 14, z, C.r);
      if (r <= 3.6) put(x, 15, z, C.t);
      if (r <= 4.4) put(x, 16, z, r > 3.6 ? C.b2 : C.b);
      if (r <= 3.6) put(x, 17, z, C.b);
      if (r <= 2.6) put(x, 18, z, C.b);
      if (r <= 1.6) put(x, 19, z, C.b2);
      if (r <= 0.8) { put(x, 20, z, C.g); put(x, 21, z, C.g); put(x, 22, z, C.g); }
    }
  },
  bigben: function (put) {
    var S = "#CDB98A", S2 = "#A8925F", W = "#3C4250", F = "#F3EEDC", G = "#D9A441", K = "#2A2E36", D = "#3E4A55";
    for (var x = -6; x < 6; x++) for (var z = -6; z < 6; z++) {
      var ax = x + .5, az = z + .5, m = Math.max(Math.abs(ax), Math.abs(az));
      var u = Math.abs(ax) >= Math.abs(az) ? az : ax;
      var rib = Math.round(u - .5) % 2 === 0;
      if (m <= 5.5) { put(x, 0, z, S2); put(x, 1, z, S); }
      for (var y = 2; y <= 13; y++) if (m <= 4) {
        var c = S;
        if (m > 3) { c = rib ? S2 : S; if (!rib && Math.abs(u) <= 2.5 && [4, 5, 8, 9, 12].indexOf(y) >= 0) c = W; }
        put(x, y, z, c);
      }
      for (y = 14; y <= 17; y++) if (m <= 5) {
        c = S;
        if (m > 4 && Math.abs(u) <= 3.5) {
          var d = Math.hypot(u, y - 15.5);
          c = d <= 0.8 ? K : (d <= 2.3 ? F : G);
        }
        put(x, y, z, c);
      }
      for (y = 18; y <= 19; y++) if (m <= 4) put(x, y, z, m > 3 ? (rib ? S : W) : S);
      if (m <= 4) put(x, 20, z, G);
      if (m <= 3) put(x, 21, z, D);
      if (m <= 2.5) { put(x, 22, z, D); put(x, 23, z, D); }
      if (m <= 1.5) { put(x, 24, z, D); put(x, 25, z, D); }
      if (m <= 0.5) { put(x, 26, z, G); put(x, 27, z, G); }
    }
  },
  taj: function (put) {
    var R = "#B5654A", M = "#EEEAE2", M2 = "#D9D3C7", A = "#BDB4A3", G = "#D8C38A", Au = "#D9A441";
    for (var x = -10; x < 10; x++) for (var z = -10; z < 10; z++) {
      var ax = x + .5, az = z + .5, m = Math.max(Math.abs(ax), Math.abs(az)), r = Math.hypot(ax, az);
      var u = Math.abs(ax) >= Math.abs(az) ? az : ax, au = Math.abs(u);
      if (m <= 9.5) put(x, 0, z, R);
      if (m <= 8.5) { put(x, 1, z, M2); put(x, 2, z, m > 7.5 ? M2 : M); }
      var mx = Math.abs(ax), mz = Math.abs(az);
      if ((mx === 7.5 || mx === 8.5) && (mz === 7.5 || mz === 8.5)) {
        for (var y = 3; y <= 14; y++) put(x, y, z, (y === 6 || y === 10 || y === 14) ? G : M);
        put(x, 15, z, Au);
      }
      var inBody = m <= 5.5 && (mx + mz) <= 9.5;
      for (y = 3; y <= 10; y++) if (inBody) {
        var c = M;
        if (m > 4.5) {
          if ((au <= 1.5 && y >= 4 && y <= 8) || (au <= 0.5 && y === 9)) c = A;
          else if ((au === 2.5 && y >= 4 && y <= 9) || (au <= 2.5 && y === 10)) c = G;
        }
        put(x, y, z, c);
      }
      if (inBody) put(x, 11, z, m > 4.5 ? G : M);
      if ((mx === 3.5 || mx === 4.5) && (mz === 3.5 || mz === 4.5)) { put(x, 12, z, M); put(x, 13, z, M); put(x, 14, z, Au); }
      else {
        if (r <= 3.0) { put(x, 12, z, M); put(x, 13, z, M); }
        if (r <= 4.0) put(x, 14, z, M);
        if (r <= 4.6) { put(x, 15, z, M); put(x, 16, z, M); }
        if (r <= 4.0) put(x, 17, z, M);
        if (r <= 3.2) put(x, 18, z, M);
        if (r <= 2.2) put(x, 19, z, M);
        if (r <= 1.2) put(x, 20, z, M2);
        if (r <= 0.8) { put(x, 21, z, Au); put(x, 22, z, Au); }
      }
    }
  }
};

function key(x, y, z) { return x + "," + y + "," + z; }

function buildModel(id) {
  var map = new Map(), list = [];
  MODELS[id](function (x, y, z, c) {
    var k = key(x, y, z);
    if (!map.has(k)) { var v = { x: x, y: y, z: z, c: c, i: list.length }; map.set(k, v); list.push(v); }
    else map.get(k).c = c;
  });
  return { id: id, map: map, list: list };
}

var N6 = [[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0], [0, 0, 1], [0, 0, -1]];

/* 24 个轴向旋转（整数 3×3，按行） */
var ROTS = (function () {
  var out = [], perms = [[0, 1, 2], [0, 2, 1], [1, 0, 2], [1, 2, 0], [2, 0, 1], [2, 1, 0]];
  perms.forEach(function (p) {
    for (var s = 0; s < 8; s++) {
      var M = [[0, 0, 0], [0, 0, 0], [0, 0, 0]];
      for (var r = 0; r < 3; r++) M[r][p[r]] = (s >> r & 1) ? -1 : 1;
      var det = M[0][0] * (M[1][1] * M[2][2] - M[1][2] * M[2][1]) - M[0][1] * (M[1][0] * M[2][2] - M[1][2] * M[2][0]) + M[0][2] * (M[1][0] * M[2][1] - M[1][1] * M[2][0]);
      if (det === 1) out.push(M);
    }
  });
  return out; // ROTS[0] 是单位阵
})();
function rot(M, v) { return [M[0][0] * v[0] + M[0][1] * v[1] + M[0][2] * v[2], M[1][0] * v[0] + M[1][1] * v[1] + M[1][2] * v[2], M[2][0] * v[0] + M[2][1] * v[1] + M[2][2] * v[2]]; }
function mul(A, B) { var C = [[0, 0, 0], [0, 0, 0], [0, 0, 0]]; for (var i = 0; i < 3; i++) for (var j = 0; j < 3; j++) for (var k = 0; k < 3; k++) C[i][j] += A[i][k] * B[k][j]; return C; }
function rotIndex(M) { for (var i = 0; i < 24; i++) { var R = ROTS[i], ok = true; for (var a = 0; a < 3 && ok; a++) for (var b = 0; b < 3; b++) if (R[a][b] !== M[a][b]) { ok = false; break; } if (ok) return i; } return -1; }

/* 体素集合在旋转 R 下（以 2× 坐标的体素中心旋转），平移到包围盒最小角 */
function normSet(vox, R, withColor) {
  var pts = vox.map(function (v) { var p = rot(R, [2 * v.x + 1, 2 * v.y + 1, 2 * v.z + 1]); return { p: p, c: v.c }; });
  var mn = [Infinity, Infinity, Infinity];
  pts.forEach(function (q) { for (var a = 0; a < 3; a++) mn[a] = Math.min(mn[a], q.p[a]); });
  var s = new Set();
  pts.forEach(function (q) { s.add(((q.p[0] - mn[0]) / 2) + "," + ((q.p[1] - mn[1]) / 2) + "," + ((q.p[2] - mn[2]) / 2) + (withColor ? "|" + q.c : "")); });
  return s;
}
function iou(a, b) { var inter = 0; a.forEach(function (k) { if (b.has(k)) inter++; }); return inter / (a.size + b.size - inter); }

/* ---------- 切块：多切 20% 的小块（带最长边上限）→ 两两合并回 n 块（合并也守上限） ---------- */
function cut(model, n, seed, capOverride) {
  var R = rng(seed), list = model.list, map = model.map;
  var cap = capOverride || (n <= 25 ? 10 : 8);  // 最长边上限（体素）：25 档 10 × 1.2 = 12 cm，50 档 8 × 1.2 = 9.6 cm
  var m = Math.round(n * 1.2);
  var seeds = [list[Math.floor(R() * list.length)]];
  var dist = list.map(function () { return Infinity; });
  while (seeds.length < m) {
    var s = seeds[seeds.length - 1], best = -1, bi = 0;
    for (var i = 0; i < list.length; i++) {
      var v = list[i], d = (v.x - s.x) * (v.x - s.x) + (v.y - s.y) * (v.y - s.y) * 2.0 + (v.z - s.z) * (v.z - s.z);
      if (d < dist[i]) dist[i] = d;
      var w = dist[i] * (0.85 + 0.3 * R());
      if (w > best) { best = w; bi = i; }
    }
    seeds.push(list[bi]);
  }
  var owner = new Int32Array(list.length).fill(-1), sizes = [], cands = [], bb = [];
  seeds.forEach(function (s, k) { owner[s.i] = k; sizes.push(1); cands.push([]); bb.push([s.x, s.y, s.z, s.x, s.y, s.z]); });
  function ext(b) { return Math.max(b[3] - b[0], b[4] - b[1], b[5] - b[2]) + 1; }
  function withV(b, v) { return [Math.min(b[0], v.x), Math.min(b[1], v.y), Math.min(b[2], v.z), Math.max(b[3], v.x), Math.max(b[4], v.y), Math.max(b[5], v.z)]; }
  function cheb(v, s) { return Math.max(Math.abs(v.x - s.x), Math.abs(v.y - s.y), Math.abs(v.z - s.z)) + 0.001 * (Math.abs(v.x - s.x) + Math.abs(v.y - s.y) + Math.abs(v.z - s.z)); }
  function nbs(v) { var out = []; for (var j = 0; j < 6; j++) { var q = map.get(key(v.x + N6[j][0], v.y + N6[j][1], v.z + N6[j][2])); if (q) out.push(q); } return out; }
  seeds.forEach(function (s, k) { nbs(s).forEach(function (q) { if (owner[q.i] < 0) cands[k].push(q); }); });
  var left = list.length - m;
  while (left > 0) {
    var pick = -1;
    for (var k = 0; k < m; k++) {
      cands[k] = cands[k].filter(function (q) { return owner[q.i] < 0; });
      if (cands[k].length && (pick < 0 || sizes[k] < sizes[pick])) pick = k;
    }
    if (pick < 0) break;
    var c = cands[pick], bj = -1, bd = Infinity;
    for (var i2 = 0; i2 < c.length; i2++) {
      if (ext(withV(bb[pick], c[i2])) > cap) continue;
      var d2 = cheb(c[i2], seeds[pick]); if (d2 < bd) { bd = d2; bj = i2; }
    }
    if (bj < 0) { cands[pick] = []; continue; }
    var nb = c[bj];
    owner[nb.i] = pick; sizes[pick]++; bb[pick] = withV(bb[pick], nb); left--;
    nbs(nb).forEach(function (q) { if (owner[q.i] < 0) cands[pick].push(q); });
  }
  /* 被上限挡下的体素：各自连成新的小块（之后参与合并） */
  list.forEach(function (v) {
    if (owner[v.i] >= 0) return;
    var k = sizes.length; sizes.push(0); bb.push([v.x, v.y, v.z, v.x, v.y, v.z]);
    var stack = [v]; owner[v.i] = k;
    while (stack.length) {
      var cur = stack.pop(); sizes[k]++; bb[k] = withV(bb[k], cur);
      nbs(cur).forEach(function (q) { if (owner[q.i] < 0 && ext(withV(bb[k], q)) <= cap) { owner[q.i] = k; stack.push(q); } });
    }
  });
  /* 合并：每次挑最小的块，并进一个相邻、合并后仍守上限、最小的块，直到剩 n 块 */
  var alive = sizes.map(function () { return true; }), count = sizes.length;
  function adj(k) { var set = new Set(); list.forEach(function (v) { if (owner[v.i] !== k) return; nbs(v).forEach(function (q) { if (owner[q.i] !== k) set.add(owner[q.i]); }); }); return Array.from(set); }
  function merged(a, b) { var x = bb[a], y = bb[b]; return [Math.min(x[0], y[0]), Math.min(x[1], y[1]), Math.min(x[2], y[2]), Math.max(x[3], y[3]), Math.max(x[4], y[4]), Math.max(x[5], y[5])]; }
  var guard = 0;
  while (count > n && guard++ < 5000) {
    var order = sizes.map(function (sz, k) { return k; }).filter(function (k) { return alive[k]; }).sort(function (a, b) { return sizes[a] - sizes[b]; });
    var done = false;
    for (var oi = 0; oi < order.length && !done; oi++) {
      var a = order[oi], opts = adj(a).filter(function (b) { return ext(merged(a, b)) <= cap; });
      if (!opts.length) continue;
      opts.sort(function (p, q) { return sizes[p] - sizes[q]; });
      var b = opts[0];
      list.forEach(function (v) { if (owner[v.i] === a) owner[v.i] = b; });
      sizes[b] += sizes[a]; bb[b] = merged(a, b); alive[a] = false; count--; done = true;
    }
    if (!done) break;
  }
  var ids = [], map2 = {};
  alive.forEach(function (ok, k) { if (ok) { map2[k] = ids.length; ids.push(k); } });
  var pieces = ids.map(function (_, i) { return { idx: i, vox: [] }; });
  list.forEach(function (v) { pieces[map2[owner[v.i]]].vox.push(v); });
  pieces.cap = cap;
  return pieces;
}

function analyse(model, pieces, seed) {
  var R = rng(seed * 7 + 3), set = new Set();
  pieces.forEach(function (p) {
    var mn = [Infinity, Infinity, Infinity], mx = [-Infinity, -Infinity, -Infinity], cs = [0, 0, 0];
    p.vox.forEach(function (v) { var a = [v.x, v.y, v.z]; for (var i = 0; i < 3; i++) { mn[i] = Math.min(mn[i], a[i]); mx[i] = Math.max(mx[i], a[i] + 1); cs[i] += a[i] + .5; } });
    p.min = mn; p.max = mx; p.home = [(mn[0] + mx[0]) / 2, (mn[1] + mx[1]) / 2, (mn[2] + mx[2]) / 2];
    p.ext = [mx[0] - mn[0], mx[1] - mn[1], mx[2] - mn[2]];
    p.centroid = cs.map(function (c) { return c / p.vox.length; });
    var base = normSet(p.vox, ROTS[0], true), shape0 = normSet(p.vox, ROTS[0], false);
    p.allowed = []; p.shapeFits = 0; p.nearFits = 0;
    ROTS.forEach(function (M, i) {
      if (normSet(p.vox, M, true).size === base.size && iou(normSet(p.vox, M, true), base) === 1) p.allowed.push(i);
      var sh = normSet(p.vox, M, false), o = iou(sh, shape0);
      if (o === 1) p.shapeFits++;
      if (i !== 0 && o >= 0.6) p.nearFits++;
    });
    var bad = ROTS.map(function (_, i) { return i; }).filter(function (i) { return p.allowed.indexOf(i) < 0; });
    var tipped = bad.filter(function (i) { return ROTS[i][1][1] !== 1; });
    var wantTip = R() < 0.72;
    var pool = wantTip && tipped.length ? tipped : bad.filter(function (i) { return ROTS[i][1][1] === 1; });
    if (!pool.length) pool = bad;
    if (!pool.length) pool = [0];
    /* 散落朝向优先「矮」的（高度最小），放得进看台 */
    var hOf = function (i) { var e = rot(ROTS[i], p.ext); return Math.abs(e[1]) * 10 + Math.abs(e[2]); };
    var hmin = Math.min.apply(null, pool.map(hOf));
    var low = pool.filter(function (i) { return hOf(i) <= hmin + 10; });
    p.scatterRot = low[Math.floor(R() * low.length)];
    var se = rot(ROTS[p.scatterRot], p.ext); p.scatterExt = [Math.abs(se[0]), Math.abs(se[1]), Math.abs(se[2])];
    p.tipped = ROTS[p.scatterRot][1][1] !== 1;
    p.vox.forEach(function (v) { set.add(key(v.x, v.y, v.z)); });
  });
  var owner = new Map();
  pieces.forEach(function (p) { p.vox.forEach(function (v) { owner.set(key(v.x, v.y, v.z), p.idx); }); });
  pieces.forEach(function (p) {
    var nb = new Set(), ground = false;
    p.vox.forEach(function (v) {
      if (v.y === 0) ground = true;
      N6.forEach(function (d) { var o = owner.get(key(v.x + d[0], v.y + d[1], v.z + d[2])); if (o !== undefined && o !== p.idx) nb.add(o); });
    });
    p.ground = ground; p.neighbors = Array.from(nb);
  });
  /* 拼装顺序：每次挑「已有支撑」里重心最低的 */
  var placed = new Set(), order = [];
  while (order.length < pieces.length) {
    var cand = pieces.filter(function (p) { return !placed.has(p.idx) && (p.ground || p.neighbors.some(function (o) { return placed.has(o); })); });
    cand.sort(function (a, b) { return a.centroid[1] - b.centroid[1]; });
    placed.add(cand[0].idx); order.push(cand[0].idx);
  }
  var sizes = pieces.map(function (p) { return p.vox.length; });
  var mean = sizes.reduce(function (a, b) { return a + b; }, 0) / sizes.length;
  var stats = {
    pieces: pieces.length, voxels: model.list.length,
    minSize: Math.min.apply(null, sizes), maxSize: Math.max.apply(null, sizes), mean: mean,
    withinBand: sizes.filter(function (s) { return s >= mean * 0.6 && s <= mean * 1.4; }).length,
    tipped: pieces.filter(function (p) { return p.tipped; }).length,
    wrongAtStart: pieces.filter(function (p) { return p.allowed.indexOf(p.scatterRot) < 0; }).length,
    nearAmbig: pieces.filter(function (p) { return p.nearFits >= 1; }).length,
    exactAmbig: pieces.filter(function (p) { return p.shapeFits >= 2; }).length,
    slabs: pieces.filter(function (p) { return Math.min.apply(null, p.ext) < 2; }).length,
    maxEdge: Math.max.apply(null, pieces.map(function (p) { return Math.max.apply(null, p.ext); })), cap: pieces.cap
  };
  return { order: order, stats: stats };
}


/* ---------- 两翼看台排货架：左右各前后两层，每层 25 × 30 体素（x × y），块按散落朝向的包围盒排，间隔 1 体素 ---------- */
function pack(pieces) {
  var W = 25, H = 30, bins = [0, 1, 2, 3].map(function (b) { return { b: b, shelves: [], area: 0 }; });
  var order = pieces.slice().sort(function (a, b) { return b.scatterExt[1] - a.scatterExt[1] || b.scatterExt[0] - a.scatterExt[0]; });
  var failed = 0;
  order.forEach(function (p) {
    var w = p.scatterExt[0] + 1, h = p.scatterExt[1] + 1, placed = false;
    var tryBins = bins.slice().sort(function (a, b) { return a.area - b.area; });
    for (var t = 0; t < tryBins.length && !placed; t++) {
      var bin = tryBins[t];
      for (var k = 0; k < bin.shelves.length && !placed; k++) {
        var sh = bin.shelves[k];
        if (sh.x + w <= W + 1 && h <= sh.h) { p.slot = { bin: bin.b, x: sh.x, y: sh.y, w: w - 1, h: h - 1 }; sh.x += w; placed = true; }
      }
      if (!placed) {
        var top = bin.shelves.length ? bin.shelves[bin.shelves.length - 1].y + bin.shelves[bin.shelves.length - 1].h : 0;
        if (top + h <= H + 1 && w <= W + 1) { bin.shelves.push({ y: top, h: h, x: w }); p.slot = { bin: bin.b, x: 0, y: top, w: w - 1, h: h - 1 }; placed = true; }
      }
      if (placed) bin.area += w * h;
    }
    if (!placed) { failed++; p.slot = null; }
  });
  return failed;
}

function makePuzzle(id, n, seedHint) {
  var model = buildModel(id), best = null;
  for (var t = 0; t < 40; t++) {
    var seed = (seedHint || 1) + t * 101, pieces = cut(model, n, seed), a = analyse(model, pieces, seed);
    pack(pieces); var s = a.stats; s.packFail = pieces.filter(function (p) { return !p.slot; }).length; var ok = s.packFail === 0 && s.pieces === n && s.minSize >= 8 && s.maxEdge <= s.cap && s.tipped >= 0.6 * n && s.nearAmbig >= 0.3 * n && s.wrongAtStart === n;
    var score = s.nearAmbig + s.withinBand - 3 * s.slabs;
    if (!best || (ok && !best.ok) || (ok === best.ok && score > best.score)) best = { model: model, pieces: pieces, order: a.order, stats: s, seed: seed, ok: ok, score: score };
    if (ok && s.slabs === 0) break;
  }
  return best;
}

var api = { _cut: cut, _analyse: analyse, pack: pack, MODELS: MODELS, ROTS: ROTS, rot: rot, mul: mul, rotIndex: rotIndex, buildModel: buildModel, makePuzzle: makePuzzle, rng: rng, key: key };
if (typeof module !== "undefined" && module.exports) module.exports = api; else root.VJCore = api;
})(this);
