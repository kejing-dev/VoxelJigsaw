package io.embodit.voxeljigsaw.ui

import android.content.Context
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import com.pico.spatial.ui.platform.HandController
import com.pico.spatial.ui.platform.HandControllerHapticType
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.LocalHandControllerHaptic
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import io.embodit.voxeljigsaw.BuildConfig
import io.embodit.voxeljigsaw.R
import io.embodit.voxeljigsaw.domain.Quat4
import io.embodit.voxeljigsaw.domain.Vec3
import io.embodit.voxeljigsaw.platform.JigLog
import io.embodit.voxeljigsaw.scene.PanelIds
import java.lang.ref.WeakReference

/** The one live game, for the debug broadcast (debug builds). */
object Active {
    var game: WeakReference<GameController>? = null
}

/**
 * The volume: one full-size SpatialView with the 3D scene and the attachment panels (design windowModel
 * single_panel_in_volume). Gestures: a spatial tap (select a piece) and a spatial drag (move a piece or turn the
 * table), each in its own pointerInput (SDK doc: separate pointerInputs so one gesture does not eat the other).
 */
@Composable
fun JigsawScreen() {
    val context = LocalContext.current
    val game = remember { GameController(context).also { Active.game = WeakReference(it) } }
    val s by game.state.collectAsState()
    val density = LocalDensity.current
    val converter = LocalPhysicalLengthConverter.current
    val haptic = LocalHandControllerHaptic.current
    val content = remember { mutableStateOf<SpatialViewContent?>(null) }
    val playing by rememberUpdatedState(s.screen == Screen.PLAY &&s.overlay == null && !s.collapsing)

    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) { game.onFocus(focused) }

    // sounds (design T12: SoundPool; ObjectAudioComponent in a volume is unverified, needsCheck)
    val pool = remember { SoundPool.Builder().setMaxStreams(3).setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build()).build() }
    val ids = remember { mapOf("snap" to pool.load(context, R.raw.snap, 1), "pick" to pool.load(context, R.raw.pick, 1), "wrong" to pool.load(context, R.raw.wrong, 1), "done" to pool.load(context, R.raw.done, 1)) }
    LaunchedEffect(game) {
        game.effects.collect { e ->
            when (e) {
                is Effect.Sound -> if (game.s.sound) ids[e.name]?.let { pool.play(it, 0.8f, 0.8f, 1, 0, 1f) }
                Effect.Haptic -> runCatching {
                    haptic.feedback(HandControllerHapticType.Press, HandController.Right)
                    haptic.feedback(HandControllerHapticType.Press, HandController.Left)
                }
            }
        }
    }

    DisposableEffect(game) {
        game.stick.start()
        val receiver = if (BuildConfig.DEBUG) DebugCommands(game).also { context.registerReceiver(it, IntentFilter("${context.packageName}.DEBUG"), Context.RECEIVER_EXPORTED) } else null
        onDispose {
            receiver?.let { runCatching { context.unregisterReceiver(it) } }
            game.close()
            pool.release()
            Active.game = null
        }
    }

    fun meter(px: Float) = with(density) { converter.dpToLength(px.toDp(), LengthUnit.Meters) }
    fun wristOf(raw: com.pico.spatial.core.math.Quat): Quat4? {
        val c = content.value ?: return null
        val q = c.convertRotation(raw, ViewCoordinateSpace.Global, c.localSpatialCoordinateSpace)
        return Quat4(q.x, q.y, q.z, q.w)
    }

    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            // thumbstick fallback: scroll on the volume (see ControllerStick)
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent()
                    if (e.type == PointerEventType.Scroll) e.changes.firstOrNull()?.let { game.stick.feedScroll(it.scrollDelta.x, it.scrollDelta.y) }
                }
            }
        }
    ) {
        SpatialView(
            modifier = Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectSpatialTapGesture(context, TargetEntity.any()) { value ->
                        JigLog.i("TAP piece=${game.scene.viewOf(value.targetEntity)?.idx}")
                        game.scene.tap(value.targetEntity, playing)
                    }
                }
                .pointerInput(Unit) {
                    detectSpatialDragGesture(
                        context,
                        targetedToEntity = TargetEntity.any(),
                        onDragStart = { _, value ->
                            JigLog.i("DRAG start piece=${game.scene.viewOf(value.targetEntity)?.idx}")
                            game.scene.dragStart(value.targetEntity, wristOf(value.inputDevicePose.rawRotation.toQuaternion()), playing)
                        },
                        onDragEnd = { game.scene.dragEnd() },
                        onDragCancel = { game.scene.dragEnd() },
                    ) { value ->
                        val d = value.dragAmount
                        game.scene.dragMove(Vec3(meter(d.x), -meter(d.y), meter(d.z)), wristOf(value.inputDevicePose.rawRotation.toQuaternion()))
                    }
                },
            initial = { c, attachments ->
                content.value = c
                game.scene.attach(c, attachments)
                game.onAttached()
            },
            attachments = {
                AttachmentPanel(id = PanelIds.HOME) { HomePanel(s, game) }
                AttachmentPanel(id = PanelIds.TOP) { TopBar(s, game) }
                AttachmentPanel(id = PanelIds.KEYS) { TurnKeys(s, game) }
                AttachmentPanel(id = PanelIds.CARD) { LandmarkCardPanel(s, game) }
                AttachmentPanel(id = PanelIds.PILL) { DisplayPill(s, game) }
            },
        )
    }
}
