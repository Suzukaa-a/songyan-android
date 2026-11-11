package app.songyan.ime.appearance

import app.songyan.ime.candidate.CandidateBarDefinition
import app.songyan.ime.settings.ui.theme.ThemeMode

/**
 * The applied appearance as the app reads and writes it.
 *
 * Every call is blocking by design ([AppearanceStore] is synchronous); callers run it on an IO
 * dispatcher. The read path never throws: a missing or unreadable snapshot fails closed to the
 * Android default appearance (松烟墨韵), so a broken file can never leave the keyboard unpainted.
 */
class AppearanceStoreController(
    private val store: AppearanceStore,
    private val fallback: AppearanceDocument = AppearancePresets.androidDefault,
) {
    /** The applied appearance plus the wallpaper-palette and light/dark choices. */
    fun read(
        dynamicColor: Boolean,
        themeMode: ThemeMode = ThemeMode.SYSTEM,
    ): AppearanceState {
        val result = store.read()
        val document =
            if (result.source == AppearanceSnapshotSource.APPLIED) {
                // A built-in is always the shipped definition, not the copy stored when it was applied,
                // so a new release's numbers reach everyone who already uses it.
                result.snapshot.document.let { stored ->
                    AppearancePresets.ALL_BUILT_IN.firstOrNull { it.id == stored.id } ?: stored
                }
            } else {
                fallback
            }
        return AppearanceState(
            document = document,
            dynamicColor = dynamicColor,
            themeMode = themeMode,
            error = result.error,
        )
    }

    /** Every selectable appearance: the built-ins first, then the imported ones. */
    fun list(): List<AppearanceDocument> = store.list()

    /** Applies a stored appearance by id; throws [AppearanceError] when the id is unknown. */
    fun select(
        id: String,
        dynamicColor: Boolean,
        themeMode: ThemeMode = ThemeMode.SYSTEM,
    ): AppearanceState {
        val document =
            list().firstOrNull { it.id == id }
                ?: throw AppearanceError(AppearanceErrorCode.NOT_FOUND, id)
        store.apply(document)
        return AppearanceState(document = document, dynamicColor = dynamicColor, themeMode = themeMode)
    }

    /**
     * Stores [bar] as the candidate area of [current] and applies it. A built-in appearance is
     * read-only, so the edit lands in a user copy named with [copySuffix] that becomes the applied one.
     */
    fun editCandidateBar(
        current: AppearanceDocument,
        bar: CandidateBarDefinition,
        copySuffix: String,
    ): AppearanceDocument {
        val base = if (AppearancePresets.isBuiltIn(current)) current.duplicate(copySuffix) else current
        val updated = base.replacing(bar).normalized()
        store.save(updated, replacing = true)
        store.apply(updated)
        return updated
    }

    /**
     * Stores an imported appearance file (an appearance, a skin or a candidate area) as a user
     * appearance without applying it. An identity that is already taken, a built-in's included,
     * gets a fresh one so an import never overwrites what the user has.
     */
    fun import(text: String): AppearanceDocument {
        val decoded = AppearanceDocument.decodeImported(text)
        val taken = list().map { it.id }.toSet()
        val document = if (decoded.id in taken) decoded.duplicate("") else decoded
        store.save(document)
        return document
    }

    /** The exported text of one stored appearance; the same strict form the importer reads. */
    fun export(id: String): String {
        val document =
            list().firstOrNull { it.id == id }
                ?: throw AppearanceError(AppearanceErrorCode.NOT_FOUND, id)
        return document.encode()
    }

    /** Removes a user appearance; a built-in is refused. */
    fun remove(id: String) {
        val document = list().firstOrNull { it.id == id } ?: throw AppearanceError(AppearanceErrorCode.NOT_FOUND, id)
        if (AppearancePresets.isBuiltIn(document)) throw AppearanceError(AppearanceErrorCode.READ_ONLY_BUILTIN, id)
        store.remove(id)
    }
}
