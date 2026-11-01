package app.songyan.ime.packages

import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.api.lowlevel.Compose
import org.snakeyaml.engine.v2.common.ScalarStyle
import org.snakeyaml.engine.v2.constructor.StandardConstructor
import org.snakeyaml.engine.v2.exceptions.MarkedYamlEngineException
import org.snakeyaml.engine.v2.nodes.MappingNode
import org.snakeyaml.engine.v2.nodes.Node
import org.snakeyaml.engine.v2.nodes.ScalarNode
import org.snakeyaml.engine.v2.nodes.SequenceNode
import org.snakeyaml.engine.v2.nodes.Tag
import java.util.IdentityHashMap
import java.util.UUID

/**
 * Alias-bomb ("billion laughs") guard shared by every YAML read of imported or installed scheme
 * sources, ported from the iOS `BoundedYAML`.
 *
 * The composer resolves an alias by reusing the anchored node, so the composed tree is linear in
 * the input size, but anything that walks or constructs it pays for the fully expanded tree. Real
 * Rime schemes use a handful of `<<: *base` style aliases, so the cap is on the expanded node
 * count instead of banning aliases. Text without any alias token takes the unchanged fast path.
 *
 * [load] reads a document the way the engine does, because validation must not accept a file
 * that librime reads differently (librime 1.17 loads through yaml-cpp's `Node.as<string>()`):
 * - A merge key `<<` stays an ordinary key whose value is the aliased mapping. Yams on iOS merges
 *   it into the parent, but yaml-cpp and the Rime config compiler do not, so a merged key such as
 *   `schema_list` is invisible to the engine and must stay invisible to the validator. The alias
 *   and expansion budgets still count every merge, as it is an alias token like any other.
 * - Tags other than the core types are ignored and the value reads as an untagged scalar,
 *   mapping or sequence, as in Yams and yaml-cpp.
 * [compose] keeps tags untouched because [YamsYaml] needs them to reproduce Yams.
 */
object BoundedYaml {
    /** Maximum number of nodes after alias expansion. */
    const val MAXIMUM_EXPANDED_NODES: Int = 2_000_000

    /** Maximum number of alias tokens in one document. */
    const val MAXIMUM_ALIASES: Int = 100_000

    private const val MAXIMUM_SHADOW_BYTES: Int = 32 * 1024 * 1024

    /** Raised when a document would expand beyond the budgets above. */
    class LimitExceeded(
        val reason: String,
    ) : Exception("YAML alias expansion rejected: " + reason)

    /** The 1-based line a parse error from [load] points at, when the parser says. */
    fun errorLine(error: Throwable): Int? {
        var current: Throwable? = error
        while (current != null) {
            if (current is MarkedYamlEngineException) {
                current.problemMark.orElse(null)?.let { return it.line + 1 }
            }
            current = current.cause.takeIf { it !== current }
        }
        return null
    }

    /** Drop-in replacement for the library load call. */
    fun load(text: String): Any? {
        val settings = settings()
        val checked = requiresExpansionCheck(text)
        val root = Compose(settings).composeString(text)
        if (checked) root.ifPresent { checkExpandedSize(it) }
        root.ifPresent { ignoreUnknownTags(it, settings) }
        return StandardConstructor(settings).constructSingleDocument(root)
    }

    /** Drop-in replacement for the library compose call. */
    fun compose(text: String): Node? {
        val settings = settings()
        if (!requiresExpansionCheck(text)) return Compose(settings).composeString(text).orElse(null)
        val root = Compose(settings).composeString(text)
        root.ifPresent { checkExpandedSize(it) }
        return root.orElse(null)
    }

    /**
     * Reads [text] the way librime does for what a file names: every scalar, key or value, is its
     * text, whatever tag it carries. `!!null x` or `!!bool x` would otherwise come out as null or
     * true here while yaml-cpp keeps `x`, and a check on the typed value would never see it.
     * Maps and lists keep their shape; a key that is not a scalar is dropped. A key a mapping
     * repeats is kept too, as `key/=`, `key/=/=`…: yaml-cpp keeps every pair and librime acts on
     * each, so a check must see each one (a patch operator `/=` on a key names that same key).
     */
    fun loadAsText(text: String): Any? {
        val root = compose(text) ?: return null
        var budget = MAXIMUM_TEXT_NODES

        fun convert(node: Node): Any? {
            if (--budget < 0) throw LimitExceeded("more than " + MAXIMUM_TEXT_NODES + " nodes to read")
            return when (node) {
                is ScalarNode -> node.value
                is SequenceNode -> node.value.map { convert(it) }
                is MappingNode ->
                    LinkedHashMap<String, Any?>().also { map ->
                        for (tuple in node.value) {
                            val key = tuple.keyNode as? ScalarNode ?: continue
                            var name = key.value
                            while (name in map) name += "/="
                            map[name] = convert(tuple.valueNode)
                        }
                    }
                else -> null
            }
        }
        return convert(root)
    }

    private const val MAXIMUM_TEXT_NODES: Int = 500_000

    private val coreTags: Set<Tag> =
        setOf(Tag.STR, Tag.INT, Tag.FLOAT, Tag.BOOL, Tag.NULL, Tag.MAP, Tag.SEQ)

    /** Reset every tag the constructor would reject to the one the node has without a tag. */
    private fun ignoreUnknownTags(
        root: Node,
        settings: LoadSettings,
    ) {
        // Aliased nodes are shared, so visit each node object once to stay linear in the input.
        val seen = IdentityHashMap<Node, Boolean>()
        val stack = ArrayDeque<Node>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (seen.put(node, true) != null) continue
            when (node) {
                is ScalarNode -> {
                    if (node.tag !in coreTags) {
                        val plain = node.scalarStyle == ScalarStyle.PLAIN
                        node.tag = settings.schema.scalarResolver.resolve(node.value, plain)
                    }
                }
                is MappingNode -> {
                    if (node.tag !in coreTags) node.tag = Tag.MAP
                    for (tuple in node.value) {
                        stack.addLast(tuple.keyNode)
                        stack.addLast(tuple.valueNode)
                    }
                }
                is SequenceNode -> {
                    if (node.tag !in coreTags) node.tag = Tag.SEQ
                    for (child in node.value) stack.addLast(child)
                }
                else -> Unit
            }
        }
    }

    private fun settings(): LoadSettings =
        LoadSettings
            .builder()
            .setMaxAliasesForCollections(MAXIMUM_ALIASES)
            .setCodePointLimit(RecipeResourceBudget.SOURCE_FILE)
            // Rime schemes repeat keys across includes and rely on last-one-wins.
            .setAllowDuplicateKeys(true)
            .setAllowNonScalarKeys(true)
            .build()

    /**
     * Parse an alias-free shadow before the original reaches the composer, because key hashing
     * happens during composition and checking only the composed original would be too late.
     *
     * Every `*` is replaced, including those in comments and scalar content. The replacement is an
     * ordinary plain-scalar prefix, so a real alias becomes a plain scalar beginning with it and a
     * star inside plain text or a quoted scalar is not read as an alias.
     */
    private fun requiresExpansionCheck(
        text: String,
        maximumShadowBytes: Int = MAXIMUM_SHADOW_BYTES,
    ): Boolean {
        if (!text.contains('*')) return false
        val byteCount = text.toByteArray(Charsets.UTF_8).size
        val sizeFailure = LimitExceeded("alias safety pre-check exceeds " + maximumShadowBytes + " bytes")
        if (byteCount > maximumShadowBytes) throw sizeFailure
        // A short ASCII prefix keeps growth bounded and stays legal inside YAML tags. An absent
        // random prefix avoids merging ordinary keys such as 'a*b' and 'axb'.
        var prefix: String
        do {
            prefix = "Y" + UUID.randomUUID().toString().take(7)
        } while (text.contains(prefix))
        val stars = text.count { it == '*' }
        // Divide before multiplying so neither overflow nor a huge temporary is possible.
        if (stars > (maximumShadowBytes - byteCount) / (prefix.length - 1)) throw sizeFailure
        val shadow = text.replace("*", prefix)
        val root = Compose(settings()).composeString(shadow).orElse(null) ?: return false
        var aliases = 0
        var unsupportedKey = false
        val stack = ArrayDeque<Node>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            when (node) {
                is ScalarNode -> {
                    if (isAlias(node, prefix)) {
                        aliases++
                        if (aliases > MAXIMUM_ALIASES) {
                            throw LimitExceeded("more than " + MAXIMUM_ALIASES + " aliases")
                        }
                    }
                }
                is MappingNode -> {
                    for (tuple in node.value) {
                        val key = tuple.keyNode
                        if (key is ScalarNode) {
                            if (isAlias(key, prefix)) unsupportedKey = true
                        } else {
                            unsupportedKey = true
                        }
                        stack.addLast(key)
                        stack.addLast(tuple.valueNode)
                    }
                }
                is SequenceNode -> {
                    for (child in node.value) stack.addLast(child)
                }
                else -> Unit
            }
        }
        if (aliases == 0) return false
        // Collection keys may contain aliases anywhere in their descendants; reject them before
        // composing the original, regardless of key syntax.
        if (unsupportedKey) throw LimitExceeded("aliases or complex structures used as mapping keys")
        return true
    }

    private fun isAlias(
        scalar: ScalarNode,
        prefix: String,
    ): Boolean = scalar.scalarStyle == ScalarStyle.PLAIN && scalar.value.startsWith(prefix)

    /** Iterative walk with an early abort; never visits more than the budget. */
    private fun checkExpandedSize(
        root: Node,
        maximumExpandedNodes: Int = MAXIMUM_EXPANDED_NODES,
    ) {
        val stack = ArrayDeque<Node>()
        stack.addLast(root)
        var count = 0
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            count++
            if (count > maximumExpandedNodes) {
                throw LimitExceeded("more than " + maximumExpandedNodes + " nodes after expansion")
            }
            when (node) {
                is MappingNode -> {
                    for (tuple in node.value) {
                        stack.addLast(tuple.keyNode)
                        stack.addLast(tuple.valueNode)
                    }
                }
                is SequenceNode -> {
                    for (child in node.value) stack.addLast(child)
                }
                else -> Unit
            }
        }
    }
}

/**
 * The mapping value at [key] when every key is a string, matching `value as? [String: Any]`.
 * Values are the constructor's plain boxed types: String, Boolean, integer, Double, null, Map, List.
 */
@Suppress("UNCHECKED_CAST")
internal fun Any?.asStringMap(): Map<String, Any?>? {
    val map = this as? Map<*, *> ?: return null
    if (map.keys.any { it !is String }) return null
    return map as Map<String, Any?>
}

/** The list value when every element is a string, matching `value as? [String]`. */
@Suppress("UNCHECKED_CAST")
internal fun Any?.asStringList(): List<String>? {
    val list = this as? List<*> ?: return null
    if (list.any { it !is String }) return null
    return list as List<String>
}

/** Swift `String(describing:)` for the scalars a YAML document can carry. */
internal fun describeScalar(value: Any?): String =
    when (value) {
        null, YamsYaml.Null -> "<null>"
        is Boolean -> if (value) "true" else "false"
        is Double -> if (value == value.toLong().toDouble()) value.toLong().toString() + ".0" else value.toString()
        else -> value.toString()
    }
