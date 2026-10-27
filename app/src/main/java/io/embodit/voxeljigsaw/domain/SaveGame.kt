package io.embodit.voxeljigsaw.domain

import org.json.JSONArray
import org.json.JSONObject

/** A loose piece (volume space) or a parked one (turntable space) at rest: position in metres, orientation index. */
data class Rest(val idx: Int, val pos: Vec3, val rot: Int)

/** One saved puzzle (design dataModel.save): saves/<landmark>-<n>.json. */
data class SaveGame(
    val landmark: String,
    val n: Int,
    val placed: List<Int>,
    val loose: List<Rest>,
    val parked: List<Rest>,
    val elapsedSec: Int,
    val hints: Int,
    val factsSeen: List<Int>,
    val yawQuarters: Int,
) {
    fun toJson(): String =
        JSONObject().apply {
            put("v", 1)
            put("landmark", landmark)
            put("n", n)
            put("placed", JSONArray(placed))
            put("loose", rests(loose))
            put("parked", rests(parked))
            put("elapsedSec", elapsedSec)
            put("hints", hints)
            put("factsSeen", JSONArray(factsSeen))
            put("turntableYaw", ((yawQuarters % 4) + 4) % 4 * 90)
        }.toString()

    companion object {
        private fun rests(list: List<Rest>) = JSONArray().apply {
            list.forEach { r -> put(JSONObject().put("idx", r.idx).put("pos", JSONArray(listOf(r.pos.x.toDouble(), r.pos.y.toDouble(), r.pos.z.toDouble()))).put("rotIdx", r.rot)) }
        }

        private fun parseRests(a: JSONArray, n: Int): List<Rest> = List(a.length()) { i ->
            val o = a.getJSONObject(i)
            val p = o.getJSONArray("pos")
            val r = Rest(o.getInt("idx"), Vec3(p.getDouble(0).toFloat(), p.getDouble(1).toFloat(), p.getDouble(2).toFloat()), o.getInt("rotIdx"))
            require(r.idx in 0 until n && r.rot in 0 until 24 && r.pos.isFinite()) { "bad rest $i" }
            r
        }

        /** Parses and checks a save against its puzzle size; throws on anything inconsistent (design S18). */
        fun parse(text: String, n: Int): SaveGame {
            val o = JSONObject(text)
            require(o.getInt("n") == n) { "piece count" }
            val placed = o.getJSONArray("placed").let { a -> List(a.length()) { a.getInt(it) } }
            val loose = parseRests(o.getJSONArray("loose"), n)
            val parked = parseRests(o.getJSONArray("parked"), n)
            val all = placed + loose.map { it.idx } + parked.map { it.idx }
            require(all.size == n && all.toSet().size == n && placed.all { it in 0 until n }) { "pieces do not add up" }
            val yaw = o.getInt("turntableYaw")
            require(yaw % 90 == 0) { "yaw" }
            return SaveGame(
                landmark = o.getString("landmark"),
                n = n,
                placed = placed,
                loose = loose,
                parked = parked,
                elapsedSec = o.getInt("elapsedSec"),
                hints = o.getInt("hints"),
                factsSeen = o.getJSONArray("factsSeen").let { a -> List(a.length()) { a.getInt(it) } },
                yawQuarters = yaw / 90,
            )
        }
    }
}

/** Per landmark and count: finished, best time (design dataModel.records). */
data class Record(val completed: Boolean, val bestSec: Int, val hintsAtBest: Int)
