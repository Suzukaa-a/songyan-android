package app.songyan.ime.keyboard.session

import app.songyan.ime.keyboard.engine.EngineBackendError
import app.songyan.ime.keyboard.engine.InputLayoutPolicy
import app.songyan.ime.keyboard.engine.KeyboardResourceSet
import app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.keyboard.support.UserTerminology
import kotlinx.coroutines.CancellationException

// Schema and layout switching for [InputSession], split out to keep the session file small. Bodies are
// moved verbatim; the public entry points stay on the class.

internal suspend fun InputSession.selectLayoutImpl(requested: KeyboardLayoutKind) {
    if (retired) return
    val resource = resourceSet
    val newLayout = resource?.let { legalLayoutFor(requested, selectedSchemaID, it) } ?: requested
    if (newLayout == currentLayout) return
    val previousCompact = compactSpellingLayout
    compactSpellingLayout = false
    var succeeded = false
    try {
        val set = resourceSet
        if (set != null && set.usesIndependentSelection) {
            val pair = set.combination(newLayout, selectedSchemaID)
            if (pair == null) {
                onError?.invoke(EngineBackendError.UnsupportedSchema("this scheme has no such keyboard layout"))
                return
            }
            try {
                validateSelection(pair.engineSchemaID)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                refreshAfterSelectionFailure(error)
                return
            }
            if (!prepareForConfigurationChange()) return
            try {
                engine.selectSchema(pair.engineSchemaID)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                refreshAfterSelectionFailure(error)
                return
            }
            currentLayout = newLayout
            selectedSchemaID = pair.schemaID
            reloadOptions()
            refresh()
            succeeded = true
            return
        }
        val schemaID =
            if (set != null) {
                set.schemaIDFor(newLayout) ?: run {
                    onError?.invoke(EngineBackendError.UnsupportedSchema(newLayout.wire))
                    return
                }
            } else {
                BuiltinTemplateSchemas.defaultSchemaID(newLayout) ?: run {
                    onError?.invoke(EngineBackendError.UnsupportedSchema(newLayout.wire))
                    return
                }
            }
        validateSelection(schemaID)
        if (!prepareForConfigurationChange()) return
        engine.selectSchema(schemaID)
        selectedSchemaID = schemaID
        currentLayout = newLayout
        reloadOptions()
        refresh()
        succeeded = true
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        refreshAfterSelectionFailure(error)
    } finally {
        if (!succeeded) compactSpellingLayout = previousCompact
    }
}

internal suspend fun InputSession.selectSchemaImpl(schemaID: String) {
    if (retired || schemaID == selectedSchemaID) return
    val set = resourceSet
    if (set != null) {
        val pairs = set.availableCombinations.filter { it.schemaID == schemaID }
        val pair = pairs.firstOrNull { it.layout == currentLayout } ?: pairs.firstOrNull()
        if (pair == null) {
            onError?.invoke(EngineBackendError.UnsupportedSchema("this scheme has no usable keyboard layout"))
            return
        }
        selectLayout(pair.layout, schemaID)
        return
    }
    try {
        val target = schemaID
        validateSelection(target)
        if (!prepareForConfigurationChange()) return
        engine.selectSchema(target)
        selectedSchemaID = schemaID
        reloadOptions()
        refresh()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        refreshAfterSelectionFailure(error)
    }
}

internal suspend fun InputSession.selectLayoutBoundImpl(
    requested: KeyboardLayoutKind,
    boundSchemaID: String,
) {
    if (retired) return
    val resource = resourceSet
    val newLayout = resource?.let { legalLayoutFor(requested, boundSchemaID, it) } ?: requested
    if (newLayout == currentLayout && boundSchemaID == selectedSchemaID) return
    val previousCompact = compactSpellingLayout
    compactSpellingLayout = false
    var succeeded = false
    try {
        val target = engineSchemaFor(boundSchemaID, newLayout)
        validateSelection(target)
        resourceSet?.incompatibilityReason(newLayout, boundSchemaID)?.let {
            throw EngineBackendError.UnsupportedSchema(it)
        }
        if (!prepareForConfigurationChange()) return
        engine.selectSchema(target)
        selectedSchemaID = boundSchemaID
        currentLayout = newLayout
        reloadOptions()
        refresh()
        succeeded = true
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        refreshAfterSelectionFailure(error)
    } finally {
        if (!succeeded) compactSpellingLayout = previousCompact
    }
}

internal suspend fun InputSession.revalidateCurrentSchemaImpl() {
    if (retired) return
    try {
        val target = engineSchemaFor(selectedSchemaID, currentLayout)
        validateSelection(target)
        if (!prepareForConfigurationChange()) return
        engine.selectSchema(target)
        reloadOptions()
        refresh()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        refreshAfterSelectionFailure(error)
    }
}

internal fun InputSession.engineSchemaFor(
    sourceID: String,
    layout: KeyboardLayoutKind,
): String {
    val resources = resourceSet ?: return sourceID
    return resources.logicalSchemaID(sourceID, layout)
        ?: throw EngineBackendError.UnsupportedSchema(UserTerminology.layoutUnavailableReason(layout))
}

internal suspend fun InputSession.validateSelection(schemaID: String) {
    engine.validateSchema(schemaID)
    resourceSet?.enabledMembershipRefusal(schemaID, engine.availableSchemas)?.let { refusal ->
        throw EngineBackendError.SchemaNotEnabled(refusal.schemaID, refusal.enabledSchemaIDs)
    }
}

/**
 * Brings the running layout back inside what the current schema's family allows. A stored layout
 * the family forbids was allowed when it was saved, so the same schema keeps running on the shape
 * the family uses instead of failing on every editor start.
 */
internal suspend fun InputSession.repairFamilyLayoutImpl() {
    if (retired) return
    val set = resourceSet ?: return
    if (InputLayoutPolicy.allows(currentLayout, set.familyOf(selectedSchemaID))) return
    selectLayoutImpl(currentLayout)
}

/**
 * The layout a switch actually runs. A scheme family whose keys are not the shipped folded shapes
 * keeps the identity shape, so a pairing saved before that rule lands on what the set offers
 * instead of refusing the switch; anything the family allows passes through unchanged.
 */
private fun legalLayoutFor(
    layout: KeyboardLayoutKind,
    schemaID: String,
    set: KeyboardResourceSet,
): KeyboardLayoutKind =
    if (InputLayoutPolicy.allows(layout, set.familyOf(schemaID))) {
        layout
    } else {
        set.layoutsFor(schemaID).firstOrNull() ?: KeyboardLayoutKind.QWERTY26
    }

internal fun InputSession.refreshAfterSelectionFailure(error: Throwable) {
    // Read-only snapshots must never replay the commit produced by the confirmation.
    var state = engine.currentSnapshot()
    state = state.copy(committed = "")
    if (inputUnavailableReason != null) {
        selectedSchemaID = resourceSet?.sourceSchemaID(state.schemaID) ?: state.schemaID
        options = emptyList()
    }
    apply(state)
    onError?.invoke(error)
}
