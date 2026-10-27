package io.embodit.voxeljigsaw.platform

import android.util.Log
import io.embodit.voxeljigsaw.domain.Vec3
import java.util.Locale

/**
 * One line per event under the tag `VoxelJigsaw`, with stable prefixes the QA scripts grep for:
 * `STATE`, `LOAD`, `GRAB`, `RELEASE`, `SNAP`, `PARK`, `TURN`, `STICK`, `TABLE`, `SAVE`, `CMD`, `DUMP`, `BOUNDS`.
 */
object JigLog {
    const val TAG = "VoxelJigsaw"

    fun i(line: String) {
        Log.i(TAG, line)
    }

    fun w(line: String, error: Throwable? = null) {
        Log.w(TAG, line, error)
    }

    fun e(line: String, error: Throwable? = null) {
        Log.e(TAG, line, error)
    }

    fun pos(v: Vec3?): String = if (v == null) "null" else String.format(Locale.US, "(%.3f, %.3f, %.3f)", v.x, v.y, v.z)
}
