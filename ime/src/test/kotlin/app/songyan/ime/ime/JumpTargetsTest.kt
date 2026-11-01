package app.songyan.ime.ime

import app.songyan.ime.keyboard.engine.KeyboardSchemaDescriptor
import app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import org.junit.Assert.assertEquals
import org.junit.Test

class JumpTargetsTest {
    private fun schema(id: String) = KeyboardSchemaDescriptor(id = id, name = id)

    private val set =
        requireNotNull(
            KeyboardResourceSets.forEngineSchemas(listOf(schema(BuiltinTemplateSchemas.QUANPIN), schema("rime_ice"))),
        )

    @Test
    fun aBundledInputMethodMayJumpToEveryLayout() {
        assertEquals(
            KeyboardLayoutKind.entries.toSet(),
            JumpTargets.usable(KeyboardLayoutKind.QWERTY26, set, BuiltinTemplateSchemas.QUANPIN),
        )
    }

    @Test
    fun anInputMethodWithoutA9KeyLayoutCannotJumpThere() {
        // An imported scheme with no compact companion runs on 26 keys only, as the keyboard menu's
        // switch would find: a jump to 9 键 is not offered rather than refused after the tap.
        assertEquals(
            setOf(KeyboardLayoutKind.QWERTY26),
            JumpTargets.usable(KeyboardLayoutKind.QWERTY26, set, "rime_ice"),
        )
    }

    @Test
    fun theRunningLayoutIsAlwaysATargetAndNoResourceSetLimitsNothing() {
        assertEquals(
            setOf(KeyboardLayoutKind.QWERTY26, KeyboardLayoutKind.PHONE9),
            JumpTargets.usable(KeyboardLayoutKind.PHONE9, set, "rime_ice"),
        )
        assertEquals(
            KeyboardLayoutKind.entries.toSet(),
            JumpTargets.usable(KeyboardLayoutKind.QWERTY26, null, "rime_ice"),
        )
    }
}
