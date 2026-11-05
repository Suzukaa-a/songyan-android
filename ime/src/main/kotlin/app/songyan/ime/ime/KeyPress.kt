package app.songyan.ime.ime

import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutKey
import app.songyan.ime.layout.LayoutOption
import app.songyan.ime.layout.LayoutPageTarget

/**
 * Stable names of the physical slots.
 *
 * A press must survive relabels (shift, composition, footer balancing) yet must not be routed into
 * a different key after a page or layout switch. The name is the key's action - or the rail word -
 * plus its occurrence ordinal, so both properties hold.
 */
internal object KeyIdentity {
    fun list(
        keys: List<LayoutKey>,
        railIndices: Set<Int>,
        labels: List<String>,
    ): List<String> {
        val seen = HashMap<String, Int>(keys.size * 2)
        val result = ArrayList<String>(keys.size)
        for (index in keys.indices) {
            val base =
                if (index in railIndices) {
                    "rail:" + labels.getOrNull(index).orEmpty()
                } else {
                    "key:" + signature(keys[index].action)
                }
            val ordinal = seen[base] ?: 0
            seen[base] = ordinal + 1
            result += base + "#" + ordinal
        }
        return result
    }

    fun index(identities: List<String>): Map<String, Int> {
        val map = HashMap<String, Int>(identities.size * 2)
        for (index in identities.indices) map[identities[index]] = index
        return map
    }

    private fun signature(action: LayoutAction): String =
        action.type.name + ":" + (action.value ?: "") + ":" + (action.asciiValue ?: "")
}

/**
 * What one finger pressed, captured at ACTION_DOWN.
 *
 * The layout can be rebuilt while the finger is still down (composition state, rail choices,
 * candidate redraws); every field here is what the release must use, never a fresh index into the
 * rebuilt key list.
 */
internal data class KeyPressSnapshot(
    val identity: String,
    val action: LayoutAction,
    val railLabel: String?,
    val longPress: List<LayoutOption>,
    val swipeCodes: List<String>?,
    /** The small character drawn in the key's corner; a flick types it when a long-press option matches. */
    val hint: String? = null,
    /** The slot of a rail choice among the rail's keys, top to bottom; -1 for any other key. */
    val railPosition: Int = -1,
    /** Whether this rail choice is already applied (drawn as chosen, not tappable). */
    val railApplied: Boolean = false,
    val t9FirstKey: Boolean = false,
) {
    val isBackspace: Boolean get() = action.type == LayoutActionKind.BACKSPACE

    val isSpace: Boolean get() = action.type == LayoutActionKind.SPACE

    val isSymbolPanelKey: Boolean
        get() =
            action.type == LayoutActionKind.PAGE_SWITCH &&
                action.pageSwitchTarget == LayoutPageTarget.SYMBOLS
}

/** Which vertical flick types the hint drawn on a key; the stored values are the settings' own. */
enum class FlickDirection(
    val wire: String,
) {
    OFF("off"),
    UP("up"),
    DOWN("down"),
    ;

    companion object {
        fun resolve(stored: String?): FlickDirection = entries.firstOrNull { it.wire == stored } ?: UP
    }
}

/**
 * What a vertical flick on a character key types: only what the key shows in its corner, so the
 * gesture never types something the user cannot see. Pure so the rules are testable without a view.
 */
internal object KeyFlick {
    /**
     * The flick's option, or null when the key has no flick: the long-press option whose label is
     * the drawn hint. A hint without a matching option (a layout's decorative label) types nothing.
     *
     * @param digit the digit a nine-key digit key shows and types, which needs no long-press option
     */
    fun target(
        snapshot: KeyPressSnapshot,
        direction: FlickDirection,
        digit: String?,
    ): LayoutOption? {
        if (!eligible(snapshot, direction)) return null
        if (digit != null) return LayoutOption(digit, LayoutAction.literal(digit))
        val hint = snapshot.hint?.takeIf { it.isNotEmpty() } ?: return null
        return snapshot.longPress.firstOrNull { it.label == hint }
    }

    /** Whether a vertical flick on this key is a flick at all, whether or not it has something to type. */
    fun eligible(
        snapshot: KeyPressSnapshot,
        direction: FlickDirection,
    ): Boolean {
        if (direction == FlickDirection.OFF) return false
        if (snapshot.railLabel != null || snapshot.railPosition >= 0) return false
        val type = snapshot.action.type
        return type == LayoutActionKind.CODE || type == LayoutActionKind.LITERAL
    }

    /**
     * How far a finger [travel]ling the flick's way has turned the key into its hint: nothing within
     * the touch [slop], all of it at the [threshold] that arms the flick.
     */
    fun progress(
        travel: Float,
        slop: Float,
        threshold: Float,
    ): Float = ((travel - slop) / (threshold - slop).coerceAtLeast(1f)).coerceIn(0f, 1f)
}

/**
 * The drawn progress of a key turning into what a gesture will type (a flick's hint, a merged key's
 * swiped letter), shared by the keycap and the bubble above it.
 *
 * The finger sets where it should be; the drawing eases there over [DURATION_MS], so the jump when
 * a flick arms (to 1) or is pulled back (to the finger's spot) is a short glide, not a cut. Only the
 * key a finger is flicking has a morph; nothing allocates per frame.
 */
internal class FlickMorph {
    /** The key being flicked, or -1. */
    var key: Int = -1
        private set

    /** The way the finger goes, one of -1, 0, 1 per axis: the label leaves that way, the target comes in behind it. */
    var towardX: Int = 0
        private set
    var towardY: Int = -1
        private set

    private var from = 0f
    private var to = 0f
    private var startedAt = 0L

    fun set(
        key: Int,
        towardX: Int,
        towardY: Int,
        target: Float,
        now: Long,
        motionScale: Float,
    ) {
        if (key != this.key) {
            this.key = key
            from = 0f
            to = 0f
            startedAt = now
        }
        this.towardX = towardX
        this.towardY = towardY
        if (target == to) return
        from = value(now, motionScale)
        to = target
        startedAt = now
    }

    fun clear() {
        key = -1
        from = 0f
        to = 0f
    }

    /** The eased progress at [now]; the system's animator scale 0 shows the target at once. */
    fun value(
        now: Long,
        motionScale: Float,
    ): Float {
        val duration = DURATION_MS * motionScale
        if (duration <= 0f) return to
        val t = ((now - startedAt) / duration).coerceIn(0f, 1f)
        val eased = 1f - (1f - t) * (1f - t)
        return from + (to - from) * eased
    }

    fun animating(
        now: Long,
        motionScale: Float,
    ): Boolean = key >= 0 && value(now, motionScale) != to

    companion object {
        const val DURATION_MS = 90f
    }
}

/**
 * The one tick an accepted press produces.
 *
 * It fires synchronously with ACTION_DOWN (not with the release or with a commit), so a held key
 * that opens a menu, a popup or the repeat loop has ticked exactly once when it opened. A rail
 * choice is engine-backed and has no layout action of its own, so it ticks as its own word.
 */
internal object PressFeedback {
    fun tick(snapshot: KeyPressSnapshot): LayoutAction {
        val rail = snapshot.railLabel
        return if (rail != null) LayoutAction.literal(rail) else snapshot.action
    }
}

/**
 * Which keys a drifted finger may still activate on release.
 *
 * A press that slid off its key used to recover only onto character keys, so punctuation on the
 * 123/symbol pages (and the space bar) silently swallowed the gesture. Recovery uses the same slop
 * rules for every key that types something.
 */
internal object SlideRecovery {
    fun accepts(action: LayoutAction?): Boolean =
        when (action?.type) {
            LayoutActionKind.CODE, LayoutActionKind.LITERAL, LayoutActionKind.SPACE -> true
            else -> false
        }
}
