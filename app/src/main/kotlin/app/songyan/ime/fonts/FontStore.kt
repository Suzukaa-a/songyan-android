package app.songyan.ime.fonts

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import app.songyan.ime.appearance.font.FontCascade
import app.songyan.ime.appearance.font.FontFile
import app.songyan.ime.appearance.font.FontFileNames
import app.songyan.ime.appearance.font.FontRole
import app.songyan.ime.ime.DisplayFontSpec
import app.songyan.ime.ime.DisplayFonts
import app.songyan.ime.prefs.KeyboardPreferences
import app.songyan.ime.settings.state.FontEntry
import app.songyan.ime.settings.state.FontGateway
import app.songyan.ime.settings.state.FontImportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * The imported font files in app storage, their import order and the display font choices. The
 * keyboard and the settings screen share this one view of them: the order and choices are
 * preferences, the files are copies made at import so they outlive the picker's grant.
 */
class FontStore(
    private val context: Context,
    private val preferences: KeyboardPreferences,
    private val dir: File,
) : FontGateway {
    override val fonts: Flow<List<FontEntry>> =
        preferences.fontOrder
            .map { order -> order.map { name -> FontEntry(name, familyOf(name)) } }
            .flowOn(Dispatchers.IO)

    override fun choice(role: FontRole): Flow<String> = preferences.displayFont(role)

    /** The font files that draw candidates and keys, resolved against what can be read right now. */
    val displayFonts: Flow<DisplayFontSpec> =
        combine(
            preferences.fontOrder,
            preferences.displayFont(FontRole.CANDIDATES),
            preferences.displayFont(FontRole.KEYS),
        ) { order, candidates, keys ->
            val readable = order.filterTo(HashSet()) { familyOf(it) != null }
            DisplayFontSpec(
                candidates = FontCascade.files(candidates, order, readable).map { File(dir, it) },
                keys = FontCascade.files(keys, order, readable).map { File(dir, it) },
            )
        }.distinctUntilChanged().flowOn(Dispatchers.IO)

    override suspend fun import(uris: List<Uri>): FontImportResult =
        withContext(Dispatchers.IO) {
            if (!dir.isDirectory && !dir.mkdirs()) return@withContext FontImportResult(failed = true)
            dir.listFiles { f -> f.name.startsWith(TEMP_PREFIX) }?.forEach { it.delete() }
            val imported = ArrayList<String>()
            val rejected = ArrayList<String>()
            var failed = false
            for (uri in uris) {
                val display = displayName(uri)
                val temp = File(dir, TEMP_PREFIX + System.nanoTime())
                try {
                    if (!copy(uri, temp)) {
                        rejected.add(display)
                        continue
                    }
                    val info = FontFile.read(temp)
                    if (info == null) {
                        rejected.add(display)
                        continue
                    }
                    val stored =
                        FontFileNames.storedName(
                            display,
                            dir
                                .list {
                                    _,
                                    n,
                                    ->
                                    !n.startsWith(TEMP_PREFIX)
                                }.orEmpty()
                                .toList(),
                        )
                    if (!temp.renameTo(File(dir, stored))) {
                        failed = true
                        continue
                    }
                    preferences.updateFontOrder { it + stored }
                    imported.add(info.familyName)
                } catch (_: IOException) {
                    failed = true
                } finally {
                    temp.delete()
                }
            }
            FontImportResult(imported, rejected, failed)
        }

    override suspend fun delete(file: String) {
        withContext(Dispatchers.IO) {
            preferences.forgetFont(file)
            File(dir, file).delete()
        }
    }

    override suspend fun choose(
        role: FontRole,
        file: String,
    ) = preferences.setDisplayFont(role, file)

    override fun previewTypeface(files: List<String>): Typeface? = DisplayFonts.build(files.map { File(dir, it) })

    private val families = HashMap<String, Pair<String, String?>>()

    /** Family name of a stored font, null if the file is gone or no longer a font; cached per file state. */
    @Synchronized
    private fun familyOf(name: String): String? {
        val file = File(dir, name)
        val stamp = "${file.length()}:${file.lastModified()}"
        families[name]?.let { (seen, family) -> if (seen == stamp) return family }
        val family = FontFile.read(file)?.familyName
        families[name] = stamp to family
        return family
    }

    private fun displayName(uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    /** Copies [uri] to [to]; false when it is larger than a font may be. */
    private fun copy(
        uri: Uri,
        to: File,
    ): Boolean {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("cannot open")
        input.use { source ->
            to.outputStream().use { sink ->
                val buffer = ByteArray(BUFFER)
                var total = 0L
                while (true) {
                    val n = source.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > FontFile.MAX_BYTES) return false
                    sink.write(buffer, 0, n)
                }
            }
        }
        return true
    }

    private companion object {
        const val TEMP_PREFIX = ".import-"
        const val BUFFER = 64 * 1024
    }
}
