package app.songyan.ime.ime

import app.songyan.ime.keyboard.engine.KeyboardResourceSet
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import java.util.EnumSet

/** Which keyboard layouts a jump key may switch to (see [KeyboardController.jumpToPage]). */
internal object JumpTargets {
    /**
     * The running layout, and every other one [schemaId] can be used with in [set]: the same pairing
     * rule the session applies when the keyboard menu switches layouts, checked before the key is drawn
     * so a jump that would be refused is not offered. Without a resource set the keyboard menu offers
     * every layout, and so does this.
     */
    fun usable(
        current: KeyboardLayoutKind,
        set: KeyboardResourceSet?,
        schemaId: String,
    ): Set<KeyboardLayoutKind> =
        KeyboardLayoutKind.entries.filterTo(EnumSet.noneOf(KeyboardLayoutKind::class.java)) {
            it == current || set == null || set.incompatibilityReason(it, schemaId) == null
        }

    /** What [usable] answered for these inputs. */
    class Memo(
        val current: KeyboardLayoutKind,
        val set: KeyboardResourceSet?,
        val schemaId: String,
        val layouts: Set<KeyboardLayoutKind>,
    )
}
