package app.songyan.ime.settings.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import app.songyan.ime.layout.BuiltinLayouts
import app.songyan.ime.layout.KeyboardComposition
import app.songyan.ime.layout.KeyboardCompositionKey
import app.songyan.ime.layout.KeyboardPageDefinition
import app.songyan.ime.layout.KeyboardSymbolPresentation
import app.songyan.ime.layout.LayoutException
import app.songyan.ime.layout.LayoutRegistry
import app.songyan.ime.settings.state.LayoutEditResult
import app.songyan.ime.settings.state.LayoutImportOutcome
import app.songyan.ime.settings.state.LayoutLibraryHolder
import app.songyan.ime.settings.state.LayoutLibrarySource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** What a library action did, shown once; the screen maps it onto copy. */
sealed interface LibraryEvent {
    data class Imported(
        val name: String,
        val copied: Boolean,
    ) : LibraryEvent

    data object Exported : LibraryEvent

    data object ExportFailed : LibraryEvent

    /** The catalog refused an edit; nothing changed. */
    data class Failed(
        val error: LayoutException,
    ) : LibraryEvent
}

/**
 * Actions of the layout list, the page library and the page pairing. Every write goes through the app
 * module's bridge, so the keyboard and the settings screens read the same catalog.
 */
class LayoutLibraryViewModel(
    private val source: LayoutLibrarySource,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    val registry: StateFlow<LayoutRegistry?> = source.registry

    private val mutableEvent = MutableStateFlow<LibraryEvent?>(null)

    /** The event to surface once, or null; the screen shows it and consumes it. */
    val event: StateFlow<LibraryEvent?> = mutableEvent.asStateFlow()

    fun refresh() = source.refresh()

    fun consumeEvent() {
        mutableEvent.value = null
    }

    fun exportFileName(id: String): String = source.exportFileName(id)

    /** A copy of a layout (a bundled one included) under a new id, named [name]. */
    fun duplicateLayout(
        id: String,
        name: String,
    ) = edit { registry ->
        val original = registry.resolvedLayout(id) ?: throw LayoutException.UnknownId(id)
        registry.copy(original, newId(), name).first
    }

    fun deleteLayout(id: String) = edit { it.remove(id) }

    /** Puts an edited bundled layout back to what ships with the app. */
    fun restorePreset(id: String) =
        edit { registry ->
            val preset = BuiltinLayouts.byId(id) ?: throw LayoutException.UnknownId(id)
            registry.replace(preset)
        }

    fun importLayout(uri: Uri) {
        viewModelScope.launch {
            when (val outcome = source.importFrom(uri)) {
                is LayoutImportOutcome.Imported ->
                    mutableEvent.value =
                        LibraryEvent.Imported(outcome.name, outcome.copied)
                is LayoutImportOutcome.Rejected -> mutableEvent.value = LibraryEvent.Failed(outcome.error)
            }
        }
    }

    fun exportLayout(
        id: String,
        uri: Uri,
    ) {
        viewModelScope.launch {
            mutableEvent.value = if (source.exportTo(id, uri)) LibraryEvent.Exported else LibraryEvent.ExportFailed
        }
    }

    /**
     * A copy of a page under a new id; an automatic page becomes the editable rows it stands for, and
     * the built-in categorized page the categories the keyboard shows now.
     */
    fun duplicatePage(
        id: String,
        name: String,
    ) {
        viewModelScope.launch {
            val snapshot =
                if (registry.value?.page(id)?.let { it.isCategorized && it.categories == null } == true) {
                    source.symbolPageCategories()
                } else {
                    null
                }
            edit { registry ->
                val original =
                    registry.page(id)
                        ?: throw LayoutException.Invalid(app.songyan.ime.layout.LayoutInvalidCode.PAGE_UNKNOWN, id)
                val seeded = if (snapshot != null) original.withCategories(snapshot) else original
                registry.withPage(seeded.duplicated(newId(), name))
            }
        }
    }

    fun deletePage(id: String) = edit { it.withoutPage(id) }

    /** Saves the pairing of one keyboard: its main layout and its 123, symbol and ABC pages. */
    fun saveComposition(
        key: KeyboardCompositionKey,
        composition: KeyboardComposition,
    ) = edit { it.withComposition(composition, key.rawValue) }

    private fun edit(change: (LayoutRegistry) -> LayoutRegistry) {
        viewModelScope.launch {
            val result = source.edit(change)
            if (result is LayoutEditResult.Rejected) mutableEvent.value = LibraryEvent.Failed(result.error)
        }
    }

    class Factory : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(
            modelClass: Class<T>,
            extras: CreationExtras,
        ): T {
            @Suppress("UNCHECKED_CAST")
            return LayoutLibraryViewModel(LayoutLibraryHolder.current) as T
        }
    }
}

/** What saving an editor draft did. */
sealed interface SaveOutcome {
    data object Saved : SaveOutcome

    data class Failed(
        val error: LayoutException,
    ) : SaveOutcome
}

/**
 * The draft of one layout. The layout is read from the catalog once; nothing is written until
 * [save] or [saveAsCopy] succeeds, and a refused save keeps the draft so the user can fix it.
 */
class LayoutEditorViewModel(
    private val source: LayoutLibrarySource,
    private val layoutId: String,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val mutableDraft = MutableStateFlow<LayoutDraft?>(null)
    val draft: StateFlow<LayoutDraft?> = mutableDraft.asStateFlow()

    private val mutableOutcome = MutableStateFlow<SaveOutcome?>(null)
    val outcome: StateFlow<SaveOutcome?> = mutableOutcome.asStateFlow()

    init {
        viewModelScope.launch {
            val registry = source.registry.first { it != null }
            mutableDraft.value = registry?.resolvedLayout(layoutId)?.let { LayoutDraft(it) }
        }
    }

    fun update(change: (LayoutDraft) -> LayoutDraft) = mutableDraft.update { it?.let(change) }

    fun consumeOutcome() {
        mutableOutcome.value = null
    }

    fun save() {
        val current = mutableDraft.value ?: return
        saveWith { registry -> registry.replace(current.layout) }
    }

    /** Stores the draft as a new layout named [name] and leaves the original as it was. */
    fun saveAsCopy(name: String) {
        val current = mutableDraft.value ?: return
        saveWith { registry -> registry.copy(current.layout, newId(), name).first }
    }

    private fun saveWith(change: (LayoutRegistry) -> LayoutRegistry) {
        viewModelScope.launch {
            mutableOutcome.value =
                when (val result = source.edit(change)) {
                    is LayoutEditResult.Saved -> SaveOutcome.Saved
                    is LayoutEditResult.Rejected -> SaveOutcome.Failed(result.error)
                }
        }
    }

    class Factory(
        private val layoutId: String,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(
            modelClass: Class<T>,
            extras: CreationExtras,
        ): T {
            @Suppress("UNCHECKED_CAST")
            return LayoutEditorViewModel(LayoutLibraryHolder.current, layoutId) as T
        }
    }
}

/** The draft of one page, with the same save rules as [LayoutEditorViewModel]. */
class PageEditorViewModel(
    private val source: LayoutLibrarySource,
    private val pageId: String,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val mutableDraft = MutableStateFlow<PageDraft?>(null)
    val draft: StateFlow<PageDraft?> = mutableDraft.asStateFlow()

    private val mutableOutcome = MutableStateFlow<SaveOutcome?>(null)
    val outcome: StateFlow<SaveOutcome?> = mutableOutcome.asStateFlow()

    init {
        viewModelScope.launch {
            val registry = source.registry.first { it != null }
            mutableDraft.value = registry?.page(pageId)?.let { PageDraft(it) }
        }
    }

    fun update(change: (PageDraft) -> PageDraft) = mutableDraft.update { it?.let(change) }

    /** A fresh id for a category the user adds or duplicates. */
    fun newCategoryId(): String = newId()

    fun consumeOutcome() {
        mutableOutcome.value = null
    }

    fun save() {
        val current = mutableDraft.value ?: return
        saveWith { registry -> registry.withPage(current.page) }
    }

    fun saveAsCopy(name: String) {
        val current = mutableDraft.value ?: return
        saveWith { registry -> registry.withPage(current.page.copy(id = newId(), name = name)) }
    }

    private fun saveWith(change: (LayoutRegistry) -> LayoutRegistry) {
        viewModelScope.launch {
            mutableOutcome.value =
                when (val result = source.edit(change)) {
                    is LayoutEditResult.Saved -> SaveOutcome.Saved
                    is LayoutEditResult.Rejected -> SaveOutcome.Failed(result.error)
                }
        }
    }

    class Factory(
        private val pageId: String,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(
            modelClass: Class<T>,
            extras: CreationExtras,
        ): T {
            @Suppress("UNCHECKED_CAST")
            return PageEditorViewModel(LayoutLibraryHolder.current, pageId) as T
        }
    }
}

/** A categorized symbol page that carries its own categories: the editor edits them instead of rows. */
val KeyboardPageDefinition.isCategorizedWithCategories: Boolean
    get() = symbolPresentation == KeyboardSymbolPresentation.CATEGORIZED && categories != null

/**
 * Page definitions the page library can show and edit: those the keyboard draws from rows, and the
 * categorized symbol page once it carries categories of its own (the built-in one is copied first).
 */
val KeyboardPageDefinition.isEditablePage: Boolean
    get() = !resolvesAtRuntime && (symbolPresentation != KeyboardSymbolPresentation.CATEGORIZED || categories != null)

private val KeyboardPageDefinition.isCategorized: Boolean
    get() = symbolPresentation == KeyboardSymbolPresentation.CATEGORIZED
