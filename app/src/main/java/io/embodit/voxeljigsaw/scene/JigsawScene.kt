package io.embodit.voxeljigsaw.scene

import android.os.SystemClock
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.HoverEffectComponent
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import io.embodit.voxeljigsaw.domain.Mesher
import io.embodit.voxeljigsaw.domain.Puzzle
import io.embodit.voxeljigsaw.domain.Quat4
import io.embodit.voxeljigsaw.domain.Release
import io.embodit.voxeljigsaw.domain.Rest
import io.embodit.voxeljigsaw.domain.Rot24
import io.embodit.voxeljigsaw.domain.Rules
import io.embodit.voxeljigsaw.domain.Vec3
import io.embodit.voxeljigsaw.platform.ControllerStick
import io.embodit.voxeljigsaw.platform.JigLog
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the scene tells the game (all on the main thread). */
interface SceneListener {
    fun onPicked(idx: Int)

    fun onSelected(idx: Int?)

    fun onHolding(idx: Int?)

    fun onPlaced(idx: Int)

    fun onWrongWay(idx: Int)

    fun onCollapseDone()

    fun onCompleteShown()

    fun onTableTurned()
}

/**
 * The volume's 3D content (design entities3d), owned on the ECS side: turntable, pieces, ghosts, outlines,
 * sweep ring and the attachment panel holders. A frame loop (the reference repo's main-thread clock) runs the
 * held piece, the thumbstick, the turntable and every tween; Compose only sends discrete events here.
 *
 * Frames: "volume" = the design's volume coordinates (origin on the volume floor centre, +Y up, +Z towards
 * the user); "table" = the turntable's model frame (origin on the table-top centre, turns with the table).
 */
class JigsawScene(private val listener: SceneListener, private val stick: ControllerStick) {
    companion object {
        /**
         * Everything in the volume is drawn this much larger than the design's metres (user feedback 2026-10-23:
         * "整体要大一点"). The design numbers stay as they are; the root entity and the panels carry the factor,
         * and the manifest volume is 1.0 x 0.6 x 0.5 m times it.
         */
        const val WORLD_SCALE = 1.4f

        /** SpatialView origin is the volume centre; the design origin is the floor centre. */
        val ROOT_OFFSET = Vec3(0f, -0.30f * WORLD_SCALE, 0f)
        val VOL_MIN = Vec3(-0.5f, 0f, -0.25f)
        val VOL_MAX = Vec3(0.5f, 0.6f, 0.25f)
        val TABLE = Vec3(0f, 0f, -0.04f)
        const val TABLE_R = 0.14f
        const val TABLE_TOP = 0.03f
        const val LOOSE_MIN = 0.06f
        private const val FOLLOW_TAU = 0.05f
        private const val STICK_DEAD = 0.15f
        private const val STICK_RATE = (PI / 2).toFloat() // rad/s at full push
        private val UP = Vec3(0f, 1f, 0f)
        private val RIGHT = Vec3(1f, 0f, 0f)
    }

    enum class Where { LOOSE, HELD, PARKED, PLACED }

    inner class PieceView(val idx: Int, val holder: Entity, val visual: ModelEntity, val mesh: MeshResource, val half: Vec3) {
        var where = Where.LOOSE
        var pos = Vec3.ZERO
        var rot = Quat4.IDENTITY
        var rotIdx = 0
        var wing = Vec3.ZERO
        var busy = false // a tween owns it

        fun write() = holder.setLocal(position = pos, rotation = rot)

        fun setCollider(placed: Boolean) {
            val size = half * 2f
            val box = if (placed) size else Vec3(max(size.x, LOOSE_MIN), max(size.y, LOOSE_MIN), max(size.z, LOOSE_MIN))
            holder.components.set(CollisionComponent(listOf(ShapeResource.createBox(box.sdk())), physics))
        }

        /** Half extent of its box in its parent frame, at its snapped orientation. */
        fun halfInParent(): Vec3 = Rot24.extent(rotIdx, half * 2f) * 0.5f
    }

    private val shared = SharedResources()
    private lateinit var mats: Materials
    private lateinit var physics: PhysicsMaterialResource
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loop: Job? = null

    val root = Entity().named("jigsaw:root")
    private val table = Entity().named("turntable")
    private val model = Entity().named("turntable:model")
    private val tableCollider = Entity().named("turntable:collider")
    private lateinit var cube: MeshResource
    private lateinit var selOutline: Outline
    private lateinit var hintOutline: Outline
    private lateinit var flashOutline: Outline
    private var shadow: ModelEntity? = null
    private var sweep: ModelEntity? = null
    private var whole: ModelEntity? = null
    private var peekGhost: ModelEntity? = null
    private var slotGhost: ModelEntity? = null
    private var content: SpatialViewContent? = null
    private val panels = HashMap<String, Entity>()

    var puzzle: Puzzle? = null
        private set
    val views = ArrayList<PieceView>()
    private val voxelMats = HashMap<String, Material>()
    private val ghostMats = HashMap<String, Material>()
    private val wholeMeshes = HashMap<String, MeshResource>()
    private val pieceMeshes = HashMap<String, List<MeshResource>>()

    // turntable
    var yaw = 0f // radians, continuous
        private set
    private var pitch = 0f
    private var yawTarget: Float? = null
    private var pitchHome = false
    private var tableDragging = false
    private var spinLeft = 0f
    private var previewSpin = false
    var displayMode = false

    // held piece
    private var held: PieceView? = null
    private var target = Vec3.ZERO
    private var handle = Vec3.ZERO
    private var grabRot = Quat4.IDENTITY
    private var wristStart: Quat4? = null
    private var wristNow: Quat4? = null
    private var stickYaw = 0f
    var wristFollow = true

    /** Easy mode: wider snap and self-righting pieces (Rules.EASY_SNAP_M). */
    var easy = true

    // selection, stick turning of a resting piece
    var selected: PieceView? = null
        private set
    private var selStick = 0f
    private var selBase = Quat4.IDENTITY

    // timed effects
    private var hintIdx: Int? = null
    private var hintLeft = 0f
    private var peekLeft = 0f
    private var flashLeft = 0f
    private var sweepT = -1f
    private var collapseT = -1f
    private var collapsePlan: List<Triple<PieceView, Vec3, Quat4>> = emptyList()
    private val tweens = ArrayList<Tween>()
    private var lastNs = 0L
    var frames = 0L
        private set

    private class Tween(val view: PieceView?, val dur: Float, val step: (Float) -> Unit, val done: () -> Unit) {
        var t = 0f
    }

    // ------------------------------------------------------------------------------------------
    // setup

    fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments) {
        this.content = content
        mats = Materials(shared)
        physics = PhysicsMaterialResource(0.8f, 0.65f, 0f)
        cube = shared.keep(Meshes.cube())
        root.setLocal(position = ROOT_OFFSET, scale = Vec3(WORLD_SCALE, WORLD_SCALE, WORLD_SCALE))
        content.addEntity(root)
        root.addChild(table)
        table.setLocal(position = TABLE)
        table.addChild(model)
        model.setLocal(position = Vec3(0f, TABLE_TOP, 0f))
        val cyl = shared.keep(Meshes.cylinder("turntable"))
        model(cyl, mats.limestone, "turntable:top", table).setLocal(position = Vec3(0f, TABLE_TOP / 2, 0f), scale = Vec3(TABLE_R * 2, TABLE_TOP, TABLE_R * 2))
        val ring = shared.keep(Meshes.ring("turntable:ring", 0.47f))
        model(ring, mats.ring, "turntable:ring", table).setLocal(position = Vec3(0f, TABLE_TOP + 0.0006f, 0f), scale = Vec3(TABLE_R * 1.9f, 1f, TABLE_R * 1.9f))
        model(cube, mats.gold, "turntable:mark", table).setLocal(position = Vec3(0f, TABLE_TOP + 0.001f, TABLE_R - 0.012f), scale = Vec3(0.008f, 0.003f, 0.016f))
        table.addChild(tableCollider)
        tableCollider.setLocal(position = Vec3(0f, TABLE_TOP / 2, 0f))
        tableCollider.components.set(CollisionComponent(listOf(ShapeResource.createBox(Vector3(TABLE_R * 2, TABLE_TOP + 0.01f, TABLE_R * 2))), physics))
        tableCollider.components.set(InteractableComponent())
        selOutline = Outline(cube, mats.gold, "outline:select")
        hintOutline = Outline(cube, mats.gold, "outline:hint")
        flashOutline = Outline(cube, mats.gold, "outline:flash")
        for (o in listOf(selOutline, hintOutline, flashOutline)) {
            root.addChild(o.root)
            o.root.enabled = false
        }
        val disc = shared.keep(Meshes.disc("shadow"))
        shadow = model(disc, mats.shadow, "shadow", root).also { it.enabled = false }
        val sweepMesh = shared.keep(Meshes.ring("sweep", 0.44f))
        sweep = model(sweepMesh, mats.sweep, "sweep", model).also { it.enabled = false }
        for (id in PanelIds.ALL) {
            attachments.entity(id)?.let {
                content.addEntity(it)
                panels[id] = it
                hidePanel(id)
            }
        }
        lastNs = SystemClock.elapsedRealtimeNanos()
        loop = scope.launch {
            while (isActive) {
                delay(16)
                val now = SystemClock.elapsedRealtimeNanos()
                val dt = ((now - lastNs) / 1e9f).coerceIn(0.001f, 0.1f)
                lastNs = now
                runCatching { frame(dt) }.onFailure { JigLog.e("FRAME failed: $it", it) }
            }
        }
        JigLog.i("SCENE attached panels=${panels.keys}")
    }

    /** Builds the meshes of [p] (once per puzzle key) and lays the pieces out at their homes, hidden. */
    fun load(p: Puzzle) {
        clearPieces()
        puzzle = p
        val mat = voxelMats.getOrPut(p.id) { mats.voxels(p.palette) }
        val wholeMesh = wholeMeshes.getOrPut(p.id) { shared.keep(Meshes.fromData(Mesher.mesh(p.pieces.flatMap { it.voxels }, Vec3.ZERO, p.voxelM, p.palette.size), "whole:${p.id}")) }
        whole?.destroy()
        whole = model(wholeMesh, mat, "whole:${p.id}", model).also { it.enabled = false }
        peekGhost?.destroy()
        peekGhost = model(wholeMesh, ghostMats.getOrPut(p.id) { mats.voxelGhost(p.palette, 0.25f) }, "peek", model).also { it.enabled = false }
        val t0 = SystemClock.elapsedRealtime()
        val meshes = pieceMeshes.getOrPut(p.key) {
            p.pieces.map { piece -> shared.keep(Meshes.fromData(Mesher.mesh(piece.voxels, piece.home, p.voxelM, p.palette.size), "piece:${p.key}:${piece.idx}")) }
        }
        val t1 = SystemClock.elapsedRealtime()
        for (piece in p.pieces) {
            val mesh = meshes[piece.idx]
            val holder = Entity().named("piece:${piece.idx}")
            holder.components.set(InteractableComponent())
            holder.components.set(HoverEffectComponent())
            val visual = model(mesh, mat, "piece:${piece.idx}:mesh", holder)
            val v = PieceView(piece.idx, holder, visual, mesh, piece.extent * (p.voxelM / 2f))
            v.wing = piece.wing
            holder.enabled = false
            root.addChild(holder)
            views += v
        }
        sweep?.setLocal(scale = Vec3(p.radius * p.voxelM * 2.3f, 1f, p.radius * p.voxelM * 2.3f))
        JigLog.i("LOAD puzzle=${p.key} pieces=${p.n} meshes=${t1 - t0}ms entities=${SystemClock.elapsedRealtime() - t1}ms")
    }

    private fun clearPieces() {
        cancelHold()
        select(null)
        tweens.clear()
        collapseT = -1f
        sweepT = -1f
        hintIdx = null
        slotGhost?.destroy()
        slotGhost = null
        for (o in listOf(selOutline, hintOutline, flashOutline)) park(o)
        views.forEach { runCatching { it.holder.destroy() } }
        views.clear()
    }

    private fun park(o: Outline) {
        o.root.enabled = false
        if (o.root.getParent() != root) root.addChild(o.root)
    }

    // ------------------------------------------------------------------------------------------
    // frames

    private fun tableQuat(): Quat4 = Quat4.axisAngle(UP, yaw) * Quat4.axisAngle(RIGHT, pitch)

    fun yawQuarters(): Int = ((yaw / (PI.toFloat() / 2)).roundToInt() % 4 + 4) % 4

    /** Table-frame point to volume. */
    fun toVolume(p: Vec3): Vec3 = TABLE + tableQuat().rotate(p + Vec3(0f, TABLE_TOP, 0f))

    fun toTable(v: Vec3): Vec3 = tableQuat().conjugate().rotate(v - TABLE) - Vec3(0f, TABLE_TOP, 0f)

    /** Volume position of a piece wherever it hangs. */
    fun volumePos(v: PieceView): Vec3 = if (v.where == Where.PARKED || v.where == Where.PLACED) toVolume(v.pos) else v.pos

    private fun parentOf(v: PieceView): Entity = if (v.where == Where.PARKED || v.where == Where.PLACED) model else root

    private fun reparent(v: PieceView, toModel: Boolean) {
        val parent = if (toModel) model else root
        if (v.holder.getParent() != parent) parent.addChild(v.holder)
    }

    // ------------------------------------------------------------------------------------------
    // game commands

    /** Home preview: the whole landmark on the table, turning once in 12 s (S02). */
    fun showPreview(p: Puzzle) {
        if (puzzle?.key != p.key) load(p)
        views.forEach { it.holder.enabled = false }
        displayMode = false
        whole?.enabled = true
        previewSpin = true
        pitch = 0f
        spinLeft = 0f
    }

    /** New game: the landmark stands 0.5 s, then breaks along its seams into the wings (S04). */
    fun startNew(p: Puzzle, collapse: Boolean = true) {
        load(p)
        previewSpin = false
        yaw = 0f
        pitch = 0f
        yawTarget = null
        val plan = ArrayList<Triple<PieceView, Vec3, Quat4>>()
        for (v in views) {
            val piece = p.pieces[v.idx]
            v.where = Where.LOOSE
            v.rotIdx = piece.scatterRot
            v.setCollider(false)
            plan += Triple(v, piece.wing, Rot24.quat(piece.scatterRot))
        }
        if (!collapse) {
            plan.forEach { (v, at, q) -> v.pos = at; v.rot = q; v.write(); v.holder.enabled = true }
            whole?.enabled = false
            listener.onCollapseDone()
            return
        }
        whole?.enabled = true
        collapsePlan = plan
        collapseT = 0f
        JigLog.i("STATE collapse start puzzle=${p.key}")
    }

    /** Restores a saved game (S03 → S05). */
    fun restore(p: Puzzle, placed: List<Int>, loose: List<Rest>, parked: List<Rest>, yawQuarters: Int) {
        load(p)
        previewSpin = false
        yaw = yawQuarters * PI.toFloat() / 2
        pitch = 0f
        yawTarget = null
        whole?.enabled = false
        for (idx in placed) setPlaced(views[idx])
        for (r in loose) views[r.idx].apply { where = Where.LOOSE; rotIdx = r.rot; rot = Rot24.quat(r.rot); pos = clampVolume(r.pos, halfInParent()); setCollider(false); reparent(this, false); write(); holder.enabled = true }
        for (r in parked) views[r.idx].apply { where = Where.PARKED; rotIdx = r.rot; rot = Rot24.quat(r.rot); pos = r.pos; setCollider(false); reparent(this, true); write(); holder.enabled = true }
        applyTable()
        JigLog.i("STATE restored puzzle=${p.key} placed=${placed.size} parked=${parked.size} yaw=${yawQuarters * 90}")
    }

    private fun setPlaced(v: PieceView) {
        val p = puzzle ?: return
        v.where = Where.PLACED
        v.rotIdx = Rot24.IDENTITY
        v.rot = Quat4.IDENTITY
        v.pos = p.homeM(v.idx)
        reparent(v, true)
        v.setCollider(true)
        v.write()
        v.holder.enabled = true
    }

    fun snapshot(): Triple<List<Int>, List<Rest>, List<Rest>> {
        val placed = views.filter { it.where == Where.PLACED }.map { it.idx }
        val loose = views.filter { it.where == Where.LOOSE || it.where == Where.HELD }.map { v ->
            if (v.where == Where.HELD) Rest(v.idx, handle, Rot24.nearest(heldRot())) else Rest(v.idx, v.pos, v.rotIdx)
        }
        val parked = views.filter { it.where == Where.PARKED }.map { Rest(it.idx, it.pos, it.rotIdx) }
        return Triple(placed, loose, parked)
    }

    fun placedCount(): Int = views.count { it.where == Where.PLACED }

    // ------------------------------------------------------------------------------------------
    // input: grab, drag, release (detectSpatialDragGesture) and tap (detectSpatialTapGesture)

    fun viewOf(e: Entity?): PieceView? {
        var p = e
        while (p != null) {
            val v = views.firstOrNull { it.holder == p }
            if (v != null) return v
            if (p == root) return null
            p = p.getParent()
        }
        return null
    }

    private fun isTable(e: Entity?): Boolean {
        var p = e
        while (p != null) {
            if (p == table) return true
            p = p.getParent()
        }
        return false
    }

    private var dragKind = 0 // 0 none, 1 piece, 2 table

    fun dragStart(target: Entity?, wrist: Quat4?, playing: Boolean) {
        val v = viewOf(target)
        if (playing && v != null && (v.where == Where.LOOSE || v.where == Where.PARKED) && !v.busy && collapseT < 0f) {
            grab(v, wrist)
            dragKind = 1
            return
        }
        if ((playing || displayMode) && (isTable(target) || v?.where == Where.PLACED) && spinLeft <= 0f) {
            dragKind = 2
            tableDragging = true
            yawTarget = null
            pitchHome = false
            JigLog.i("TABLE drag start yaw=${deg(yaw)}")
            return
        }
        dragKind = 0
    }

    fun dragMove(deltaContent: Vec3, wrist: Quat4?) {
        val delta = deltaContent / WORLD_SCALE
        when (dragKind) {
            1 -> {
                val v = held ?: return
                target = clampVolume(target + delta, v.half.length.let { Vec3(it, it, it) } * 0.8f)
                wristNow = wrist
            }
            2 -> {
                yaw += delta.x / TABLE_R
                if (displayMode) pitch = (pitch - delta.y / TABLE_R).coerceIn(-PI.toFloat() / 6, PI.toFloat() / 6)
            }
        }
    }

    fun dragEnd() {
        when (dragKind) {
            1 -> release()
            2 -> {
                tableDragging = false
                yawTarget = (yaw / (PI.toFloat() / 2)).roundToInt() * PI.toFloat() / 2
                pitchHome = displayMode
                JigLog.i("TABLE release yaw=${deg(yaw)} -> ${deg(yawTarget!!)}")
            }
        }
        dragKind = 0
    }

    fun tap(target: Entity?, playing: Boolean) {
        if (!playing) return
        val v = viewOf(target)
        if (v != null && (v.where == Where.LOOSE || v.where == Where.PARKED) && !v.busy) {
            select(if (selected == v) null else v)
        } else {
            select(null)
        }
    }

    private fun grab(v: PieceView, wrist: Quat4?) {
        select(null)
        // a timed hint ends when its piece is picked up (design S10 exits); the easy-mode guide keeps its ghost to aim at
        if (hintIdx == v.idx) {
            if (hintLeft < Float.MAX_VALUE / 2) endHint() else {
                focusLeft = 0f
                v.visual.setLocal(position = Vec3.ZERO)
                hintOutline.fit(v.half, 0.003f)
            }
        }
        if (v.where == Where.PARKED) {
            v.pos = toVolume(v.pos)
            v.rot = tableQuat() * v.rot
            v.rotIdx = Rot24.nearest(v.rot)
            reparent(v, false)
        }
        v.where = Where.HELD
        held = v
        target = v.pos
        handle = v.pos
        grabRot = v.rot
        wristStart = wrist
        wristNow = wrist
        stickYaw = 0f
        v.write()
        listener.onPicked(v.idx)
        listener.onHolding(v.idx)
        JigLog.i("GRAB idx=${v.idx} at=${JigLog.pos(v.pos)} rot=${v.rotIdx}")
    }

    private fun heldRot(): Quat4 {
        val wristDelta = if (wristFollow) {
            val s = wristStart
            val n = wristNow
            if (s != null && n != null) n * s.conjugate() else Quat4.IDENTITY
        } else Quat4.IDENTITY
        return (Quat4.axisAngle(UP, stickYaw) * wristDelta * grabRot).normalized()
    }

    private fun cancelHold() {
        val v = held ?: return
        v.where = Where.LOOSE
        v.pos = handle
        v.rotIdx = Rot24.nearest(heldRot())
        v.rot = Rot24.quat(v.rotIdx)
        v.write()
        held = null
        shadow?.enabled = false
        listener.onHolding(null)
    }

    /** Lets go of the held piece where it is (window lost focus): orientation snapped, stays loose (design lifecycle). */
    fun dropHeld() {
        if (held != null) {
            JigLog.i("RELEASE idx=${held?.idx} dropped (focus lost)")
            cancelHold()
        }
        if (tableDragging) dragEnd()
    }

    private fun release() {
        val v = held ?: return
        val p = puzzle ?: return
        held = null
        shadow?.enabled = false
        listener.onHolding(null)
        val volRot = Rot24.nearest(heldRot())
        val k = yawQuarters()
        val local = toTable(handle)
        val localRot = Rot24.toTable(volRot, k)
        val placed = views.filter { it.where == Where.PLACED }.map { it.idx }.toSet()
        val verdict = Rules.judge(p, v.idx, local, localRot, placed, easy)
        JigLog.i("RELEASE idx=${v.idx} vol=${JigLog.pos(handle)} table=${JigLog.pos(local)} rot=$volRot local=$localRot yaw=${k * 90} -> $verdict")
        v.pos = handle
        v.rot = heldRot()
        v.where = Where.LOOSE
        when (verdict) {
            is Release.Snap -> snapInto(v, localRot)
            is Release.WrongWay -> wrongWay(v, local, localRot)
            Release.Park -> parkAt(v, local, localRot, 0.25f)
            Release.Away -> {
                v.rotIdx = volRot
                if (inWings(handle) && views.none { it != v && it.where == Where.LOOSE && it.pos.distanceTo(handle) < 0.04f }) {
                    tweenTo(v, handle, Rot24.quat(volRot), 0.12f, false) { JigLog.i("PARK idx=${v.idx} stays in the wing") }
                } else flyHome(v)
            }
        }
    }

    private fun inWings(p: Vec3): Boolean = abs(p.x) in 0.16f..0.5f && p.y in 0.04f..0.52f

    /** Snaps [v] (turntable orientation [localRot]) into its home: 0.15 s ease, seam flash, sound (S08). */
    private fun snapInto(v: PieceView, localRot: Int) {
        val p = puzzle ?: return
        // anything resting on the spot goes back to the wings
        val homeVol = toVolume(p.homeM(v.idx))
        views.filter { it != v && (it.where == Where.LOOSE || it.where == Where.PARKED) && !it.busy && volumePos(it).distanceTo(homeVol) < 0.035f }.forEach {
            JigLog.i("PARK idx=${it.idx} pushed off the spot of ${v.idx}")
            flyHome(it)
        }
        if (selected == v) select(null)
        // tween in the table frame
        val fromLocal = toTable(v.pos)
        val fromRot = tableQuat().conjugate() * v.rot
        v.where = Where.PARKED
        reparent(v, true)
        v.pos = fromLocal
        v.rot = fromRot
        v.rotIdx = localRot
        v.write()
        v.busy = true
        val toPos = p.homeM(v.idx)
        val toRot = Rot24.quat(localRot)
        tweens += Tween(v, 0.15f, { t ->
            val e = easeOut(t)
            v.pos = fromLocal.lerp(toPos, e)
            v.rot = nlerp(fromRot, toRot, e)
            v.write()
        }) {
            v.busy = false
            setPlaced(v)
            flash(v)
            JigLog.i("SNAP idx=${v.idx} placed=${placedCount()}/${p.n}")
            listener.onPlaced(v.idx)
        }
    }

    private fun wrongWay(v: PieceView, local: Vec3, localRot: Int) {
        listener.onWrongWay(v.idx)
        val base = v.rot
        val at = v.pos
        v.busy = true
        tweens += Tween(v, 0.36f, { t ->
            v.rot = Quat4.axisAngle(UP, sin(t * 4 * PI.toFloat()) * 0.21f * (1 - t)) * base
            v.pos = at
            v.write()
        }) {
            v.busy = false
            parkAt(v, local, localRot, 0.25f)
        }
    }

    /** Parks [v] beside the landmark (turntable frame, turns with the table). */
    private fun parkAt(v: PieceView, local: Vec3, localRot: Int, dur: Float) {
        val p = puzzle ?: return
        val to = Rules.parkPosition(p, v.idx, local, localRot)
        val fromLocal = toTable(v.pos)
        val fromRot = tableQuat().conjugate() * v.rot
        v.where = Where.PARKED
        reparent(v, true)
        v.pos = fromLocal
        v.rot = fromRot
        v.rotIdx = localRot
        v.write()
        val toRot = Rot24.quat(localRot)
        v.busy = true
        tweens += Tween(v, dur, { t ->
            val e = easeOut(t)
            v.pos = fromLocal.lerp(to, e)
            v.rot = nlerp(fromRot, toRot, e)
            v.write()
        }) {
            v.busy = false
            v.pos = clampTableInVolume(to, v.halfInParent())
            v.write()
            JigLog.i("PARK idx=${v.idx} at=${JigLog.pos(v.pos)} rot=$localRot")
        }
    }

    /** Flies [v] back to its own wing spot in 0.4 s, keeping its (snapped) orientation. */
    private fun flyHome(v: PieceView) {
        val fromVol = volumePos(v)
        val fromRot = if (v.where == Where.PARKED) tableQuat() * v.rot else v.rot
        val rotVol = Rot24.nearest(fromRot)
        if (selected == v) select(null)
        v.where = Where.LOOSE
        reparent(v, false)
        v.rotIdx = rotVol
        v.pos = fromVol
        v.rot = fromRot
        tweenTo(v, v.wing, Rot24.quat(rotVol), 0.4f, true) { JigLog.i("PARK idx=${v.idx} flew home") }
    }

    private fun tweenTo(v: PieceView, to: Vec3, toRot: Quat4, dur: Float, arc: Boolean, done: () -> Unit) {
        val from = v.pos
        val fromRot = v.rot
        v.busy = true
        tweens += Tween(v, dur, { t ->
            val e = easeInOut(t)
            val lift = if (arc) sin(e * PI.toFloat()) * 0.05f else 0f
            v.pos = from.lerp(to, e) + Vec3(0f, lift, 0f)
            v.rot = nlerp(fromRot, toRot, e)
            v.write()
        }) {
            v.busy = false
            v.pos = to
            v.rot = toRot
            v.write()
            done()
        }
    }

    // ------------------------------------------------------------------------------------------
    // turn keys and the thumbstick on a resting piece

    fun select(v: PieceView?) {
        if (selected == v) return
        commitSelStick()
        selected = v
        if (v == null) {
            park(selOutline)
            hidePanel(PanelIds.KEYS)
        } else {
            v.holder.addChild(selOutline.root)
            selOutline.fit(v.half)
            selOutline.root.setLocal(position = Vec3.ZERO, rotation = Quat4.IDENTITY)
            selOutline.root.enabled = true
        }
        listener.onSelected(v?.idx)
        JigLog.i("STATE select idx=${v?.idx}")
    }

    /** Turn key: ↻ ([tip] = false) or ⤵ ([tip] = true), applied in volume space (design rules.turnKeys). */
    fun turnSelected(tip: Boolean) {
        val v = selected ?: return
        if (v.busy) return
        commitSelStick()
        val key = if (tip) Rot24.TIP_X else Rot24.TURN_Y
        val k = yawQuarters()
        val newIdx = if (v.where == Where.PARKED) Rot24.toTable(Rot24.compose(key, Rot24.toVolume(v.rotIdx, k)), k) else Rot24.compose(key, v.rotIdx)
        rotateResting(v, newIdx, "key ${if (tip) "tip" else "turn"}")
    }

    private fun rotateResting(v: PieceView, newIdx: Int, why: String) {
        val p = puzzle ?: return
        val from = v.rot
        val to = Rot24.quat(newIdx)
        v.rotIdx = newIdx
        v.busy = true
        tweens += Tween(v, 0.2f, { t ->
            v.rot = nlerp(from, to, easeInOut(t))
            v.write()
        }) {
            v.busy = false
            v.rot = to
            v.write()
            JigLog.i("TURN idx=${v.idx} $why -> rot=$newIdx where=${v.where}")
            // a parked piece turned the right way goes in by itself (design rules.wrongOrientation)
            if (v.where == Where.PARKED && newIdx in p.pieces[v.idx].allowed) {
                JigLog.i("SNAP idx=${v.idx} parked piece turned right")
                v.pos = toVolume(v.pos)
                v.rot = tableQuat() * v.rot
                v.where = Where.LOOSE
                reparent(v, false)
                v.write()
                snapInto(v, newIdx)
            } else if (v.where == Where.LOOSE) {
                v.pos = clampVolume(v.pos, v.halfInParent())
                v.write()
            }
        }
    }

    private fun commitSelStick() {
        val v = selected ?: return
        if (selStick == 0f) return
        selStick = 0f
        val idx = Rot24.nearest(v.rot)
        if (v.busy) return
        rotateResting(v, idx, "stick")
    }

    // ------------------------------------------------------------------------------------------
    // hints, peek, completion

    /**
     * Marks piece [idx]: a breathing gold frame on it and its gold ghost at the right place. [seconds] null keeps it
     * until the piece goes in (the easy-mode step guide); [focus] also bobs the piece for 4 s so the eye finds it
     * (user feedback 2026-10-23: "点击提示高亮那一块").
     */
    fun hint(idx: Int, seconds: Float? = 4f, focus: Boolean = false) {
        val p = puzzle ?: return
        val v = views.getOrNull(idx) ?: return
        if (v.where == Where.PLACED) return
        if (hintIdx != idx) {
            endHint()
            hintIdx = idx
            v.holder.addChild(hintOutline.root)
            slotGhost = model(v.mesh, mats.goldGhost, "ghost:${v.idx}", model).also { it.setLocal(position = p.homeM(idx)) }
        }
        hintLeft = if (seconds == null) Float.MAX_VALUE else seconds * Linger.k
        if (focus) {
            focusLeft = 4f * Linger.k
            if (selected != v && held != v && !v.busy) select(v)
        }
        hintOutline.fit(v.half, if (focus) 0.0045f else 0.003f)
        hintOutline.root.setLocal(position = Vec3.ZERO, rotation = Quat4.IDENTITY)
        hintOutline.root.enabled = true
        JigLog.i("STATE hint idx=$idx persistent=${seconds == null} focus=$focus")
    }

    fun endHint() {
        if (hintIdx == null) return
        views.getOrNull(hintIdx!!)?.visual?.setLocal(position = Vec3.ZERO)
        focusLeft = 0f
        hintIdx = null
        park(hintOutline)
        slotGhost?.destroy()
        slotGhost = null
    }

    val hinting: Boolean get() = hintIdx != null && hintLeft < Float.MAX_VALUE / 2

    val focusing: Boolean get() = focusLeft > 0f

    private var focusLeft = 0f

    /** Turn keys (false = ↻, true = ⤵) that bring piece [idx] to a right orientation from where it rests; null if held / placed. */
    fun keysToFit(idx: Int): List<Boolean>? {
        val p = puzzle ?: return null
        val v = views.getOrNull(idx) ?: return null
        if (v.where == Where.PLACED || v.where == Where.HELD) return null
        val k = yawQuarters()
        val volRot = if (v.where == Where.PARKED) Rot24.toVolume(v.rotIdx, k) else v.rotIdx
        return Rot24.keyPath(volRot, p.pieces[idx].allowed.map { Rot24.toVolume(it, k) }.toSet())
    }

    fun peek() {
        peekLeft = 3f * Linger.k
        peekGhost?.enabled = true
        JigLog.i("STATE peek")
    }

    val peeking: Boolean get() = peekLeft > 0f

    /** Last piece in: whole mesh, gold ring up the landmark 1.2 s, one slow turn 6 s, then the card (S15). */
    fun complete(animate: Boolean) {
        select(null)
        endHint()
        views.forEach { it.holder.enabled = false }
        whole?.enabled = true
        yawTarget = null
        if (!animate) {
            sweepT = -1f
            spinLeft = 0f
            listener.onCompleteShown()
            return
        }
        sweepT = 0f
        sweep?.enabled = true
        spinLeft = 2 * PI.toFloat()
        JigLog.i("STATE complete sweep")
    }

    /** Debug and auto-play: places [idx] through the same snap path as a real release. */
    fun autoPlace(idx: Int) {
        val v = views.getOrNull(idx) ?: return
        if (v.where == Where.PLACED || v.busy) return
        if (held == v) cancelHold()
        if (v.where == Where.PARKED) {
            v.pos = toVolume(v.pos)
            v.rot = tableQuat() * v.rot
            v.where = Where.LOOSE
            reparent(v, false)
        }
        snapInto(v, puzzle!!.pieces[idx].allowed.first())
    }

    /** Debug `place idx wrongOrient|elsewhere|ok`: a release at the home with that orientation, or far away. */
    fun debugRelease(idx: Int, mode: String) {
        val p = puzzle ?: return
        val v = views.getOrNull(idx) ?: return
        if (v.where == Where.PLACED || v.busy) return
        select(null)
        if (v.where == Where.PARKED) {
            v.pos = toVolume(v.pos); v.rot = tableQuat() * v.rot; v.where = Where.LOOSE; reparent(v, false)
        }
        val k = yawQuarters()
        val homeVol = toVolume(p.homeM(idx))
        val localRot = when (mode) {
            "wrongOrient" -> p.pieces[idx].scatterRot
            else -> p.pieces[idx].allowed.first()
        }
        v.where = Where.HELD
        held = v
        handle = if (mode == "elsewhere") Vec3(0f, 0.48f, 0.18f) else homeVol
        grabRot = Rot24.quat(Rot24.toVolume(localRot, k))
        wristStart = null
        wristNow = null
        stickYaw = 0f
        release()
    }

    /** Debug `hold idx`: picks a piece up and holds it over the turntable (S06). */
    fun debugHold(idx: Int) {
        val v = views.getOrNull(idx) ?: return
        if (v.where == Where.PLACED || v.busy) return
        grab(v, null)
        target = Vec3(0.02f, 0.42f, 0.04f)
        dragKind = 1
    }

    fun debugYaw(deg: Int) {
        yaw = Math.toRadians(deg.toDouble()).toFloat()
        yawTarget = null
        applyTable()
        listener.onTableTurned()
    }

    /** Entities whose box crosses the volume boundary (design acceptance: 0 in any rest state). */
    fun boundsCheck(): List<String> {
        val out = ArrayList<String>()
        for (v in views) {
            if (!v.holder.enabled) continue
            val c = volumePos(v)
            val volRot = if (v.where == Where.PARKED || v.where == Where.PLACED) Rot24.toVolume(v.rotIdx, yawQuarters()) else v.rotIdx
            val h = Rot24.extent(volRot, v.half * 2f) * 0.5f
            if (c.x - h.x < VOL_MIN.x - 1e-3f || c.x + h.x > VOL_MAX.x + 1e-3f || c.y - h.y < VOL_MIN.y - 1e-3f || c.y + h.y > VOL_MAX.y + 1e-3f || c.z - h.z < VOL_MIN.z - 1e-3f || c.z + h.z > VOL_MAX.z + 1e-3f) {
                out += "piece:${v.idx}@${JigLog.pos(c)}"
            }
        }
        val p = puzzle
        if (p != null && whole?.enabled == true) {
            val r = p.radius * p.voxelM
            if (TABLE.z - r < VOL_MIN.z || TABLE.z + r > VOL_MAX.z || TABLE_TOP + p.top * p.voxelM > VOL_MAX.y) out += "whole"
        }
        return out
    }

    // ------------------------------------------------------------------------------------------
    // attachment panels: shown in place or shrunk to nothing at the volume centre (kept enabled)

    fun showPanel(id: String, at: Vec3, rotation: Quat4 = Quat4.IDENTITY) {
        panels[id]?.setLocal(position = at * WORLD_SCALE + ROOT_OFFSET, rotation = rotation, scale = Vec3(WORLD_SCALE, WORLD_SCALE, WORLD_SCALE))
    }

    fun hidePanel(id: String) {
        panels[id]?.setLocal(position = Vec3(0f, 0.05f, -0.2f) * WORLD_SCALE + ROOT_OFFSET, scale = Vec3(0.001f, 0.001f, 0.001f))
    }

    /** Where the turn keys float: 3 cm above the selected piece, kept inside the volume. */
    fun keysAnchor(): Vec3? {
        val v = selected ?: return null
        val c = volumePos(v)
        val volRot = if (v.where == Where.PARKED) Rot24.toVolume(v.rotIdx, yawQuarters()) else v.rotIdx
        val h = Rot24.extent(volRot, v.half * 2f) * 0.5f
        return Vec3(c.x.coerceIn(-0.45f, 0.45f), min(c.y + h.y + 0.03f + 0.02f, 0.57f), (c.z + 0.02f).coerceIn(-0.2f, 0.23f))
    }

    // ------------------------------------------------------------------------------------------
    // the frame

    private fun frame(dt: Float) {
        frames++
        val now = SystemClock.elapsedRealtime()
        // tweens
        if (tweens.isNotEmpty()) {
            val list = ArrayList(tweens)
            for (tw in list) {
                tw.t = min(1f, tw.t + dt / tw.dur)
                tw.step(tw.t)
                if (tw.t >= 1f) {
                    tweens.remove(tw)
                    tw.done()
                }
            }
        }
        // collapse: 0.5 s standing, then 1.2 s of pieces tumbling into the wings
        if (collapseT >= 0f) collapseFrame(dt)
        // thumbstick
        val x = stick.x(now)
        val push = if (abs(x) < STICK_DEAD) 0f else sign(x) * ((abs(x) - STICK_DEAD) / (1 - STICK_DEAD)).let { it * it }
        // right push turns clockwise seen from above (−Y rotation), like the ↻ key
        val turn = -push * STICK_RATE * dt
        held?.let { v ->
            stickYaw += turn
            val step = target - handle
            handle = handle + step * (1f - exp(-dt / FOLLOW_TAU))
            v.pos = handle
            v.rot = heldRot()
            v.write()
            shadow?.let { s ->
                val d = Vec3(handle.x - TABLE.x, 0f, handle.z - TABLE.z).length
                val y = if (d < TABLE_R) TABLE_TOP + 0.0015f else 0.0015f
                s.setLocal(position = Vec3(handle.x, y, handle.z), scale = Vec3(0.06f, 1f, 0.06f))
                s.enabled = true
            }
        }
        val sel = selected
        if (held == null && sel != null && !sel.busy) {
            if (push != 0f) {
                if (selStick == 0f) selBase = sel.rot
                selStick += turn
                sel.rot = Quat4.axisAngle(UP, selStick) * selBase
                sel.write()
            } else if (selStick != 0f) commitSelStick()
        }
        // turntable
        if (spinLeft > 0f) {
            val step = min(spinLeft, (2 * PI.toFloat() / 6f) * dt)
            yaw += step
            spinLeft -= step
            if (spinLeft <= 0f) {
                yaw = (yaw / (PI.toFloat() / 2)).roundToInt() * PI.toFloat() / 2
                listener.onCompleteShown()
            }
        } else if (previewSpin) {
            yaw += (2 * PI.toFloat() / 12f) * dt
        } else if (!tableDragging) {
            yawTarget?.let { t ->
                val k = 1f - exp(-dt / 0.08f)
                yaw += (t - yaw) * k
                if (abs(t - yaw) < 0.002f) {
                    yaw = t
                    yawTarget = null
                    listener.onTableTurned()
                }
            }
            if (pitchHome) {
                pitch += (0f - pitch) * (1f - exp(-dt / 0.1f))
                if (abs(pitch) < 0.002f) {
                    pitch = 0f
                    pitchHome = false
                }
            }
        }
        applyTable()
        // sweep ring
        if (sweepT >= 0f) {
            val p = puzzle
            sweepT += dt / 1.2f
            if (p != null) sweep?.setLocal(position = Vec3(0f, min(sweepT, 1f) * p.top * p.voxelM, 0f))
            if (sweepT >= 1f) {
                sweepT = -1f
                sweep?.enabled = false
            }
        }
        // hint breathing, peek, seam flash
        if (hintIdx != null) {
            hintLeft -= dt
            hintOutline.root.setOpacity(0.45f + 0.55f * (0.5f + 0.5f * sin(now / 1000f * 2 * PI.toFloat() / 1.2f)))
            val hv = views.getOrNull(hintIdx!!)
            if (focusLeft > 0f) {
                focusLeft -= dt
                // the marked piece bobs 1.2 cm so it stands out of the pile; only its mesh moves, never its resting place
                val bob = if (focusLeft > 0f && hv != null && held != hv) 0.012f * (0.5f + 0.5f * sin(now / 1000f * 2 * PI.toFloat() / 0.8f)) else 0f
                hv?.visual?.setLocal(position = Vec3(0f, bob, 0f))
                if (focusLeft <= 0f) hintOutline.fit(hv?.half ?: Vec3.ZERO, 0.003f)
            }
            if (hintLeft <= 0f || hv?.where == Where.PLACED) endHint()
        }
        if (peekLeft > 0f) {
            peekLeft -= dt
            if (peekLeft <= 0f) peekGhost?.enabled = false
        }
        if (flashLeft > 0f) {
            flashLeft -= dt
            flashOutline.root.setOpacity((flashLeft / 0.35f).coerceIn(0.35f, 1f))
            if (flashLeft <= 0f) park(flashOutline)
        }
        // turn keys follow the selected piece
        keysAnchor()?.let { showPanel(PanelIds.KEYS, it) }
    }

    private fun collapseFrame(dt: Float) {
        val p = puzzle ?: return
        collapseT += dt / max(1f, Linger.k / 3f)
        if (collapseT < 0.5f) return
        if (whole?.enabled == true) {
            whole?.enabled = false
            collapsePlan.forEach { (v, _, _) ->
                v.pos = toVolume(p.homeM(v.idx))
                v.rot = Quat4.IDENTITY
                v.write()
                v.holder.enabled = true
            }
        }
        val t = ((collapseT - 0.5f) / 1.2f).coerceIn(0f, 1f)
        val n = collapsePlan.size
        collapsePlan.forEachIndexed { i, (v, to, q) ->
            // staggered by height: the top goes first
            val delay = 0.25f * (1f - p.pieces[v.idx].home.y / p.top)
            val s = ((t - delay) / (1f - 0.25f)).coerceIn(0f, 1f)
            val e = easeInOut(s)
            val from = toVolume(p.homeM(v.idx))
            val lift = sin(e * PI.toFloat()) * 0.06f
            v.pos = from.lerp(to, e) + Vec3(0f, lift, 0f)
            // tumble: an extra turn about a per-piece axis that fades out
            val axis = Vec3(sin(i * 1.7f), 0.6f, sin(i * 2.9f + 1f)).normalized()
            v.rot = Quat4.axisAngle(axis, (1f - e) * e * 6f) * nlerp(Quat4.IDENTITY, q, e)
            v.write()
        }
        if (t >= 1f) {
            collapseT = -1f
            collapsePlan.forEach { (v, to, q) -> v.pos = to; v.rot = q; v.write() }
            collapsePlan = emptyList()
            JigLog.i("STATE collapse done n=$n")
            listener.onCollapseDone()
        }
    }

    val collapsing: Boolean get() = collapseT >= 0f

    private fun applyTable() {
        table.setLocal(rotation = tableQuat())
    }

    private fun flash(v: PieceView) {
        v.holder.addChild(flashOutline.root)
        flashOutline.fit(v.half, 0.0018f)
        flashOutline.root.setLocal(position = Vec3.ZERO, rotation = Quat4.IDENTITY)
        flashOutline.root.enabled = true
        flashLeft = 0.35f * Linger.k
    }

    private fun clampVolume(p: Vec3, half: Vec3): Vec3 {
        val m = 0.01f
        return Vec3(
            p.x.coerceIn(VOL_MIN.x + half.x + m, VOL_MAX.x - half.x - m),
            p.y.coerceIn(VOL_MIN.y + half.y + m, VOL_MAX.y - half.y - m),
            p.z.coerceIn(VOL_MIN.z + half.z + m, VOL_MAX.z - half.z - m),
        )
    }

    /** Keeps a parked piece (table frame) inside the volume at the current table stop. */
    private fun clampTableInVolume(local: Vec3, halfLocal: Vec3): Vec3 {
        // the table turns: keep the radial distance small enough for every stop
        val maxR = min(VOL_MAX.z - TABLE.z, TABLE.z - VOL_MIN.z) - max(halfLocal.x, halfLocal.z) - 0.01f
        val r = sqrt(local.x * local.x + local.z * local.z)
        return if (r <= maxR || r < 1e-4f) local else Vec3(local.x * maxR / r, local.y, local.z * maxR / r)
    }

    fun close() {
        scope.cancel()
        runCatching { root.destroy() }
        views.clear()
        panels.clear()
        shared.close()
        content = null
        JigLog.i("SCENE closed")
    }

    fun dump(): String {
        val p = puzzle
        return "puzzle=${p?.key} yaw=${deg(yaw)} pitch=${deg(pitch)} held=${held?.idx} selected=${selected?.idx} hint=$hintIdx peek=${peekLeft > 0f} " +
            "collapse=${collapseT >= 0f} placed=${placedCount()} parked=${views.count { it.where == Where.PARKED }} loose=${views.count { it.where == Where.LOOSE }} " +
            "frames=$frames stick=${stick.status()}"
    }

    private fun deg(r: Float) = (r * 180f / PI.toFloat()).roundToInt()
}

internal fun easeOut(t: Float): Float = 1f - (1f - t) * (1f - t) * (1f - t)

internal fun easeInOut(t: Float): Float = if (t < 0.5f) 4 * t * t * t else 1 - ((-2 * t + 2).let { it * it * it }) / 2

internal fun nlerp(a: Quat4, b: Quat4, t: Float): Quat4 {
    val d = a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w
    val s = if (d < 0) -1f else 1f
    return Quat4(a.x + (b.x * s - a.x) * t, a.y + (b.y * s - a.y) * t, a.z + (b.z * s - a.z) * t, a.w + (b.w * s - a.w) * t).normalized()
}

/** Debug `linger on`: timed effects (hint, peek, seam flash, messages) last [K] times longer so a screenshot can catch them. */
object Linger {
    @Volatile var k = 1f
}

object PanelIds {
    const val HOME = "home"
    const val TOP = "topbar"
    const val KEYS = "keys"
    const val CARD = "card"
    const val PILL = "pill"
    val ALL = listOf(HOME, TOP, KEYS, CARD, PILL)
}
