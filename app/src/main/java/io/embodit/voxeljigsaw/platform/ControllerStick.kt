package io.embodit.voxeljigsaw.platform

import android.os.SystemClock
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import io.embodit.voxeljigsaw.platform.JigLog
import kotlin.math.abs

/**
 * The controllers' thumbstick X axis (factory default D1: the stick turns the held / selected piece about
 * its vertical axis), ported from Furniture Fit. Two sources, the stronger wins:
 *  1. `ControllerTrackingProvider`'s action listener (SDK doc "Obtain controller input actions"). The doc says
 *     it only delivers in Full Space; this app is a Shared Space volume, so `supportState` is expected to read
 *     NOT_IN_FULL_SPACE and this source stays silent until a device shows otherwise.
 *  2. Horizontal scroll events on the volume's view ([feedScroll]): the system's own stick-to-scroll mapping
 *     for windows, if the device sends one (unverified on a device, logged as STICK scroll).
 * The listener runs on the data source thread: it only stores the value; the frame loop reads it.
 */
class ControllerStick {
    private var provider: ControllerTrackingProvider? = null

    @Volatile private var x = 0f
    @Volatile private var atMs = 0L
    @Volatile var actions = 0L
        private set

    /** Debug: a stick value that wins over the controllers until [debugUntilMs] (emulator QA). */
    @Volatile private var debugX = 0f
    @Volatile private var debugUntilMs = 0L

    // the stick pushed further of the two controllers
    private val listener =
        ControllerTrackingProvider.ControllerActionListener { a ->
            val l = a.left.thumbstickValue.x
            val r = a.right.thumbstickValue.x
            x = if (abs(r) >= abs(l)) r else l
            atMs = SystemClock.elapsedRealtime()
            actions++
        }

    fun start() {
        if (provider != null) return
        val p = ControllerTrackingProvider()
        runCatching { p.addControllerActionListener(listener) }.onFailure { JigLog.w("STICK listener: $it") }
        val result = runCatching { p.start() }
        JigLog.i("STICK start=${result.getOrNull() ?: result.exceptionOrNull()} support=${runCatching { p.supportState }.getOrNull()}")
        provider = p
    }

    fun stop() {
        provider?.let { p ->
            runCatching { p.removeControllerActionListener(listener) }
            runCatching { p.stop() }
        }
        provider = null
        x = 0f
        actions = 0L
    }

    /** Thumbstick X in [-1, 1] (right +); 0 without a controller or when the last report is stale. */
    fun x(nowMs: Long): Float {
        if (nowMs < debugUntilMs) return debugX
        val scroll = if (nowMs < scrollUntilMs) scrollX else 0f
        val p = provider ?: return scroll
        if (runCatching { p.supportState }.getOrNull() != DataProvider.SupportState.SUPPORTED) return scroll
        if (nowMs - atMs > STALE_MS) return scroll
        val c = x.coerceIn(-1f, 1f)
        return if (abs(c) >= abs(scroll)) c else scroll
    }

    @Volatile private var scrollX = 0f
    @Volatile private var scrollUntilMs = 0L
    @Volatile var scrolls = 0L
        private set

    /** A horizontal scroll step on the volume (stick tilted); held briefly so a stream of steps reads as a steady push. */
    fun feedScroll(dx: Float) {
        if (dx == 0f) return
        scrollX = (dx * SCROLL_GAIN).coerceIn(-1f, 1f)
        scrollUntilMs = SystemClock.elapsedRealtime() + SCROLL_HOLD_MS
        if (scrolls++ % 20 == 0L) JigLog.i("STICK scroll dx=$dx")
    }

    /** Debug `stick`: holds [value] for [ms]. */
    fun debugHold(value: Float, ms: Long) {
        debugX = value.coerceIn(-1f, 1f)
        debugUntilMs = SystemClock.elapsedRealtime() + ms
    }

    fun status(): String = "support=${runCatching { provider?.supportState }.getOrNull()} actions=$actions x=${"%.2f".format(x)} scrolls=$scrolls"

    private companion object {
        const val STALE_MS = 500L
        const val SCROLL_HOLD_MS = 160L
        const val SCROLL_GAIN = 1f
    }
}
