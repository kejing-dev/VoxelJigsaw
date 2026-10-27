package io.embodit.voxeljigsaw.domain

/**
 * Greedy voxel mesher (design budget: outer faces merged per colour). Faces between two voxels of the set
 * are dropped; coplanar faces of one colour merge into rectangles. Output is plain arrays so it runs on the JVM;
 * the scene turns it into an SDK MeshModel. UV u points at the centre of the colour's texel in a 1-row palette
 * texture of [paletteSize] texels (v = 0.5), so filtering can never bleed between colours.
 */
class MeshData(
    val positions: FloatArray,
    val normals: FloatArray,
    val uvs: FloatArray,
    val indices: IntArray,
) {
    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = indices.size / 3
}

object Mesher {
    /**
     * Meshes [voxels] in metres: a voxel (x, y, z) spans [x, x+1] × [y, y+1] × [z, z+1] voxels, shifted by
     * −[pivot] (voxels) and scaled by [voxelM].
     */
    fun mesh(voxels: List<Voxel>, pivot: Vec3, voxelM: Float, paletteSize: Int): MeshData {
        val minX = voxels.minOf { it.x }
        val minY = voxels.minOf { it.y }
        val minZ = voxels.minOf { it.z }
        val sx = voxels.maxOf { it.x } - minX + 1
        val sy = voxels.maxOf { it.y } - minY + 1
        val sz = voxels.maxOf { it.z } - minZ + 1
        val dims = intArrayOf(sx, sy, sz)
        val grid = IntArray(sx * sy * sz) { -1 }
        fun at(c: IntArray): Int {
            if (c[0] < 0 || c[1] < 0 || c[2] < 0 || c[0] >= sx || c[1] >= sy || c[2] >= sz) return -1
            return grid[c[0] + sx * (c[1] + sy * c[2])]
        }
        for (v in voxels) grid[(v.x - minX) + sx * ((v.y - minY) + sy * (v.z - minZ))] = v.color

        val pos = ArrayList<Float>()
        val nor = ArrayList<Float>()
        val uv = ArrayList<Float>()
        val idx = ArrayList<Int>()
        val origin = floatArrayOf(minX - pivot.x, minY - pivot.y, minZ - pivot.z)

        for (d in 0 until 3) {
            val u = (d + 1) % 3
            val w = (d + 2) % 3
            for (sign in intArrayOf(1, -1)) {
                val mask = IntArray(dims[u] * dims[w])
                for (slice in 0 until dims[d]) {
                    // colour of each face of this slice that looks out along sign·d, -1 where none
                    for (j in 0 until dims[w]) for (i in 0 until dims[u]) {
                        val c = IntArray(3)
                        c[d] = slice; c[u] = i; c[w] = j
                        val here = at(c)
                        c[d] = slice + sign
                        mask[i + j * dims[u]] = if (here >= 0 && at(c) < 0) here else -1
                    }
                    var j = 0
                    while (j < dims[w]) {
                        var i = 0
                        while (i < dims[u]) {
                            val color = mask[i + j * dims[u]]
                            if (color < 0) {
                                i++
                                continue
                            }
                            var width = 1
                            while (i + width < dims[u] && mask[i + width + j * dims[u]] == color) width++
                            var height = 1
                            grow@ while (j + height < dims[w]) {
                                for (k in 0 until width) if (mask[i + k + (j + height) * dims[u]] != color) break@grow
                                height++
                            }
                            for (y in 0 until height) for (x in 0 until width) mask[i + x + (j + y) * dims[u]] = -1
                            quad(d, u, w, sign, slice, i, j, width, height, color, origin, voxelM, paletteSize, pos, nor, uv, idx)
                            i += width
                        }
                        j++
                    }
                }
            }
        }
        return MeshData(pos.toFloatArray(), nor.toFloatArray(), uv.toFloatArray(), idx.toIntArray())
    }

    private fun quad(
        d: Int, u: Int, w: Int, sign: Int, slice: Int, i: Int, j: Int, width: Int, height: Int, color: Int,
        origin: FloatArray, voxelM: Float, paletteSize: Int,
        pos: ArrayList<Float>, nor: ArrayList<Float>, uv: ArrayList<Float>, idx: ArrayList<Int>,
    ) {
        val plane = (slice + if (sign > 0) 1 else 0).toFloat()
        val base = pos.size / 3
        val corners = arrayOf(floatArrayOf(i.toFloat(), j.toFloat()), floatArrayOf((i + width).toFloat(), j.toFloat()), floatArrayOf((i + width).toFloat(), (j + height).toFloat()), floatArrayOf(i.toFloat(), (j + height).toFloat()))
        val texU = (color + 0.5f) / paletteSize
        for (c in corners) {
            val p = FloatArray(3)
            p[d] = plane; p[u] = c[0]; p[w] = c[1]
            for (a in 0 until 3) pos += (p[a] + origin[a]) * voxelM
            val n = FloatArray(3)
            n[d] = sign.toFloat()
            for (a in 0 until 3) nor += n[a]
            uv += texU
            uv += 0.5f
        }
        // (u, w, d) is right-handed, so corners 0-1-2-3 run counter-clockwise seen from +d
        if (sign > 0) {
            idx += base; idx += base + 1; idx += base + 2
            idx += base; idx += base + 2; idx += base + 3
        } else {
            idx += base; idx += base + 2; idx += base + 1
            idx += base; idx += base + 3; idx += base + 2
        }
    }
}
