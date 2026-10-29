package io.embodit.voxeljigsaw.ui

import io.embodit.voxeljigsaw.domain.Landmarks
import io.embodit.voxeljigsaw.domain.Record

enum class Screen { LOADING, HOME, PLAY, COMPLETE, DISPLAY, ERROR }

/** What covers the main content of the top bar / home panel (design menu, confirm strip, settings). */
enum class Overlay { MENU, CONFIRM, SETTINGS }

enum class MessageKind { TEACH, WRONG, FACT }

data class Message(val kind: MessageKind, val text: String, val serial: Long)

data class UiState(
    val screen: Screen = Screen.LOADING,
    val overlay: Overlay? = null,
    val lang: Lang = Lang.ZH,
    val langSetting: String = "follow",
    val sound: Boolean = true,
    val wrist: Boolean = true,
    val easy: Boolean = true,
    /** Step guide: which step comes next (1-based), and the turn keys that would right its piece (null: none needed / unknown). */
    val step: Int = 0,
    val stepKeys: List<Boolean>? = null,
    val landmark: String = "qinian",
    /** 0 = level 1 (8–10 pieces), 1 = level 2 (16); the piece count depends on the landmark (Landmarks.LEVELS). */
    val level: Int = 0,
    val records: Map<String, Record> = emptyMap(),
    /** Placed count of a readable save, per "<landmark>-<n>". */
    val saves: Map<String, Int> = emptyMap(),
    val placed: Int = 0,
    val total: Int = 8,
    val message: Message? = null,
    val hints: Int = 0,
    val elapsedSec: Int = 0,
    val hintOn: Boolean = false,
    val peekOn: Boolean = false,
    val selected: Int? = null,
    val holding: Int? = null,
    val factsSeen: List<Int> = emptyList(),
    val errorReason: String? = null,
    val saveLost: Boolean = false,
    val loading: Float = 0f,
    val cardShown: Boolean = false,
    val pillOpen: Boolean = false,
    val collapsing: Boolean = false,
    /** Design state id (S01–S18) for logs and QA. */
    val designState: String = "S01",
) {
    val count: Int get() = Landmarks.count(landmark, level)
    val key: String get() = "$landmark-$count"
    val strings: Strings get() = if (lang == Lang.ZH) ZH else EN

    companion object {
        private val ZH = Strings(Lang.ZH)
        private val EN = Strings(Lang.EN)
    }
}
