package app.songyan.ime.packages

import app.songyan.ime.keyboard.engine.InputLanguageResolver
import java.io.File

/**
 * The language a dictionary's text is written in, read at deployment so candidates are drawn with
 * that language's glyphs instead of always Simplified Chinese. Kana entries (5 % or more) make it
 * Japanese, Hangul entries (20 % or more) Korean; Chinese is told by the characters only one script
 * uses (20 or more of one side and at least twice the other); an even mix follows the system's
 * Chinese script; a dictionary with none of these forces no language.
 */
object DictionaryLanguage {
    private const val MAX_BYTES = 4L * 1024 * 1024
    private const val MAX_ENTRIES = 50_000
    private const val KANA_SHARE = 0.05
    private const val HANGUL_SHARE = 0.20
    private const val SCRIPT_MIN = 20
    private const val SCRIPT_RATIO = 2

    private val simplifiedOnly: Set<Int>
    private val traditionalOnly: Set<Int>

    init {
        val lines =
            requireNotNull(DictionaryLanguage::class.java.getResourceAsStream("/script-characters.txt")) {
                "script-characters.txt is missing"
            }.use { it.readBytes().toString(Charsets.UTF_8).lines() }
        simplifiedOnly = lines[0].codePoints().toArray().toSet()
        traditionalOnly = lines[1].codePoints().toArray().toSet()
    }

    /** The wire value for the entries' [texts]: `zh-Hans`, `zh-Hant`, `ja`, `ko`, `zh` (system) or `none`. */
    fun detect(texts: Sequence<String>): String {
        var entries = 0
        var kana = 0
        var hangul = 0
        var simplified = 0
        var traditional = 0
        var han = false
        for (text in texts) {
            entries++
            var hasKana = false
            var hasHangul = false
            text.codePoints().forEach { cp ->
                when {
                    isKana(cp) -> hasKana = true
                    isHangul(cp) -> hasHangul = true
                    cp in simplifiedOnly -> {
                        simplified++
                        han = true
                    }
                    cp in traditionalOnly -> {
                        traditional++
                        han = true
                    }
                    isHan(cp) -> han = true
                }
            }
            if (hasKana) kana++
            if (hasHangul) hangul++
        }
        return when {
            entries == 0 -> InputLanguageResolver.DETECTED_NONE
            kana.toDouble() / entries >= KANA_SHARE -> "ja"
            hangul.toDouble() / entries >= HANGUL_SHARE -> "ko"
            simplified >= SCRIPT_MIN && simplified >= SCRIPT_RATIO * traditional -> "zh-Hans"
            traditional >= SCRIPT_MIN && traditional >= SCRIPT_RATIO * simplified -> "zh-Hant"
            han -> InputLanguageResolver.DETECTED_SYSTEM_CHINESE
            else -> InputLanguageResolver.DETECTED_NONE
        }
    }

    /**
     * The language of the dictionary [name] in [sourceDir] (`<name>.dict.yaml`, with the tables it imports
     * one level down); null when the file cannot be read. Reads at most 4 MiB and 50,000 entries.
     */
    fun read(
        sourceDir: File,
        name: String,
    ): String? {
        val main = dictionaryFile(sourceDir, name) ?: return null
        val budget = Budget()
        val texts = ArrayList<String>()
        val imports = readTable(main, texts, budget) ?: return null
        for (table in imports) {
            val file = dictionaryFile(sourceDir, table) ?: continue
            readTable(file, texts, budget)
        }
        return detect(texts.asSequence())
    }

    private class Budget {
        var bytes = 0L
        var entries = 0
        val spent get() = bytes >= MAX_BYTES || entries >= MAX_ENTRIES
    }

    private fun dictionaryFile(
        sourceDir: File,
        name: String,
    ): File? =
        if (name.isEmpty() || name.any { it == '/' || it == '\\' } || name == "." || name == "..") {
            null
        } else {
            File(sourceDir, "$name.dict.yaml").takeIf { it.isFile }
        }

    /** The entries' text added to [texts]; returns the tables the header imports, or null when unreadable. */
    private fun readTable(
        file: File,
        texts: MutableList<String>,
        budget: Budget,
    ): List<String>? =
        try {
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                val imports = ArrayList<String>()
                var columns = listOf("text", "code", "weight")
                var inHeader = true
                val header = ArrayList<String>()
                while (!budget.spent) {
                    val line = reader.readLine() ?: break
                    budget.bytes += line.length + 1
                    if (inHeader) {
                        if (line == "...") {
                            inHeader = false
                            columns = headerList(header, "columns").ifEmpty { columns }
                            imports += headerList(header, "import_tables")
                        } else {
                            header += line
                        }
                        continue
                    }
                    if (line.isEmpty() || line.startsWith("#")) continue
                    val fields = line.split('\t')
                    val at = columns.indexOf("text").coerceAtLeast(0)
                    fields.getOrNull(at)?.takeIf { it.isNotEmpty() }?.let {
                        texts += it
                        budget.entries++
                    }
                }
                // A file without a header end is not a dictionary table.
                if (inHeader) null else imports
            }
        } catch (_: java.io.IOException) {
            null
        }

    private fun headerList(
        header: List<String>,
        key: String,
    ): List<String> {
        val start = header.indexOfFirst { it.trim() == "$key:" }
        if (start < 0) return emptyList()
        return header
            .drop(start + 1)
            .takeWhile { it.trimStart().startsWith("- ") }
            .map {
                it
                    .trim()
                    .removePrefix("- ")
                    .trim()
                    .trim('"', '\'')
            }
    }

    private fun isKana(cp: Int) = cp in 0x3040..0x30FF || cp in 0x31F0..0x31FF || cp in 0xFF66..0xFF9F

    private fun isHangul(cp: Int) = cp in 0xAC00..0xD7A3 || cp in 0x1100..0x11FF || cp in 0x3130..0x318F

    private fun isHan(cp: Int) =
        cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0x20000..0x2A6DF || cp in 0xF900..0xFAFF
}
