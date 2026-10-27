package io.embodit.voxeljigsaw.domain

import java.io.File
import kotlin.math.abs
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PuzzleRulesTest {
    private val assets = File("src/main/assets/puzzles")

    private fun puzzle(key: String) = Puzzle.parse(File(assets, "$key.json").readText())

    private val keys = Landmarks.IDS.flatMap { id -> Landmarks.COUNTS.map { "$id-$it" } }

    @Test
    fun rotationTableMatchesTheBake() {
        val baked = JSONArray(File(assets, "rots.json").readText())
        assertEquals(24, baked.length())
        for (i in 0 until 24) {
            val m = baked.getJSONArray(i)
            val flat = IntArray(9) { m.getJSONArray(it / 3).getInt(it % 3) }
            assertTrue("rot $i", flat.contentEquals(Rot24.ALL[i]))
        }
    }

    @Test
    fun quaternionsAgreeWithMatrices() {
        val axes = listOf(Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f), Vec3(0f, 0f, 1f), Vec3(0.3f, -0.7f, 0.2f))
        for (r in 0 until 24) for (v in axes) {
            val a = Rot24.quat(r).rotate(v)
            val b = Rot24.apply(r, v)
            assertTrue("rot $r", a.distanceTo(b) < 1e-4f)
            assertEquals(r, Rot24.nearest(Rot24.quat(r)))
        }
    }

    @Test
    fun turnKeysReachEveryOrientationWithinFivePresses() {
        var worst = 0
        for (a in 0 until 24) for (b in 0 until 24) {
            val d = Rot24.keyDistance(a, b)
            assertTrue("$a -> $b unreachable", d >= 0)
            worst = maxOf(worst, d)
        }
        assertTrue("worst $worst", worst <= 5)
    }

    @Test
    fun judgementIsTheSameAtAllFourTurntableStops() {
        val p = puzzle("qinian-25")
        for (piece in p.pieces) {
            val wrong = (0 until 24).first { it !in piece.allowed }
            for (k in 0 until 4) {
                val good = Rot24.toVolume(piece.allowed.first(), k)
                assertTrue(Rot24.toTable(good, k) in piece.allowed)
                assertFalse(Rot24.toTable(Rot24.toVolume(wrong, k), k) in piece.allowed)
                // a world-space position over the home, seen from the turned table
                val world = Rot24.apply(Rot24.yaw(k), p.homeM(piece.idx))
                val local = Rot24.apply(Rot24.yaw(-k), world)
                val r = Rules.judge(p, piece.idx, local, Rot24.toTable(good, k), emptySet())
                assertTrue("piece ${piece.idx} yaw $k: $r", r is Release.Snap)
            }
        }
    }

    @Test
    fun releaseOutcomes() {
        val p = puzzle("bigben-25")
        val piece = p.pieces[3]
        val home = p.homeM(3)
        val ok = piece.allowed.first()
        val bad = (0 until 24).first { it !in piece.allowed && Rules.fitRots(p, 3, 3).none { r -> r == it } }
        assertEquals(Release.Snap(3, ok), Rules.judge(p, 3, home + Vec3(0.01f, 0f, 0.01f), ok, emptySet()))
        assertEquals(Release.WrongWay(3), Rules.judge(p, 3, home, bad, emptySet()))
        assertEquals(Release.Park, Rules.judge(p, 3, home + Vec3(0.05f, 0f, 0f), ok, emptySet()))
        assertEquals(Release.Away, Rules.judge(p, 3, Vec3(0.4f, 0.3f, 0.2f), ok, emptySet()))
        // a placed home is no target any more
        assertTrue(Rules.judge(p, 3, home, ok, setOf(3)) !is Release.Snap)
    }

    @Test
    fun easyModeSnapsAnyOrientationNearTheHome() {
        val p = puzzle("qinian-25")
        val piece = p.pieces[2]
        val home = p.homeM(2)
        val wrong = piece.scatterRot
        assertEquals(Release.WrongWay(2), Rules.judge(p, 2, home, wrong, emptySet()))
        assertEquals(Release.Snap(2, piece.allowed.first()), Rules.judge(p, 2, home + Vec3(0.03f, 0f, 0f), wrong, emptySet(), easy = true))
        assertTrue(Rules.judge(p, 2, home + Vec3(0.06f, 0f, 0f), wrong, emptySet(), easy = true) is Release.Park)
    }

    @Test
    fun keyPathLeadsIntoTheAllowedSet() {
        for (key in keys) {
            val p = puzzle(key)
            for (piece in p.pieces) for (k in 0 until 4) {
                val from = Rot24.toVolume(piece.scatterRot, k)
                val targets = piece.allowed.map { Rot24.toVolume(it, k) }.toSet()
                val path = Rot24.keyPath(from, targets)
                assertTrue(path.size in 1..5)
                var r = from
                for (tip in path) r = Rot24.compose(if (tip) Rot24.TIP_X else Rot24.TURN_Y, r)
                assertTrue("$key ${piece.idx}", r in targets)
            }
        }
    }

    @Test
    fun parkedPiecesClearTheLandmark() {
        for (key in keys) {
            val p = puzzle(key)
            for (piece in p.pieces) {
                val rot = piece.scatterRot
                val at = Rules.parkPosition(p, piece.idx, p.homeM(piece.idx), rot)
                val half = Rot24.extent(rot, piece.extent) * (p.voxelM / 2f)
                val y0 = ((at.y - half.y) / p.voxelM).toInt()
                val y1 = ((at.y + half.y) / p.voxelM).toInt() + 1
                val radial = kotlin.math.sqrt(at.x * at.x + at.z * at.z)
                val inner = radial - (abs(at.x) / radial * half.x + abs(at.z) / radial * half.z)
                assertTrue("$key piece ${piece.idx}", inner >= p.envelopeM(y0, y1) + Rules.PARK_GAP_M - 1e-4f)
                assertTrue(at.y - half.y >= 0f)
            }
        }
    }

    @Test
    fun bakedSetsPassTheAmbiguityGates() {
        for (key in keys) {
            val p = puzzle(key)
            assertEquals(p.n, p.pieces.size)
            assertTrue(key, p.pieces.all { it.scatterRot !in it.allowed })
            assertTrue(key, p.pieces.count { it.tipped } >= 0.6 * p.n)
            assertEquals(p.n, p.order.distinct().size)
            assertTrue(p.segCount.all { it > 0 })
            val cap = if (p.n == 25) 10f else 8f
            assertTrue(p.pieces.all { maxOf(it.extent.x, it.extent.y, it.extent.z) <= cap })
        }
    }

    @Test
    fun hintsAndSegments() {
        val p = puzzle("qinian-25")
        assertEquals(p.order[0], Rules.nextHint(p, emptySet()))
        assertEquals(p.order[1], Rules.nextHint(p, setOf(p.order[0])))
        val seg0 = p.piecesOfSegment(0)
        val before = seg0.dropLast(1).toSet()
        assertEquals(null, Rules.segmentDone(p, seg0.first(), before))
        assertEquals(0, Rules.segmentDone(p, seg0.last(), before + seg0.last()))
    }

    @Test
    fun meshesAreClosedAndWithinBudget() {
        val cube = Mesher.mesh(listOf(Voxel(0, 0, 0, 0)), Vec3(0.5f, 0.5f, 0.5f), 1f, 1)
        assertEquals(12, cube.triangleCount)
        assertTrue(cube.positions.all { abs(it) == 0.5f })
        for (key in keys) {
            val p = puzzle(key)
            var total = 0
            for (piece in p.pieces) {
                val m = Mesher.mesh(piece.voxels, piece.home, p.voxelM, p.palette.size)
                assertTrue("$key ${piece.idx} tris ${m.triangleCount}", m.triangleCount in 12..800)
                total += m.triangleCount
            }
            val whole = Mesher.mesh(p.pieces.flatMap { it.voxels }, Vec3(0f, 0f, 0f), p.voxelM, p.palette.size)
            assertTrue("$key whole ${whole.triangleCount}", whole.triangleCount <= 30_000)
            assertTrue(total <= 30_000)
        }
    }

    @Test
    fun saveRoundTripAndCorruption() {
        val p = puzzle("taj-25")
        val loose = p.pieces.drop(3).map { Rest(it.idx, it.wing, it.scatterRot) }
        val s = SaveGame("taj", 25, listOf(0, 1), loose.drop(1), listOf(Rest(loose[0].idx, Vec3(0.1f, 0.02f, 0.05f), 5)) + Rest(2, Vec3(0f, 0.1f, 0.2f), 0), 125, 2, listOf(0), 3)
        val back = SaveGame.parse(s.toJson(), 25)
        assertEquals(s.copy(yawQuarters = 3), back)
        try {
            SaveGame.parse(s.copy(placed = listOf(0)).toJson(), 25)
            fail("missing piece accepted")
        } catch (_: IllegalArgumentException) {
        }
        try {
            SaveGame.parse("{garbage", 25)
            fail("garbage accepted")
        } catch (_: Exception) {
        }
        assertNotNull(back)
    }
}
