package app.songyan.ime.ime

import app.songyan.ime.candidate.KeyboardContentDock
import app.songyan.ime.candidate.KeyboardOneHandMode
import app.songyan.ime.candidate.ToolbarLayout
import app.songyan.ime.ime.panels.QuickSettingChange
import app.songyan.ime.keyboard.engine.InputLayoutPolicy
import app.songyan.ime.keyboard.engine.KeyboardResourceSet
import app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.keyboard.session.InputSession
import app.songyan.ime.keyboard.support.CandidateTextSize
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
    scope.launch { source.keyHeight.collect { applyKeyHeight(it) } }
    scope.launch { source.bottomGapDp.collect { applyBottomGap(it) } }
    scope.launch { source.showSwitchKey.collect { applyShowSwitchKey(it) } }
    scope.launch { source.longPressDelayMs.collect { keyboard.longPressOverrideMs = HoldDelay.clamp(it) } }
    scope.launch { source.colorMode.collect { colorMode = ColorMode.resolve(it) } }
    scope.launch { source.keyFlickDirection.collect { keyboard.flickDirection = FlickDirection.resolve(it) } }
    scope.launch { source.doubleSpacePeriod.collect { doubleSpacePeriodEnabled = it } }
    scope.launch { source.keyPreview.collect { keyboard.keyPreviewEnabled = it } }
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
        source.collapsedCandidateComments.collect { candidates.collapsedComments = it }
    }
    scope.launch {
        source.keyFeedback.collect { value -> feedback.settings = value }
    }
    scope.launch { source.widePosition.collect { keyboard.dock = KeyboardContentDock.of(it) } }
    scope.launch { source.splitMode.collect { keyboard.splitMode = SplitMode.resolve(it) } }
    scope.launch { source.splitWidthDp.collect { keyboard.splitWidthDp = KeyboardGeometry.clampSplitWidthDp(it) } }
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
 * The keyboard menu offers every input method the running engine lists and every layout, less the
 * ones the user hid in settings; the ones in use are always there so the menu shows where you are.
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
    val own = KeyboardMenuEntries.schemas(listed, menuHiddenSchemas, setOf(schemaId, preferredSchema))
    val others =
        KeyboardMenuEntries.otherSchemas(otherInputMethods, menuHiddenSchemas, listed.map { it.first }.toSet(), own)
    menu.setSchemas(own + others.map { it.menuId to it.label }, others.associate { it.menuId to it.method })
    val offered = KeyboardMenuEntries.layouts(menuHiddenLayouts, layoutKind, jumpableLayouts())
    menu.setLayoutNames(layoutNamesByKind())
    val main = mainDefinition().id
    menu.setLayouts(offered, KeyboardMenuEntries.boards(layoutBoards(), menuHiddenLayouts, main), main)
    menu.update(schemaId, layoutKind)
}

/** The physical boards the running input method types on, preferred first; empty keeps the shapes. */
internal fun KeyboardController.layoutBoards(): List<String> =
    InputLayoutPolicy.boardsOf(layoutFamilies().familyOf(schemaId))

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

    /** Before the engine has listed its input methods the bundled ones stand in. */
    fun schemas(
        listed: List<Pair<String, String>>,
        hidden: Set<String>,
        current: Set<String>,
    ): List<Pair<String, String>> =
        listed
            .ifEmpty { BuiltinTemplateSchemas.SOURCE_SCHEMA_IDS.map { it to "" } }
            .filter { (id, _) -> id !in hidden || id in current }

    /**
     * The layout chips: the running input method's usable shapes, less the ones the user hid. The
     * running one always stays, so the menu can show where the keyboard is.
     */
    fun layouts(
        hidden: Set<String>,
        current: KeyboardLayoutKind,
        usable: Set<KeyboardLayoutKind> = KeyboardLayoutKind.entries.toSet(),
    ): List<KeyboardLayoutKind> =
        KeyboardLayoutKind.entries.filter {
            (it.wire !in hidden || it == current) && (it in usable || it == current)
        }

    /**
     * The physical [boards] the menu offers: the ones the user hid in settings (by board id) are
     * dropped, except the one the keyboard draws, so the menu still shows where it is.
     */
    fun boards(
        boards: List<String>,
        hidden: Set<String>,
        mainLayoutId: String?,
    ): List<String> = boards.filter { it !in hidden || it == mainLayoutId }

    /**
     * One chip of the layout row: a shape, and the physical board it stands for when the input
     * method types on several boards that share the identity shape's slot.
     */
    data class LayoutChip(
        val kind: KeyboardLayoutKind,
        val boardId: String? = null,
    )

    /**
     * The layout row for [offered] shapes. The identity shape's chip is replaced by one chip per
     * entry of [boards] (preferred first); a main layout that is none of them (the user's own) keeps
     * a chip of the shape itself, after the boards. Without boards the row is the shapes as they are.
     */
    fun layoutChips(
        offered: List<KeyboardLayoutKind>,
        boards: List<String>,
        mainLayoutId: String?,
    ): List<LayoutChip> =
        offered.flatMap { kind ->
            when {
                kind != KeyboardLayoutKind.QWERTY26 || boards.isEmpty() -> listOf(LayoutChip(kind))
                mainLayoutId in boards -> boards.map { LayoutChip(kind, it) }
                else -> boards.map { LayoutChip(kind, it) } + LayoutChip(kind)
            }
        }

    /**
     * The layout after [current] in [offered] (the menu's list, already limited to what the running
     * input method can use), wrapping around; the first one when [current] is not offered. Null when
     * there is nothing else to switch to.
     */
    fun nextLayout(
        offered: List<KeyboardLayoutKind>,
        current: KeyboardLayoutKind,
    ): KeyboardLayoutKind? {
        if (offered.none { it != current }) return null
        val at = offered.indexOf(current)
        return offered[(at + 1) % offered.size]
    }
}
