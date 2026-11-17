package app.songyan.ime.settings.state

import app.songyan.ime.keyboard.engine.KeyboardResourceSet
import app.songyan.ime.keyboard.engine.PublishedKeyboard
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.layout.ApplicableLayouts
import app.songyan.ime.layout.KeyboardScheme
import app.songyan.ime.layout.LayoutRegistry
import app.songyan.ime.layout.LegacyKeyboards
import app.songyan.ime.layout.UserKeyboards

/** One keyboard as 我的键盘 lists it: what it is and what its layout is called. */
data class KeyboardRow(
    val keyboard: PublishedKeyboard,
    val layoutName: LayoutDisplayName,
    val inUse: Boolean = false,
)

/** The keyboards of one input method, and the ones it could still be given. */
data class KeyboardGroup(
    val schemeId: String,
    val schemeName: String,
    val keyboards: List<KeyboardRow>,
    val addable: List<KeyboardRow>,
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

/** The shapes the app state says [this] input method's engine data can run. */
fun InstalledScheme.keyboardScheme(): KeyboardScheme =
    KeyboardScheme(id, supportedLayouts.mapNotNullTo(LinkedHashSet()) { KeyboardLayoutKind.fromWire(it) })

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

    fun row(keyboard: PublishedKeyboard) =
        KeyboardRow(
            keyboard = keyboard,
            layoutName =
                layoutDisplayName(
                    keyboard.board?.let(registry::resolvedLayout),
                    KeyboardLayoutOption.fromId(keyboard.layout.wire),
                ),
            inUse = keyboard.id == current?.id,
        )
    val groups =
        listed.map { scheme ->
            val own = runnable.filter { it.schemaID == scheme.id }
            // The keyboard in use is listed even when the list does not hold it, as in the menu.
            val shown = if (current?.schemaID == scheme.id && own.none { it.id == current.id }) own + current else own
            val ids = shown.mapTo(HashSet()) { it.id }
            KeyboardGroup(
                schemeId = scheme.id,
                schemeName = scheme.name,
                keyboards = shown.map(::row),
                addable =
                    ApplicableLayouts
                        .of(scheme.keyboardScheme(), registry, families)
                        .filter { it.id !in ids }
                        .map(::row),
            )
        }
    return KeyboardList(groups, current, stored, legacy)
}
