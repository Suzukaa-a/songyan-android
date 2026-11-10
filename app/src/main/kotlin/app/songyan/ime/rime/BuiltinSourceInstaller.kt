package app.songyan.ime.rime

import app.songyan.ime.packages.JapaneseSchemePresets
import java.io.File
import kotlin.math.roundToLong

/**
 * Materialises the bundled Rime data (built-in package, prelude supplements and
 * OpenCC dictionaries) into an ordinary directory. librime needs real files:
 * it memory-maps dictionaries, so APK assets cannot be read in place.
 *
 * Mirrors the iOS frontend rule: package files win, missing prelude
 * configuration files are supplemented from the frontend copy, and a missing
 * `default.yaml` gets its `schema_list` rewritten to the schemas actually
 * installed.
 */
class BuiltinSourceInstaller(
    private val assets: AssetTree,
    private val packageId: String = "songyan-builtin",
) {
    fun install(
        target: File,
        stamp: String,
    ) {
        val stampFile = File(target, STAMP_NAME)
        if (stampFile.isFile && stampFile.readText().trim() == stamp && File(target, "default.yaml").isFile) {
            return
        }
        target.deleteRecursively()
        target.mkdirs()

        copyTree("builtin", target)
        supplementFrontendFiles(target)
        stampFile.writeText(stamp)
    }

    /**
     * Adds what librime needs beside a source tree whose configuration it does not provide itself:
     * the OpenCC data, the prelude presets the tree does not override, and the upstream notices.
     *
     * Exposed because an imported dictionary source is built from the same templates and needs the
     * same complete tree; existing files always win, so a source that ships its own copy is kept.
     */
    fun supplementFrontendFiles(target: File) {
        supplementPresets(target)
        // A package's own OpenCC data wins: its configs may reference tables the bundled set lacks.
        copyTree("opencc", File(target, "opencc"), keepExisting = true)
        for (name in ATTRIBUTION_FILES) {
            val assetPath = "prelude/$name"
            if (File(target, "frontend-rime-prelude/$name").isFile) continue
            if (!assetExists(assetPath)) continue
            copyAssetFile(assets, assetPath, File(target, "frontend-rime-prelude/$name"))
        }
        mergeStarterDictionary(target)
    }

    /** The stamp written by [install], or null when the source was never materialised here. */
    fun installedStamp(target: File): String? =
        File(target, STAMP_NAME)
            .takeIf { it.isFile }
            ?.readText()
            ?.trim()

    /**
     * The package ships a deliberately small starter dictionary; the Android build bundles a
     * broader starter table separately so a fresh install can type common words before the user
     * imports anything. The rows are appended to the package dictionary rather than replacing it.
     *
     * The two tables weigh on different scales: the package's hand-set weights top out in the
     * thousands, the starter's corpus frequencies in the hundreds of thousands. A word both list
     * therefore takes the starter's frequency, otherwise a common character the package also lists
     * (你, 我, 是, 的) would sink below every rarer homophone the starter adds. The package still
     * decides how that frequency splits across the word's readings: each reading gets its share
     * relative to the word's strongest reading in the package (的 de 3000 / di 60).
     */
    private fun mergeStarterDictionary(target: File) {
        val basic = File(target, BASIC_DICTIONARY)
        if (!basic.isFile) return
        val starter =
            try {
                assets.open(STARTER_DICTIONARY).use { it.readBytes() }
            } catch (_: java.io.IOException) {
                return
            }
        val lines = basic.readText().split('\n')
        val headerEnd = lines.indexOfFirst { it.trim() == "..." }
        if (headerEnd < 0) return

        val rows = ArrayList<String>()
        // Package row index per word/reading, and each word's strongest package weight.
        val packageRows = HashMap<String, Int>()
        val packageMax = HashMap<String, Long>()
        for (index in headerEnd + 1 until lines.size) {
            val line = lines[index]
            val columns = dictionaryColumns(line)
            if (columns != null) {
                packageRows[columns[0] + '\u0000' + columns[1]] = rows.size
                val weight = weightOf(columns)
                if (weight != null) packageMax.merge(columns[0], weight, ::maxOf)
            }
            rows += line
        }
        val keys = HashSet(packageRows.keys)
        var changed = false
        for (line in starter.toString(Charsets.UTF_8).split('\n')) {
            if (line.isEmpty() || line.startsWith("#")) continue
            val columns = dictionaryColumns(line) ?: continue
            val key = columns[0] + '\u0000' + columns[1]
            val packageRow = packageRows[key]
            if (packageRow == null) {
                if (keys.add(key)) {
                    rows += line
                    changed = true
                }
                continue
            }
            val packageColumns = requireNotNull(dictionaryColumns(rows[packageRow]))
            val packageWeight = weightOf(packageColumns) ?: continue
            val starterWeight = weightOf(columns) ?: continue
            val strongest = packageMax[columns[0]] ?: continue
            if (strongest <= 0) continue
            val scaled = (starterWeight.toDouble() * packageWeight / strongest).roundToLong()
            if (scaled <= packageWeight) continue
            packageColumns[2] = scaled.toString()
            rows[packageRow] = packageColumns.joinToString("\t")
            changed = true
        }
        if (!changed) return
        basic.writeText((lines.subList(0, headerEnd + 1) + rows).joinToString("\n") + "\n")
    }

    /** The tab-separated columns of an entry row, or null for comments and non-entry lines. */
    private fun dictionaryColumns(line: String): MutableList<String>? {
        if (line.startsWith("#")) return null
        val columns = line.split('\t')
        return if (columns.size < 2) null else columns.toMutableList()
    }

    /** The integer weight column; null when absent or written in another form (e.g. a percentage). */
    private fun weightOf(columns: List<String>): Long? = columns.getOrNull(2)?.trim()?.toLongOrNull()

    private fun supplementPresets(target: File) {
        val schemas =
            target
                .listFiles()
                .orEmpty()
                .filter { it.isFile && it.name.endsWith(".schema.yaml") }
                .map { it.name.removeSuffix(".schema.yaml") }
                .sorted()
        for (name in PRESET_FILES) {
            val destination = File(target, name)
            if (destination.isFile) continue
            val assetPath = "prelude/$name"
            if (!assetExists(assetPath)) continue
            val bytes = assets.open(assetPath).use { it.readBytes() }
            val data =
                if (name == "default.yaml") {
                    rewriteSchemaList(bytes, schemas)
                } else {
                    bytes
                }
            destination.writeBytes(data)
        }
        // Scheme-specific patches travel beside the package sources, never inside them.
        JapaneseSchemePresets.supplement(target)
    }

    private fun rewriteSchemaList(
        bytes: ByteArray,
        schemas: List<String>,
    ): ByteArray {
        val text = bytes.toString(Charsets.UTF_8)
        val list =
            if (schemas.isEmpty()) {
                "schema_list: []\n"
            } else {
                "schema_list:\n" + schemas.joinToString("") { "  - schema: $it\n" }
            }
        val replaced = SCHEMA_LIST_BLOCK.replace(text) { list }
        return replaced.toByteArray(Charsets.UTF_8)
    }

    private fun copyTree(
        assetPath: String,
        target: File,
        keepExisting: Boolean = false,
    ) {
        target.mkdirs()
        for (name in assets.list(assetPath)) {
            val childAssetPath = if (assetPath.isEmpty()) name else "$assetPath/$name"
            val childTarget = File(target, name)
            if (assets.isDirectory(childAssetPath)) {
                copyTree(childAssetPath, childTarget, keepExisting)
            } else if (!keepExisting || !childTarget.exists()) {
                copyAssetFile(assets, childAssetPath, childTarget)
            }
        }
    }

    private fun assetExists(assetPath: String): Boolean =
        try {
            assets.open(assetPath).use { }
            true
        } catch (_: java.io.IOException) {
            false
        }

    companion object {
        /**
         * Bumped whenever the bundled Rime data changes in a way existing installs must pick up.
         * It participates in the install stamp so an app update re-deploys the built-in package
         * instead of reusing a generation compiled from older data.
         */
        const val DATA_VERSION: String = "6"

        private const val STAMP_NAME = ".installed"
        private const val BASIC_DICTIONARY = "songyan_basic.dict.yaml"
        private const val STARTER_DICTIONARY = "starter/songyan_starter.dict.yaml"
        private val PRESET_FILES = listOf("default.yaml", "key_bindings.yaml", "punctuation.yaml", "symbols.yaml")
        private val ATTRIBUTION_FILES = listOf("LICENSE", "AUTHORS", "PROVENANCE.md", "COPYING")
        private val SCHEMA_LIST_BLOCK = Regex("(?m)^schema_list:\n(?:  - schema: [^\n]+\n)*")
    }
}
