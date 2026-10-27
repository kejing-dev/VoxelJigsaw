package io.embodit.voxeljigsaw.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** What happens to a piece let go of (design spatialLayout.release, rules.wrongOrientation / elsewhere). */
sealed interface Release {
    /** Snaps into [slot] (its own home or an identical piece's home) with turntable orientation [rot]. */
    data class Snap(val slot: Int, val rot: Int) : Release

    /** Within 2.5 cm of an empty right place but turned the wrong way: shake, message, then park. */
    data class WrongWay(val slot: Int) : Release

    /** Near the landmark (≤ 8 cm from any empty place): parked beside it, no message. */
    data object Park : Release

    /** Away from the landmark: stays where dropped if that is a free wing spot, otherwise flies home. */
    data object Away : Release
}

object Rules {
    const val SNAP_M = 0.025f

    /** Easy mode (user feedback 2026-10-23, "too hard"): a wider snap, and the piece turns itself the right way. */
    const val EASY_SNAP_M = 0.04f
    const val NEAR_M = 0.08f
    const val PARK_GAP_M = 0.01f

    /** Orientations under which piece [idx] fits [slot]: its own allowed set, or the rotations onto an identical piece. */
    fun fitRots(p: Puzzle, idx: Int, slot: Int): Set<Int> =
        if (slot == idx) p.pieces[idx].allowed else p.pieces[idx].equiv.firstOrNull { it.idx == slot }?.rots ?: emptySet()

    /** Empty places piece [idx] could go: its own and those of identical pieces not placed yet. */
    fun candidateSlots(p: Puzzle, idx: Int, placed: Set<Int>): List<Int> =
        (listOf(idx) + p.pieces[idx].equiv.map { it.idx }).filter { it !in placed }

    /**
     * Judges a release in turntable space: [pos] metres from the table-top centre, [rot] the snapped
     * orientation (Rot24 index, turntable space). [placed] are the pieces already in.
     */
    fun judge(p: Puzzle, idx: Int, pos: Vec3, rot: Int, placed: Set<Int>, easy: Boolean = false): Release {
        val slots = candidateSlots(p, idx, placed)
        if (easy) {
            slots.map { it to pos.distanceTo(p.homeM(it)) }.filter { it.second <= EASY_SNAP_M }.minByOrNull { it.second }?.let { (slot, _) ->
                val fits = fitRots(p, idx, slot)
                return Release.Snap(slot, if (rot in fits) rot else fits.first())
            }
        }
        val near = slots.map { it to pos.distanceTo(p.homeM(it)) }.filter { it.second <= SNAP_M }.sortedBy { it.second }
        near.firstOrNull { rot in fitRots(p, idx, it.first) }?.let { return Release.Snap(it.first, rot) }
        near.firstOrNull()?.let { return Release.WrongWay(it.first) }
        val anyEmpty = p.pieces.indices.filter { it !in placed && it != idx }.plus(idx)
        if (anyEmpty.any { pos.distanceTo(p.homeM(it)) <= NEAR_M }) return Release.Park
        return Release.Away
    }

    /**
     * Where a parked piece rests (turntable space): pushed out along the radius through [pos] until its box,
     * turned by [rot], clears the landmark's outline at its height by 1 cm, never below the table top.
     */
    fun parkPosition(p: Puzzle, idx: Int, pos: Vec3, rot: Int): Vec3 {
        val half = Rot24.extent(rot, p.pieces[idx].extent) * (p.voxelM / 2f)
        val y = max(pos.y, half.y + 0.002f)
        val y0 = ((y - half.y) / p.voxelM).toInt()
        val y1 = ((y + half.y) / p.voxelM).toInt() + 1
        val env = p.envelopeM(y0, y1)
        var dx = pos.x
        var dz = pos.z
        val len = sqrt(dx * dx + dz * dz)
        if (len < 1e-4f) {
            dx = 0f
            dz = 1f
        } else {
            dx /= len
            dz /= len
        }
        // distance from the box centre to its far corner along this direction
        val reach = abs(dx) * half.x + abs(dz) * half.z
        val r = max(len, env + reach + PARK_GAP_M)
        return Vec3(dx * r, y, dz * r)
    }

    /** Index of the next piece for a hint: first in assembly order not placed and not [except]. */
    fun nextHint(p: Puzzle, placed: Set<Int>, except: Int? = null): Int? = p.order.firstOrNull { it !in placed && it != except }

    /** Segments finished by placing [idx] given the pieces placed before it. */
    fun segmentDone(p: Puzzle, idx: Int, placedAfter: Set<Int>): Int? {
        val seg = p.pieces[idx].segment
        return if (p.piecesOfSegment(seg).all { it in placedAfter }) seg else null
    }
}
