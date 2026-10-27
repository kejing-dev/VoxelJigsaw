package io.embodit.voxeljigsaw.domain

import kotlin.math.abs

/**
 * The 24 axis-aligned rotations, in the exact order of the bake (vj-core.js `ROTS`): permutations
 * [0,1,2] [0,2,1] [1,0,2] [1,2,0] [2,0,1] [2,1,0], sign bits 0..7, keeping det = +1. Index 0 is identity.
 * A matrix is stored row-major as 9 ints; `apply(m, v)` = m · v. Piece orientations, allowed sets and the
 * scatter orientation in the puzzle files are indices into this table.
 */
object Rot24 {
    val ALL: List<IntArray> = build()

    const val IDENTITY = 0

    private fun build(): List<IntArray> {
        val out = ArrayList<IntArray>(24)
        val perms = arrayOf(intArrayOf(0, 1, 2), intArrayOf(0, 2, 1), intArrayOf(1, 0, 2), intArrayOf(1, 2, 0), intArrayOf(2, 0, 1), intArrayOf(2, 1, 0))
        for (p in perms) {
            for (s in 0 until 8) {
                val m = IntArray(9)
                for (r in 0 until 3) m[r * 3 + p[r]] = if ((s shr r) and 1 == 1) -1 else 1
                if (det(m) == 1) out += m
            }
        }
        return out
    }

    private fun det(m: IntArray): Int =
        m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])

    fun mul(a: IntArray, b: IntArray): IntArray {
        val c = IntArray(9)
        for (i in 0 until 3) for (j in 0 until 3) {
            var sum = 0
            for (k in 0 until 3) sum += a[i * 3 + k] * b[k * 3 + j]
            c[i * 3 + j] = sum
        }
        return c
    }

    fun index(m: IntArray): Int = ALL.indexOfFirst { it.contentEquals(m) }

    /** a ∘ b as indices: first b, then a. */
    fun compose(a: Int, b: Int): Int = index(mul(ALL[a], ALL[b]))

    fun apply(r: Int, v: Vec3): Vec3 {
        val m = ALL[r]
        return Vec3(m[0] * v.x + m[1] * v.y + m[2] * v.z, m[3] * v.x + m[4] * v.y + m[5] * v.z, m[6] * v.x + m[7] * v.y + m[8] * v.z)
    }

    /** Extent of a box with edge lengths [e] after rotation [r] (absolute values). */
    fun extent(r: Int, e: Vec3): Vec3 {
        val v = apply(r, e)
        return Vec3(abs(v.x), abs(v.y), abs(v.z))
    }

    private val QUATS: List<Quat4> = ALL.map { m ->
        // column vectors are the images of the unit axes
        Quat4.fromBasis(Vec3(m[0].toFloat(), m[3].toFloat(), m[6].toFloat()), Vec3(m[1].toFloat(), m[4].toFloat(), m[7].toFloat()), Vec3(m[2].toFloat(), m[5].toFloat(), m[8].toFloat()))
    }

    fun quat(r: Int): Quat4 = QUATS[r]

    /** The axis orientation nearest to [q] (largest |dot|): the "snap to 90°" of a released piece. */
    fun nearest(q: Quat4): Int {
        var best = 0
        var bestDot = -1f
        for (i in 0 until 24) {
            val p = QUATS[i]
            val d = abs(p.x * q.x + p.y * q.y + p.z * q.z + p.w * q.w)
            if (d > bestDot) {
                bestDot = d
                best = i
            }
        }
        return best
    }

    /** Turntable yaw by k quarter turns (counter-clockwise seen from above, i.e. +90° about +Y). */
    fun yaw(k: Int): Int = index(yawMatrix(k))

    private fun yawMatrix(k: Int): IntArray {
        val i = ((k % 4) + 4) % 4
        val c = intArrayOf(1, 0, -1, 0)[i]
        val s = intArrayOf(0, 1, 0, -1)[i]
        return intArrayOf(c, 0, s, 0, 1, 0, -s, 0, c)
    }

    /** Turn key ↻: a quarter turn about the vertical axis, clockwise seen from above (−90° about +Y). */
    val TURN_Y: Int = yaw(-1)

    /** Turn key ⤵: tip forward, the top falls towards the user (+90° about +X: +Y goes to +Z). */
    val TIP_X: Int = index(intArrayOf(1, 0, 0, 0, 0, -1, 0, 1, 0))

    /** Orientation in turntable space of a piece whose volume orientation is [volumeRot], turntable at [yawQuarters]. */
    fun toTable(volumeRot: Int, yawQuarters: Int): Int = compose(yaw(-yawQuarters), volumeRot)

    fun toVolume(tableRot: Int, yawQuarters: Int): Int = compose(yaw(yawQuarters), tableRot)

    /**
     * Shortest sequence of turn keys (false = ↻, true = ⤵; applied in volume space) that takes [from] to any of
     * [targets]; empty when [from] is already one of them. Step hints show it as key symbols.
     */
    fun keyPath(from: Int, targets: Set<Int>): List<Boolean> {
        if (from in targets) return emptyList()
        val prev = IntArray(24) { -1 }
        val via = BooleanArray(24)
        prev[from] = from
        val queue = ArrayDeque<Int>()
        queue += from
        while (queue.isNotEmpty()) {
            val r = queue.removeFirst()
            for (tip in booleanArrayOf(false, true)) {
                val n = compose(if (tip) TIP_X else TURN_Y, r)
                if (prev[n] >= 0) continue
                prev[n] = r
                via[n] = tip
                if (n in targets) {
                    val path = ArrayList<Boolean>()
                    var c = n
                    while (c != from) {
                        path += via[c]
                        c = prev[c]
                    }
                    return path.reversed()
                }
                queue += n
            }
        }
        return emptyList()
    }

    /** Fewest presses of ↻ / ⤵ (applied in volume space, on the left) that take [from] to [to]. */
    fun keyDistance(from: Int, to: Int): Int {
        if (from == to) return 0
        val seen = IntArray(24) { -1 }
        seen[from] = 0
        val queue = ArrayDeque<Int>()
        queue += from
        while (queue.isNotEmpty()) {
            val r = queue.removeFirst()
            for (key in intArrayOf(TURN_Y, TIP_X)) {
                val n = compose(key, r)
                if (seen[n] < 0) {
                    seen[n] = seen[r] + 1
                    if (n == to) return seen[n]
                    queue += n
                }
            }
        }
        return -1
    }
}
