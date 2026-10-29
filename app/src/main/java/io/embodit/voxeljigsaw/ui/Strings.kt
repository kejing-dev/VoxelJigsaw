package io.embodit.voxeljigsaw.ui

import android.content.Context
import org.json.JSONObject

enum class Lang { ZH, EN }

/** UI copy in both languages (design i18n; terminology: 地标、块、拼上、转台、提示、全貌 — never 体素 / 槽位). */
class Strings(val lang: Lang) {
    private val zh = lang == Lang.ZH

    val loading = if (zh) "正在搬砖…" else "Loading bricks…"
    val start = if (zh) "开始" else "Start"
    val resume = if (zh) "继续" else "Resume"
    val restart = if (zh) "重新开始" else "Restart"
    fun pieces(n: Int) = if (zh) "$n 块" else "$n pieces"
    val hint = if (zh) "提示" else "Hint"
    val peek = if (zh) "全貌" else "Peek"
    val more = if (zh) "更多" else "More"
    val settings = if (zh) "设置" else "Settings"
    val home = if (zh) "回首页" else "Home"
    val homeShort = if (zh) "首页" else "Home"
    val cancel = if (zh) "取消" else "Cancel"
    val ok = if (zh) "确定" else "Restart"
    val display = if (zh) "摆着看" else "Display"
    val next = if (zh) "下一座" else "Next landmark"
    val intro = if (zh) "介绍" else "About"
    val back = if (zh) "返回" else "Back"
    val wrong = if (zh) "位置对了，转一转" else "Right spot, turn it around"
    val teach = if (zh) listOf("捏住一块，拖到发光的地方", "点一下块，可以转方向", "拖已经拼好的部分，可以转台子")
    else listOf("Pinch a piece and drag it to the glow", "Tap a piece to turn it", "Drag the built part to spin the turntable")
    /** Step line (user feedback 2026-10-23: a hint for every piece, every step). */
    fun step(n: Int, total: Int) = if (zh) "第 $n / $total 步" else "Step $n of $total"
    val stepEasy = if (zh) "把发光的块拖到金色虚影，松手自动摆正" else "Drag the glowing piece onto the gold ghost; it turns itself right"
    val stepTurn = if (zh) "点发光的块，按" else "Tap the glowing piece, press"
    val stepThenDrag = if (zh) "转对方向，再拖到金色虚影" else "to turn it, then drag it onto the gold ghost"
    val stepReady = if (zh) "方向对了，拖到金色虚影" else "It faces the right way: drag it onto the gold ghost"
    val stepAsk = if (zh) "不知道下一块？点这里或提示键" else "Which piece next? Tap here or the hint key"
    val easyMode = if (zh) "简单模式" else "Easy mode"
    val easyNote = if (zh) "一步步标出下一块，放近了自动摆正" else "Marks every next piece; close enough snaps in the right way"
    val stickTip = if (zh) "摇杆左右转动" else "Thumbstick left/right turns the piece"
    fun confirm(name: String, n: Int) = if (zh) "重新开始$name $n 块？这一局的进度会清掉" else "Restart $name ($n pieces)? This puzzle's progress will be cleared."
    val seen = if (zh) "拼的时候看到的" else "Found while building"
    fun time(t: String, h: Int) = if (zh) "用时 $t · 提示 $h 次" else "Time $t · $h hints"
    val language = if (zh) "语言" else "Language"
    val follow = if (zh) "跟随系统" else "System"
    val sound = if (zh) "音效" else "Sound"
    val mute = if (zh) "静音" else "Mute"
    val unmute = if (zh) "打开声音" else "Sound on"
    val wrist = if (zh) "手腕跟转" else "Turn with wrist"
    val wristNote = if (zh) "关掉后拿起的块只移动不转，用转向键转" else "Off: a held piece only moves; turn it with the keys"
    val about = if (zh) "关于" else "About"
    fun aboutText(version: String) = if (zh) "体素拼图 $version · 地标资料来自公开百科（维基百科等），模型为原创体素" else "Voxel Jigsaw $version · Landmark facts from public encyclopedias (Wikipedia and others); original voxel models"
    val errTitle = if (zh) "这座地标没搭起来" else "This landmark didn't load"
    val errSave = if (zh) "这局的进度没能恢复" else "This puzzle's progress could not be restored"
    val retry = if (zh) "重试" else "Try again"
    val built = if (zh) "建成" else "Built"
    val height = if (zh) "高度" else "Height"
    val best = if (zh) "最好" else "Best"
    val turnY = if (zh) "横着转 90°" else "Turn 90°"
    val tipX = if (zh) "往前翻 90°" else "Tip forward 90°"
    val turnYShort = if (zh) "横转" else "Turn"
    val tipXShort = if (zh) "前翻" else "Tip"
    val done = if (zh) "已完成" else "Done"

    companion object {
        fun clock(sec: Int) = "${sec / 60}:${(sec % 60).toString().padStart(2, '0')}"
    }
}

/** Landmark copy from assets/landmarks.json, one language. */
data class LandmarkText(val name: String, val short: String, val place: String, val built: String, val height: String, val scale: String, val story: String, val segs: List<String>, val facts: List<String>)

class LandmarkBook(private val texts: Map<String, Map<Lang, LandmarkText>>) {
    fun of(id: String, lang: Lang): LandmarkText = texts.getValue(id).getValue(lang)

    companion object {
        fun load(context: Context): LandmarkBook {
            val root = JSONObject(context.assets.open("landmarks.json").bufferedReader().use { it.readText() }).getJSONObject("landmarks")
            val map = HashMap<String, Map<Lang, LandmarkText>>()
            for (id in root.keys()) {
                val o = root.getJSONObject(id)
                map[id] = mapOf(Lang.ZH to text(o.getJSONObject("zh")), Lang.EN to text(o.getJSONObject("en")))
            }
            return LandmarkBook(map)
        }

        private fun text(o: JSONObject): LandmarkText {
            fun list(k: String) = o.getJSONArray(k).let { a -> List(a.length()) { a.getString(it) } }
            return LandmarkText(o.getString("name"), o.getString("short"), o.getString("place"), o.getString("built"), o.getString("height"), o.getString("scale"), o.getString("story"), list("segs"), list("facts"))
        }
    }
}
