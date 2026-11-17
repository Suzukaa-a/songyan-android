package app.songyan.ime.fonts

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.appearance.font.FontRole
import app.songyan.ime.prefs.KeyboardPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Font import, order, choices and deletion on the real preference store and real font files. */
@RunWith(AndroidJUnit4::class)
class FontStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = KeyboardPreferences(context)
    private lateinit var dir: File
    private lateinit var picked: File
    private lateinit var store: FontStore

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "font-store-test-fonts").also { it.deleteRecursively() }
        picked = File(context.cacheDir, "font-store-test-picked").also { it.deleteRecursively() }
        picked.mkdirs()
        store = FontStore(context, preferences, dir)
        runBlocking {
            preferences.updateFontOrder { emptyList() }
            FontRole.entries.forEach { preferences.setDisplayFont(it, "") }
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            preferences.updateFontOrder { emptyList() }
            FontRole.entries.forEach { preferences.setDisplayFont(it, "") }
        }
        dir.deleteRecursively()
        picked.deleteRecursively()
    }

    private fun pick(
        name: String,
        from: String?,
        text: String? = null,
    ): Uri {
        val file = File(picked, name)
        if (from != null) File(from).copyTo(file, overwrite = true) else file.writeText(text.orEmpty())
        return Uri.fromFile(file)
    }

    private fun importTwo() =
        runBlocking {
            store.import(
                listOf(
                    pick("Coming Soon.ttf", "/system/fonts/ComingSoon.ttf"),
                    pick("notes.ttf", null, "not a font"),
                    pick("Dancing.ttf", "/system/fonts/DancingScript-Regular.ttf"),
                ),
            )
        }

    @Test
    fun importKeepsFontsInImportOrderAndRejectsOtherFiles() {
        val result = importTwo()
        assertEquals(2, result.imported.size)
        assertEquals(listOf("notes.ttf"), result.rejected)
        assertFalse(result.failed)
        val fonts = runBlocking { store.fonts.first() }
        assertEquals(listOf("Coming Soon.ttf", "Dancing.ttf"), fonts.map { it.file })
        assertTrue(fonts.all { it.readable })
        assertFalse(File(dir, "notes.ttf").exists())
        assertEquals(emptyList<String>(), dir.list { _, n -> n.startsWith(".import-") }.orEmpty().toList())
    }

    @Test
    fun importingTheSameNameKeepsBothFiles() {
        importTwo()
        runBlocking { store.import(listOf(pick("Dancing.ttf", "/system/fonts/DancingScript-Regular.ttf"))) }
        val names = runBlocking { store.fonts.first() }.map { it.file }
        assertEquals(listOf("Coming Soon.ttf", "Dancing.ttf", "Dancing-2.ttf"), names)
    }

    @Test
    fun theChosenFontLeadsEachRoleAndTheOthersFollowInImportOrder() {
        importTwo()
        runBlocking {
            store.choose(FontRole.CANDIDATES, "Dancing.ttf")
            val spec = store.displayFonts.first()
            assertEquals(listOf("Dancing.ttf", "Coming Soon.ttf"), spec.candidates.map { it.name })
            assertTrue(spec.keys.isEmpty())
            assertEquals("Dancing.ttf", store.choice(FontRole.CANDIDATES).first())
            assertEquals("", store.choice(FontRole.KEYS).first())
        }
    }

    @Test
    fun deletingAFontRemovesItsFileAndEveryChoiceOfIt() {
        importTwo()
        runBlocking {
            store.choose(FontRole.CANDIDATES, "Dancing.ttf")
            store.choose(FontRole.KEYS, "Dancing.ttf")
            store.delete("Dancing.ttf")
            assertFalse(File(dir, "Dancing.ttf").exists())
            assertEquals(listOf("Coming Soon.ttf"), store.fonts.first().map { it.file })
            assertEquals("", store.choice(FontRole.CANDIDATES).first())
            assertEquals("", store.choice(FontRole.KEYS).first())
            assertEquals(emptyList<File>(), store.displayFonts.first().candidates)
        }
    }

    @Test
    fun aFontThatCannotBeReadAnyMoreIsListedAsSuchAndDrawsAsTheSystemFont() {
        importTwo()
        runBlocking {
            store.choose(FontRole.KEYS, "Dancing.ttf")
            File(dir, "Dancing.ttf").writeText("damaged")
            val broken = store.fonts.first().first { it.file == "Dancing.ttf" }
            assertNull(broken.family)
            assertFalse(broken.readable)
            assertTrue(
                store.displayFonts
                    .first()
                    .keys
                    .isEmpty(),
            )
            File(dir, "Coming Soon.ttf").delete()
            assertFalse(
                store.fonts
                    .first()
                    .first { it.file == "Coming Soon.ttf" }
                    .readable,
            )
        }
    }

    @Test
    fun theFamilyNameIsReadFromTheFont() {
        importTwo()
        val family = runBlocking { store.fonts.first() }.first().family
        assertNotNull(family)
        assertTrue(family!!.contains("Coming", ignoreCase = true))
    }
}
