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
    /** The slot of a rail choice among the rail's keys, top to bottom; -1 for any other key. */
    val railPosition: Int = -1,
    /** Whether this rail choice is already applied (drawn as chosen, not tappable). */
    val railApplied: Boolean = false,
) {
    val isBackspace: Boolean get() = action.type == LayoutActionKind.BACKSPACE

    val isSpace: Boolean get() = action.type == LayoutActionKind.SPACE

    val isSymbolPanelKey: Boolean
        get() =
            action.type == LayoutActionKind.PAGE_SWITCH &&
                action.pageSwitchTarget == LayoutPageTarget.SYMBOLS
}

/**
 * Which long-press option a vertical flick on a character key types: the first for up, the second
 * for down. Pure so the rules are testable without a view.
 */
internal object KeyFlick {
    /**
     * @param digitFlick the key is a nine-key digit key, which keeps its own upward flick
     * @param merged the key is a merged key whose sideways swipes pick letters
     */
    fun option(
        snapshot: KeyPressSnapshot,
        up: Boolean,
        enabled: Boolean,
        digitFlick: Boolean,
        merged: Boolean,
    ): LayoutOption? {
        if (!eligible(snapshot, enabled, merged) || digitFlick) return null
        return snapshot.longPress.getOrNull(if (up) 0 else 1)
    }

    /** Whether a vertical flick on this key is a flick at all, whether or not it has an option that way. */
    fun eligible(
        snapshot: KeyPressSnapshot,
        enabled: Boolean,
        merged: Boolean,
    ): Boolean {
        if (!enabled || merged || snapshot.railLabel != null || snapshot.railPosition >= 0) return false
        val type = snapshot.action.type
        return type == LayoutActionKind.CODE || type == LayoutActionKind.LITERAL
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
