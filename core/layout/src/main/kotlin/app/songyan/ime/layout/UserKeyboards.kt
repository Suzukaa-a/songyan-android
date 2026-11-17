package app.songyan.ime.layout

import app.songyan.ime.keyboard.engine.InputLayoutPolicy
import app.songyan.ime.keyboard.engine.LayoutFamilyContext
import app.songyan.ime.keyboard.engine.PublishedKeyboard
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** One input method of the running package, with the shapes its engine data can run. */
data class KeyboardScheme(
    val id: String,
    val shapes: Set<KeyboardLayoutKind>,
)

/** The pairing key of a keyboard: its input method, its shape and the layout it names, if any. */
fun PublishedKeyboard.compositionKey(): KeyboardCompositionKey =
    KeyboardCompositionKey(
        schemaID,
        LayoutInput.Builtin.fromWire(layout.wire) ?: LayoutInput.Builtin.QWERTY26,
        board,
    )

/** The shape a layout's keys are encoded for; a layout bound only to a scheme types one letter per key. */
val LayoutDefinition.shape: KeyboardLayoutKind
    get() = input.builtinLayout?.let { KeyboardLayoutKind.fromWire(it.wire) } ?: KeyboardLayoutKind.QWERTY26

/**
 * The keyboards the menu offered before there was a list of them: every input method the user had
 * not hidden, on every layout the user had not hidden. The list is derived from those two sets each
 * time, so a user who never edits their keyboards keeps exactly the menu they had.
 */
object LegacyKeyboards {
    /**
     * [current] is the keyboard in use; it stays even when hidden, as the old menu kept it. Every
     * keyboard returned names its layout: the main layout of the shape's pairing, or one of the
     * physical boards of the scheme's family, which shared the identity shape's slot.
     */
    fun of(
        schemes: List<KeyboardScheme>,
        hiddenSchemas: Set<String>,
        hiddenLayouts: Set<String>,
        registry: LayoutRegistry,
        families: LayoutFamilyContext,
        current: PublishedKeyboard? = null,
    ): List<PublishedKeyboard> =
        schemes
            .filter { it.id !in hiddenSchemas || it.id == current?.schemaID }
            .flatMap { scheme ->
                val inUse = current?.takeIf { it.schemaID == scheme.id }
                ofScheme(scheme, hiddenLayouts, registry, families, inUse)
            }

    private fun ofScheme(
        scheme: KeyboardScheme,
        hiddenLayouts: Set<String>,
        registry: LayoutRegistry,
        families: LayoutFamilyContext,
        inUse: PublishedKeyboard?,
    ): List<PublishedKeyboard> {
        val boards = InputLayoutPolicy.boardsOf(families.familyOf(scheme.id))
        val drawn = inUse?.let { UserKeyboards.named(it, registry, families).board }
        val result = ArrayList<PublishedKeyboard>()
        for (kind in KeyboardLayoutKind.entries) {
            val running = inUse?.layout == kind
            if (!running && (kind.wire in hiddenLayouts || kind !in scheme.shapes)) continue
            val main = UserKeyboards.named(PublishedKeyboard(scheme.id, kind), registry, families).board
            if (kind != KeyboardLayoutKind.QWERTY26 || boards.isEmpty()) {
                result += PublishedKeyboard(scheme.id, kind, main)
                continue
            }
            for (board in boards) {
                if (board in hiddenLayouts && !(running && board == drawn)) continue
                result += PublishedKeyboard(scheme.id, kind, board)
            }
            // A main layout that is none of the boards (the user's own) kept a chip of the shape.
            if (main != null && main !in boards) result += PublishedKeyboard(scheme.id, kind, main)
        }
        return result
    }
}

/**
 * The user's keyboards: the stored list for the input methods the user has edited, and the derived
 * one ([LegacyKeyboards]) for every other. Pure logic; the preferences own the two stored values.
 */
object UserKeyboards {
    /** The stored list and the input methods it speaks for, as the preferences keep them. */
    data class Stored(
        val keyboards: List<PublishedKeyboard> = emptyList(),
        /** Input methods whose keyboards are exactly the stored ones, possibly none. */
        val schemes: Set<String> = emptySet(),
    )

    /**
     * The keyboards in menu order: input methods as [schemes] lists them, each with its stored
     * keyboards when the user has edited them and its [legacy] ones otherwise.
     */
    fun effective(
        stored: Stored,
        schemes: List<String>,
        legacy: List<PublishedKeyboard>,
    ): List<PublishedKeyboard> =
        schemes.distinct().flatMap { scheme ->
            val source = if (scheme in stored.schemes) stored.keyboards else legacy
            source.filter { it.schemaID == scheme }.distinctBy { it.id }
        }

    /** [stored] speaking for [scheme] too, starting from what the menu showed for it. */
    private fun covering(
        stored: Stored,
        scheme: String,
        legacy: List<PublishedKeyboard>,
    ): Stored {
        if (scheme in stored.schemes) return stored
        val kept = stored.keyboards.filter { it.schemaID != scheme }
        return Stored(kept + legacy.filter { it.schemaID == scheme }.distinctBy { it.id }, stored.schemes + scheme)
    }

    /** Adds [keyboard] after the other keyboards of its input method; adding twice changes nothing. */
    fun adding(
        keyboard: PublishedKeyboard,
        stored: Stored,
        legacy: List<PublishedKeyboard>,
    ): Stored {
        val base = covering(stored, keyboard.schemaID, legacy)
        if (base.keyboards.any { it.id == keyboard.id }) return base
        return base.copy(keyboards = base.keyboards + keyboard)
    }

    fun removing(
        keyboard: PublishedKeyboard,
        stored: Stored,
        legacy: List<PublishedKeyboard>,
    ): Stored {
        val base = covering(stored, keyboard.schemaID, legacy)
        return base.copy(keyboards = base.keyboards.filter { it.id != keyboard.id })
    }

    /**
     * [keyboard] with its layout named. A record without one reads as it always did: the main layout
     * of its shape's pairing, which falls back to the family's board and then the shape's own layout.
     * A named layout that can no longer be drawn reads the same way.
     */
    fun named(
        keyboard: PublishedKeyboard,
        registry: LayoutRegistry,
        families: LayoutFamilyContext,
    ): PublishedKeyboard = keyboard.copy(board = registry.composition(keyboard.compositionKey(), families).mainLayoutID)

    /** Whether [keyboard] draws the layout it names: the layout exists and fits the input method and shape. */
    fun drawsItsLayout(
        keyboard: PublishedKeyboard,
        registry: LayoutRegistry,
        families: LayoutFamilyContext,
    ): Boolean = keyboard.board == null || named(keyboard, registry, families).board == keyboard.board

    /**
     * The keyboards of [all] the engine can run now: the input method is listed with that shape and
     * the layout can be drawn. The others stay in the list for when their data is ready again.
     */
    fun runnable(
        all: List<PublishedKeyboard>,
        schemes: List<KeyboardScheme>,
        registry: LayoutRegistry,
        families: LayoutFamilyContext,
    ): List<PublishedKeyboard> {
        val shapes = schemes.associate { it.id to it.shapes }
        return all.filter { it.layout in shapes[it.schemaID].orEmpty() && drawsItsLayout(it, registry, families) }
    }

    /**
     * The keyboard an input method chip switches to: the one with the layout in use, then one of the
     * shape in use, then the input method's first.
     */
    fun switchTarget(
        candidates: List<PublishedKeyboard>,
        current: PublishedKeyboard,
    ): PublishedKeyboard? =
        candidates.firstOrNull { it.board != null && it.board == current.board && it.layout == current.layout }
            ?: candidates.firstOrNull { it.layout == current.layout }
            ?: candidates.firstOrNull()

    /** The keyboard after [current] among [candidates], wrapping around; null with nothing else to go to. */
    fun next(
        candidates: List<PublishedKeyboard>,
        current: PublishedKeyboard,
    ): PublishedKeyboard? {
        if (candidates.none { it.id != current.id }) return null
        val at = candidates.indexOfFirst { it.id == current.id }
        return candidates[(at + 1) % candidates.size]
    }

    /** The layouts the keyboards name, for the check that keeps a layout in use from being deleted. */
    fun layoutIds(keyboards: List<PublishedKeyboard>): Set<String> =
        keyboards.mapNotNullTo(LinkedHashSet()) { it.board }

    private val json = Json { ignoreUnknownKeys = true }
    private val ids = ListSerializer(String.serializer())

    /** The stored list: a JSON array of keyboard ids, in order. */
    fun encode(keyboards: List<PublishedKeyboard>): String = json.encodeToString(ids, keyboards.map { it.id })

    /** Ids that no longer parse are dropped; a value that is not a list reads as no keyboards. */
    fun decode(text: String?): List<PublishedKeyboard> {
        if (text.isNullOrEmpty()) return emptyList()
        val raw =
            try {
                json.decodeFromString(ids, text)
            } catch (_: IllegalArgumentException) {
                return emptyList()
            }
        return raw.mapNotNull { PublishedKeyboard.parse(it) }.distinctBy { it.id }
    }
}
