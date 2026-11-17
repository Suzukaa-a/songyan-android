package app.songyan.ime.settings.state

import app.songyan.ime.keyboard.engine.KeyboardResourceSet
import app.songyan.ime.keyboard.engine.PublishedKeyboard
import app.songyan.ime.keyboard.engine.SchemaKeyProfile
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.layout.ApplicableLayouts
import app.songyan.ime.layout.GeneratedLayouts
import app.songyan.ime.layout.KeyboardScheme
import app.songyan.ime.layout.LayoutCodeCoverage
import app.songyan.ime.layout.LayoutDefinition
import app.songyan.ime.layout.LayoutRegistry
import app.songyan.ime.layout.LegacyKeyboards
import app.songyan.ime.layout.UserKeyboards
import app.songyan.ime.settings.R

/** One keyboard as 我的键盘 lists it: what it is and what its layout is called. */
data class KeyboardRow(
    val keyboard: PublishedKeyboard,
    val layoutName: LayoutDisplayName,
    val inUse: Boolean = false,
    /** The codes the input method needs that this layout has no key for. */
    val missingCodes: List<String> = emptyList(),
    /** Set while the layout is yet to be made from the key profile; adding the keyboard installs it. */
    val generated: ApplicableLayouts.Generated? = null,
)

/** The keyboards of one input method, and the ones it could still be given. */
data class KeyboardGroup(
    val schemeId: String,
    val schemeName: String,
    val keyboards: List<KeyboardRow>,
    val addable: List<KeyboardRow>,
    /** True when the input method's speller could not be read: which codes it needs is unknown. */
    val unknownCodes: Boolean = false,
    val profile: SchemaKeyProfile? = null,
)

/**
 * 我的键盘: every input method of the input scheme in use with its keyboards. [stored] and [legacy]
 * are what an edit starts from ([UserKeyboards.adding] and [UserKeyboards.removing]).
 */
data class KeyboardList(
    val groups: List<KeyboardGroup> = emptyList(),
    /** The keyboard in use, its layout named; null while no input method is in use. */
    val current: PublishedKeyboard? = null,
    val stored: UserKeyboards.Stored = UserKeyboards.Stored(),
    val legacy: List<PublishedKeyboard> = emptyList(),
) {
    /** Every keyboard listed, in order. */
    val keyboards: List<KeyboardRow> get() = groups.flatMap { it.keyboards }

    /** The layouts the listed keyboards name; a layout among them cannot be deleted. */
    val layoutIds: Set<String> get() = UserKeyboards.layoutIds(keyboards.map { it.keyboard })
}

/** The layout [row] stands for when it is still to be made, named [name]; null for a layout that exists. */
fun KeyboardGroup.generatedLayout(
    row: KeyboardRow,
    name: String,
): LayoutDefinition? {
    val profile = profile ?: return null
    return when (row.generated) {
        ApplicableLayouts.Generated.KEYS26 -> GeneratedLayouts.keys26(schemeId, name, profile)
        ApplicableLayouts.Generated.STROKES -> GeneratedLayouts.strokes(schemeId, name, profile)
        null -> null
    }
}

private fun generatedOf(keyboard: PublishedKeyboard): ApplicableLayouts.Generated? =
    when (keyboard.board) {
        GeneratedLayouts.keys26Id(keyboard.schemaID) -> ApplicableLayouts.Generated.KEYS26
        GeneratedLayouts.strokesId(keyboard.schemaID) -> ApplicableLayouts.Generated.STROKES
        else -> null
    }

private fun generatedNameRes(kind: ApplicableLayouts.Generated): Int =
    when (kind) {
        ApplicableLayouts.Generated.KEYS26 -> R.string.layout_name_keys26
        ApplicableLayouts.Generated.STROKES -> R.string.layout_name_strokes
    }

/** The shapes the app state says [this] input method's engine data can run. */
fun InstalledScheme.keyboardScheme(): KeyboardScheme =
    KeyboardScheme(id, supportedLayouts.mapNotNullTo(LinkedHashSet()) { KeyboardLayoutKind.fromWire(it) }, profile)

/**
 * Builds 我的键盘 from the same values the keyboard menu reads: the stored list, the two hidden sets
 * that decide the input methods never edited, and the catalog. Keyboards the engine cannot run or
 * the catalog cannot draw are left out, as the menu leaves them out.
 */
fun keyboardList(
    schemes: List<InstalledScheme>,
    inUseSchemeId: String?,
    layoutId: String,
    layoutDefinitionId: String?,
    stored: UserKeyboards.Stored,
    hiddenSchemas: Set<String>,
    hiddenLayouts: Set<String>,
    registry: LayoutRegistry,
): KeyboardList {
    val listed = schemes.filterNot { KeyboardResourceSet.EncodingAdapter.isGeneratedID(it.id) }
    val families = familiesOfSchemes(listed)
    val keyboardSchemes = listed.map { it.keyboardScheme() }
    val shape = KeyboardLayoutKind.fromWire(layoutId)
    val selected =
        inUseSchemeId
            ?.takeIf { id -> shape != null && listed.any { it.id == id } }
            ?.let { PublishedKeyboard(it, requireNotNull(shape), layoutDefinitionId) }
    val current = selected?.let { UserKeyboards.named(it, registry, families) }
    val legacy = LegacyKeyboards.of(keyboardSchemes, hiddenSchemas, hiddenLayouts, registry, families, selected)
    val effective = UserKeyboards.effective(stored, listed.map { it.id }, legacy)
    val runnable =
        UserKeyboards
            .runnable(effective, keyboardSchemes, registry, families)
            .map { UserKeyboards.named(it, registry, families) }
            .distinctBy { it.id }

    fun row(
        keyboard: PublishedKeyboard,
        entry: ApplicableLayouts.Entry? = null,
        profile: SchemaKeyProfile? = null,
    ): KeyboardRow {
        val layout = keyboard.board?.let(registry::resolvedLayout)
        val generated = entry?.generated
        val name =
            when {
                layout == null && generated != null -> LayoutDisplayName.Worded(generatedNameRes(generated))
                else -> layoutDisplayName(layout, KeyboardLayoutOption.fromId(keyboard.layout.wire))
            }
        val missing =
            entry?.missingCodes
                ?: if (layout != null &&
                    profile != null &&
                    keyboard.layout == KeyboardLayoutKind.QWERTY26 &&
                    generatedOf(keyboard) == null
                ) {
                    LayoutCodeCoverage.missing(layout, profile)
                } else {
                    emptyList()
                }
        return KeyboardRow(keyboard, name, keyboard.id == current?.id, missing, generated)
    }
    val groups =
        listed.map { scheme ->
            val own = runnable.filter { it.schemaID == scheme.id }
            // The keyboard in use is listed even when the list does not hold it, as in the menu.
            val shown = if (current?.schemaID == scheme.id && own.none { it.id == current.id }) own + current else own
            val ids = shown.mapTo(HashSet()) { it.id }
            val entries = ApplicableLayouts.entries(scheme.keyboardScheme(), registry, families)
            val byId = entries.associateBy { it.keyboard.id }
            KeyboardGroup(
                schemeId = scheme.id,
                schemeName = scheme.name,
                keyboards = shown.map { row(it, byId[it.id], scheme.profile) },
                addable = entries.filter { it.keyboard.id !in ids }.map { row(it.keyboard, it, scheme.profile) },
                unknownCodes = scheme.profile?.kind == SchemaKeyProfile.Kind.UNKNOWN,
                profile = scheme.profile,
            )
        }
    return KeyboardList(groups, current, stored, legacy)
}
