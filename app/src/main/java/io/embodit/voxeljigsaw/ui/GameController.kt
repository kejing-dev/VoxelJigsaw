package io.embodit.voxeljigsaw.ui

import android.content.Context
import android.os.SystemClock
import io.embodit.voxeljigsaw.data.SaveRead
import io.embodit.voxeljigsaw.data.SaveStore
import io.embodit.voxeljigsaw.domain.Landmarks
import io.embodit.voxeljigsaw.domain.Puzzle
import io.embodit.voxeljigsaw.domain.Quat4
import io.embodit.voxeljigsaw.domain.Record
import io.embodit.voxeljigsaw.domain.Rules
import io.embodit.voxeljigsaw.domain.SaveGame
import io.embodit.voxeljigsaw.domain.Vec3
import io.embodit.voxeljigsaw.platform.ControllerStick
import io.embodit.voxeljigsaw.platform.JigLog
import io.embodit.voxeljigsaw.scene.JigsawScene
import io.embodit.voxeljigsaw.scene.PanelIds
import io.embodit.voxeljigsaw.scene.SceneListener
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One-shot feedback the screen plays (SoundPool and controller haptics live in Compose). */
sealed interface Effect {
    data class Sound(val name: String) : Effect

    data object Haptic : Effect
}

/**
 * The game: screen state machine (design states S01–S18), saves and records, messages, the timer, and where
 * each attachment panel sits. The 3D side is [JigsawScene]; it reports discrete events through [SceneListener].
 */
class GameController(context: Context) : SceneListener {
    private val app = context.applicationContext
    val store = SaveStore(app)
    val book: LandmarkBook = LandmarkBook.load(app)
    val stick = ControllerStick()
    val scene = JigsawScene(this, stick)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val puzzles = HashMap<String, Puzzle>()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    val effects = MutableSharedFlow<Effect>(extraBufferCapacity = 8)

    private var messageJob: Job? = null
    private var serial = 0L
    private var teachStep = -1
    private var snappedAt = 0L
    var focused = true
    private var attached = false
    private var lastSavedPlaced = -1

    init {
        _state.update { it.copy(langSetting = store.language, lang = resolveLang(store.language), sound = store.sound, wrist = store.wrist, easy = store.easy) }
        scene.wristFollow = store.wrist
        scene.easy = store.easy
        // the step line: next piece in assembly order and the turn keys it still needs (cheap, 3 Hz)
        scope.launch {
            while (isActive) {
                delay(330)
                val st = _state.value
                if (st.screen != Screen.PLAY || st.collapsing) continue
                val p = scene.puzzle ?: continue
                val next = nextPiece() ?: continue
                val keys = scene.keysToFit(next)?.takeIf { it.isNotEmpty() }
                val step = p.n - (p.n - scene.placedCount()) + 1
                if (st.step != step || st.stepKeys != keys) _state.update { it.copy(step = step, stepKeys = keys) }
            }
        }
        scope.launch {
            while (isActive) {
                delay(1000)
                val s = _state.value
                if (s.screen == Screen.PLAY && focused && s.overlay == null && !s.collapsing) _state.update { it.copy(elapsedSec = it.elapsedSec + 1) }
            }
        }
    }

    private fun resolveLang(setting: String): Lang =
        when (setting) {
            "zh" -> Lang.ZH
            "en" -> Lang.EN
            else -> if (Locale.getDefault().language.startsWith("zh")) Lang.ZH else Lang.EN
        }

    val s: UiState get() = _state.value

    private fun set(block: (UiState) -> UiState) {
        val before = _state.value
        _state.update { block(it) }
        val after = _state.value.let { it.copy(designState = designStateOf(it)) }
        _state.value = after
        if (before.designState != after.designState || before.screen != after.screen || before.overlay != after.overlay) {
            JigLog.i("STATE ${after.designState} screen=${after.screen} overlay=${after.overlay} key=${after.key} placed=${after.placed}/${after.total} lang=${after.lang}")
        }
        layoutPanels(after)
    }

    private fun designStateOf(s: UiState): String =
        when (s.screen) {
            Screen.LOADING -> "S01"
            Screen.ERROR -> "S18"
            Screen.HOME -> when (s.overlay) {
                Overlay.SETTINGS -> "S17"
                Overlay.CONFIRM -> "S14"
                else -> if (s.saves.containsKey(s.key)) "S03" else "S02"
            }
            Screen.PLAY -> when {
                s.overlay == Overlay.MENU -> "S13"
                s.overlay == Overlay.CONFIRM -> "S14"
                s.overlay == Overlay.SETTINGS -> "S17"
                s.collapsing -> "S04"
                s.holding != null -> "S06"
                s.message?.kind == MessageKind.WRONG -> "S09"
                s.message?.kind == MessageKind.FACT -> "S12"
                SystemClock.elapsedRealtime() - snappedAt < 600 * io.embodit.voxeljigsaw.scene.Linger.k -> "S08"
                s.selected != null -> "S07"
                s.hintOn && (scene.focusing || scene.hinting) -> "S10"
                s.peekOn -> "S11"
                else -> "S05"
            }
            Screen.COMPLETE -> "S15"
            Screen.DISPLAY -> "S16"
        }

    // ------------------------------------------------------------------------------------------
    // panels (design spatialLayout; volume metres, rotations about the panel centre)

    private val tiltDown15 = Quat4.axisAngle(Vec3(1f, 0f, 0f), Math.toRadians(15.0).toFloat())
    private val leanBack30 = Quat4.axisAngle(Vec3(1f, 0f, 0f), Math.toRadians(-30.0).toFloat())
    private val turnLeft15 = Quat4.axisAngle(Vec3(0f, 1f, 0f), Math.toRadians(-15.0).toFloat())

    private fun layoutPanels(s: UiState) {
        if (!attached) return
        val sc = scene
        val homeShown = s.screen == Screen.HOME || s.screen == Screen.LOADING || s.screen == Screen.ERROR || (s.screen == Screen.PLAY && s.overlay == Overlay.SETTINGS)
        if (homeShown) sc.showPanel(PanelIds.HOME, Vec3(0f, 0.10f, 0.21f), leanBack30) else sc.hidePanel(PanelIds.HOME)
        if (s.screen == Screen.PLAY && s.overlay != Overlay.SETTINGS) {
            val h = PanelSizes.topHeight(s) * PanelSizes.M_PER_DP
            sc.showPanel(PanelIds.TOP, Vec3(0f, 0.58f - h / 2f, 0.17f), tiltDown15)
        } else sc.hidePanel(PanelIds.TOP)
        if (s.screen == Screen.COMPLETE && s.cardShown) sc.showPanel(PanelIds.CARD, Vec3(0.33f, 0.27f, 0.08f), turnLeft15) else sc.hidePanel(PanelIds.CARD)
        if (s.screen == Screen.DISPLAY) sc.showPanel(PanelIds.PILL, Vec3(0f, 0.045f, 0.14f), leanBack30) else sc.hidePanel(PanelIds.PILL)
        if (s.selected == null || s.screen != Screen.PLAY) sc.hidePanel(PanelIds.KEYS)
    }

    fun onAttached() {
        attached = true
        boot()
    }

    // ------------------------------------------------------------------------------------------
    // loading and home

    private fun puzzle(id: String, n: Int): Puzzle {
        val key = "$id-$n"
        return puzzles.getOrPut(key) { Puzzle.parse(app.assets.open("puzzles/$key.json").bufferedReader().use { it.readText() }) }
    }

    private fun refreshSaves(): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (id in Landmarks.IDS) for (n in Landmarks.COUNTS) store.progress("$id-$n", n)?.let { out["$id-$n"] = it }
        return out
    }

    fun boot() {
        set { it.copy(screen = Screen.LOADING, overlay = null, loading = 0f, errorReason = null, saveLost = false) }
        scope.launch {
            delay((350 * io.embodit.voxeljigsaw.scene.Linger.k).toLong())
            try {
                Landmarks.IDS.forEachIndexed { i, id ->
                    puzzle(id, 25)
                    set { it.copy(loading = (i + 1) / 3f) }
                    delay(60)
                }
                set { it.copy(records = store.records(), saves = refreshSaves()) }
                goHome()
            } catch (t: Throwable) {
                JigLog.e("LOAD failed: $t", t)
                set { it.copy(screen = Screen.ERROR, errorReason = t.message ?: t.javaClass.simpleName) }
            }
        }
    }

    fun goHome() {
        scene.dropHeld()
        if (s.screen == Screen.PLAY) saveNow()
        scene.select(null)
        scene.endHint()
        scene.displayMode = false
        dismissMessage()
        set { it.copy(screen = Screen.HOME, overlay = null, saves = refreshSaves(), records = store.records(), holding = null, selected = null, hintOn = false, peekOn = false, cardShown = false, pillOpen = false, collapsing = false) }
        preview()
    }

    private fun preview() {
        try {
            scene.showPreview(puzzle(s.landmark, s.count))
        } catch (t: Throwable) {
            fail(t, saveLost = false)
        }
    }

    fun selectLandmark(id: String) {
        if (id == s.landmark) return
        set { it.copy(landmark = id, overlay = null) }
        preview()
    }

    fun selectCount(n: Int) {
        if (n == s.count) return
        set { it.copy(count = n, overlay = null) }
        preview()
    }

    // ------------------------------------------------------------------------------------------
    // a game

    /** Home's main button: resume the saved game or start a new one. */
    fun startOrResume() {
        when (val read = store.read(s.key, s.count)) {
            is SaveRead.Ok -> resume(read.save)
            is SaveRead.Broken -> fail(IllegalStateException(read.reason), saveLost = true)
            SaveRead.None -> startNew()
        }
    }

    private fun fail(t: Throwable, saveLost: Boolean) {
        JigLog.e("LOAD error key=${s.key} saveLost=$saveLost: $t")
        dismissMessage()
        set { it.copy(screen = Screen.ERROR, overlay = null, errorReason = t.message ?: t.javaClass.simpleName, saveLost = saveLost) }
    }

    /** S18 "try again": a broken save is dropped and a fresh game starts; a missing puzzle reloads from S01. */
    fun retry() {
        if (s.saveLost) {
            store.delete(s.key)
            set { it.copy(saveLost = false, errorReason = null) }
            startNew()
        } else {
            puzzles.clear()
            boot()
        }
    }

    fun startNew() {
        val p = try {
            puzzle(s.landmark, s.count)
        } catch (t: Throwable) {
            fail(t, saveLost = false)
            return
        }
        store.delete(s.key)
        dismissMessage()
        teachStep = if (store.taught) -1 else 0
        set { it.copy(screen = Screen.PLAY, overlay = null, placed = 0, total = p.n, hints = 0, elapsedSec = 0, factsSeen = emptyList(), holding = null, selected = null, hintOn = false, peekOn = false, cardShown = false, collapsing = true, saves = it.saves - it.key) }
        scene.displayMode = false
        scene.startNew(p, collapse = true)
        lastSavedPlaced = -1
    }

    private fun resume(save: SaveGame) {
        val p = puzzle(s.landmark, s.count)
        dismissMessage()
        teachStep = -1
        scene.restore(p, save.placed, save.loose, save.parked, save.yawQuarters)
        set { it.copy(screen = Screen.PLAY, overlay = null, placed = save.placed.size, total = p.n, hints = save.hints, elapsedSec = save.elapsedSec, factsSeen = save.factsSeen, holding = null, selected = null, hintOn = false, peekOn = false, collapsing = false) }
        lastSavedPlaced = save.placed.size
        guide()
    }

    fun saveNow() {
        val p = scene.puzzle ?: return
        if (s.screen != Screen.PLAY || scene.collapsing) return
        if (scene.placedCount() >= p.n) return
        val (placed, loose, parked) = scene.snapshot()
        val save = SaveGame(p.id, p.n, placed, loose, parked, s.elapsedSec, s.hints, s.factsSeen, scene.yawQuarters())
        runCatching { store.write(p.key, save) }.onFailure { JigLog.e("SAVE failed: $it") }
        lastSavedPlaced = placed.size
        JigLog.i("SAVE key=${p.key} placed=${placed.size} loose=${loose.size} parked=${parked.size} yaw=${scene.yawQuarters() * 90} t=${s.elapsedSec}")
    }

    fun onFocus(f: Boolean) {
        focused = f
        if (!f) {
            scene.dropHeld()
            saveNow()
        }
    }

    private fun nextPiece(): Int? {
        val p = scene.puzzle ?: return null
        val placed = scene.views.filter { it.where == JigsawScene.Where.PLACED }.map { it.idx }.toSet()
        return Rules.nextHint(p, placed)
    }

    /** Easy mode: the next piece in assembly order stays marked, with its ghost at the right place, until it goes in. */
    private fun guide() {
        if (!s.easy || s.screen != Screen.PLAY || scene.collapsing) return
        nextPiece()?.let { scene.hint(it, seconds = null) }
    }

    /** Hint (top bar key or the step line): marks the next piece, bobs it and selects it so its turn keys come up. */
    fun hint() {
        val idx = nextPiece() ?: return
        scene.hint(idx, seconds = if (s.easy) null else 4f, focus = true)
        set { it.copy(hints = it.hints + 1, hintOn = true) }
        watchTimed()
    }

    fun peek() {
        scene.peek()
        set { it.copy(peekOn = true) }
        watchTimed()
    }

    private var timedJob: Job? = null

    /** Clears the hint / peek flags when the scene's timers run out. */
    private fun watchTimed() {
        timedJob?.cancel()
        timedJob = scope.launch {
            while (isActive && (s.hintOn || s.peekOn)) {
                delay(100)
                if (s.hintOn && !scene.hinting && !scene.focusing) set { it.copy(hintOn = false) }
                if (s.peekOn && !scene.peeking) set { it.copy(peekOn = false) }
            }
        }
    }

    fun openMenu() = set { it.copy(overlay = if (it.overlay == Overlay.MENU) null else Overlay.MENU) }

    fun closeOverlay() = set { it.copy(overlay = null) }

    fun askRestart() = set { it.copy(overlay = Overlay.CONFIRM) }

    fun confirmRestart() {
        store.delete(s.key)
        set { it.copy(overlay = null, saves = it.saves - it.key) }
        startNew()
    }

    fun openSettings() = set { it.copy(overlay = Overlay.SETTINGS) }

    fun setLanguage(v: String) {
        store.language = v
        set { it.copy(langSetting = v, lang = resolveLang(v)) }
    }

    fun setSound(v: Boolean) {
        store.sound = v
        set { it.copy(sound = v) }
    }

    fun setEasy(v: Boolean) {
        store.easy = v
        scene.easy = v
        set { it.copy(easy = v) }
        if (v) guide() else if (!s.hintOn) scene.endHint()
    }

    fun setWrist(v: Boolean) {
        store.wrist = v
        scene.wristFollow = v
        set { it.copy(wrist = v) }
    }

    fun turn(tip: Boolean) = scene.turnSelected(tip)

    // complete / display
    fun showDisplay() {
        scene.displayMode = true
        set { it.copy(screen = Screen.DISPLAY, pillOpen = false, cardShown = false) }
    }

    fun togglePill() = set { it.copy(pillOpen = !it.pillOpen) }

    fun showCard() {
        scene.displayMode = false
        set { it.copy(screen = Screen.COMPLETE, cardShown = true, pillOpen = false) }
    }

    fun nextLandmark() {
        val ids = Landmarks.IDS
        val start = ids.indexOf(s.landmark)
        val next = (1..ids.size).map { ids[(start + it) % ids.size] }.firstOrNull { store.records()["$it-${s.count}"]?.completed != true } ?: ids[(start + 1) % ids.size]
        set { it.copy(landmark = next) }
        goHome()
    }

    // ------------------------------------------------------------------------------------------
    // messages

    private fun message(kind: MessageKind, text: String, seconds: Long) {
        messageJob?.cancel()
        val m = Message(kind, text, ++serial)
        set { it.copy(message = m) }
        messageJob = scope.launch {
            delay((seconds * 1000 * io.embodit.voxeljigsaw.scene.Linger.k).toLong())
            if (s.message?.serial == m.serial) set { it.copy(message = null) }
        }
    }

    fun dismissMessage() {
        messageJob?.cancel()
        if (s.message != null) set { it.copy(message = null) }
    }

    // ------------------------------------------------------------------------------------------
    // SceneListener

    override fun onPicked(idx: Int) {
        effects.tryEmit(Effect.Sound("pick"))
        if (s.hintOn && !scene.hinting && !scene.focusing) set { it.copy(hintOn = false) }
    }

    override fun onSelected(idx: Int?) = set { it.copy(selected = idx) }

    override fun onHolding(idx: Int?) = set { it.copy(holding = idx) }

    override fun onPlaced(idx: Int) {
        val p = scene.puzzle ?: return
        snappedAt = SystemClock.elapsedRealtime()
        effects.tryEmit(Effect.Sound("snap"))
        effects.tryEmit(Effect.Haptic)
        val placedSet = scene.views.filter { it.where == JigsawScene.Where.PLACED }.map { it.idx }.toSet()
        set { it.copy(placed = placedSet.size) }
        scope.launch {
            delay((650 * io.embodit.voxeljigsaw.scene.Linger.k).toLong())
            set { it }
        }
        if (placedSet.size >= p.n) {
            finish(p)
            return
        }
        val seg = Rules.segmentDone(p, idx, placedSet)
        if (seg != null && seg !in s.factsSeen) {
            set { it.copy(factsSeen = it.factsSeen + seg) }
            val t = book.of(p.id, s.lang)
            message(MessageKind.FACT, t.facts[seg], 6)
        } else if (teachStep >= 0) {
            teachStep++
            if (teachStep <= 2) message(MessageKind.TEACH, s.strings.teach[teachStep], 6)
            if (teachStep == 1 && !s.easy) Rules.nextHint(p, placedSet)?.let { autoHint(it) }
            if (teachStep >= 2) {
                store.taught = true
                teachStep = -1
            }
        }
        guide()
        saveNow()
    }

    private fun autoHint(idx: Int) {
        scene.hint(idx)
        set { it.copy(hintOn = true) }
        watchTimed()
    }

    override fun onWrongWay(idx: Int) {
        effects.tryEmit(Effect.Sound("wrong"))
        message(MessageKind.WRONG, s.strings.wrong, 3)
    }

    override fun onCollapseDone() {
        set { it.copy(collapsing = false) }
        if (teachStep == 0) {
            message(MessageKind.TEACH, s.strings.teach[0], 8)
            if (!s.easy) scene.puzzle?.let { p -> Rules.nextHint(p, emptySet())?.let { autoHint(it) } }
        }
        guide()
        saveNow()
    }

    override fun onCompleteShown() {
        if (s.screen == Screen.COMPLETE) set { it.copy(cardShown = true) }
    }

    override fun onTableTurned() {
        if (s.screen == Screen.PLAY) saveNow()
    }

    private fun finish(p: Puzzle) {
        val records = store.records().toMutableMap()
        val old = records[p.key]
        val sec = s.elapsedSec
        records[p.key] = if (old == null || !old.completed || sec < old.bestSec) Record(true, sec, s.hints) else old
        store.writeRecords(records)
        store.delete(p.key)
        dismissMessage()
        effects.tryEmit(Effect.Sound("done"))
        set { it.copy(screen = Screen.COMPLETE, overlay = null, records = records, saves = it.saves - p.key, cardShown = false, selected = null, holding = null, hintOn = false, peekOn = false) }
        scene.complete(animate = true)
        JigLog.i("STATE complete key=${p.key} time=${sec}s hints=${s.hints}")
    }

    // ------------------------------------------------------------------------------------------
    // debug (QA broadcast, debug builds only)

    fun debugSeed(id: String, n: Int, placedCount: Int) {
        set { it.copy(landmark = id, count = n) }
        val p = puzzle(id, n)
        store.delete(p.key)
        store.taught = true
        teachStep = -1
        dismissMessage()
        set { it.copy(screen = Screen.PLAY, overlay = null, placed = 0, total = p.n, hints = 0, elapsedSec = 0, factsSeen = emptyList(), holding = null, selected = null, hintOn = false, peekOn = false, collapsing = false, cardShown = false) }
        scene.displayMode = false
        scene.startNew(p, collapse = false)
        val ids = p.order.take(placedCount)
        val loose = scene.snapshot().second.filter { it.idx !in ids }
        scene.restore(p, ids, loose, emptyList(), 0)
        val segs = (0 until 3).filter { seg -> p.piecesOfSegment(seg).all { it in ids } }
        set { it.copy(placed = ids.size, factsSeen = segs) }
        guide()
        saveNow()
    }

    /** Debug S05: the first teaching line, as on a first game. */
    fun debugTeach() = message(MessageKind.TEACH, s.strings.teach[0], 8)

    fun debugSolveNext() {
        val p = scene.puzzle ?: return
        val placed = scene.views.filter { it.where == JigsawScene.Where.PLACED }.map { it.idx }.toSet()
        Rules.nextHint(p, placed)?.let { scene.autoPlace(it) }
    }

    fun debugSolveAll() {
        val p = scene.puzzle ?: return
        scope.launch {
            for (idx in p.order) {
                if (scene.views[idx].where != JigsawScene.Where.PLACED) {
                    scene.autoPlace(idx)
                    delay(200)
                }
            }
        }
    }

    fun debugCorruptSave() {
        store.corrupt(s.key)
        set { it.copy(saves = refreshSaves()) }
    }

    fun debugLocale(v: String) = setLanguage(if (v == "follow") "follow" else v)

    fun close() {
        saveNow()
        scope.cancel()
        stick.stop()
        scene.close()
        attached = false
    }
}

/** Attachment panel sizes in dp (design panels[].sizeDp) and the dp → metre factor of an attachment at scale 1. */
object PanelSizes {
    const val M_PER_DP = 0.00082f
    const val HOME_W = 560f
    const val HOME_H = 244f
    const val SETTINGS_H = 420f
    const val TOP_W = 480f
    const val TOP_H = 132f
    const val TOP_MSG_H = 150f
    const val TOP_STEP_KEYS_H = 150f
    const val MENU_H = 280f
    const val KEYS_W = 112f
    const val KEYS_H = 48f
    const val CARD_W = 290f
    const val CARD_H = 388f
    const val PILL_W = 220f
    const val PILL_H = 48f

    fun topHeight(s: UiState): Float =
        when {
            s.overlay == Overlay.MENU -> MENU_H
            s.overlay == Overlay.CONFIRM -> MENU_H
            s.message != null -> TOP_MSG_H
            else -> TOP_H
        }

    fun homeHeight(s: UiState): Float = if (s.overlay == Overlay.SETTINGS) SETTINGS_H else HOME_H
}
