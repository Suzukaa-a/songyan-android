package app.songyan.ime.ime

import app.songyan.ime.candidate.KeyboardContentDock
import app.songyan.ime.candidate.KeyboardOneHandMode
import app.songyan.ime.candidate.ToolbarLayout
import app.songyan.ime.ime.panels.QuickSettingChange
import app.songyan.ime.keyboard.engine.EngineOptionKind
import app.songyan.ime.keyboard.engine.EngineOptionState
import app.songyan.ime.keyboard.engine.InputLayoutPolicy
import app.songyan.ime.keyboard.engine.KeyboardResourceSet
import app.songyan.ime.keyboard.engine.LayoutFamilyContext
import app.songyan.ime.keyboard.engine.PublishedKeyboard
import app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.keyboard.session.InputSession
import app.songyan.ime.keyboard.support.CandidateTextSize
import app.songyan.ime.layout.KeyboardScheme
import app.songyan.ime.layout.LayoutRegistry
import app.songyan.ime.layout.LegacyKeyboards
import app.songyan.ime.layout.UserKeyboards
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Applies the stored key height; the views re-solve their rows only when it actually changed. */
internal fun KeyboardController.applyKeyHeight(wire: String) {
    val height = KeyHeight.resolve(wire)
    if (KeyboardGeometry.keyHeight == height) return
    KeyboardGeometry.keyHeight = height
    refreshAppearance()
}

/** Applies the stored bottom band; like the key height it only re-solves the views when it changed. */
internal fun KeyboardController.applyBottomGap(dp: Int) {
    val value = KeyboardGeometry.clampBottomGapDp(dp)
    if (KeyboardGeometry.bottomGapDp == value) return
    KeyboardGeometry.bottomGapDp = value
    refreshAppearance()
}

/** The switch key is part of the key set, so the page is built and drawn again. */
internal fun KeyboardController.applyShowSwitchKey(show: Boolean) {
    if (showsSwitchKey == show) return
    showsSwitchKey = show
    keyboard.showsGlobe = show
    enqueue { render() }
}

/** A changed placement takes effect at once; the next render moves the readings. */
internal fun KeyboardController.applyReadingPlacement(wire: String) {
    val placement = ReadingPlacement.resolve(wire)
    if (readingPlacement == placement) return
    readingPlacement = placement
    render()
}

/** Narrows or restores the content column; the panels and the bar follow the column. */
internal fun KeyboardController.applyOneHand() {
    shell.mode = oneHandMode
    toolbar.oneHand = oneHandMode
    if (oneHandMode != KeyboardOneHandMode.OFF) lastOneHandSide = oneHandMode
    // The menu's one-handed row names the state.
    syncToolbar()
}

internal fun KeyboardController.applyHideIdleToolbar(hide: Boolean) {
    if (hideIdleToolbar == hide) return
    hideIdleToolbar = hide
    syncToolbar()
}

internal fun KeyboardController.applyToolbarLayout(wire: String) {
    val layout = ToolbarLayout.parse(wire)
    if (toolbarLayout == layout) return
    toolbarLayout = layout
    syncToolbar()
}

/** The toolbar's one-handed button: off, or back to the side used last (right until one was chosen). */
internal fun KeyboardController.toggleOneHand() {
    val next = if (oneHandMode == KeyboardOneHandMode.OFF) lastOneHandSide else KeyboardOneHandMode.OFF
    applyQuickSetting(QuickSettingChange.OneHand(next))
}

internal fun KeyboardController.observePreferences() {
    val source = preferences ?: return
    scope.launch {
        source.layoutId.collect { id ->
            val kind = KeyboardLayoutKind.fromWire(id) ?: return@collect
            if (preferredLayout == kind) return@collect
            preferredLayout = kind
            enqueue { applyPreferenceChange() }
        }
    }
    scope.launch {
        source.activeSchemaId.collect { id ->
            if (id.isBlank() || preferredSchema == id) return@collect
            preferredSchema = id
            enqueue { applyPreferenceChange() }
        }
    }
    scope.launch {
        source.layoutDefinitionId.collect { id ->
            if (preferredLayoutDefinitionId == id) return@collect
            preferredLayoutDefinitionId = id
            enqueue { applyPreferenceChange() }
        }
    }
    scope.launch {
        source.myKeyboards.collect {
            storedKeyboards = it
            syncMenu()
        }
    }
    scope.launch { source.keyHeight.collect { applyKeyHeight(it) } }
    scope.launch { source.bottomGapDp.collect { applyBottomGap(it) } }
    scope.launch { source.showSwitchKey.collect { applyShowSwitchKey(it) } }
    scope.launch { source.longPressDelayMs.collect { keyboard.longPressOverrideMs = HoldDelay.clamp(it) } }
    scope.launch { source.colorMode.collect { colorMode = ColorMode.resolve(it) } }
    scope.launch { source.keyFlickDirection.collect { keyboard.flickDirection = FlickDirection.resolve(it) } }
    scope.launch { source.doubleSpacePeriod.collect { doubleSpacePeriodEnabled = it } }
    scope.launch { source.keyPreview.collect { keyboard.keyPreviewEnabled = it } }
    scope.launch { source.cursorSwipeAnywhere.collect { keyboard.cursorSwipeAnywhere = it } }
    scope.launch { source.keyGapScaling.collect { keyboard.keyGapScaling = it } }
    scope.launch { source.oneHandWidthPercent.collect { shell.widthPercent = it } }
    scope.launch { source.hideIdleToolbar.collect { applyHideIdleToolbar(it) } }
    scope.launch { source.toolbarLayout.collect { applyToolbarLayout(it) } }
    scope.launch {
        source.symbolLock.collect {
            if (symbolLock == it) return@collect
            symbolLock = it
            enqueue { render() }
        }
    }
    scope.launch { source.releaseHaptic.collect { feedback.releaseEnabled = it } }
    scope.launch {
        source.oneHand.collect { value ->
            val mode = KeyboardOneHandMode.resolve(value)
            if (oneHandMode == mode) return@collect
            oneHandMode = mode
            applyOneHand()
        }
    }
    scope.launch {
        source.candidateTextSize.collect { value ->
            val factor = CandidateTextSize.fromId(value).factor
            if (CandidateBarStyle.textFactor == factor && candidates.textSizeFactor == factor) return@collect
            CandidateBarStyle.textFactor = factor
            candidates.textSizeFactor = factor
            // Larger text may need a taller row, which every part of the keyboard lays out around.
            refreshAppearance()
        }
    }
    scope.launch {
        source.displayFonts.collect { spec ->
            if (displayFonts == spec) return@collect
            // Opening font files is disk work; the views are redrawn on the main thread afterwards.
            withContext(Dispatchers.IO) { DisplayFonts.configure(spec) }
            displayFonts = spec
            refreshAppearance()
        }
    }
    scope.launch {
        source.showCandidateComments.collect { candidates.showComments = it }
    }
    scope.launch {
        source.collapsedCandidateComments.collect { candidates.collapsedComments = it }
    }
    scope.launch {
        source.keyFeedback.collect { value -> feedback.settings = value }
    }
    scope.launch { source.widePosition.collect { keyboard.dock = KeyboardContentDock.of(it) } }
    scope.launch { source.splitMode.collect { keyboard.splitMode = SplitMode.resolve(it) } }
    scope.launch { source.splitWidthDp.collect { keyboard.splitWidthDp = KeyboardGeometry.clampSplitWidthDp(it) } }
    scope.launch { source.marginWidthDp.collect { keyboard.marginWidthDp = KeyboardGeometry.clampSplitWidthDp(it) } }
    scope.launch { source.middleGapPercent.collect { keyboard.middleGapPercent = it } }
    scope.launch {
        source.nineKeyCandidateSide.collect {
            keyboard.nineKeyCandidateSide =
                app.songyan.ime.candidate.NineKeyCandidateSide
                    .resolve(it)
        }
    }
    scope.launch {
        source.sideMarginPerMille.collect {
            keyboard.sideMarginPerMille =
                KeyboardGeometry.clampSideMarginPerMille(it)
        }
    }
    scope.launch { source.readingPlacement.collect { applyReadingPlacement(it) } }
    scope.launch {
        source.menuHiddenSchemas.collect {
            menuHiddenSchemas = it
            syncMenu()
        }
    }
    scope.launch {
        source.menuHiddenLayouts.collect {
            menuHiddenLayouts = it
            syncMenu()
        }
    }
    scope.launch {
        source.shuangpinHints.collect {
            if (shuangpinHints == it) return@collect
            shuangpinHints = it
            enqueue { render() }
        }
    }
    scope.launch {
        source.t9QuickSymbols.collect {
            if (t9QuickSymbols == it) return@collect
            t9QuickSymbols = it
            enqueue { render() }
        }
    }
    scope.launch {
        source.voiceInputEnabled.collect {
            voiceInputEnabled = it
            voiceInput.configure(voiceInputEnabled, voiceInputScriptId)
        }
    }
    scope.launch {
        source.voiceInputScriptId.collect {
            voiceInputScriptId = it
            voiceInput.configure(voiceInputEnabled, voiceInputScriptId)
        }
    }
}

/** Renders again whenever the layout catalog is installed: a new layout, page or pairing shows at once. */
internal fun KeyboardController.observeLayoutCatalog() {
    scope.launch {
        KeyboardLayoutCatalog.snapshot.collect {
            readingKeyMaps.clear()
            enqueue { applyPreferenceChange() }
        }
    }
}

internal suspend fun KeyboardController.readPreferences() {
    val source = preferences ?: return
    val layout = runCatching { source.layoutId.first() }.getOrNull()
    layout?.let { KeyboardLayoutKind.fromWire(it) }?.let { preferredLayout = it }
    runCatching { source.activeSchemaId.first() }.getOrNull()?.takeIf { it.isNotBlank() }?.let {
        preferredSchema = it
    }
    runCatching { source.layoutDefinitionId.first() }.onSuccess { preferredLayoutDefinitionId = it }
    runCatching { source.myKeyboards.first() }.getOrNull()?.let { storedKeyboards = it }
    runCatching { source.keyHeight.first() }.getOrNull()?.let { applyKeyHeight(it) }
    runCatching { source.bottomGapDp.first() }.getOrNull()?.let { applyBottomGap(it) }
    runCatching { source.showSwitchKey.first() }.getOrNull()?.let {
        showsSwitchKey = it
        keyboard.showsGlobe = it
    }
    runCatching { source.longPressDelayMs.first() }.getOrNull().let {
        keyboard.longPressOverrideMs =
            HoldDelay.clamp(
                it,
            )
    }
    runCatching { source.colorMode.first() }.getOrNull()?.let { colorMode = ColorMode.resolve(it) }
    runCatching { source.symbolLock.first() }.getOrNull()?.let { symbolLock = it }
    runCatching { source.keyFlickDirection.first() }.getOrNull()?.let {
        keyboard.flickDirection = FlickDirection.resolve(it)
    }
    runCatching { source.doubleSpacePeriod.first() }.getOrNull()?.let { doubleSpacePeriodEnabled = it }
    runCatching { source.keyPreview.first() }.getOrNull()?.let { keyboard.keyPreviewEnabled = it }
    runCatching { source.cursorSwipeAnywhere.first() }.getOrNull()?.let { keyboard.cursorSwipeAnywhere = it }
    runCatching { source.keyGapScaling.first() }.getOrNull()?.let { keyboard.keyGapScaling = it }
    runCatching { source.oneHandWidthPercent.first() }.getOrNull()?.let { shell.widthPercent = it }
    runCatching { source.hideIdleToolbar.first() }.getOrNull()?.let { applyHideIdleToolbar(it) }
    runCatching { source.toolbarLayout.first() }.getOrNull()?.let { applyToolbarLayout(it) }
    runCatching { source.releaseHaptic.first() }.getOrNull()?.let { feedback.releaseEnabled = it }
    runCatching { source.oneHand.first() }.getOrNull()?.let {
        oneHandMode = KeyboardOneHandMode.resolve(it)
        applyOneHand()
    }
    runCatching { source.widePosition.first() }.getOrNull()?.let { keyboard.dock = KeyboardContentDock.of(it) }
    runCatching { source.splitMode.first() }.getOrNull()?.let { keyboard.splitMode = SplitMode.resolve(it) }
    runCatching { source.splitWidthDp.first() }.getOrNull()?.let {
        keyboard.splitWidthDp = KeyboardGeometry.clampSplitWidthDp(it)
    }
    runCatching { source.middleGapPercent.first() }.getOrNull()?.let { keyboard.middleGapPercent = it }
    runCatching { source.nineKeyCandidateSide.first() }.getOrNull()?.let {
        keyboard.nineKeyCandidateSide =
            app.songyan.ime.candidate.NineKeyCandidateSide
                .resolve(it)
    }
    runCatching { source.marginWidthDp.first() }.getOrNull()?.let {
        keyboard.marginWidthDp = KeyboardGeometry.clampSplitWidthDp(it)
    }
    runCatching { source.sideMarginPerMille.first() }.getOrNull()?.let {
        keyboard.sideMarginPerMille = KeyboardGeometry.clampSideMarginPerMille(it)
    }
    runCatching { source.readingPlacement.first() }.getOrNull()?.let { readingPlacement = ReadingPlacement.resolve(it) }
    runCatching { source.shuangpinHints.first() }.getOrNull()?.let { shuangpinHints = it }
    runCatching { source.t9QuickSymbols.first() }.getOrNull()?.let { t9QuickSymbols = it }
}

internal suspend fun KeyboardController.applyPreferences(active: InputSession) {
    visitingLayout = false
    val layout = preferredLayout
    val schema = preferredSchema
    if (active.layout == layout && active.selectedSchemaID == schema) {
        // A stored layout the new scheme's family cannot use is repaired here as well: the pair can
        // match the preference and still be one the family forbids.
        active.repairFamilyLayout()
    } else {
        active.selectLayout(layout, schema)
    }
    syncSelection(active)
    persistSelection(active)
}

/**
 * Writes back a selection the keyboard itself had to change because the scheme's family cannot use
 * the saved layout, so the next editor does not retry the refused pairing. Any other difference
 * (a switch that failed, companions still being generated) keeps the user's choice for next time.
 */
internal suspend fun KeyboardController.persistSelection(active: InputSession) {
    if (preferredLayout == layoutKind) return
    val set = active.resourceSet ?: return
    if (InputLayoutPolicy.allows(preferredLayout, set.familyOf(active.selectedSchemaID))) return
    preferredLayout = layoutKind
    // The named layout belonged to the refused shape; the repaired one follows its own pairing.
    preferredLayoutDefinitionId = null
    preferences?.setLayoutId(layoutKind.wire)
}

/** Applies a preference written elsewhere (settings app, the in-keyboard menu). */
internal suspend fun KeyboardController.applyPreferenceChange() {
    val active = session ?: return
    if (!ready) return
    applyPreferences(active)
    updateSwipe(active)
    syncMenu()
    render()
}

/**
 * The keyboard menu lists the user's keyboards: the input methods that have one, then the keyboards
 * of the running input method. The keyboard in use is always there so the menu shows where you are.
 */
internal fun KeyboardController.syncMenu() {
    // The menu covering a switch to another package holds still; it is redrawn once it closes.
    if (announceWhenReady && menuOpen) return
    val engine = session?.engine ?: pendingBackend
    // A layout's generated companion runs as its own schema but is not an input method to pick.
    val companions =
        engine
            ?.encodingAdapters
            ?.map { it.schemaID }
            ?.toSet()
            .orEmpty()
    val listed =
        engine
            ?.availableSchemas
            ?.filter { it.id !in companions && !KeyboardResourceSet.EncodingAdapter.isGeneratedID(it.id) }
            ?.map { it.id to it.displayName }
            .orEmpty()
    val keyboards = menuKeyboards(listed.map { it.first })
    val own = KeyboardMenuEntries.schemas(listed, keyboards, setOf(schemaId, preferredSchema))
    val others =
        KeyboardMenuEntries.otherSchemas(otherInputMethods, menuHiddenSchemas, listed.map { it.first }.toSet(), own)
    menu.setSchemas(own + others.map { it.menuId to it.label }, others.associate { it.menuId to it.method })
    val registry = KeyboardLayoutCatalog.snapshot.value.registry
    menu.setKeyboards(
        keyboards.of(schemaId).map { keyboard ->
            KeyboardSwitcherPanel.KeyboardChip(
                keyboard,
                layoutName(context, keyboard.board?.let(registry::resolvedLayout), keyboard.layout),
            )
        },
    )
    menu.setSwitches(menuSwitches())
    menu.update(schemaId, keyboards.current.id)
}

/**
 * The running input method's own schema switches, worded by the labels its schema declares: every
 * switch and option group it declares except the 中/英 key's `ascii_mode` (which the engine never lists).
 */
internal fun KeyboardController.menuSwitches(): List<KeyboardSwitcherPanel.SwitchEntry> {
    val declared =
        session
            ?.takeIf { ready }
            ?.options
            .orEmpty()
    return MenuSwitches.entries(declared) { kind -> context.getString(MenuSwitches.fallbackLabel(kind)) }
}

/** What the keyboard menu lists for the engine's switches; pure so the rule is tested without a keyboard. */
internal object MenuSwitches {
    @androidx.annotation.StringRes
    fun fallbackLabel(kind: EngineOptionKind): Int =
        when (kind) {
            EngineOptionKind.TRADITIONAL -> R.string.ime_qs_engine_traditional
            EngineOptionKind.SIMPLIFIED -> R.string.ime_qs_engine_simplified
            EngineOptionKind.EMOJI -> R.string.ime_qs_engine_emoji
            EngineOptionKind.FULL_SHAPE -> R.string.ime_qs_engine_full_shape
            EngineOptionKind.DECLARED -> R.string.ime_qs_engine_character_form
        }

    fun entries(
        declared: List<EngineOptionState>,
        fallback: (EngineOptionKind) -> String,
    ): List<KeyboardSwitcherPanel.SwitchEntry> {
        val seen = HashSet<String>()
        return declared.mapNotNull { state ->
            val descriptor = state.descriptor
            if (descriptor.choiceGroup.isEmpty()) {
                val labels = descriptor.stateLabels
                // A switch that words neither state is named by its kind rather than shown as a code.
                val label = if (labels.size == 2) labels[if (state.value) 1 else 0] else fallback(descriptor.kind)
                KeyboardSwitcherPanel.SwitchEntry.Toggle(descriptor.name, label, state.value)
            } else if (seen.add(descriptor.choiceGroup.first())) {
                val members = descriptor.choiceGroup.mapNotNull { name -> declared.find { it.descriptor.name == name } }
                KeyboardSwitcherPanel.SwitchEntry.Group(
                    members.map {
                        KeyboardSwitcherPanel.SwitchEntry.Group.Option(
                            it.descriptor.name,
                            it.descriptor.choiceLabel ?: it.descriptor.name,
                            it.value,
                        )
                    },
                )
            } else {
                null
            }
        }
    }
}

/**
 * The user's keyboards the engine can run now, for the input methods the engine [listed] (the
 * bundled ones before it has), with the keyboard in use. Worked out when the menu is synced or a
 * keyboard is switched, never per key.
 */
internal fun KeyboardController.menuKeyboards(listed: List<String>? = null): KeyboardMenuEntries.Keyboards {
    val set = session?.takeIf { ready }?.resourceSet
    val ids =
        (
            listed ?: run {
                val engine = session?.engine ?: pendingBackend
                val companions =
                    engine
                        ?.encodingAdapters
                        ?.map { it.schemaID }
                        ?.toSet()
                        .orEmpty()
                engine
                    ?.availableSchemas
                    ?.map { it.id }
                    ?.filter { it !in companions && !KeyboardResourceSet.EncodingAdapter.isGeneratedID(it) }
                    .orEmpty()
            }
        ).ifEmpty { BuiltinTemplateSchemas.SOURCE_SCHEMA_IDS }
    // Without a resource set every shape is offered, as the session cannot refuse one yet.
    val shapes = set?.availableCombinations?.groupBy({ it.schemaID }, { it.layout })
    val schemes =
        ids.map { id ->
            KeyboardScheme(
                id,
                shapes?.let {
                    it[id].orEmpty().toSet()
                } ?: KeyboardLayoutKind.entries.toSet(),
                set?.keyProfiles?.get(id),
            )
        }
    val current =
        PublishedKeyboard(schemaId, layoutKind, preferredLayoutDefinitionId.takeIf { layoutKind == preferredLayout })
    return KeyboardMenuEntries.keyboards(
        schemes,
        storedKeyboards,
        menuHiddenSchemas,
        menuHiddenLayouts,
        KeyboardLayoutCatalog.snapshot.value.registry,
        layoutFamilies(),
        current,
    )
}

/** An input method of an installed package other than the running one; plain data the service reads from storage. */
data class OtherInputMethod(
    val sourceId: String,
    val schemaId: String,
    val name: String,
    val packageName: String,
) {
    /** Cannot collide with an engine schema id, which never starts with a NUL. */
    val menuId: String get() = "$MENU_ID_PREFIX$sourceId/$schemaId"

    companion object {
        const val MENU_ID_PREFIX = "\u0000src/"
    }
}

internal object KeyboardMenuEntries {
    /** A chip that switches to another package: its menu id, its label and the data it acts on. */
    data class OtherChip(
        val menuId: String,
        val label: String,
        val method: OtherInputMethod,
    )

    /**
     * The chips for [others], after the running package's [own] chips. Ones the user hid in settings
     * are dropped by plain schema id, and so are ones whose id the running engine already lists, so
     * a chip never stands for two input methods. A name that would read the same as another chip
     * gets the package name appended.
     */
    fun otherSchemas(
        others: List<OtherInputMethod>,
        hidden: Set<String>,
        running: Set<String>,
        own: List<Pair<String, String>>,
    ): List<OtherChip> {
        val offered = others.filter { it.schemaId !in hidden && it.schemaId !in running }
        val ownNames = own.map { (id, name) -> name.ifBlank { BuiltinTemplateSchemas.NAMES[id] ?: id } }
        val counts = (ownNames + offered.map { it.name }).groupingBy { it }.eachCount()
        return offered.map {
            val label = if ((counts[it.name] ?: 0) > 1) "${it.name} · ${it.packageName}" else it.name
            OtherChip(it.menuId, label, it)
        }
    }

    /**
     * The input method chips: the ones with a keyboard, and the ones in use. Before the engine has
     * listed its input methods the bundled ones stand in.
     */
    fun schemas(
        listed: List<Pair<String, String>>,
        keyboards: Keyboards,
        current: Set<String>,
    ): List<Pair<String, String>> {
        val shown = keyboards.all.mapTo(HashSet()) { it.schemaID }
        return listed
            .ifEmpty { BuiltinTemplateSchemas.SOURCE_SCHEMA_IDS.map { it to "" } }
            .filter { (id, _) -> id in shown || id in current }
    }

    /** The keyboards the menu works with: every one the engine can run, each naming its layout. */
    class Keyboards(
        val all: List<PublishedKeyboard>,
        /** The keyboard in use, its layout named. */
        val current: PublishedKeyboard,
    ) {
        /**
         * The layout chips of [schemaId]. The keyboard in use is among its input method's even when
         * the list does not hold it, so the menu can show where the keyboard is.
         */
        fun of(schemaId: String): List<PublishedKeyboard> {
            val own = all.filter { it.schemaID == schemaId }
            return if (schemaId != current.schemaID || own.any { it.id == current.id }) own else own + current
        }
    }

    /**
     * The user's keyboards ([UserKeyboards.effective]) limited to what [schemes] can run and the
     * catalog can draw. [current] is the keyboard in use as the controller holds it.
     */
    fun keyboards(
        schemes: List<KeyboardScheme>,
        stored: UserKeyboards.Stored,
        hiddenSchemas: Set<String>,
        hiddenLayouts: Set<String>,
        registry: LayoutRegistry,
        families: LayoutFamilyContext,
        current: PublishedKeyboard,
    ): Keyboards {
        val legacy = LegacyKeyboards.of(schemes, hiddenSchemas, hiddenLayouts, registry, families, current)
        val effective = UserKeyboards.effective(stored, schemes.map { it.id }, legacy)
        val runnable =
            UserKeyboards
                .runnable(effective, schemes, registry, families)
                .map { UserKeyboards.named(it, registry, families) }
                .distinctBy { it.id }
        return Keyboards(runnable, UserKeyboards.named(current, registry, families))
    }
}
