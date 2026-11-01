package app.songyan.ime.rime

import app.songyan.ime.keyboard.engine.EngineOptionDescriptor
import app.songyan.ime.keyboard.engine.EngineOptionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The user's switch choices: kept per scheme source, input method and switch. */
class EngineOptionPreferencesTest {
    private val directory: File = Files.createTempDirectory("option-prefs").toFile()
    private val preferences = EngineOptionPreferences(directory)

    private fun option(
        resource: String = "pinyin",
        schema: String = "luna",
        name: String = "simplification",
    ) = EngineOptionDescriptor(resource, schema, name, EngineOptionKind.SIMPLIFIED)

    @Test
    fun aChoiceNeverMadeReadsAsNull() {
        assertNull(preferences.read(option()))
    }

    @Test
    fun aChoiceSurvivesANewStoreOverTheSameDirectory() {
        preferences.write(option(), true)
        assertEquals(true, EngineOptionPreferences(directory).read(option()))
        preferences.write(option(), false)
        assertEquals(false, EngineOptionPreferences(directory).read(option()))
    }

    @Test
    fun choicesAreKeptApartBySourceInputMethodAndSwitch() {
        preferences.write(option(), true)
        assertNull(preferences.read(option(resource = "other")))
        assertNull(preferences.read(option(schema = "bopomofo")))
        assertNull(preferences.read(option(name = "emoji")))
    }

    @Test
    fun idsWithPathCharactersStayInsideTheDirectory() {
        val awkward = option(resource = "../escape", schema = "a/b", name = "x y")
        preferences.write(awkward, true)
        assertEquals(true, preferences.read(awkward))
        val root = directory.canonicalPath + File.separator
        assertTrue(directory.walkTopDown().all { it.canonicalPath.startsWith(root) || it == directory })
        assertEquals(1, directory.walkTopDown().count { it.isFile })
    }

    @Test
    fun aDamagedFileIsNoChoice() {
        preferences.write(option(), true)
        directory.walkTopDown().filter { it.isFile }.forEach { it.writeText("{broken") }
        assertNull(preferences.read(option()))
    }

    @Test
    fun noTemporaryFileIsLeftBehind() {
        preferences.write(option(), true)
        assertEquals(
            emptyList<String>(),
            directory
                .walkTopDown()
                .filter { it.name.endsWith(".tmp") }
                .map { it.name }
                .toList(),
        )
    }
}
