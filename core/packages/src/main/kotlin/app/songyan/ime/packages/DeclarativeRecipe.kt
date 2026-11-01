package app.songyan.ime.packages

import java.net.URI
import java.net.URISyntaxException

/** One declarative recipe argument, rendered as `name=value` inside the recipe identity. */
data class RecipeArgument(
    val name: String,
    val value: String,
)

/** One external download declared by a recipe. */
data class RecipeExternalResource(
    val path: String,
    val url: URI,
)

/**
 * Declarative subset of the plum recipe script, ported from the iOS `DeclarativeRecipe`.
 *
 * Patches stay Rime `__patch` operations, not a front-end approximation of Rime's YAML merge
 * semantics. The parser is a line-oriented validator as on iOS: it accepts exactly the four
 * top-level sections and the indentation the iOS format supports, and rejects everything else, so a
 * recipe can never smuggle in a shell step.
 */
class DeclarativeRecipe private constructor(
    val path: String,
    val declaredName: String?,
    val declaredArguments: List<String>,
    val displayDescription: String?,
    val downloads: List<RecipeExternalResource>,
    val installPatterns: List<String>,
    val patchTargets: List<String>,
    internal val patches: List<Patch>,
    val source: ByteArray,
) {
    internal class Patch(
        val target: String,
        val body: String,
    )

    companion object {
        private val TOP_LEVEL_HEADERS = setOf("recipe:", "download_files:", "install_files:", "patch_files:")
        private val PACKAGE_REFERENCE = Regex("^[A-Za-z0-9_./-]+$")
        private val ARGUMENT_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
        private val ARGUMENT_VALUE = Regex("^[A-Za-z0-9_./-]*$")

        /** Parses and validates one recipe; every failure is [RecipeInstallError]. */
        fun parse(
            path: String,
            source: ByteArray,
        ): DeclarativeRecipe {
            RecipePlanSupport.checkPath(path)
            val text =
                decodeUtf8(source)
                    ?: throw RecipeInstallError.InvalidManifest("configuration files must be UTF-8")
            if (text.contains('\r') || text.contains('\t') || text.contains('\u0000')) {
                throw RecipeInstallError.InvalidManifest(
                    "configuration files must use LF newlines and space indentation",
                )
            }
            RecipePlanSupport.validateYaml(text, path)
            val document = BoundedYaml.load(text).asStringMap()
            val displayDescription = document?.get("recipe").asStringMap()?.get("description") as? String

            val sections = LinkedHashMap<String, MutableList<String>>()
            val foldedSections = HashSet<String>()
            var section: String? = null
            val sourceLines = splitLinesKeepingEmpty(text).toMutableList()
            if (sourceLines.lastOrNull() == "") sourceLines.removeAt(sourceLines.size - 1)
            for (line in sourceLines) {
                if (line.isEmpty() || line.startsWith("#") || line == "---") {
                    section?.let { sections.getOrPut(it) { mutableListOf() }.add(line) }
                    continue
                }
                if (!line.startsWith(" ")) {
                    val folded = line == "install_files: >-" || line == "download_files: >-"
                    val header = if (folded) line.dropLast(3) else line
                    val name = header.dropLast(1)
                    if (header !in TOP_LEVEL_HEADERS || sections[name] != null) {
                        throw RecipeInstallError.InvalidManifest("unsupported top-level statement: " + line)
                    }
                    section = name
                    sections[name] = mutableListOf()
                    if (folded) foldedSections.add(name)
                } else {
                    val current = section
                    if (current == null) {
                        throw RecipeInstallError.InvalidManifest("configuration is missing its top-level declarations")
                    }
                    sections.getOrPut(current) { mutableListOf() }.add(line)
                }
            }

            val metadata =
                sections["recipe"]
                    ?: throw RecipeInstallError.InvalidManifest("configuration is missing its metadata section")
            var name: String? = null
            val args = mutableListOf<String>()
            var readingArgs = false
            var readingDescription = false
            for (line in metadata) {
                val clean = line.trim { it.isWhitespace() }
                if (clean.isEmpty() || clean.startsWith("#")) continue
                if (line.startsWith("  Rx: ")) {
                    name = line.drop(6).trim { it == '"' || it == '\'' || it == ' ' }
                    readingArgs = false
                    readingDescription = false
                } else if (line == "  args:") {
                    readingArgs = true
                    readingDescription = false
                } else if (line.startsWith("  description:")) {
                    readingDescription = true
                    readingArgs = false
                } else if (line.startsWith("    ") && readingDescription) {
                    continue
                } else if (line.startsWith("    - ") && readingArgs) {
                    args.add(line.drop(6))
                } else {
                    throw RecipeInstallError.InvalidManifest("unsupported metadata statement: " + line)
                }
            }

            fun list(key: String): List<String> {
                val prefix = if (foldedSections.contains(key)) "  " else "  - "
                val values = sections[key] ?: return emptyList()
                val result = ArrayList<String>(values.size)
                for (line in values) {
                    val clean = line.trim { it.isWhitespace() }
                    if (clean.isEmpty() || clean.startsWith("#")) continue
                    if (!line.startsWith(prefix)) {
                        throw RecipeInstallError.InvalidManifest(key + " supports only a list, one item per line")
                    }
                    val value = line.drop(prefix.length)
                    if (value.isEmpty() ||
                        value.any { it.isWhitespace() } ||
                        value.contains('$') ||
                        value.contains('`') ||
                        value.contains('"') ||
                        value.contains('\'')
                    ) {
                        throw RecipeInstallError.InvalidManifest(
                            key + " does not support quotes or variable interpolation: " + value,
                        )
                    }
                    result.add(value)
                }
                return result
            }

            val downloads = mutableListOf<RecipeExternalResource>()
            val downloadPaths = HashSet<String>()
            for (value in list("download_files")) {
                val parts = splitKeepingEmpty(value, "::")
                if (parts.size >
                    2
                ) {
                    throw RecipeInstallError.InvalidManifest("unsupported download declaration: " + value)
                }
                val rawUrl = parts.last()
                val url =
                    try {
                        URI(rawUrl)
                    } catch (_: URISyntaxException) {
                        null
                    }
                if (url == null || url.scheme != "https" || url.host == null) {
                    throw RecipeInstallError.InvalidManifest("external downloads support only HTTPS links: " + value)
                }
                val filename = if (parts.size == 2) parts[0] else splitKeepingEmpty(rawUrl, "/").last()
                RecipePlanSupport.checkPath(filename)
                if (!downloadPaths.add(filename)) {
                    throw RecipeInstallError.InvalidManifest("duplicate download target: " + filename)
                }
                downloads.add(RecipeExternalResource(filename, url))
            }

            val patterns = list("install_files")
            for (pattern in patterns) RecipePlanSupport.checkPath(pattern, glob = true)

            val patches = mutableListOf<Patch>()
            var target: String? = null
            var bodyLines = mutableListOf<String>()

            fun finish() {
                val current = target ?: return
                if (!bodyLines.any { it.startsWith("  - ") }) {
                    throw RecipeInstallError.InvalidManifest("patch_files target must contain a patch list: " + current)
                }
                patches.add(Patch(current, bodyLines.joinToString("\n") + "\n"))
            }
            for (line in sections["patch_files"] ?: emptyList()) {
                if (line.startsWith("  ") && !line.startsWith("   ") && line.endsWith(":") && !line.startsWith("  #")) {
                    finish()
                    target = line.drop(2).dropLast(1)
                    bodyLines = mutableListOf()
                } else if (line.isEmpty() || line.startsWith("#") || line.startsWith("  #")) {
                    if (target != null) bodyLines.add(if (line.startsWith("  ")) line.drop(2) else line)
                } else {
                    if (target == null || !line.startsWith("    ")) {
                        throw RecipeInstallError.InvalidManifest("patch_files indentation is incorrect: " + line)
                    }
                    bodyLines.add(line.drop(2))
                }
            }
            finish()

            return DeclarativeRecipe(
                path = path,
                declaredName = name,
                declaredArguments = args,
                displayDescription = displayDescription,
                downloads = downloads,
                installPatterns = patterns,
                patchTargets = patches.map { it.target },
                patches = patches,
                source = source,
            )
        }
    }

    /**
     * Applies this recipe's `patch_files` to [existingFiles] and returns the complete effective file
     * tree with changed-path identities.
     *
     * This is the configuration half of the iOS `DeclarativeRecipePlan`: `install_files`,
     * declared downloads, grammar/model overrides and the install transaction are not ported here
     * yet, so callers must supply every resource the package needs.
     */
    fun applyPatches(
        existingFiles: Map<String, ByteArray>,
        packageReference: String,
        selectedRecipe: String,
        arguments: List<RecipeArgument> = emptyList(),
    ): RecipePatchApplication {
        if (!PACKAGE_REFERENCE.matches(packageReference)) {
            throw RecipeInstallError.InvalidManifest("unsupported package reference: " + packageReference)
        }
        val environment = LinkedHashMap<String, String>()
        for (argument in arguments) {
            if (!ARGUMENT_NAME.matches(argument.name) || !ARGUMENT_VALUE.matches(argument.value)) {
                throw RecipeInstallError.InvalidManifest("arguments support only name=value: " + argument.name)
            }
            environment[argument.name] = argument.value
        }
        // The identity is used as a literal managed-block marker (RecipePlanSupport.patch matches
        // the exact line), so grammar-legal characters such as '.' are accepted here.
        val rx =
            packageReference + ":" + selectedRecipe + ":" +
                arguments.joinToString(",") { it.name + "=" + it.value }
        if (selectedRecipe.isNotEmpty()) {
            RecipePlanSupport.checkPath(selectedRecipe)
            if (declaredName != selectedRecipe) {
                throw RecipeInstallError.InvalidManifest("the recipe does not match the selected recipe path")
            }
        }
        val output = LinkedHashMap(existingFiles)
        val replaced = mutableListOf<String>()
        val retained = mutableListOf<String>()
        for (patch in patches) {
            val target = RecipePlanSupport.expand(patch.target, environment)
            RecipePlanSupport.checkPath(target)
            if (!target.endsWith(".yaml")) {
                throw RecipeInstallError.InvalidManifest("patch targets support only YAML files")
            }
            val body = RecipePlanSupport.expand(patch.body, environment)
            val prior = output[target] ?: ByteArray(0)
            val text =
                decodeUtf8(prior)
                    ?: throw RecipeInstallError.InvalidResource("patch target is not UTF-8: " + target)
            if (text.contains("# Rx: " + rx + "\n") || text.contains("# Rx: " + rx + " {")) replaced.add(target)
            val foreignMarker = "# Rx: " + rx + " {"
            if (splitLinesKeepingEmpty(
                    text,
                ).any { it.startsWith("# Rx: ") && it != foreignMarker }
            ) {
                retained.add(target)
            }
            output[target] = RecipePlanSupport.patch(text, body, rx).toByteArray(Charsets.UTF_8)
        }
        RecipeResourceBudget.validate(output.mapValues { it.value.size })
        val before = existingFiles.mapValues { sha256Hex(it.value) }
        val after =
            output
                .map { (path, data) ->
                    val unchanged = existingFiles[path]?.contentEquals(data) == true
                    path to (if (unchanged) before.getValue(path) else sha256Hex(data))
                }.toMap(LinkedHashMap())
        val changedPaths = after.keys.filter { before[it] != after[it] }.sorted()
        return RecipePatchApplication(
            recipePath = path,
            packageReference = packageReference,
            rx = rx,
            arguments = arguments,
            files = output,
            changedPaths = changedPaths,
            replacedManagedBlocks = replaced,
            retainedDifferentInvocations = retained,
        )
    }
}

/** Result of applying a recipe's `patch_files` to an in-memory workspace. */
class RecipePatchApplication(
    val recipePath: String,
    val packageReference: String,
    val rx: String,
    val arguments: List<RecipeArgument>,
    val files: Map<String, ByteArray>,
    val changedPaths: List<String>,
    val replacedManagedBlocks: List<String>,
    val retainedDifferentInvocations: List<String>,
)
