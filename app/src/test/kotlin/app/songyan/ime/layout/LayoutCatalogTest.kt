package app.songyan.ime.layout

import app.songyan.ime.settings.state.LayoutEditResult
import app.songyan.ime.settings.state.LayoutImportOutcome
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LayoutCatalogTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var directory: File
    private val published = mutableListOf<LayoutRegistry>()

    @Before
    fun setUp() {
        directory = temporary.newFolder("layouts")
        published.clear()
    }

    private fun kotlinx.coroutines.test.TestScope.catalog(newId: () -> String = { "fresh-id" }) =
        LayoutCatalog(
            LayoutStore(directory.absolutePath),
            StandardTestDispatcher(testScheduler),
            { published += it },
            newId,
        )

    private val mine = BuiltinLayouts.qwerty26.copyWithId("mine", "我的 26 键")

    @Test
    fun loadingWithoutAFileYieldsTheBundledLayoutsAndHandsThemToTheKeyboard() =
        runTest {
            val catalog = catalog()
            assertNull(catalog.registry.value)
            catalog.load()
            assertEquals(
                BuiltinLayouts.all.map { it.id },
                catalog.registry.value!!
                    .layouts
                    .map { it.id },
            )
            assertEquals(1, published.size)
            // Nothing is written until the user edits.
            assertTrue(directory.listFiles().isNullOrEmpty())
        }

    @Test
    fun anEditIsStoredAndPublishedAndSurvivesAReload() =
        runTest {
            val catalog = catalog()
            assertEquals(LayoutEditResult.Saved, catalog.edit { it.install(mine.encoded()) })
            assertNotNull(published.last().layout("mine"))
            val reloaded = catalog()
            reloaded.load()
            assertNotNull(reloaded.registry.value!!.layout("mine"))
        }

    @Test
    fun aRejectedEditChangesNeitherTheStoreNorTheKeyboard() =
        runTest {
            val catalog = catalog()
            catalog.edit { it.install(mine.encoded()) }
            val before = File(directory, LayoutStore.CATALOG_FILE_NAME).readText()
            val publications = published.size
            val result = catalog.edit { it.remove("qwerty26") }
            assertTrue(result is LayoutEditResult.Rejected)
            assertEquals(before, File(directory, LayoutStore.CATALOG_FILE_NAME).readText())
            assertEquals(publications, published.size)
            assertNotNull(catalog.registry.value!!.layout("mine"))
        }

    @Test
    fun aCorruptCatalogFallsBackToTheBundledLayoutsAndIsOnlyReplacedByAnEdit() =
        runTest {
            File(directory, LayoutStore.CATALOG_FILE_NAME).writeText("{ not a catalog")
            val catalog = catalog()
            catalog.load()
            assertEquals(
                BuiltinLayouts.all.map { it.id },
                catalog.registry.value!!
                    .layouts
                    .map { it.id },
            )
            assertEquals("{ not a catalog", File(directory, LayoutStore.CATALOG_FILE_NAME).readText())
            assertEquals(LayoutEditResult.Saved, catalog.edit { it.install(mine.encoded()) })
            assertNotNull(LayoutStore(directory.absolutePath).load().layout("mine"))
        }

    @Test
    fun importingAddsTheLayoutAndAnIdInUseGetsAFreshOne() =
        runTest {
            val catalog = catalog(newId = { "second" })
            catalog.load()
            val first = catalog.import(mine.encoded())
            assertEquals(LayoutImportOutcome.Imported("我的 26 键", copied = false), first)
            val second = catalog.import(mine.encoded())
            assertEquals(LayoutImportOutcome.Imported("我的 26 键", copied = true), second)
            val layouts = catalog.registry.value!!.layouts
            assertNotNull(layouts.firstOrNull { it.id == "mine" })
            assertNotNull(layouts.firstOrNull { it.id == "second" })
        }

    @Test
    fun aBrokenImportIsRejectedAndChangesNothing() =
        runTest {
            val catalog = catalog()
            catalog.load()
            val result = catalog.import("{\"id\": 1}")
            assertTrue(result is LayoutImportOutcome.Rejected)
            assertEquals(
                BuiltinLayouts.all.size,
                catalog.registry.value!!
                    .layouts.size,
            )
        }

    @Test
    fun anExportReadsBackAsTheSameLayoutAndABundledOneIsExportable() =
        runTest {
            val catalog = catalog()
            catalog.edit { it.install(mine.encoded()) }
            assertEquals(mine, LayoutJson.decode(catalog.export("mine")!!))
            assertEquals(BuiltinLayouts.phone9, LayoutJson.decode(catalog.export("phone9")!!))
            assertNull(catalog.export("missing"))
        }

    @Test
    fun theFileReaderStopsAtTheDefinitionBudget() {
        val ok = ByteArray(LayoutResourceBudget.DEFINITION_BYTES)
        assertEquals(ok.size, LayoutFileReader.decode(ok).length)
        val error =
            org.junit.Assert.assertThrows(LayoutException.Invalid::class.java) {
                LayoutFileReader.decode(ByteArray(LayoutResourceBudget.DEFINITION_BYTES + 1))
            }
        assertEquals(LayoutInvalidCode.RESOURCE_BUDGET_EXCEEDED, error.code)
    }

    @Test
    fun aPairingWhoseInputMethodIsGoneIsDroppedAndItsPageCanBeDeleted() =
        runTest {
            val catalog = catalog()
            catalog.load()
            val page = KeyboardPagePresets.numericRowsPage(ascii = false).duplicated("my-digits", "我的数字")
            val live = KeyboardCompositionKey("songyan_quanpin", LayoutInput.Builtin.QWERTY26).rawValue
            val gone = KeyboardCompositionKey("rime_ice", LayoutInput.Builtin.PHONE9).rawValue
            assertEquals(
                LayoutEditResult.Saved,
                catalog.edit {
                    it
                        .withPage(page)
                        .withComposition(KeyboardComposition(mainLayoutID = "qwerty26"), live)
                        .withComposition(KeyboardComposition(mainLayoutID = "phone9", numericPageID = page.id), gone)
                },
            )
            // The keyboard of the deleted package still holds the page, though no keyboard can show it.
            assertTrue(catalog.edit { it.withoutPage(page.id) } is LayoutEditResult.Rejected)
            assertEquals(1, catalog.pruneCompositions { it == "rime_ice" })
            assertEquals(setOf(live), LayoutStore(directory.absolutePath).load().compositions.keys)
            assertEquals(setOf(live), published.last().compositions.keys)
            assertEquals(LayoutEditResult.Saved, catalog.edit { it.withoutPage(page.id) })
        }

    @Test
    fun nothingIsWrittenWhenNoPairingIsObsolete() =
        runTest {
            val catalog = catalog()
            catalog.load()
            val before = published.size
            assertEquals(0, catalog.pruneCompositions { true })
            assertEquals(before, published.size)
            assertTrue(directory.listFiles().isNullOrEmpty())
        }
}
