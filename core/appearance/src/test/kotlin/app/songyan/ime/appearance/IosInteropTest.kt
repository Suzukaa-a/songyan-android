package app.songyan.ime.appearance

import app.songyan.ime.candidate.CandidateBarDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import java.io.File

/**
 * Files written by the iOS app's own encoders (`scripts/ios-interop.sh <ios-repo> export <dir>`,
 * committed under test resources/ios-interop) must import here and read back byte-for-byte as the
 * same document, and must paint the same colours as the Android copy of a shared built-in.
 *
 * When SONGYAN_INTEROP_OUT is set, the same files are re-written by the Android encoders into that
 * directory so `scripts/ios-interop.sh <ios-repo> verify <dir>` can decode them with the iOS code.
 */
class IosInteropTest {
    private val directory =
        File(IosInteropTest::class.java.getResource("/ios-interop")!!.toURI())

    private fun files(suffix: String): List<File> =
        directory.listFiles { file -> file.name.endsWith(suffix) }!!.sortedBy { it.name }

    /** The JSON tree with every number as a double: iOS writes `14`, Android `14.0`, and both mean the same. */
    private fun tree(text: String): JsonElement = canonical(Json.parseToJsonElement(text))

    private fun canonical(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> JsonObject(element.mapValues { canonical(it.value) })
            is JsonArray -> JsonArray(element.map(::canonical))
            is JsonPrimitive ->
                if (!element.isString && element.content.toDoubleOrNull() != null) {
                    JsonPrimitive(element.content.toDouble())
                } else {
                    element
                }
        }

    @Test
    fun theFixturesAreThere() {
        assertTrue(files(AppearanceDocument.FILE_EXTENSION).size >= 6)
        assertTrue(files(".songyanskin.json").isNotEmpty())
        assertTrue(files(".songyancand.json").isNotEmpty())
    }

    @Test
    fun everyIosAppearanceImportsAndReadsBackIdentically() {
        for (file in files(AppearanceDocument.FILE_EXTENSION)) {
            val text = file.readText()
            val document = AppearanceDocument.decodeImported(text)
            assertTrue(document.identityIsConsistent, file.name)
            assertEquals(tree(text), tree(document.encode()), file.name)
            assertEquals(document, AppearanceDocument.decode(document.encode()), file.name)
        }
    }

    @Test
    fun anIosSkinFileImportsAsAnAppearanceWithTheDefaultCandidateArea() {
        for (file in files(".songyanskin.json")) {
            val text = file.readText()
            val skin = SkinDefinition.decode(text)
            assertEquals(tree(text), tree(skin.encode()), file.name)
            val imported = AppearanceDocument.decodeImported(text)
            assertEquals(skin.id, imported.id, file.name)
            assertEquals(CandidateBarDefinition.systemDefault.collapsed, imported.candidateBar.collapsed, file.name)
        }
    }

    @Test
    fun anIosCandidateAreaFileImportsAsAnAppearanceWithTheDefaultSkin() {
        for (file in files(".songyancand.json")) {
            val text = file.readText()
            val bar = CandidateBarDefinition.decode(text)
            assertEquals(tree(text), tree(bar.encode()), file.name)
            val imported = AppearanceDocument.decodeImported(text)
            assertEquals(bar.expanded, imported.candidateBar.expanded, file.name)
            assertEquals(bar.controls, imported.candidateBar.controls, file.name)
        }
    }

    @Test
    fun theCustomisedCandidateAreaKeepsEveryAuthoredValue() {
        val document =
            AppearanceDocument.decodeImported(
                File(directory, "appearance-grid-look.songyanappearance.json").readText(),
            )
        val bar = document.candidateBar
        assertEquals(52.0, bar.collapsed.height)
        assertEquals(CandidateBarDefinition.Expanded.Mode.GRID, bar.expanded.mode)
        assertEquals(3, bar.expanded.columns)
        assertEquals(CandidateBarDefinition.Controls.PagePosition.LEADING, bar.controls.pagePosition)
        assertEquals(SkinAppearance.DARK, document.appearance)
    }

    @Test
    fun everyIosBuiltInIsAnAndroidBuiltInWithTheSameIdentity() {
        val iosIds =
            files(AppearanceDocument.FILE_EXTENSION)
                .map { AppearanceDocument.decodeImported(it.readText()).id }
                .filter { it.startsWith("builtin-") }
        assertTrue(iosIds.isNotEmpty())
        for (id in iosIds) assertTrue(id in AppearancePresets.BUILT_IN_IDS, id)
    }

    @Test
    fun aSharedBuiltInPaintsTheSameColoursAsTheIosOne() {
        val shared = files(AppearanceDocument.FILE_EXTENSION).map { AppearanceDocument.decodeImported(it.readText()) }
        for (ios in shared) {
            val android = AppearancePresets.ALL_BUILT_IN.firstOrNull { it.id == ios.id } ?: continue
            for (dark in listOf(false, true)) {
                // The Android built-ins leave the keycap shadow at the format default while iOS writes
                // "none"; every colour and metric must still agree.
                val expected = KeyboardPalette.of(ios, dark).copy(drawsKeyShadow = false)
                val actual = KeyboardPalette.of(android, dark).copy(drawsKeyShadow = false)
                assertEquals(expected, actual, ios.id + " dark=" + dark)
            }
        }
    }

    @Test
    fun aSharedBuiltInDiffersFromTheIosOneOnlyInThePagePositionAndTheName() {
        // The Android built-ins write the page buttons at the trailing end while the iOS built-ins
        // write their own value ("both"), so the comparison ignores exactly that field. The file-format
        // default stays iOS's "both", and an explicit value is honoured on import. The display name is the
        // other legitimate difference: the iOS file carries its localized name, Android resolves it by id.
        val shared = files(AppearanceDocument.FILE_EXTENSION).map { AppearanceDocument.decodeImported(it.readText()) }
        var compared = 0
        val neutral = CandidateBarDefinition.Controls.PagePosition.BOTH
        for (ios in shared) {
            val android = AppearancePresets.ALL_BUILT_IN.firstOrNull { it.id == ios.id } ?: continue
            compared++
            val iosBar = ios.candidateBar.run { copy(name = "", controls = controls.copy(pagePosition = neutral)) }
            val androidBar =
                android.candidateBar.run {
                    copy(
                        name = "",
                        controls = controls.copy(pagePosition = neutral),
                    )
                }
            assertEquals(iosBar, androidBar, ios.id)
            val position = android.candidateBar.controls.pagePosition
            assertEquals(CandidateBarDefinition.Controls.PagePosition.TRAILING, position, ios.id)
        }
        assertTrue(compared >= 6)
    }

    @Test
    fun anExplicitPagePositionIsHonouredAndAnAbsentOneIsBoth() {
        val explicit = """{"formatVersion":1,"id":"p","name":"p","controls":{"pagePosition":"trailing"}}"""
        val absent = """{"formatVersion":1,"id":"p","name":"p"}"""
        val trailing = CandidateBarDefinition.decode(explicit).controls.pagePosition
        assertEquals(CandidateBarDefinition.Controls.PagePosition.TRAILING, trailing)
        val both = CandidateBarDefinition.decode(absent).controls.pagePosition
        assertEquals(CandidateBarDefinition.Controls.PagePosition.BOTH, both)
    }

    @Test
    fun writesTheAndroidEncodingsForTheIosDecoders() {
        val out = System.getenv("SONGYAN_INTEROP_OUT")?.let(::File) ?: return
        out.mkdirs()
        for (file in files(AppearanceDocument.FILE_EXTENSION)) {
            File(out, file.name).writeText(AppearanceDocument.decodeImported(file.readText()).encode())
        }
        for (file in files(
            ".songyanskin.json",
        )) {
            File(out, file.name).writeText(SkinDefinition.decode(file.readText()).encode())
        }
        for (file in files(".songyancand.json")) {
            File(out, file.name).writeText(CandidateBarDefinition.decode(file.readText()).encode())
        }
        // Every Android built-in goes out too, so the iOS side sees what Android users will export.
        for (document in AppearancePresets.ALL_BUILT_IN) {
            File(out, "android-" + document.id + AppearanceDocument.FILE_EXTENSION).writeText(document.encode())
        }
    }
}
