package io.embodit.voxeljigsaw.platform

import android.os.SystemClock
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import kotlin.math.abs

/**
 * The controllers' thumbstick as discrete pushes (profile D18, headset feedback 2026-10-28: "turn tip 分别用 joystick X Y
 * 控制，descrete…按一下 joystick 左就转 90 度"): [steps] reports one step per push on each axis and nothing more until the
 * stick has come back to the middle. Two sources, the stronger wins:
 *  1. `ControllerTrackingProvider`'s action listener (SDK doc "Obtain controller input actions"). The doc says it only
 *     delivers in Full Space; this app is a Shared Space volume.
 *  2. Scroll events on the volume's view ([feedScroll]): the system's stick-to-scroll mapping for windows. v0.1.3 turned
 *     pieces continuously on the headset from one of the two (which one is logged: STICK scroll / STICK listener).
 * The listener runs on the data source thread: it only stores values; the frame loop reads them.
 */
class ControllerStick {
    private var provider: ControllerTrackingProvider? = null

    @Volatile private var x = 0f
    @Volatile private var y = 0f
    @Volatile private var atMs = 0L
    @Volatile var actions = 0L
        private set

    /** Debug: a stick value that wins over the controllers until [debugUntilMs] (emulator QA). */
    @Volatile private var debugX = 0f
    @Volatile private var debugY = 0f
    @Volatile private var debugUntilMs = 0L

    // the stick pushed further of the two controllers, per axis
    private val listener =
        ControllerTrackingProvider.ControllerActionListener { a ->
            val l = a.left.thumbstickValue
            val r = a.right.thumbstickValue
            x = if (abs(r.x) >= abs(l.x)) r.x else l.x
            y = if (abs(r.y) >= abs(l.y)) r.y else l.y
            atMs = SystemClock.elapsedRealtime()
            if (actions++ % 200 == 0L && (abs(x) > 0.3f || abs(y) > 0.3f)) JigLog.i("STICK listener x=$x y=$y")
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
        y = 0f
        actions = 0L
    }

    /** Thumbstick (x right +, y up +) in [-1, 1]; 0 without a controller or when the last report is stale. */
    fun value(nowMs: Long): Pair<Float, Float> {
        if (nowMs < debugUntilMs) return debugX to debugY
        val sx = if (nowMs < scrollUntilMs) scrollX else 0f
        val sy = if (nowMs < scrollUntilMs) scrollY else 0f
        val p = provider
        val live = p != null && nowMs - atMs <= STALE_MS && runCatching { p.supportState }.getOrNull() == DataProvider.SupportState.SUPPORTED
        val cx = if (live) x.coerceIn(-1f, 1f) else 0f
        val cy = if (live) y.coerceIn(-1f, 1f) else 0f
        return (if (abs(cx) >= abs(sx)) cx else sx) to (if (abs(cy) >= abs(sy)) cy else sy)
    }

    private var armedX = true
    private var armedY = true

    /**
     * One step per push: (-1 / 0 / +1 on X, the same on Y). An axis fires when pushed past [PRESS] and re-arms only once
     * it is back under [RELEASE], so holding the stick gives exactly one quarter turn.
     */
    fun steps(nowMs: Long): Pair<Int, Int> {
        val (vx, vy) = value(nowMs)
        val fireX = armedX && abs(vx) >= PRESS
        val fireY = armedY && abs(vy) >= PRESS
        var sx = 0
        var sy = 0
        if (fireX || fireY) {
            // a diagonal push is one quarter turn about the stronger axis; both axes wait for the middle again
            if (fireX && (!fireY || abs(vx) >= abs(vy))) {
                sx = if (vx > 0) 1 else -1
            } else {
                sy = if (vy > 0) 1 else -1
            }
            armedX = false
            armedY = false
        }
        if (!armedX && abs(vx) < RELEASE && abs(vy) < PRESS) armedX = true
        if (!armedY && abs(vy) < RELEASE && abs(vx) < PRESS) armedY = true
        return sx to sy
    }

    @Volatile private var scrollX = 0f
    @Volatile private var scrollY = 0f
    @Volatile private var scrollUntilMs = 0L
    @Volatile var scrolls = 0L
        private set

    /**
     * A scroll step on the volume (stick tilted); held briefly so a stream of steps reads as one steady push. Compose's
     * scroll y is positive downwards, so stick up (content scrolls up) arrives as a negative dy.
     */
    fun feedScroll(dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        scrollX = (dx * SCROLL_GAIN).coerceIn(-1f, 1f)
        scrollY = (-dy * SCROLL_GAIN).coerceIn(-1f, 1f)
        scrollUntilMs = SystemClock.elapsedRealtime() + SCROLL_HOLD_MS
        if (scrolls++ % 20 == 0L) JigLog.i("STICK scroll dx=$dx dy=$dy")
    }

    /** Debug `stick x,y,ms`: holds the value for [ms]. */
    fun debugHold(vx: Float, vy: Float, ms: Long) {
        debugX = vx.coerceIn(-1f, 1f)
        debugY = vy.coerceIn(-1f, 1f)
        debugUntilMs = SystemClock.elapsedRealtime() + ms
    }

    fun status(): String = "support=${runCatching { provider?.supportState }.getOrNull()} actions=$actions x=${"%.2f".format(x)} y=${"%.2f".format(y)} scrolls=$scrolls"

    private companion object {
        const val STALE_MS = 500L

        /** A scroll stream with gaps shorter than this is one push (longer than v0.1.3's 160 ms: one push must not step twice). */
        const val SCROLL_HOLD_MS = 260L
        const val SCROLL_GAIN = 1f
        const val PRESS = 0.6f
        const val RELEASE = 0.3f
    }
}
