package io.embodit.voxeljigsaw.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import io.embodit.voxeljigsaw.platform.JigLog
import io.embodit.voxeljigsaw.scene.JigsawScene

/**
 * QA hooks (design verification.debugHooks), registered in debug builds only:
 *
 *   adb shell am broadcast -a io.embodit.voxeljigsaw.DEBUG -p io.embodit.voxeljigsaw --es cmd <name> [--es s '<args>']
 *
 * seed <landmark> <25|50> <placedCount> · solveNext · solve · place <idx|next> <ok|wrongOrient|elsewhere> ·
 * hold <idx|next> · select <idx|next> · turn <y|x> · yaw <0|90|180|270> · state <S01..S18> · locale <zh|en|follow> ·
 * corruptSave · boundsCheck · linger <on|off> · stick <x>,<ms> · hint · peek · menu · home · settings · display · card · count <25|50> ·
 * landmark <id> · start · dump · resetAll. Every command logs `CMD <name> ...` and its result.
 */
class DebugCommands(private val g: GameController) : BroadcastReceiver() {
    private val main = Handler(Looper.getMainLooper())

    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        val arg = intent.getStringExtra("s") ?: ""
        main.post { runCatching { run(cmd, arg.trim()) }.onFailure { JigLog.e("CMD $cmd failed: $it", it) } }
    }

    private val scene get() = g.scene

    private fun pieceArg(a: String): Int? {
        val p = scene.puzzle ?: return null
        if (a.isEmpty() || a == "next") return p.order.firstOrNull { scene.views[it].where != JigsawScene.Where.PLACED }
        return a.toIntOrNull()
    }

    private fun later(ms: Long, block: () -> Unit) = main.postDelayed({ runCatching(block).onFailure { JigLog.e("CMD later failed: $it", it) } }, ms)

    fun run(cmd: String, a: String) {
        JigLog.i("CMD $cmd $a")
        val parts = a.split(Regex("[ ,]+")).filter { it.isNotEmpty() }
        when (cmd) {
            "seed" -> g.debugSeed(parts.getOrElse(0) { "qinian" }, parts.getOrElse(1) { "25" }.toInt(), parts.getOrElse(2) { "0" }.toInt())
            "solveNext" -> g.debugSolveNext()
            "solve" -> g.debugSolveAll()
            "place" -> pieceArg(parts.getOrElse(0) { "next" })?.let { scene.debugRelease(it, parts.getOrElse(1) { "ok" }) }
            "hold" -> pieceArg(parts.getOrElse(0) { "next" })?.let { scene.debugHold(it) }
            "select" -> pieceArg(parts.getOrElse(0) { "next" })?.let { idx -> scene.select(scene.views[idx]) }
            "turn" -> scene.turnSelected(parts.getOrElse(0) { "y" } == "x")
            "yaw" -> scene.debugYaw(parts.getOrElse(0) { "0" }.toInt())
            "locale" -> g.debugLocale(parts.getOrElse(0) { "follow" })
            "corruptSave" -> g.debugCorruptSave()
            "boundsCheck" -> scene.boundsCheck().let { JigLog.i("BOUNDS outside=${it.size} ${it.joinToString(" ")}") }
            "stick" -> g.stick.debugHold(parts.getOrElse(0) { "0.8" }.toFloat(), parts.getOrElse(1) { "1500" }.toLong())
            "linger" -> io.embodit.voxeljigsaw.scene.Linger.k = if (parts.getOrElse(0) { "on" } == "on") 15f else 1f
            "hint" -> g.hint()
            "peek" -> g.peek()
            "menu" -> g.openMenu()
            "home" -> g.goHome()
            "settings" -> g.openSettings()
            "display" -> g.showDisplay()
            "card" -> g.showCard()
            "count" -> g.selectCount(parts.getOrElse(0) { "25" }.toInt())
            "landmark" -> g.selectLandmark(parts.getOrElse(0) { "qinian" })
            "start" -> g.startOrResume()
            "dump" -> JigLog.i("DUMP state=${g.s.designState} screen=${g.s.screen} overlay=${g.s.overlay} key=${g.s.key} placed=${g.s.placed}/${g.s.total} hints=${g.s.hints} t=${g.s.elapsedSec} msg=${g.s.message?.text} ${scene.dump()}")
            "resetAll" -> {
                g.store.clearAll()
                g.boot()
            }
            "state" -> state(parts.getOrElse(0) { "S05" }.uppercase())
            else -> JigLog.w("CMD unknown $cmd")
        }
    }

    /** Puts the app into a named design state for a screenshot (design states[].qaCapture). */
    private fun state(id: String) {
        val seed = { n: Int -> g.debugSeed("qinian", 25, n) }
        when (id) {
            "S01" -> g.boot()
            "S02" -> {
                g.selectLandmark("qinian"); g.selectCount(25)
                g.store.delete("qinian-25")
                g.goHome()
            }
            "S03" -> {
                seed(3)
                later(300) { g.goHome() }
            }
            "S04" -> {
                g.selectLandmark("qinian"); g.selectCount(25)
                g.store.delete("qinian-25")
                g.startNew()
            }
            "S05" -> {
                seed(0)
                later(200) { g.debugTeach() }
            }
            "S06" -> {
                seed(0)
                later(300) { pieceArg("next")?.let { scene.debugHold(it) } }
            }
            "S07" -> {
                seed(0)
                later(300) { pieceArg("next")?.let { scene.select(scene.views[it]) } }
            }
            "S08" -> {
                seed(0)
                later(300) { g.debugSolveNext() }
            }
            "S09" -> {
                seed(0)
                later(300) { pieceArg("next")?.let { scene.debugRelease(it, "wrongOrient") } }
            }
            "S10" -> {
                seed(0)
                later(300) { g.hint() }
            }
            "S11" -> {
                seed(0)
                later(300) { g.peek() }
            }
            "S12" -> {
                // everything before the last piece of the first segment to finish in assembly order
                g.debugSeed("qinian", 25, 0)
                val p = scene.puzzle ?: return
                val k = (0 until 3).minOf { seg -> p.piecesOfSegment(seg).maxOf { p.order.indexOf(it) } }
                g.debugSeed("qinian", 25, k)
                later(300) { g.debugSolveNext() }
            }
            "S13" -> {
                seed(5)
                later(300) { g.openMenu() }
            }
            "S14" -> {
                seed(5)
                later(300) { g.askRestart() }
            }
            "S15" -> {
                seed(24)
                later(300) { g.debugSolveNext() }
            }
            "S16" -> {
                seed(24)
                later(300) { g.debugSolveNext() }
                later(8500) { g.showDisplay() }
            }
            "S17" -> {
                g.goHome()
                later(200) { g.openSettings() }
            }
            "S18" -> {
                seed(3)
                later(300) {
                    g.goHome()
                    g.debugCorruptSave()
                    g.startOrResume()
                }
            }
            else -> JigLog.w("CMD state unknown $id")
        }
    }
}
