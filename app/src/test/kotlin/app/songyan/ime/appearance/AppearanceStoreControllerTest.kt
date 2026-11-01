package app.songyan.ime.appearance

import app.songyan.ime.candidate.CandidateBarDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppearanceStoreControllerTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var directory: File
    private lateinit var controller: AppearanceStoreController

    @Before
    fun setUp() {
        directory = temporary.newFolder("appearance")
        controller = AppearanceStoreController(AppearanceStore(directory.absolutePath))
    }

    @Test
    fun anEmptyStoreReadsAsTheAndroidDefault() {
        val state = controller.read(dynamicColor = false)
        assertEquals(AppearancePresets.androidDefault, state.document)
        assertNull(state.error)
        assertFalse(state.dynamicColor)
    }

    @Test
    fun theWallpaperChoiceIsCarriedThroughTheRead() {
        assertTrue(controller.read(dynamicColor = true).dynamicColor)
    }

    @Test
    fun aCorruptSnapshotFailsClosedToTheAndroidDefault() {
        File(directory, AppearanceStore.ACTIVE_FILE_NAME).writeText("{ not an appearance")
        val state = controller.read(dynamicColor = true)
        assertEquals(AppearancePresets.androidDefault, state.document)
        assertNotNull(state.error)
    }

    @Test
    fun selectingABuiltInPersistsItForTheNextRead() {
        val selected = controller.select("builtin-bamboo", dynamicColor = true)
        assertEquals("builtin-bamboo", selected.document.id)
        val reloaded = AppearanceStoreController(AppearanceStore(directory.absolutePath)).read(dynamicColor = true)
        assertEquals("builtin-bamboo", reloaded.document.id)
        assertNull(reloaded.error)
    }

    @Test
    fun selectingAnUnknownIdThrowsNotFound() {
        val error = assertThrows(AppearanceError::class.java) { controller.select("missing", dynamicColor = false) }
        assertEquals(AppearanceErrorCode.NOT_FOUND, error.code)
    }

    @Test
    fun theListKeepsEveryBuiltIn() {
        val ids = controller.list().map { it.id }
        assertTrue(ids.containsAll(AppearancePresets.BUILT_IN_IDS))
        assertTrue(ids.contains(AppearanceDocument.SYSTEM_DEFAULT_ID))
    }

    @Test
    fun theAndroidDefaultIsTheInkAppearance() {
        assertEquals(AppearancePresets.SONGYAN_ID, AppearancePresets.androidDefault.id)
    }

    private fun gridBar() =
        CandidateBarDefinition.systemDefault.run {
            copy(expanded = expanded.copy(mode = CandidateBarDefinition.Expanded.Mode.GRID, columns = 3))
        }

    @Test
    fun editingTheCandidateBarOfABuiltInLandsInAUserCopyThatIsApplied() {
        val applied = controller.editCandidateBar(AppearancePresets.androidDefault, gridBar(), copySuffix = " (custom)")
        assertFalse(AppearancePresets.isBuiltIn(applied))
        assertEquals(AppearancePresets.androidDefault.name + " (custom)", applied.name)
        assertEquals(3, applied.candidateBar.expanded.columns)
        assertEquals(applied.id, applied.candidateBar.id)
        // The copy was applied, listed once, and the built-in itself is untouched.
        assertEquals(applied.id, controller.read(dynamicColor = false).document.id)
        assertEquals(1, controller.list().count { it.id == applied.id })
        assertEquals(
            AppearancePresets.androidDefault,
            controller.list().first {
                it.id ==
                    AppearancePresets.androidDefault.id
            },
        )
    }

    @Test
    fun editingTheCandidateBarOfAUserAppearanceKeepsItsIdentity() {
        val first = controller.editCandidateBar(AppearancePresets.androidDefault, gridBar(), " (custom)")
        val listBar =
            first.candidateBar.copy(
                controls =
                    first.candidateBar.controls.copy(
                        pagePosition = CandidateBarDefinition.Controls.PagePosition.LEADING,
                    ),
            )
        val second = controller.editCandidateBar(first, listBar, " (custom)")
        assertEquals(first.id, second.id)
        assertEquals(
            CandidateBarDefinition.Controls.PagePosition.LEADING,
            controller
                .list()
                .first {
                    it.id == first.id
                }.candidateBar.controls.pagePosition,
        )
        assertEquals(3, second.candidateBar.expanded.columns)
    }

    @Test
    fun anImportedAppearanceIsStoredWithoutBeingApplied() {
        val text = AppearancePresets.androidDefault.duplicate(" imported").encode()
        val imported = controller.import(text)
        assertEquals(AppearancePresets.androidDefault.name + " imported", imported.name)
        assertTrue(controller.list().any { it.id == imported.id })
        assertEquals(AppearancePresets.androidDefault.id, controller.read(dynamicColor = false).document.id)
    }

    @Test
    fun importingAFileWhoseIdentityIsTakenGetsAFreshOneAndKeepsTheOtherCopy() {
        val text = AppearancePresets.androidDefault.duplicate(" imported").encode()
        val first = controller.import(text)
        val second = controller.import(text)
        assertTrue(first.id != second.id)
        assertEquals(first.name, second.name)
        assertEquals(2, controller.list().count { it.name == first.name })
        // A built-in's own file is imported as a copy, never over the built-in.
        val builtIn = controller.import(AppearancePresets.androidDefault.encode())
        assertTrue(builtIn.id != AppearancePresets.androidDefault.id)
    }

    @Test
    fun aStandaloneCandidateBarFileImportsAsAnAppearanceWithTheDefaultSkin() {
        val file = gridBar().copy(id = "my-grid", name = "My grid").encode()
        val imported = controller.import(file)
        assertEquals("my-grid", imported.id)
        assertEquals(3, imported.candidateBar.expanded.columns)
        assertEquals(app.songyan.ime.appearance.SkinDefinition.systemDefault.typography, imported.skin.typography)
    }

    @Test
    fun aBrokenImportStoresNothing() {
        val before = controller.list()
        assertThrows(AppearanceError::class.java) { controller.import("{\"hello\": 1}") }
        assertThrows(AppearanceError::class.java) { controller.import("not json") }
        assertEquals(before, controller.list())
    }

    @Test
    fun anExportReadsBackAsTheSameAppearance() {
        val user = controller.import(AppearancePresets.androidDefault.duplicate(" mine").encode())
        assertEquals(user, AppearanceDocument.decodeImported(controller.export(user.id)))
        val builtInText = controller.export(AppearancePresets.androidDefault.id)
        assertEquals(AppearancePresets.androidDefault, AppearanceDocument.decode(builtInText))
    }

    @Test
    fun onlyAUserAppearanceCanBeRemoved() {
        val user = controller.import(AppearancePresets.androidDefault.duplicate(" mine").encode())
        controller.remove(user.id)
        assertFalse(controller.list().any { it.id == user.id })
        val refused =
            assertThrows(AppearanceError::class.java) { controller.remove(AppearancePresets.androidDefault.id) }
        assertEquals(AppearanceErrorCode.READ_ONLY_BUILTIN, refused.code)
        assertEquals(
            AppearanceErrorCode.NOT_FOUND,
            assertThrows(AppearanceError::class.java) {
                controller.remove("nothing")
            }.code,
        )
    }
}
