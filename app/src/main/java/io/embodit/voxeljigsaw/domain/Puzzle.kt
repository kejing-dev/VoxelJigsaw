package io.embodit.voxeljigsaw.domain

import org.json.JSONArray
import org.json.JSONObject

/** Landmark ids in home order (design content.landmarks). */
object Landmarks {
    val IDS = listOf("qinian", "bigben", "taj")

    /**
     * Piece count of level 1 and level 2 per landmark (headset feedback 2026-10-28: "块数少一点 5-10 块一关，10-20 块第二关");
     * must match assets/puzzles/levels.json written by the bake (checked by a unit test). The Taj needs 10 in level 1: its
     * minarets only join the rest through the plinth.
     */
    val LEVELS = mapOf("qinian" to listOf(8, 16), "bigben" to listOf(8, 16), "taj" to listOf(10, 16))

    fun count(id: String, level: Int): Int = LEVELS.getValue(id)[level.coerceIn(0, 1)]

    fun counts(id: String): List<Int> = LEVELS.getValue(id)

    /** Level (0 or 1) of a puzzle with [n] pieces, or -1 when it is not one of this landmark's levels. */
    fun levelOf(id: String, n: Int): Int = LEVELS[id]?.indexOf(n) ?: -1
}

/** One voxel of a piece: model grid coordinates (x, z centred on 0, y up from 0) and a palette index. */
data class Voxel(val x: Int, val y: Int, val z: Int, val color: Int)

/** Another piece that looks exactly like this one at its home after one of [rots] (turntable space). */
data class Equiv(val idx: Int, val rots: Set<Int>)

/**
 * One piece of a baked puzzle (tools/bake/bake_puzzles.js). Positions are in voxels of the model grid;
 * [home] is the centre of the piece's bounding box (voxel corners), the pivot of its mesh.
 */
data class Piece(
    val idx: Int,
    val home: Vec3,
    val min: Vec3,
    val max: Vec3,
    val voxels: List<Voxel>,
    val allowed: Set<Int>,
    val scatterRot: Int,
    val tipped: Boolean,
    val wing: Vec3,
    val segment: Int,
    val equiv: List<Equiv>,
) {
    val extent: Vec3 get() = max - min
}

/** A landmark cut into n pieces, read from assets/puzzles/<id>-<n>.json. */
data class Puzzle(
    val id: String,
    val n: Int,
    val voxelM: Float,
    val palette: List<Int>,
    val top: Int,
    val radius: Float,
    val layerRadius: List<Float>,
    val order: List<Int>,
    val segCount: List<Int>,
    val pieces: List<Piece>,
) {
    val key: String get() = "$id-$n"

    /** Home of a piece in turntable space (metres, origin on the table top centre). */
    fun homeM(idx: Int): Vec3 = pieces[idx].home * voxelM

    /** Largest radius of the landmark (metres from the turntable axis) over the voxel layers [y0, y1). */
    fun envelopeM(y0: Int, y1: Int): Float {
        var r = 0f
        for (y in y0.coerceAtLeast(0) until y1.coerceAtMost(layerRadius.size)) r = maxOf(r, layerRadius[y])
        return r * voxelM
    }

    fun piecesOfSegment(seg: Int): List<Int> = pieces.filter { it.segment == seg }.map { it.idx }

    companion object {
        fun parse(text: String): Puzzle {
            val o = JSONObject(text)
            fun vec(a: JSONArray) = Vec3(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat())
            fun ints(a: JSONArray) = List(a.length()) { a.getInt(it) }
            val palette = o.getJSONArray("palette").let { a -> List(a.length()) { parseHex(a.getString(it)) } }
            val pieces = o.getJSONArray("pieces").let { arr ->
                List(arr.length()) { i ->
                    val p = arr.getJSONObject(i)
                    val vox = p.getJSONArray("vox").let { va -> List(va.length()) { j -> va.getJSONArray(j).let { v -> Voxel(v.getInt(0), v.getInt(1), v.getInt(2), v.getInt(3)) } } }
                    val eq = p.getJSONArray("equiv").let { ea -> List(ea.length()) { j -> ea.getJSONObject(j).let { e -> Equiv(e.getInt("idx"), ints(e.getJSONArray("rots")).toSet()) } } }
                    Piece(
                        idx = p.getInt("idx"),
                        home = vec(p.getJSONArray("home")),
                        min = vec(p.getJSONArray("min")),
                        max = vec(p.getJSONArray("max")),
                        voxels = vox,
                        allowed = ints(p.getJSONArray("allowed")).toSet(),
                        scatterRot = p.getInt("scatterRot"),
                        tipped = p.getBoolean("tipped"),
                        wing = vec(p.getJSONArray("wing")),
                        segment = p.getInt("seg"),
                        equiv = eq,
                    )
                }
            }
            require(pieces.size == o.getInt("n")) { "piece count ${pieces.size} != n" }
            return Puzzle(
                id = o.getString("id"),
                n = o.getInt("n"),
                voxelM = o.getDouble("voxel").toFloat(),
                palette = palette,
                top = o.getInt("top"),
                radius = o.getDouble("radius").toFloat(),
                layerRadius = o.getJSONArray("layerRadius").let { a -> List(a.length()) { a.getDouble(it).toFloat() } },
                order = ints(o.getJSONArray("order")),
                segCount = ints(o.getJSONArray("segCount")),
                pieces = pieces,
            )
        }

        fun parseHex(s: String): Int = (0xFF shl 24) or s.removePrefix("#").toInt(16)
    }
}
