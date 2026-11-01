package app.songyan.ime.layout

import android.content.Context
import android.net.Uri
import app.songyan.ime.content.QuickContentRepository
import app.songyan.ime.ime.KeyboardLayoutCatalog
import app.songyan.ime.prefs.LogAndContinue
import app.songyan.ime.settings.state.LayoutEditResult
import app.songyan.ime.settings.state.LayoutImportOutcome
import app.songyan.ime.settings.state.LayoutLibraryHolder
import app.songyan.ime.settings.state.LayoutLibrarySource
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.declaredInputMethodsWhileIdle
import app.songyan.ime.storage.readAtMost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * The settings bridge onto [LayoutCatalog]: it adds the picked documents. The keyboard receives each
 * stored catalog through [KeyboardLayoutCatalog], in the same process, so a layout saved in settings is
 * on the keyboard the next time it draws.
 */
class LayoutRepository internal constructor(
    private val context: Context,
    private val catalog: LayoutCatalog,
    private val contentDirectory: File? = null,
    /** The input methods still provided on the device (see [declaredInputMethodsWhileIdle]); null when unknown. */
    private val inputMethods: () -> Set<String>? = { null },
) : LayoutLibrarySource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + LogAndContinue)

    override val registry: StateFlow<LayoutRegistry?> get() = catalog.registry

    /** Reads the stored catalog without blocking the caller. */
    fun start() = refresh()

    override fun refresh() {
        scope.launch {
            catalog.load()
            dropPairingsWithoutInputMethod()
        }
    }

    /**
     * A pairing outlives the scheme package that offered its input method (the package was deleted),
     * so it is dropped here, from what is installed now: convergent, and an inventory that cannot be
     * read drops nothing.
     */
    private suspend fun dropPairingsWithoutInputMethod() {
        val providers = inputMethods() ?: return
        catalog.pruneCompositions { it !in providers }
    }

    override suspend fun edit(change: (LayoutRegistry) -> LayoutRegistry): LayoutEditResult = catalog.edit(change)

    override suspend fun symbolPageCategories(): List<KeyboardPageCategory> =
        withContext(Dispatchers.IO) {
            val directory = contentDirectory ?: return@withContext super.symbolPageCategories()
            val symbols = QuickContentRepository(directory).loadSymbols()
            val library =
                symbols.catalog.orderedCategories.map { category ->
                    SymbolPageCategories.LibrarySet(
                        category.id,
                        category.name,
                        symbols.catalog.itemsIn(category.id).map { it.text },
                    )
                }
            SymbolPageCategories.snapshot(symbols.recents, library, BuiltinLayouts.t9QuickSymbolValues("", false))
        }

    override suspend fun importFrom(uri: Uri): LayoutImportOutcome =
        withContext(Dispatchers.IO) {
            val text =
                try {
                    LayoutFileReader.read(context, uri)
                } catch (error: LayoutException) {
                    return@withContext LayoutImportOutcome.Rejected(error)
                } catch (error: IOException) {
                    return@withContext LayoutImportOutcome.Rejected(
                        LayoutException.Invalid(LayoutInvalidCode.UNREADABLE_SOURCE),
                    )
                }
            catalog.import(text)
        }

    override suspend fun exportTo(
        id: String,
        uri: Uri,
    ): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val text = catalog.export(id) ?: return@runCatching false
                val output = context.contentResolver.openOutputStream(uri, "wt") ?: return@runCatching false
                output.use { it.write(text.encodeToByteArray()) }
                true
            }.getOrDefault(false)
        }

    companion object {
        /**
         * Builds the repository, starts the load and installs the settings bridge. Only the application
         * context is captured; [storage] is passed in so no path is resolved on the main thread.
         */
        fun create(
            context: Context,
            storage: AppStorage,
        ): LayoutRepository {
            val catalog =
                LayoutCatalog(
                    store = LayoutStore(storage.layoutsDir.absolutePath),
                    io = Dispatchers.IO,
                    publish = KeyboardLayoutCatalog::install,
                )
            val repository =
                LayoutRepository(context.applicationContext, catalog, storage.contentDir) {
                    declaredInputMethodsWhileIdle(storage)
                }
            LayoutLibraryHolder.install(repository)
            repository.start()
            return repository
        }
    }
}

/** Reads a picked layout file within the format's byte budget, so a huge file never fills memory. */
internal object LayoutFileReader {
    @Throws(LayoutException::class, IOException::class)
    fun read(
        context: Context,
        uri: Uri,
    ): String {
        val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("no stream")
        stream.use { return decode(it.readAtMost(LayoutResourceBudget.DEFINITION_BYTES + 1)) }
    }

    internal fun decode(bytes: ByteArray): String {
        if (bytes.size > LayoutResourceBudget.DEFINITION_BYTES) {
            throw LayoutException.Invalid(LayoutInvalidCode.RESOURCE_BUDGET_EXCEEDED, "definitionBytes")
        }
        return bytes.decodeToString()
    }
}
