package app.songyan.ime.ime

/** What a toolbar chip offers to paste. */
enum class ToolbarChipKind {
    /** Text the user copied a moment ago. */
    COPIED,

    /** A one-time code found in copied text (for example an SMS the user copied). */
    CODE,
}

/**
 * A tappable suggestion shown in the idle toolbar position. [text] is exactly what a tap commits;
 * [clipTimestamp] identifies the clip it came from so one clip is offered once.
 */
data class ToolbarChip(
    val kind: ToolbarChipKind,
    val text: String,
    val clipTimestamp: Long,
    val expiresAt: Long,
) {
    /** The chip's visible text: whitespace collapsed to one line and cut to [maxChars]. */
    fun label(maxChars: Int = LABEL_MAX_CHARS): String {
        val oneLine = text.trim().replace(WHITESPACE, " ")
        if (oneLine.length <= maxChars) return oneLine
        // Never cut a surrogate pair in half.
        var end = maxChars - 1
        if (end > 0 && Character.isHighSurrogate(oneLine[end - 1])) end--
        return oneLine.substring(0, end).trimEnd() + "…"
    }

    companion object {
        const val LABEL_MAX_CHARS = 40
        private val WHITESPACE = Regex("\\s+")
    }
}

/** What the policy can tell about a clip from its description alone, without reading the content. */
data class ClipObservation(
    val available: Boolean,
    val textMimeType: Boolean,
    /** The host flagged the clip with `EXTRA_IS_SENSITIVE`; its content is never read. */
    val sensitive: Boolean,
    /** `ClipDescription.getTimestamp()` (epoch ms): when the clip was set. */
    val timestamp: Long,
)

/**
 * Decides when a copied-text chip may be read and shown. Free of Android types so the privacy and
 * expiry rules are unit-tested on the JVM; the controller maps clip descriptions onto
 * [ClipObservation] and supplies the clock.
 *
 * - Nothing is read while the field is private ([setAllowed] false) or for a sensitive clip.
 * - A chip lives [ttlMs] after the clip was set, until it is used or dismissed, whichever is first.
 * - A clip that was used or dismissed is never offered again, even if the listener fires twice.
 */
class ToolbarChipPolicy(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxChars: Int = MAX_CHARS,
) {
    private var allowed = false
    private var current: ToolbarChip? = null
    private var settled = NONE
    private var rejected = NONE

    /** Mirrors "the keyboard is showing a field that is not private". Leaving one drops the chip. */
    fun setAllowed(value: Boolean) {
        allowed = value
        if (!value) current = null
    }

    /**
     * Looks at a clip change or the clip present when the keyboard shows. True when the clip's text
     * should now be read and handed to [offer]. A different clip always drops the chip of the older one.
     */
    fun shouldRead(
        observation: ClipObservation,
        now: Long,
    ): Boolean {
        if (observation.timestamp != current?.clipTimestamp) current = null
        return when {
            !allowed -> false
            !observation.available || observation.timestamp <= 0L -> false
            observation.sensitive || !observation.textMimeType -> false
            observation.timestamp == settled || observation.timestamp == rejected -> false
            observation.timestamp == current?.clipTimestamp -> false
            now - observation.timestamp > ttlMs -> false
            else -> true
        }
    }

    /** Takes the text of a clip [shouldRead] approved; null (and the clip is skipped) when it is no use. */
    fun offer(
        observation: ClipObservation,
        text: String?,
        now: Long,
    ): ToolbarChip? {
        val trimmed = text?.trim().orEmpty()
        if (!allowed || trimmed.isEmpty() || trimmed.length > maxChars) {
            rejected = observation.timestamp
            return null
        }
        val code = ClipCodeRule.extract(trimmed)
        // A clock that runs behind the clip's stamp must not stretch the lifetime.
        val start = minOf(observation.timestamp, now)
        val chip =
            ToolbarChip(
                kind = if (code != null) ToolbarChipKind.CODE else ToolbarChipKind.COPIED,
                text = code ?: trimmed,
                clipTimestamp = observation.timestamp,
                expiresAt = start + ttlMs,
            )
        current = chip
        return chip
    }

    /** The chip to show now; an expired one is dropped on the way. */
    fun current(now: Long): ToolbarChip? {
        val chip = current ?: return null
        if (now >= chip.expiresAt) {
            current = null
            return null
        }
        return chip
    }

    /** The chip was pasted: this clip is not offered again. */
    fun used() = settle()

    /** The user closed the chip: this clip is not offered again. */
    fun dismiss() = settle()

    private fun settle() {
        current?.let { settled = it.clipTimestamp }
        current = null
    }

    companion object {
        const val DEFAULT_TTL_MS = 60_000L

        /** The same bound as a clipboard history entry (4 KB of text). */
        const val MAX_CHARS = 4_096
        private const val NONE = Long.MIN_VALUE
    }
}

/**
 * Picks a one-time code out of copied text, only when the text clearly is a code message.
 *
 * The rule, in full: the text is at most [MAX_TEXT] characters, contains a code keyword (Chinese:
 * 验证码 校验码 动态码 动态密码 确认码 安全码 and the traditional forms; English: verification, security
 * code, one-time, OTP, passcode, login code, code, PIN), and has exactly one distinct standalone run of
 * 4 to 8 ASCII digits. "Standalone" means no letter, digit, or number punctuation touches it, so phone
 * numbers, amounts, dates, versions and order numbers are not taken. Anything else is plain text.
 */
object ClipCodeRule {
    const val MAX_TEXT = 300

    private val KEYWORD =
        Regex(
            "验证码|驗證碼|校验码|校驗碼|动态码|動態碼|动态密码|動態密碼|确认码|確認碼|安全码|安全碼|" +
                "verification|security code|one[- ]?time|\\botp\\b|passcode|login code|\\bcode\\b|\\bpin\\b",
            RegexOption.IGNORE_CASE,
        )
    private val DIGITS = Regex("(?<![0-9A-Za-z.,+\\-/:$¥￥])[0-9]{4,8}(?![0-9A-Za-z]|[.,\\-/:%]\\d)")

    fun extract(text: String): String? {
        if (text.length > MAX_TEXT || !KEYWORD.containsMatchIn(text)) return null
        val found = DIGITS.findAll(text).map { it.value }.toSet()
        return found.singleOrNull()
    }
}

/** What the idle top band shows; chosen in one place so the views never disagree. */
enum class BandContent {
    CANDIDATES,
    INLINE_SUGGESTIONS,
    CHIP,
    TOOLBAR,
    ;

    companion object {
        /**
         * Candidates win while something is composed or noticed. Idle, the system's autofill
         * suggestions come first (they are for this very field), then the copied-text chip, then the
         * regular toolbar.
         */
        fun of(
            idle: Boolean,
            hasInlineSuggestions: Boolean,
            hasChip: Boolean,
        ): BandContent =
            when {
                !idle -> CANDIDATES
                hasInlineSuggestions -> INLINE_SUGGESTIONS
                hasChip -> CHIP
                else -> TOOLBAR
            }
    }
}

/** Where the chip and its close button sit in a toolbar of the given size; all values in pixels. */
data class ToolbarChipGeometry(
    val chipLeft: Int,
    val chipRight: Int,
    val dismissLeft: Int,
    val dismissRight: Int,
    val height: Int,
) {
    /** 0 for the chip, 1 for the close button, -1 for neither. */
    fun hit(
        x: Float,
        y: Float,
    ): Int =
        when {
            y < 0f || y >= height -> -1
            x >= chipLeft && x < chipRight -> 0
            x >= dismissLeft && x < dismissRight -> 1
            else -> -1
        }

    companion object {
        /**
         * The chip hugs its text ([contentWidth] includes its own padding and icon), sits in the
         * middle of the bar and never takes more than half of it; the close button is a full touch
         * target at the trailing edge, where the autofill band keeps its own.
         */
        fun of(
            width: Int,
            height: Int,
            contentWidth: Int,
            marginPx: Int,
            dismissPx: Int,
        ): ToolbarChipGeometry {
            val chip = contentWidth.coerceIn(0, maxWidth(width, marginPx, dismissPx))
            val left = (width - chip) / 2
            val dismissRight = width - marginPx
            return ToolbarChipGeometry(left, left + chip, dismissRight - dismissPx, dismissRight, height)
        }

        /** The widest a centred chip may be: half the bar, and clear of the close button on either side. */
        fun maxWidth(
            width: Int,
            marginPx: Int,
            dismissPx: Int,
        ): Int = minOf(width / 2, width - 2 * (marginPx + dismissPx)).coerceAtLeast(0)
    }
}
