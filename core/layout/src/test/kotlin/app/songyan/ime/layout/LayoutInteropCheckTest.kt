package app.songyan.ime.layout

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Android half of the semantic layout interop check (driven by `scripts/check-layout-interop.sh`; it does
 * nothing unless SONGYAN_LAYOUT_INTEROP_DIR is set).
 *
 * Every step reads layout files, decodes them with the Android decoder and writes, per input file,
 * `<name>.dump.json` (the same canonical dump `scripts/ios-interop` writes: rows, keys, actions, widths,
 * rails, splits, top-level fields; or the rejection) and `<name>.enc.json` (the Android encoding of what
 * it decoded). The script diffs the dumps; nothing here asserts, so a mismatch never hides in a decoder
 * that silently drops data.
 *
 * Steps (SONGYAN_LAYOUT_INTEROP_STEP, input and output directories relative to the work directory):
 *   first   also writes the Android built-ins and an authored catalog into `inputs`; dumps `inputs` into `a1`
 *   second  dumps `i1-enc` into `a2` and `i2-enc` into `a3`
 */
class LayoutInteropCheckTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun runsTheRequestedStep() {
        val work = System.getenv("SONGYAN_LAYOUT_INTEROP_DIR")?.let(::File) ?: return
        when (val step = System.getenv("SONGYAN_LAYOUT_INTEROP_STEP")) {
            "first" -> {
                writeAndroidInputs(File(work, "inputs"))
                dump(File(work, "inputs"), File(work, "a1"))
            }
            "second" -> {
                dump(File(work, "i1-enc"), File(work, "a2"))
                dump(File(work, "i2-enc"), File(work, "a3"))
            }
            else -> error("unknown SONGYAN_LAYOUT_INTEROP_STEP: $step")
        }
    }

    private fun writeAndroidInputs(inputs: File) {
        inputs.mkdirs()
        for (layout in BuiltinLayouts.all) File(inputs, "android-${layout.id}.json").writeText(layout.encoded())
        val folder = temporary.newFolder("authored")
        LayoutStore(folder.absolutePath).save(androidAuthoredCatalog())
        File(
            folder,
            LayoutStore.CATALOG_FILE_NAME,
        ).copyTo(File(inputs, "catalog-android-authored.json"), overwrite = true)
    }

    private fun dump(
        from: File,
        to: File,
    ) {
        to.mkdirs()
        for (file in from.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }) {
            val text = file.readText()
            var encoded: String? = null
            val result =
                try {
                    if (file.name == LayoutStore.CATALOG_FILE_NAME || file.name.startsWith("catalog-")) {
                        val registry = LayoutRegistryCodec.decode(text)
                        registry.validate()
                        encoded = LayoutRegistryCodec.encode(registry)
                        catalogDump(registry)
                    } else {
                        val layout = LayoutJson.decode(text)
                        // What the import screen does: install into a catalog. It must neither fail nor change the layout.
                        val installed =
                            LayoutRegistry
                                .create()
                                .install(
                                    text,
                                    replaceExisting = true,
                                ).layout(layout.id)!!
                        encoded = layout.encoded()
                        buildJsonObject {
                            put("ok", true)
                            put("kind", "layout")
                            put("layout", layoutDump(layout))
                            if (layoutDump(installed) !=
                                layoutDump(layout)
                            ) {
                                put("installChanged", layoutDump(installed))
                            }
                        }
                    }
                } catch (error: LayoutException) {
                    buildJsonObject {
                        put("ok", false)
                        put("error", error.toString())
                    }
                } catch (error: IllegalArgumentException) {
                    buildJsonObject {
                        put("ok", false)
                        put("error", error.toString())
                    }
                }
            File(to, file.name + ".dump.json").writeText(
                Json {
                    prettyPrint = true
                }.encodeToString(JsonElement.serializer(), result),
            )
            encoded?.let { File(to, file.name + ".enc.json").writeText(it) }
        }
    }

    private fun <T> wire(
        serializer: KSerializer<T>,
        value: T,
    ): String = (LayoutJson.format.encodeToJsonElement(serializer, value) as JsonPrimitive).content

    private fun actionDump(action: LayoutAction): JsonObject =
        buildJsonObject {
            put("type", wire(LayoutActionKind.serializer(), action.type))
            action.value?.let { put("value", it) }
            action.asciiValue?.let { put("asciiValue", it) }
        }

    private fun keyDump(key: LayoutKey): JsonObject =
        buildJsonObject {
            put("label", key.label)
            put("width", key.widthWeight)
            put("action", actionDump(key.action))
            key.secondaryLabel?.let { put("secondaryLabel", it) }
            key.titleMode?.let { put("titleMode", wire(LayoutKeyTitleMode.serializer(), it)) }
            key.longPress?.let { options ->
                putJsonArray("longPress") {
                    for (option in options) {
                        add(
                            buildJsonObject {
                                put("label", option.label)
                                put("action", actionDump(option.action))
                            },
                        )
                    }
                }
            }
        }

    private fun keys(list: List<LayoutKey>): JsonArray = JsonArray(list.map(::keyDump))

    private fun layoutDump(layout: LayoutDefinition): JsonObject =
        buildJsonObject {
            put("id", layout.id)
            put("name", layout.name)
            put("rows", JsonArray(layout.rows.map { keys(it.keys) }))
            putJsonObject("input") {
                layout.input.schemaID?.let { put("schemaID", it) }
                layout.input.builtinLayout?.let { put("builtinLayout", it.wire) }
            }
            layout.note?.let { put("note", it) }
            layout.longPressDelay?.let { put("longPressDelay", it) }
            layout.t9FirstKeyBehavior?.let { put("t9FirstKeyBehavior", wire(T9FirstKeyBehavior.serializer(), it)) }
            layout.t9QuickSymbols?.let { put("t9QuickSymbols", it) }
            layout.candidateArea?.let {
                putJsonObject("candidateArea") {
                    put("candidates", it.candidates)
                    put("reading", it.reading)
                }
            }
            layout.auxiliaryAreas?.let { areas ->
                putJsonArray("auxiliaryAreas") {
                    for (area in areas) {
                        add(
                            buildJsonObject {
                                put("position", wire(LayoutAuxiliaryArea.Position.serializer(), area.position))
                                put("role", wire(LayoutAuxiliaryArea.Role.serializer(), area.role))
                                put("keys", keys(area.keys))
                            },
                        )
                    }
                }
            }
            layout.split?.let { put("split", splitDump(it)) }
        }

    private fun splitDump(split: LayoutSplitConfiguration): JsonObject =
        buildJsonObject {
            put("activation", wire(LayoutSplitConfiguration.Activation.serializer(), split.activation))
            putJsonArray("rowBreaks") { split.rowBreaks.forEach { add(JsonPrimitive(it)) } }
            put("gapFraction", split.gapFraction)
            put("automaticGap", split.automaticGap)
            split.halves?.let { halves ->
                putJsonArray("halves") {
                    for (half in halves) {
                        add(
                            buildJsonObject {
                                put("left", keys(half.left))
                                put("right", keys(half.right))
                            },
                        )
                    }
                }
            }
        }

    private fun catalogDump(registry: LayoutRegistry): JsonObject =
        buildJsonObject {
            put("ok", true)
            put("kind", "catalog")
            putJsonObject("layouts") { for (layout in registry.layouts) put(layout.id, layoutDump(layout)) }
            putJsonObject("pages") { for (page in registry.pages) put(page.id, pageDump(page)) }
            put(
                "compositions",
                LayoutJson.format.encodeToJsonElement(
                    MapSerializer(String.serializer(), KeyboardComposition.serializer()),
                    registry.compositions,
                ),
            )
        }

    private fun pageDump(page: KeyboardPageDefinition): JsonObject =
        buildJsonObject {
            put("id", page.id)
            put("name", page.name)
            put("kind", wire(KeyboardPageKind.serializer(), page.kind))
            put("rows", JsonArray(page.rows.map { keys(it.keys) }))
            page.split?.let { put("split", splitDump(it)) }
            page.symbolPresentation?.let {
                put(
                    "symbolPresentation",
                    wire(KeyboardSymbolPresentation.serializer(), it),
                )
            }
            page.numericPresentation?.let {
                put(
                    "numericPresentation",
                    wire(KeyboardNumericPresentation.serializer(), it),
                )
            }
            page.defaultSymbolMarks?.let { put("defaultSymbolMarks", wire(KeyboardNumericMarks.serializer(), it)) }
            put("categoryCount", page.categories?.size ?: 0)
            put("hasChrome", page.chrome != null)
        }
}
