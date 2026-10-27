package io.embodit.voxeljigsaw.data

import android.content.Context
import io.embodit.voxeljigsaw.domain.Record
import io.embodit.voxeljigsaw.domain.SaveGame
import io.embodit.voxeljigsaw.platform.JigLog
import java.io.File
import org.json.JSONObject

/** Result of reading one save: none, a good one, or one that could not be parsed (design S18). */
sealed interface SaveRead {
    data object None : SaveRead

    data class Ok(val save: SaveGame) : SaveRead

    data class Broken(val reason: String) : SaveRead
}

/**
 * App-private JSON files (design dataModel.storage, fully offline): saves/<landmark>-<n>.json, records.json,
 * and settings in SharedPreferences. Writes go through a temp file and a rename.
 */
class SaveStore(context: Context) {
    private val dir = File(context.filesDir, "saves").apply { mkdirs() }
    private val recordsFile = File(context.filesDir, "records.json")
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun file(key: String) = File(dir, "$key.json")

    fun read(key: String, n: Int): SaveRead {
        val f = file(key)
        if (!f.exists()) return SaveRead.None
        return try {
            SaveRead.Ok(SaveGame.parse(f.readText(), n))
        } catch (t: Throwable) {
            JigLog.w("SAVE broken key=$key: $t")
            SaveRead.Broken(t.message ?: t.javaClass.simpleName)
        }
    }

    /** Placed count of a readable save, for the home cards; null when there is none. */
    fun progress(key: String, n: Int): Int? = (read(key, n) as? SaveRead.Ok)?.save?.placed?.size

    fun write(key: String, save: SaveGame) {
        val tmp = File(dir, "$key.json.tmp")
        tmp.writeText(save.toJson())
        if (!tmp.renameTo(file(key))) {
            file(key).delete()
            tmp.renameTo(file(key))
        }
    }

    fun delete(key: String) {
        file(key).delete()
    }

    /** Debug: overwrite a save with garbage (QA S18). */
    fun corrupt(key: String) {
        file(key).writeText("{\"n\": broken")
    }

    fun records(): Map<String, Record> =
        try {
            if (!recordsFile.exists()) emptyMap()
            else {
                val o = JSONObject(recordsFile.readText())
                o.keys().asSequence().associateWith { k -> o.getJSONObject(k).let { Record(it.getBoolean("completed"), it.getInt("bestSec"), it.getInt("hintsAtBest")) } }
            }
        } catch (t: Throwable) {
            JigLog.w("SAVE records unreadable: $t")
            emptyMap()
        }

    fun writeRecords(records: Map<String, Record>) {
        val o = JSONObject()
        records.forEach { (k, r) -> o.put(k, JSONObject().put("completed", r.completed).put("bestSec", r.bestSec).put("hintsAtBest", r.hintsAtBest)) }
        recordsFile.writeText(o.toString())
    }

    var language: String
        get() = prefs.getString("lang", "follow") ?: "follow"
        set(v) = prefs.edit().putString("lang", v).apply()

    var sound: Boolean
        get() = prefs.getBoolean("sound", true)
        set(v) = prefs.edit().putBoolean("sound", v).apply()

    /** Easy mode (default on, user feedback 2026-10-23): step guide always on, wider snap, pieces turn themselves right. */
    var easy: Boolean
        get() = prefs.getBoolean("easy", true)
        set(v) = prefs.edit().putBoolean("easy", v).apply()

    var wrist: Boolean
        get() = prefs.getBoolean("wrist", true)
        set(v) = prefs.edit().putBoolean("wrist", v).apply()

    /** The three teaching lines have been shown once (design rules.firstRun). */
    var taught: Boolean
        get() = prefs.getBoolean("taught", false)
        set(v) = prefs.edit().putBoolean("taught", v).apply()

    fun clearAll() {
        dir.listFiles()?.forEach { it.delete() }
        recordsFile.delete()
        prefs.edit().clear().apply()
    }
}
