package app.songyan.ime.packages

import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.common.ScalarStyle
import org.snakeyaml.engine.v2.nodes.MappingNode
import org.snakeyaml.engine.v2.nodes.Node
import org.snakeyaml.engine.v2.nodes.ScalarNode
import org.snakeyaml.engine.v2.nodes.SequenceNode
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Base64

/**
 * A YAML 1.1 load and dump that reproduces Yams 5.4.0 (libyaml 0.2.x) behaviour, which the iOS
 * companion generator uses for both reading compiled schemas (`Yams.load`) and writing the
 * generated files (`Yams.dump(sortKeys: true)`). Companion files are compared byte for byte, so
 * type resolution (`yes` is a Bool, `0b11` an Int, `1.0` is written `1e+0`), key order, quoting,
 * `\uXXXX` escaping of every non-ASCII character and the 80-column folding of long scalars all
 * follow Yams instead of the YAML 1.2 loader in [BoundedYaml].
 *
 * Constructed values are String, Boolean, Long, Double, [Null], [Timestamp], [Binary],
 * `Map<String, Any?>` and `List<Any?>`.
 *
 * Not reproduced: `!!set`, `!!omap` and `!!pairs` collections (loaded as plain maps and lists, as
 * the schemas this is used for never contain them).
 */
internal object YamsYaml {
    /** `NSNull`. */
    object Null {
        override fun toString(): String = "null"
    }

    /** A `Date` constructed from a YAML timestamp; [text] is the iso8601 text Yams dumps. */
    class Timestamp internal constructor(
        val text: String,
    ) {
        override fun equals(other: Any?): Boolean = other is Timestamp && other.text == text

        override fun hashCode(): Int = text.hashCode()
    }

    /** `Data` constructed from `!!binary`; [base64] is the canonical base64 Yams dumps. */
    class Binary internal constructor(
        val base64: String,
    ) {
        override fun equals(other: Any?): Boolean = other is Binary && other.base64 == base64

        override fun hashCode(): Int = base64.hashCode()
    }

    // ---------------------------------------------------------------------------------------
    // Load
    // ---------------------------------------------------------------------------------------

    /**
     * `BoundedYAML.load(yaml:)`: null for an empty document. Throws on syntax errors and on
     * documents beyond the alias budgets.
     */
    fun load(text: String): Any? {
        recent.get(text)?.let { return copyOf(it) }
        val root = BoundedYaml.compose(text) ?: return null
        val value = construct(root, 0)
        recent.put(text, value)
        return copyOf(value)
    }

    /**
     * The last few documents loaded. One deployment reads the same compiled schema many times (once
     * for each engine feature and each compact layout it checks), and parsing it is most of the
     * deployment's own work. Callers always get their own copy, so none can change what another
     * reads.
     */
    private val recent = RecentDocuments()

    private class RecentDocuments {
        private val entries = LinkedHashMap<String, Any?>(MAX_DOCUMENTS, 0.75f, true)

        @Synchronized
        fun get(text: String): Any? = entries[text]

        @Synchronized
        fun put(
            text: String,
            value: Any?,
        ) {
            if (value == null || text.length > MAX_DOCUMENT_CHARS) return
            entries[text] = value
            if (entries.size > MAX_DOCUMENTS) entries.remove(entries.keys.first())
        }
    }

    /** A copy whose maps and lists are new; scalars are immutable and shared. */
    private fun copyOf(value: Any?): Any? =
        when (value) {
            is Map<*, *> -> {
                val copy = LinkedHashMap<String, Any?>(value.size * 2)
                for ((key, item) in value) copy[key as String] = copyOf(item)
                copy
            }
            is List<*> -> value.mapTo(ArrayList(value.size)) { copyOf(it) }
            else -> value
        }

    private const val MAX_DOCUMENTS = 4
    private const val MAX_DOCUMENT_CHARS = 1 shl 20

    /** `try? load(...) as? [String: Any]`. */
    fun loadMap(text: String): Map<String, Any?>? =
        try {
            load(text).asStringMap()
        } catch (_: Exception) {
            null
        }

    private const val MAX_DEPTH = 512
    private const val TAG_PREFIX = "tag:yaml.org,2002:"
    private const val STR = TAG_PREFIX + "str"
    private const val BOOL = TAG_PREFIX + "bool"
    private const val INT = TAG_PREFIX + "int"
    private const val FLOAT = TAG_PREFIX + "float"
    private const val NULL = TAG_PREFIX + "null"
    private const val MERGE = TAG_PREFIX + "merge"
    private const val TIMESTAMP = TAG_PREFIX + "timestamp"
    private const val VALUE = TAG_PREFIX + "value"
    private const val BINARY = TAG_PREFIX + "binary"

    // The resolver the composer itself uses, so an explicit tag can be told from an implicit one.
    private val composerResolver =
        LoadSettings
            .builder()
            .build()
            .schema.scalarResolver

    // Yams `Resolver.default` rules, in rule order. `$` follows ICU: it also matches before a
    // final line terminator, which is what `find` gives on the Java side.
    private val resolverRules: List<Pair<String, Regex>> =
        listOf(
            BOOL to Regex("^(?:yes|Yes|YES|no|No|NO|true|True|TRUE|false|False|FALSE|on|On|ON|off|Off|OFF)$"),
            INT to
                Regex(
                    "^(?:[-+]?0b[0-1_]+|[-+]?0o?[0-7_]+|[-+]?(?:0|[1-9][0-9_]*)" +
                        "|[-+]?0x[0-9a-fA-F_]+|[-+]?[1-9][0-9_]*(?::[0-5]?[0-9])+)$",
                ),
            FLOAT to
                Regex(
                    "^(?:[-+]?(?:[0-9][0-9_]*)(?:\\.[0-9_]*)?(?:[eE][-+]?[0-9]+)?" +
                        "|\\.[0-9_]+(?:[eE][-+][0-9]+)?" +
                        "|[-+]?[0-9][0-9_]*(?::[0-5]?[0-9])+\\.[0-9_]*" +
                        "|[-+]?\\.(?:inf|Inf|INF)|\\.(?:nan|NaN|NAN))$",
                ),
            MERGE to Regex("^(?:<<)$"),
            NULL to Regex("^(?:~|null|Null|NULL|)$"),
            TIMESTAMP to
                Regex(
                    "^(?:[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]" +
                        "|[0-9][0-9][0-9][0-9]-[0-9][0-9]?-[0-9][0-9]?(?:[Tt]|[ \\t]+)[0-9][0-9]?" +
                        ":[0-9][0-9]:[0-9][0-9](?:\\.[0-9]*)?" +
                        "(?:[ \\t]*(?:Z|[-+][0-9][0-9]?(?::[0-9][0-9])?))?)$",
                ),
            VALUE to Regex("^(?:=)$"),
        )

    /** Yams `Resolver.resolveTag(from:)`. */
    internal fun resolveTag(value: String): String {
        for ((tag, regex) in resolverRules) if (regex.containsMatchIn(value)) return tag
        return STR
    }

    private fun scalarTag(node: ScalarNode): String {
        val plain = node.scalarStyle == ScalarStyle.PLAIN
        val given = node.tag.value
        // Yams marks every quoted scalar as a string and resolves plain ones by value. The tag
        // the YAML 1.2 composer picked tells an explicit `!!tag` apart from an implicit one.
        val implicit = if (plain) composerResolver.resolve(node.value, true).value else STR
        if (given != implicit) {
            if (given == "!") return STR
            return given
        }
        return if (plain) resolveTag(node.value) else STR
    }

    private fun construct(
        node: Node,
        depth: Int,
    ): Any? {
        if (depth > MAX_DEPTH) throw RecipeInstallError.InvalidResource("YAML nesting too deep")
        return when (node) {
            is ScalarNode -> constructScalar(node)
            is MappingNode -> constructMapping(node, depth)
            is SequenceNode -> node.value.map { construct(it, depth + 1) }
            else -> throw RecipeInstallError.InvalidResource("unsupported YAML node")
        }
    }

    private fun constructScalar(node: ScalarNode): Any {
        val plain = node.scalarStyle == ScalarStyle.PLAIN
        val string = node.value
        return when (scalarTag(node)) {
            BOOL ->
                when (string.lowercase()) {
                    "true", "yes", "on" -> true
                    "false", "no", "off" -> false
                    else -> string
                }
            INT -> (if (plain) constructInt(string) else null) ?: string
            FLOAT -> (if (plain) constructFloat(string) else null) ?: string
            NULL -> if (plain && string in setOf("", "~", "null", "Null", "NULL")) Null else string
            TIMESTAMP -> constructTimestamp(string) ?: string
            BINARY -> constructBinary(string) ?: string
            else -> string
        }
    }

    private fun constructMapping(
        node: MappingNode,
        depth: Int,
    ): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        for ((key, value) in flatten(node)) {
            val name =
                (key as? ScalarNode)?.value
                    ?: throw RecipeInstallError.InvalidResource("YAML mapping key is not a scalar")
            result[name] = construct(value, depth + 1)
        }
        return result
    }

    /** Yams `Mapping.flatten()`: merge-key pairs first, then the mapping's own pairs. */
    private fun flatten(node: MappingNode): List<Pair<Node, Node>> {
        val merge = ArrayList<Pair<Node, Node>>()
        val own = ArrayList<Pair<Node, Node>>()
        for (tuple in node.value) {
            val key = tuple.keyNode
            val isMerge = key is ScalarNode && scalarTag(key) == MERGE
            if (!isMerge) {
                own.add(key to tuple.valueNode)
                continue
            }
            when (val value = tuple.valueNode) {
                is MappingNode -> merge.addAll(flatten(value))
                is SequenceNode ->
                    for (item in value.value.filterIsInstance<MappingNode>().reversed()) merge.addAll(flatten(item))
                else -> Unit
            }
        }
        return merge + own
    }

    private fun constructInt(scalar: String): Long? {
        val text = scalar.replace("_", "")
        if (text == "0") return 0L
        val negative = text.startsWith("-")
        val hasSign = negative || text.startsWith("+")
        val body = if (hasSign) text.substring(1) else text
        val sign = if (negative) "-" else ""
        for ((prefix, radix) in listOf("0x" to 16, "0b" to 2, "0o" to 8, "0" to 8)) {
            if (body.startsWith(prefix)) return (sign + body.substring(prefix.length)).toLongOrNull(radix)
        }
        if (body.contains(':')) return sexagesimal(text)?.toLong()
        return text.toLongOrNull()
    }

    private fun constructFloat(scalar: String): Double? {
        when (scalar) {
            ".inf", ".Inf", ".INF", "+.inf", "+.Inf", "+.INF" -> return Double.POSITIVE_INFINITY
            "-.inf", "-.Inf", "-.INF" -> return Double.NEGATIVE_INFINITY
            ".nan", ".NaN", ".NAN" -> return Double.NaN
        }
        val text = scalar.replace("_", "")
        if (text.contains(':')) return sexagesimal(text)
        return text.toDoubleOrNull()
    }

    private fun sexagesimal(value: String): Double? {
        var text = value
        var sign = 1.0
        if (text.startsWith("-")) {
            sign = -1.0
            text = text.substring(1)
        } else if (text.startsWith("+")) {
            text = text.substring(1)
        }
        val parts = splitKeepingEmpty(text, ":").map { it.toDoubleOrNull() }
        if (parts.any { it == null }) return null
        var base = 1.0
        var total = 0.0
        for (digit in parts.reversed()) {
            total += digit!! * base
            base *= 60
        }
        return sign * total
    }

    private val timestampPattern =
        Regex(
            "^([0-9][0-9][0-9][0-9])-([0-9][0-9]?)-([0-9][0-9]?)" +
                "(?:(?:[Tt]|[ \\t]+)([0-9][0-9]?):([0-9][0-9]):([0-9][0-9])(?:\\.([0-9]*))?" +
                "(?:[ \\t]*(Z|([-+])([0-9][0-9]?)(?::([0-9][0-9]))?))?)?$",
        )

    private fun constructTimestamp(string: String): Timestamp? {
        val match = timestampPattern.find(string) ?: return null
        val g = match.groups
        return try {
            val date =
                LocalDateTime.of(
                    g[1]!!.value.toInt(),
                    g[2]!!.value.toInt(),
                    g[3]!!.value.toInt(),
                    g[4]?.value?.toInt() ?: 0,
                    g[5]?.value?.toInt() ?: 0,
                    g[6]?.value?.toInt() ?: 0,
                )
            var offset = (g[10]?.value?.toInt() ?: 0) * 3600 + (g[11]?.value?.toInt() ?: 0) * 60
            if (g[9]?.value == "-") offset = -offset
            val fraction = g[7]?.value
            val nanos =
                when {
                    fraction == null || fraction.isEmpty() -> 0L
                    fraction.length < 9 -> fraction.toLong() * pow10(9 - fraction.length)
                    else -> fraction.substring(0, 9).toLong()
                }
            val seconds = date.toEpochSecond(ZoneOffset.ofTotalSeconds(offset))
            Timestamp(iso8601(seconds, nanos))
        } catch (_: Exception) {
            null
        }
    }

    private fun pow10(n: Int): Long {
        var value = 1L
        repeat(n) { value *= 10 }
        return value
    }

    private fun iso8601(
        epochSeconds: Long,
        nanos: Long,
    ): String {
        var seconds = epochSeconds
        var millis = Math.round(nanos / 1_000_000.0)
        if (millis >= 1000) {
            seconds += 1
            millis -= 1000
        }
        val base = LocalDateTime.ofEpochSecond(seconds, 0, ZoneOffset.UTC)
        val text =
            "%04d-%02d-%02dT%02d:%02d:%02d".format(
                base.year,
                base.monthValue,
                base.dayOfMonth,
                base.hour,
                base.minute,
                base.second,
            )
        if (millis == 0L) return text + "Z"
        return text + (".%03d".format(millis)).trim('0') + "Z"
    }

    private fun constructBinary(string: String): Binary? =
        try {
            val cleaned = string.filter { !it.isWhitespace() }
            Binary(Base64.getEncoder().encodeToString(Base64.getDecoder().decode(cleaned)))
        } catch (_: IllegalArgumentException) {
            null
        }

    // ---------------------------------------------------------------------------------------
    // Dump
    // ---------------------------------------------------------------------------------------

    private sealed interface Tree

    private class Scalar(
        val value: String,
        val singleQuoted: Boolean,
    ) : Tree

    private class Sequence(
        val items: List<Tree>,
    ) : Tree

    private class Mapping(
        val pairs: List<Pair<Scalar, Tree>>,
    ) : Tree

    /** `Yams.dump(object:, sortKeys: true)`. Output ends with a newline. */
    fun dump(value: Any?): String {
        val emitter = Emitter()
        emitter.emitDocument(represent(value))
        return emitter.output()
    }

    private fun represent(value: Any?): Tree =
        when (value) {
            null, Null -> Scalar("null", false)
            is Boolean -> Scalar(if (value) "true" else "false", false)
            is String -> Scalar(value, resolveTag(value) != STR)
            is Int, is Long, is Short, is Byte -> Scalar(value.toString(), false)
            is Double -> Scalar(formatDouble(value, 15), false)
            is Float -> Scalar(formatDouble(value.toString().toDouble(), 7), false)
            is Timestamp -> Scalar(value.text, false)
            is Binary -> Scalar(value.base64, false)
            is Map<*, *> -> {
                val pairs =
                    value.entries.map { (key, item) ->
                        val name =
                            key as? String
                                ?: throw RecipeInstallError.InvalidResource("mapping key is not a string")
                        (represent(name) as Scalar) to represent(item)
                    }
                Mapping(pairs.sortedWith { a, b -> compareStrings(a.first.value, b.first.value) })
            }
            is List<*> -> Sequence(value.map { represent(it) })
            else -> throw RecipeInstallError.InvalidResource("cannot represent " + value::class.simpleName)
        }

    /** Swift `String <`: canonical-equivalence order, i.e. NFC code point order. */
    internal fun compareStrings(
        a: String,
        b: String,
    ): Int {
        val x = Normalizer.normalize(a, Normalizer.Form.NFC).codePoints().toArray()
        val y = Normalizer.normalize(b, Normalizer.Form.NFC).codePoints().toArray()
        for (i in 0 until minOf(x.size, y.size)) if (x[i] != y[i]) return x[i].compareTo(y[i])
        return x.size.compareTo(y.size)
    }

    /** Yams `NumberFormatter`: scientific, at most [digits] significant digits, `e+` exponent. */
    internal fun formatDouble(
        value: Double,
        digits: Int,
    ): String {
        if (value.isNaN()) return ".nan"
        if (value.isInfinite()) return if (value > 0) ".inf" else "-.inf"
        if (value == 0.0) return if (1.0 / value < 0) "-0e+0" else "0e+0"
        val rounded = BigDecimal(value.toString()).round(MathContext(digits, RoundingMode.HALF_EVEN))
        val sign = if (rounded.signum() < 0) "-" else ""
        val all = rounded.unscaledValue().abs().toString()
        val exponent = all.length - rounded.scale() - 1
        val significant = all.trimEnd('0').ifEmpty { "0" }
        val mantissa = if (significant.length > 1) significant[0] + "." + significant.substring(1) else significant
        return sign + mantissa + "e" + (if (exponent >= 0) "+" else "") + exponent
    }

    /** The subset of libyaml's block-style emitter that Yams drives (no tags, anchors or flow style). */
    private class Emitter {
        private val out = StringBuilder()
        private val bestIndent = 2
        private val bestWidth = 80
        private var column = 0
        private var whitespace = true
        private var indention = true
        private var indent = -1
        private val indents = ArrayList<Int>()
        private var flowLevel = 0
        private var simpleKeyContext = false
        private var mappingContext = false

        // Analysis of the scalar that is about to be written.
        private var multiline = false
        private var flowPlainAllowed = false
        private var blockPlainAllowed = false
        private var singleQuotedAllowed = false
        private var scalarCodePoints = IntArray(0)
        private var scalarByteLength = 0

        fun output(): String = out.toString()

        fun emitDocument(root: Tree) {
            emitNode(root, simpleKey = false, mapping = false)
            writeIndent()
        }

        private fun emitNode(
            node: Tree,
            simpleKey: Boolean,
            mapping: Boolean,
        ) {
            simpleKeyContext = simpleKey
            mappingContext = mapping
            when (node) {
                is Scalar -> {
                    analyze(node.value)
                    emitScalar(node)
                }
                is Sequence -> if (node.items.isEmpty()) emitEmptyFlow("[", "]") else emitBlockSequence(node)
                is Mapping -> if (node.pairs.isEmpty()) emitEmptyFlow("{", "}") else emitBlockMapping(node)
            }
        }

        private fun emitEmptyFlow(
            open: String,
            close: String,
        ) {
            writeIndicator(open, needWhitespace = true, isWhitespace = true, isIndention = false)
            increaseIndent(flow = true, indentless = false)
            flowLevel++
            flowLevel--
            indent = indents.removeAt(indents.size - 1)
            writeIndicator(close, needWhitespace = false, isWhitespace = false, isIndention = false)
        }

        private fun emitBlockSequence(node: Sequence) {
            increaseIndent(flow = false, indentless = mappingContext && !indention)
            for (item in node.items) {
                writeIndent()
                writeIndicator("-", needWhitespace = true, isWhitespace = false, isIndention = true)
                emitNode(item, simpleKey = false, mapping = false)
            }
            indent = indents.removeAt(indents.size - 1)
        }

        private fun emitBlockMapping(node: Mapping) {
            increaseIndent(flow = false, indentless = false)
            for ((key, value) in node.pairs) {
                writeIndent()
                analyze(key.value)
                if (!multiline && scalarByteLength <= 128) {
                    emitNode(key, simpleKey = true, mapping = true)
                    writeIndicator(":", needWhitespace = false, isWhitespace = false, isIndention = false)
                } else {
                    writeIndicator("?", needWhitespace = true, isWhitespace = false, isIndention = true)
                    emitNode(key, simpleKey = false, mapping = true)
                    writeIndent()
                    writeIndicator(":", needWhitespace = true, isWhitespace = false, isIndention = true)
                }
                emitNode(value, simpleKey = false, mapping = true)
            }
            indent = indents.removeAt(indents.size - 1)
        }

        private enum class Style { PLAIN, SINGLE, DOUBLE }

        private fun emitScalar(node: Scalar) {
            var style = if (node.singleQuoted) Style.SINGLE else Style.PLAIN
            if (simpleKeyContext && multiline) style = Style.DOUBLE
            if (style == Style.PLAIN) {
                if ((flowLevel > 0 && !flowPlainAllowed) || (flowLevel == 0 && !blockPlainAllowed)) style = Style.SINGLE
                if (scalarByteLength == 0 && (flowLevel > 0 || simpleKeyContext)) style = Style.SINGLE
            }
            if (style == Style.SINGLE && !singleQuotedAllowed) style = Style.DOUBLE
            increaseIndent(flow = true, indentless = false)
            when (style) {
                Style.PLAIN -> writePlain(!simpleKeyContext)
                Style.SINGLE -> writeSingleQuoted(!simpleKeyContext)
                Style.DOUBLE -> writeDoubleQuoted(!simpleKeyContext)
            }
            indent = indents.removeAt(indents.size - 1)
        }

        private fun increaseIndent(
            flow: Boolean,
            indentless: Boolean,
        ) {
            indents.add(indent)
            if (indent < 0) {
                indent = if (flow) bestIndent else 0
            } else if (!indentless) {
                indent += bestIndent
            }
        }

        // ---- scalar analysis (yaml_emitter_analyze_scalar, unicode off) ----

        private fun isBreak(c: Int) = c == 0x0D || c == 0x0A || c == 0x85 || c == 0x2028 || c == 0x2029

        private fun isBlankZ(c: Int) = c == 0x20 || c == 0x09 || isBreak(c) || c == 0

        private fun isPrintable(c: Int) =
            c == 0x0A || c in 0x20..0x7E || c in 0xA0..0xD7FF || (c in 0xE000..0xFFFD && c != 0xFEFF)

        private fun analyze(value: String) {
            val s = value.codePoints().toArray()
            scalarCodePoints = s
            scalarByteLength = value.toByteArray(Charsets.UTF_8).size
            if (s.isEmpty()) {
                multiline = false
                flowPlainAllowed = false
                blockPlainAllowed = true
                singleQuotedAllowed = true
                return
            }
            var blockIndicators = false
            var flowIndicators = false
            var lineBreaks = false
            var special = false
            var leadingSpace = false
            var leadingBreak = false
            var trailingSpace = false
            var trailingBreak = false
            var breakSpace = false
            var spaceBreak = false
            var previousSpace = false
            var previousBreak = false
            if ((s.size >= 3 && s[0] == '-'.code && s[1] == '-'.code && s[2] == '-'.code) ||
                (s.size >= 3 && s[0] == '.'.code && s[1] == '.'.code && s[2] == '.'.code)
            ) {
                blockIndicators = true
                flowIndicators = true
            }
            var precededByWhitespace = true
            var followedByWhitespace = s.size < 2 || isBlankZ(s[1])
            for (i in s.indices) {
                val c = s[i]
                if (i == 0) {
                    if (c < 0x80 && c.toChar() in "#,[]{}&*!|>'\"%@`") {
                        flowIndicators = true
                        blockIndicators = true
                    }
                    if (c == '?'.code || c == ':'.code) {
                        flowIndicators = true
                        if (followedByWhitespace) blockIndicators = true
                    }
                    if (c == '-'.code && followedByWhitespace) {
                        flowIndicators = true
                        blockIndicators = true
                    }
                } else {
                    if (c < 0x80 && c.toChar() in ",?[]{}") flowIndicators = true
                    if (c == ':'.code) {
                        flowIndicators = true
                        if (followedByWhitespace) blockIndicators = true
                    }
                    if (c == '#'.code && precededByWhitespace) {
                        flowIndicators = true
                        blockIndicators = true
                    }
                }
                if (!isPrintable(c) || c > 0x7F) special = true
                if (isBreak(c)) lineBreaks = true
                if (c == 0x20) {
                    if (i == 0) leadingSpace = true
                    if (i == s.size - 1) trailingSpace = true
                    if (previousBreak) breakSpace = true
                    previousSpace = true
                    previousBreak = false
                } else if (isBreak(c)) {
                    if (i == 0) leadingBreak = true
                    if (i == s.size - 1) trailingBreak = true
                    if (previousSpace) spaceBreak = true
                    previousSpace = false
                    previousBreak = true
                } else {
                    previousSpace = false
                    previousBreak = false
                }
                precededByWhitespace = isBlankZ(c)
                if (i + 1 < s.size) followedByWhitespace = i + 2 >= s.size || isBlankZ(s[i + 2])
            }
            multiline = lineBreaks
            flowPlainAllowed = true
            blockPlainAllowed = true
            singleQuotedAllowed = true
            if (leadingSpace || leadingBreak || trailingSpace || trailingBreak) {
                flowPlainAllowed = false
                blockPlainAllowed = false
            }
            if (breakSpace) {
                flowPlainAllowed = false
                blockPlainAllowed = false
                singleQuotedAllowed = false
            }
            if (spaceBreak || special) {
                flowPlainAllowed = false
                blockPlainAllowed = false
                singleQuotedAllowed = false
            }
            if (lineBreaks) {
                flowPlainAllowed = false
                blockPlainAllowed = false
            }
            if (flowIndicators) flowPlainAllowed = false
            if (blockIndicators) blockPlainAllowed = false
        }

        // ---- writers ----

        private fun put(c: Char) {
            out.append(c)
            column++
        }

        private fun putBreak() {
            out.append('\n')
            column = 0
        }

        private fun write(c: Int) {
            out.appendCodePoint(c)
            column++
        }

        private fun writeBreak(c: Int) {
            if (c == 0x0A) {
                putBreak()
            } else {
                out.appendCodePoint(c)
                column = 0
            }
        }

        private fun writeIndent() {
            val target = if (indent >= 0) indent else 0
            if (!indention || column > target || (column == target && !whitespace)) putBreak()
            while (column < target) put(' ')
            whitespace = true
            indention = true
        }

        private fun writeIndicator(
            indicator: String,
            needWhitespace: Boolean,
            isWhitespace: Boolean,
            isIndention: Boolean,
        ) {
            if (needWhitespace && !whitespace) put(' ')
            for (c in indicator) put(c)
            whitespace = isWhitespace
            indention = indention && isIndention
        }

        private fun writePlain(allowBreaks: Boolean) {
            val s = scalarCodePoints
            var spaces = false
            var breaks = false
            if (!whitespace && (s.isNotEmpty() || flowLevel > 0)) put(' ')
            var i = 0
            while (i < s.size) {
                val c = s[i]
                if (c == 0x20) {
                    if (allowBreaks && !spaces && column > bestWidth && !(i + 1 < s.size && s[i + 1] == 0x20)) {
                        writeIndent()
                    } else {
                        write(c)
                    }
                    spaces = true
                } else if (isBreak(c)) {
                    if (!breaks && c == 0x0A) putBreak()
                    writeBreak(c)
                    indention = true
                    breaks = true
                } else {
                    if (breaks) writeIndent()
                    write(c)
                    indention = false
                    spaces = false
                    breaks = false
                }
                i++
            }
            whitespace = false
            indention = false
        }

        private fun writeSingleQuoted(allowBreaks: Boolean) {
            val s = scalarCodePoints
            var spaces = false
            var breaks = false
            writeIndicator("'", needWhitespace = true, isWhitespace = false, isIndention = false)
            for (i in s.indices) {
                val c = s[i]
                if (c == 0x20) {
                    if (allowBreaks &&
                        !spaces &&
                        column > bestWidth &&
                        i != 0 &&
                        i != s.size - 1 &&
                        !(i + 1 < s.size && s[i + 1] == 0x20)
                    ) {
                        writeIndent()
                    } else {
                        write(c)
                    }
                    spaces = true
                } else if (isBreak(c)) {
                    if (!breaks && c == 0x0A) putBreak()
                    writeBreak(c)
                    indention = true
                    breaks = true
                } else {
                    if (breaks) writeIndent()
                    if (c == '\''.code) put('\'')
                    write(c)
                    indention = false
                    spaces = false
                    breaks = false
                }
            }
            if (breaks) writeIndent()
            writeIndicator("'", needWhitespace = false, isWhitespace = false, isIndention = false)
            whitespace = false
            indention = false
        }

        private fun writeDoubleQuoted(allowBreaks: Boolean) {
            val s = scalarCodePoints
            var spaces = false
            writeIndicator("\"", needWhitespace = true, isWhitespace = false, isIndention = false)
            for (i in s.indices) {
                val c = s[i]
                if (!isPrintable(c) || c > 0x7F || c == 0xFEFF || isBreak(c) || c == '"'.code || c == '\\'.code) {
                    put('\\')
                    when (c) {
                        0x00 -> put('0')
                        0x07 -> put('a')
                        0x08 -> put('b')
                        0x09 -> put('t')
                        0x0A -> put('n')
                        0x0B -> put('v')
                        0x0C -> put('f')
                        0x0D -> put('r')
                        0x1B -> put('e')
                        0x22 -> put('"')
                        0x5C -> put('\\')
                        0x85 -> put('N')
                        0xA0 -> put('_')
                        0x2028 -> put('L')
                        0x2029 -> put('P')
                        else -> {
                            val width: Int
                            if (c <= 0xFF) {
                                put('x')
                                width = 2
                            } else if (c <= 0xFFFF) {
                                put('u')
                                width = 4
                            } else {
                                put('U')
                                width = 8
                            }
                            var k = (width - 1) * 4
                            while (k >= 0) {
                                put("0123456789ABCDEF"[(c shr k) and 0x0F])
                                k -= 4
                            }
                        }
                    }
                    spaces = false
                } else if (c == 0x20) {
                    if (allowBreaks && !spaces && column > bestWidth && i != 0 && i != s.size - 1) {
                        writeIndent()
                        if (i + 1 < s.size && s[i + 1] == 0x20) put('\\')
                    } else {
                        write(c)
                    }
                    spaces = true
                } else {
                    write(c)
                    spaces = false
                }
            }
            writeIndicator("\"", needWhitespace = false, isWhitespace = false, isIndention = false)
            whitespace = false
            indention = false
        }
    }
}
