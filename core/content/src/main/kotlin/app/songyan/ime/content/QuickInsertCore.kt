package app.songyan.ime.content

import app.songyan.ime.keyboard.engine.InputLanguage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The fixed set of date/time quick-insert formats.
 *
 * [id] matches the iOS raw value, so a preference or a backup written on either platform is
 * understood by the other. Titles are UI copy and live in `strings.xml`; this model never
 * carries user-visible text. The formats are computed locally on tap: no schema script, no Lua
 * component and no dictionary change is involved.
 *
 * The shape of each format belongs to [QuickInsertFormatting], because it follows the input
 * language of the active scheme rather than the app or system language.
 */
enum class QuickInsertDateFormat(
    val id: String,
) {
    /** 2026-09-09 */
    DATE("date"),

    /** 2026年9月9日; Japanese writes the weekday too, 2026年11月5日(木) */
    DATE_CHINESE("date-chinese"),

    /** 14:30, 24-hour without seconds */
    TIME("time"),

    /** 2026-09-09 14:30 */
    DATE_TIME("date-time"),

    /** 2026年9月9日 14:30; Japanese writes the weekday too */
    DATE_TIME_CHINESE("date-time-chinese"),
    ;

    companion object {
        /** The format stored under [id], or null for an id this build does not know. */
        fun fromId(id: String): QuickInsertDateFormat? = entries.firstOrNull { it.id == id }
    }
}

/** Date/time text computation, pure: the zone, the instant and the input language are passed in. */
object QuickInsertFormatting {
    /**
     * Formats [instant] with [format], using a stable numbering system and a 24-hour clock
     * regardless of the system region, so the inserted text matches the copies in the docs.
     *
     * Only the input language decides the shape. Japanese writes the weekday after the
     * Chinese-style dates (`2026年11月5日(木)`); traditional and simplified Chinese share one
     * wording because year, month and day are written identically in both scripts.
     */
    fun text(
        format: QuickInsertDateFormat,
        instant: Instant,
        zone: ZoneId,
        language: InputLanguage = InputLanguage.ZH_HANS,
    ): String {
        val (pattern, locale) =
            when (language) {
                InputLanguage.ZH_HANS, InputLanguage.ZH_HANT -> CHINESE_PATTERNS.getValue(format) to Locale.ROOT
                InputLanguage.JA -> JAPANESE_PATTERNS.getValue(format) to Locale.JAPAN
            }
        return DateTimeFormatter.ofPattern(pattern, locale).withZone(zone).format(instant)
    }

    private val CHINESE_PATTERNS =
        mapOf(
            QuickInsertDateFormat.DATE to "yyyy-MM-dd",
            QuickInsertDateFormat.DATE_CHINESE to "yyyy'年'M'月'd'日'",
            QuickInsertDateFormat.TIME to "HH:mm",
            QuickInsertDateFormat.DATE_TIME to "yyyy-MM-dd HH:mm",
            QuickInsertDateFormat.DATE_TIME_CHINESE to "yyyy'年'M'月'd'日' HH:mm",
        )

    private val JAPANESE_PATTERNS =
        mapOf(
            QuickInsertDateFormat.DATE to "yyyy-MM-dd",
            QuickInsertDateFormat.DATE_CHINESE to "yyyy'年'M'月'd'日'(E)",
            QuickInsertDateFormat.TIME to "HH:mm",
            QuickInsertDateFormat.DATE_TIME to "yyyy-MM-dd HH:mm",
            QuickInsertDateFormat.DATE_TIME_CHINESE to "yyyy'年'M'月'd'日'(E) HH:mm",
        )
}

/**
 * Persistence of the format the user picked last.
 *
 * Only the format id is stored, never any inserted text. The Android implementation lives in the
 * settings layer over DataStore; values must already be warm when the keyboard needs them, and
 * writes may be asynchronous there, so this interface stays synchronous and tiny.
 */
interface QuickInsertFormatPreferences {
    fun read(): QuickInsertDateFormat?

    fun write(format: QuickInsertDateFormat)
}

/** A [QuickInsertFormatPreferences] for tests and for keyboards that cannot persist. */
class InMemoryQuickInsertFormatPreferences(
    initial: QuickInsertDateFormat? = null,
) : QuickInsertFormatPreferences {
    private var value: QuickInsertDateFormat? = initial

    override fun read(): QuickInsertDateFormat? = value

    override fun write(format: QuickInsertDateFormat) {
        value = format
    }
}

/**
 * The last non-empty committed text, cached in memory for the current input session only.
 *
 * The caller resets it on focus loss or host switch. User input is never persisted, and neither
 * the clipboard nor the host text is read to seed it.
 */
class QuickInsertRecentText {
    /** The text a repeat would insert, or null when there is none. */
    var lastCommittedText: String? = null
        private set

    /** Whether a repeat is possible right now. */
    val hasRepeatableText: Boolean
        get() = lastCommittedText != null

    /** Single-line short preview for the toolbar; a repeat still inserts the full original text. */
    val repeatPreview: String?
        get() = preview(lastCommittedText)

    /**
     * Records a commit that reached the host. Whitespace-only commits (a lone space or newline)
     * are ignored and leave the existing repeat target alone.
     */
    fun recordCommitted(text: String) {
        if (!hasVisibleContent(text)) return
        lastCommittedText = text
    }

    /** Clears the session repeat target on focus loss, host switch or field change. */
    fun resetContext() {
        lastCommittedText = null
    }

    companion object {
        /** Whether [text] has anything worth repeating. */
        fun hasVisibleContent(text: String): Boolean = text.trim { it.isWhitespace() }.isNotEmpty()

        /**
         * Collapses any committed text into a one-line preview of at most [maxCharacters]
         * characters; multi-line commits stay readable as a single line with a trailing ellipsis
         * when shortened.
         */
        fun preview(
            text: String?,
            maxCharacters: Int = 24,
        ): String? {
            if (text == null) return null
            val collapsed = collapseWhitespace(text)
            if (collapsed.isEmpty()) return null
            val length = collapsed.codePointCount(0, collapsed.length)
            if (length <= maxCharacters) return collapsed
            val keep = maxOf(1, maxCharacters - 1)
            return collapsed.substring(0, collapsed.offsetByCodePoints(0, keep)) + "\u2026"
        }

        /** Trims and collapses every whitespace run to one space, so a multi-line commit reads on one line. */
        private fun collapseWhitespace(text: String): String {
            val builder = StringBuilder(text.length)
            var pendingSpace = false
            for (char in text) {
                if (char.isWhitespace()) {
                    pendingSpace = builder.isNotEmpty()
                } else {
                    if (pendingSpace) {
                        builder.append(' ')
                        pendingSpace = false
                    }
                    builder.append(char)
                }
            }
            return builder.toString()
        }
    }
}

/**
 * Quick insert: repeat the last commit, or insert the current date/time.
 *
 * Pure logic with injected effects (`now`, [zone], [confirmComposition], [insert], [onChange]),
 * so it runs in JVM tests with a fixed instant and zone while production wires the real
 * keyboard. Only the single commit exit of the host may call [recordCommitted], and only an
 * accepted insert may become a repeat target.
 *
 * Repeat ordering: the target is captured first, then the current composition is confirmed with
 * the existing semantics, then the captured target is inserted once. Capturing first keeps a
 * confirm callback that records the just-confirmed word from polluting the target.
 */
class QuickInsertCore(
    private val preferences: QuickInsertFormatPreferences = InMemoryQuickInsertFormatPreferences(),
    private val now: () -> Instant = Instant::now,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val confirmComposition: () -> Boolean = { true },
    private val insert: (String) -> Boolean = { true },
    private val onChange: () -> Unit = {},
    /**
     * The input language of the active scheme, read at the moment a date is inserted.
     *
     * The caller resolves and caches it off the key path; a tap is the only time this value is
     * needed, so nothing is recomputed per keystroke. The fallback keeps simplified Chinese, which
     * is what an unknown or not-yet-ready scheme shows today.
     */
    private val inputLanguage: () -> InputLanguage = { InputLanguage.ZH_HANS },
) {
    /** The session's repeat cache. */
    val recent: QuickInsertRecentText = QuickInsertRecentText()

    /** The format the toolbar preselects; the last one that was actually inserted. */
    var selectedFormat: QuickInsertDateFormat = preferences.read() ?: QuickInsertDateFormat.DATE
        private set

    /** The text a repeat would insert, or null. */
    val lastCommittedText: String?
        get() = recent.lastCommittedText

    val hasRepeatableText: Boolean
        get() = recent.hasRepeatableText

    val repeatPreview: String?
        get() = recent.repeatPreview

    /**
     * Records a commit that reached the host; call it from the single commit exit so Chinese
     * candidates, direct English and multi-line literals all become repeatable.
     */
    fun recordCommitted(text: String) {
        recent.recordCommitted(text)
        onChange()
    }

    /** Focus loss / host switch / field change: drops the repeat target but keeps the format. */
    fun resetContext() {
        recent.resetContext()
        onChange()
    }

    /**
     * Repeats the last commit: captures the target, confirms the composition, then inserts the
     * target once more.
     *
     * Returns false without inserting when there is nothing to repeat or the confirm refuses.
     */
    fun performRepeat(): Boolean {
        val target = recent.lastCommittedText ?: return false
        return deliverAfterConfirmingComposition(target)
    }

    /**
     * Inserts the date/time for [format] computed at the moment of the tap (so a panel left open
     * across midnight still inserts today) and remembers the format after a successful insert.
     */
    fun insertDateFormat(format: QuickInsertDateFormat): Boolean {
        val text = formattedText(format)
        if (!deliverAfterConfirmingComposition(text)) return false
        remember(format)
        return true
    }

    /** The text [format] inserts right now; computed fresh before it is displayed. */
    fun formattedText(format: QuickInsertDateFormat): String =
        QuickInsertFormatting.text(format, now(), zone, inputLanguage())

    /** Preselects [format] without inserting, for a settings row; persists it. */
    fun selectFormat(format: QuickInsertDateFormat) {
        remember(format)
    }

    private fun deliverAfterConfirmingComposition(text: String): Boolean {
        if (!confirmComposition()) return false
        if (!insert(text)) return false
        // Only an accepted insert may become a repeat target.
        recent.recordCommitted(text)
        onChange()
        return true
    }

    private fun remember(format: QuickInsertDateFormat) {
        if (format == selectedFormat) return
        selectedFormat = format
        preferences.write(format)
        onChange()
    }

    companion object {
        /** Preference key of the selected format, shared with the settings layer. */
        const val PREFERENCE_KEY: String = "quickInsert.selectedDateFormat"
    }
}
