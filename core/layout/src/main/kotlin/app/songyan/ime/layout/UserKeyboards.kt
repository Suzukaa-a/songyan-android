package app.songyan.ime.layout

import app.songyan.ime.keyboard.engine.InputLayoutPolicy
import app.songyan.ime.keyboard.engine.LayoutFamilyContext
import app.songyan.ime.keyboard.engine.PublishedKeyboard
import app.songyan.ime.keyboard.engine.SchemaKeyProfile
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** One input method of the running package, with the shapes its engine data can run. */
data class KeyboardScheme(
    val id: String,
    val shapes: Set<KeyboardLayoutKind>,
    /** What its speller needs; null while unknown (a build published before profiles). */
    val profile: SchemaKeyProfile? = null,
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

    /**
     * [stored] with exactly [keyboards] for [scheme] (possibly none), spoken for from now on; the other
     * input methods keep what they had. The install picker writes a new package's choice this way.
     */
    fun setting(
        scheme: String,
        keyboards: List<PublishedKeyboard>,
        stored: Stored,
    ): Stored =
        Stored(
            stored.keyboards.filter { it.schemaID != scheme } +
                keyboards.filter { it.schemaID == scheme }.distinctBy { it.id },
            stored.schemes + scheme,
        )

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

/**
 * Which layouts an input method can be given a keyboard on. Every place that offers a keyboard to
 * add asks here, so the rule lives in one function.
 */
object ApplicableLayouts {
    /** A keyboard that could be added, with what it cannot send and whether its layout is yet to be made. */
    data class Entry(
        val keyboard: PublishedKeyboard,
        /** The codes the scheme needs that the layout has no key for, in the scheme's order. */
        val missingCodes: List<String> = emptyList(),
        /** Set for a layout made from the key profile; it is installed into the catalog when added. */
        val generated: Generated? = null,
    )

    enum class Generated { KEYS26, STROKES }

    /** The keyboards [scheme] could have; see [entries]. */
    fun of(
        scheme: KeyboardScheme,
        registry: LayoutRegistry,
        families: LayoutFamilyContext,
    ): List<PublishedKeyboard> = entries(scheme, registry, families).map { it.keyboard }

    /**
     * The keyboards [scheme] could have, each naming its layout, in the order they are offered: per
     * shape its engine data can run (folded shapes only for syllabic schemes), on the identity shape
     * the layouts the key profile asks for first (the stroke pad; the 26-key board with the missing
     * codes when plain 26 lacks them, which a syllabic scheme only gets as an extra choice), then the
     * physical boards of its family and the user's layouts that fit the input method and shape.
     */
    fun entries(
        scheme: KeyboardScheme,
        registry: LayoutRegistry,
        families: LayoutFamilyContext,
    ): List<Entry> {
        val boards = InputLayoutPolicy.boardsOf(families.familyOf(scheme.id))
        val profile = scheme.profile
        return KeyboardLayoutKind.entries
            .filter {
                it in scheme.shapes &&
                    (it == KeyboardLayoutKind.QWERTY26 || profile?.allowsFolding != false) &&
                    InputLayoutPolicy.allows(it, families.familyOf(scheme.id))
            }.flatMap { kind ->
                val shape = PublishedKeyboard(scheme.id, kind)
                val candidates = registry.mainLayoutCandidates(shape.compositionKey(), families).map { it.id }
                val ordered = boards.filter { it in candidates } + candidates.filter { it !in boards }
                val entries = ordered.map { id -> entry(shape.copy(board = id), registry, profile, kind) }
                // A family with physical boards of its own keeps them; nothing is generated beside them.
                if (kind == KeyboardLayoutKind.QWERTY26 && profile != null && boards.isEmpty()) {
                    withGenerated(scheme.id, shape, entries, profile)
                } else {
                    entries
                }
            }
    }

    private fun entry(
        keyboard: PublishedKeyboard,
        registry: LayoutRegistry,
        profile: SchemaKeyProfile?,
        kind: KeyboardLayoutKind,
    ): Entry {
        val layout = keyboard.board?.let(registry::resolvedLayout)
        val missing =
            if (profile == null || layout == null || kind != KeyboardLayoutKind.QWERTY26) {
                emptyList()
            } else {
                LayoutCodeCoverage.missing(layout, profile)
            }
        val generated =
            when (keyboard.board) {
                GeneratedLayouts.keys26Id(keyboard.schemaID) -> Generated.KEYS26
                GeneratedLayouts.strokesId(keyboard.schemaID) -> Generated.STROKES
                else -> null
            }
        return Entry(keyboard, if (generated == null) missing else emptyList(), generated)
    }

    private fun withGenerated(
        schemeId: String,
        shape: PublishedKeyboard,
        entries: List<Entry>,
        profile: SchemaKeyProfile,
    ): List<Entry> {
        if (profile.kind == SchemaKeyProfile.Kind.UNKNOWN) return entries
        val plain = BuiltinLayouts.qwerty26
        val plainMissing = LayoutCodeCoverage.missing(plain, profile)
        val strokes = profile.kind == SchemaKeyProfile.Kind.STROKES && profile.strokes.isNotEmpty()
        val keys26 = plainMissing.isNotEmpty()

        fun generated(
            id: String,
            kind: Generated,
        ): Entry? =
            entries.firstOrNull { it.keyboard.board == id }?.copy(generated = kind)
                ?: Entry(shape.copy(board = id), emptyList(), kind)
        val keys26Entry = if (keys26) generated(GeneratedLayouts.keys26Id(schemeId), Generated.KEYS26) else null
        val strokesEntry = if (strokes) generated(GeneratedLayouts.strokesId(schemeId), Generated.STROKES) else null
        val own = setOfNotNull(keys26Entry, strokesEntry).mapTo(HashSet()) { it.keyboard.board }
        val rest = entries.filter { it.keyboard.board !in own }
        return when {
            strokesEntry != null -> listOfNotNull(strokesEntry) + rest + listOfNotNull(keys26Entry)
            profile.kind == SchemaKeyProfile.Kind.SHAPE_CODE -> listOfNotNull(keys26Entry) + rest
            else -> rest + listOfNotNull(keys26Entry)
        }
    }
}
