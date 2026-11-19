package app.songyan.ime.settings.state

import app.songyan.ime.keyboard.engine.InputLayoutPolicy
import app.songyan.ime.keyboard.engine.KeyboardResourceSet
import app.songyan.ime.keyboard.engine.LayoutFamilyContext
import app.songyan.ime.keyboard.engine.PublishedKeyboard
import app.songyan.ime.keyboard.engine.SchemaKeyProfile
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.layout.ApplicableLayouts
import app.songyan.ime.layout.BuiltinLayouts
import app.songyan.ime.layout.GeneratedLayouts
import app.songyan.ime.layout.KeyboardScheme
import app.songyan.ime.layout.LayoutCodeCoverage
import app.songyan.ime.layout.LayoutDefinition
import app.songyan.ime.layout.LayoutFold
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
    val status: KeyboardStatus = KeyboardStatus.READY,
    /** In [KeyboardGroup.offered]: the input method already has this keyboard. */
    val added: Boolean = false,
)

/** Whether a keyboard in the list can be used now. */
enum class KeyboardStatus {
    READY,

    /** Its folded engine data is still being made. */
    PREPARING,

    /** The generator refused it for this input method; it is never retried. */
    UNAVAILABLE,
}

/** The keyboards of one input method, and the ones it could still be given. */
data class KeyboardGroup(
    val schemeId: String,
    val schemeName: String,
    val keyboards: List<KeyboardRow>,
    val addable: List<KeyboardRow>,
    /**
     * Every layout the input method can have a keyboard on, in catalog order, the ones it has marked
     * [KeyboardRow.added]: the add dialog lists them all so a layout never seems unsupported.
     */
    val offered: List<KeyboardRow> = addable,
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

private fun generatedName(
    kind: ApplicableLayouts.Generated,
    profile: SchemaKeyProfile?,
): LayoutDisplayName =
    when (kind) {
        ApplicableLayouts.Generated.KEYS26 ->
            generatedKeys26Name(profile?.let { LayoutCodeCoverage.missing(BuiltinLayouts.qwerty26, it) }.orEmpty())
        ApplicableLayouts.Generated.STROKES -> LayoutDisplayName.Worded(R.string.layout_name_strokes)
    }

/**
 * The shapes [this] input method could be given a keyboard on, built or not: the identity shape
 * always, a folded one only when its speller is spelled syllable by syllable, the family allows it
 * and the generator did not refuse it. A keyboard on one that is not built yet is made on request.
 */
fun InstalledScheme.possibleScheme(families: LayoutFamilyContext): KeyboardScheme =
    KeyboardScheme(
        id,
        KeyboardLayoutKind.entries.filterTo(LinkedHashSet()) { kind ->
            kind == KeyboardLayoutKind.QWERTY26 ||
                (
                    profile?.allowsFolding != false &&
                        InputLayoutPolicy.allows(kind, families.familyOf(id)) &&
                        kind.wire !in unavailableLayouts
                )
        },
        profile,
        builtFolds,
    )

/** The shapes the app state says [this] input method's engine data can run. */
fun InstalledScheme.keyboardScheme(): KeyboardScheme =
    KeyboardScheme(
        id,
        supportedLayouts.mapNotNullTo(LinkedHashSet()) { KeyboardLayoutKind.fromWire(it) },
        profile,
        builtFolds,
    )

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
    val possible = listed.associate { it.id to it.possibleScheme(families) }
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
        status: KeyboardStatus = KeyboardStatus.READY,
    ): KeyboardRow {
        val layout = keyboard.board?.let(registry::resolvedLayout)
        val generated = entry?.generated
        val name =
            when {
                layout == null && generated != null -> generatedName(generated, profile)
                else -> layoutDisplayName(layout, KeyboardLayoutOption.fromId(keyboard.layout.wire))
            }
        val missing =
            entry?.missingCodes
                ?: if (layout != null &&
                    profile != null &&
                    generatedOf(keyboard) == null
                ) {
                    LayoutCodeCoverage.missingToReport(layout, keyboard.layout, profile)
                } else {
                    emptyList()
                }
        return KeyboardRow(keyboard, name, keyboard.id == current?.id, missing, generated, status)
    }
    val groups =
        listed.map { scheme ->
            val own = runnable.filter { it.schemaID == scheme.id }
            val ready = own.mapTo(HashSet()) { it.id }
            val allowed = possible.getValue(scheme.id)
            // A keyboard of the list on a fold that is not built yet, or that was refused, stays listed with its state.
            val waiting =
                effective
                    .filter {
                        it.schemaID == scheme.id &&
                            it.id !in ready &&
                            UserKeyboards.drawsItsLayout(it, registry, families)
                    }.filter {
                        it.layout in allowed.shapes ||
                            LayoutFold.shapeWire(registry, it) in scheme.unavailableLayouts
                    }.map { UserKeyboards.named(it, registry, families) }
            val rank = effective.filter { it.schemaID == scheme.id }.map { it.id }
            val shownOwn =
                (own + waiting)
                    .distinctBy { it.id }
                    .sortedBy { k -> rank.indexOf(k.id).let { if (it < 0) Int.MAX_VALUE else it } }
            // The keyboard in use is listed even when the list does not hold it, as in the menu.
            val shown =
                if (current?.schemaID == scheme.id &&
                    shownOwn.none { it.id == current.id }
                ) {
                    shownOwn + current
                } else {
                    shownOwn
                }
            val ids = shown.mapTo(HashSet()) { it.id }
            val entries = ApplicableLayouts.entries(allowed, registry, families)
            val byId = entries.associateBy { it.keyboard.id }
            val offered = entries.map { row(it.keyboard, it, scheme.profile).copy(added = it.keyboard.id in ids) }
            KeyboardGroup(
                schemeId = scheme.id,
                schemeName = scheme.name,
                keyboards =
                    shown.map {
                        val state =
                            when {
                                it.id in ready || it.id == current?.id -> KeyboardStatus.READY
                                LayoutFold.of(registry, it.layout, it.board) == null ||
                                    LayoutFold.shapeWire(
                                        registry,
                                        it,
                                    ) in scheme.unavailableLayouts -> KeyboardStatus.UNAVAILABLE
                                else -> KeyboardStatus.PREPARING
                            }
                        row(it, byId[it.id], scheme.profile, state)
                    },
                addable = offered.filterNot { it.added },
                offered = offered,
                unknownCodes = scheme.profile?.kind == SchemaKeyProfile.Kind.UNKNOWN,
                profile = scheme.profile,
            )
        }
    return KeyboardList(groups, current, stored, legacy)
}

/**
 * What the install picker offers a package whose install just finished: for each of its input
 * methods every layout it could have a keyboard on (a group's [KeyboardGroup.keyboards]). [checked]
 * holds the ticked keyboards' ids; the default for each input method is its first layout.
 */
data class InstallPicker(
    val groups: List<KeyboardGroup>,
    val checked: Set<String>,
) {
    /** The ticked keyboards, in the order the picker lists them. */
    val chosen: List<KeyboardRow> get() = groups.flatMap { it.keyboards }.filter { it.keyboard.id in checked }
}

/**
 * The picker for the input methods [schemaIds] of a package, read from [schemes] (the package must
 * be the one in use for its data to be known). [checked] null means the defaults. Null when none of
 * its input methods is known yet.
 */
fun installPicker(
    schemes: List<InstalledScheme>,
    schemaIds: List<String>,
    registry: LayoutRegistry,
    checked: Set<String>?,
): InstallPicker? {
    val listed = schemes.filter { it.id in schemaIds && !KeyboardResourceSet.EncodingAdapter.isGeneratedID(it.id) }
    if (listed.isEmpty()) return null
    val families = familiesOfSchemes(listed)
    val groups =
        listed.map { scheme ->
            val entries = ApplicableLayouts.entries(scheme.possibleScheme(families), registry, families)
            KeyboardGroup(
                schemeId = scheme.id,
                schemeName = scheme.name,
                keyboards =
                    entries.map { entry ->
                        val layout = entry.keyboard.board?.let(registry::resolvedLayout)
                        val name =
                            when {
                                layout == null && entry.generated != null ->
                                    generatedName(entry.generated!!, scheme.profile)
                                else ->
                                    layoutDisplayName(
                                        layout,
                                        KeyboardLayoutOption.fromId(entry.keyboard.layout.wire),
                                    )
                            }
                        KeyboardRow(entry.keyboard, name, false, entry.missingCodes, entry.generated)
                    },
                addable = emptyList(),
                unknownCodes = scheme.profile?.kind == SchemaKeyProfile.Kind.UNKNOWN,
                profile = scheme.profile,
            )
        }
    val defaults =
        groups.mapNotNullTo(LinkedHashSet()) {
            it.keyboards
                .firstOrNull()
                ?.keyboard
                ?.id
        }
    return InstallPicker(groups, checked ?: defaults)
}
