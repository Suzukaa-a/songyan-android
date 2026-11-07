package app.songyan.ime.rime

import app.songyan.ime.keyboard.engine.EngineOptionDescriptor
import app.songyan.ime.keyboard.engine.EngineOptionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun aStoredRecordIsKnownEvenWhenItsValueIsNoChoiceThisBuildReads() {
        val names = listOf("original", "traditional")
        val choice = option(name = names[1]).copy(choiceGroup = names)
        assertFalse(preferences.hasStoredValue(choice))
        preferences.write(choice, true)
        assertTrue(preferences.hasStoredValue(choice))
        // The whole group shares one record, so every member answers for it.
        assertTrue(preferences.hasStoredValue(choice.copy(name = names[0])))
        assertFalse(preferences.hasStoredValue(option(schema = "bopomofo", name = names[1]).copy(choiceGroup = names)))
        // An older or later build's literal is no choice to the engine, but it is the user's record.
        directory.walkTopDown().filter { it.isFile }.forEach { it.writeText("false") }
        assertNull(preferences.read(choice))
        assertTrue(preferences.hasStoredValue(choice))
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

    @Test
    fun aRadioChoiceUsesOneAtomicRecordAndRestoresExactlyOneMember() {
        val names = listOf("original", "traditional", "hongkong", "taiwan")
        val choices = names.map { option(name = it).copy(choiceGroup = names, choiceLabel = it) }
        assertTrue(choices.all { preferences.read(it) == null })
        preferences.write(choices[1], true)
        assertEquals(listOf(false, true, false, false), choices.map { preferences.read(it) })
        preferences.write(choices[3], true)
        val reopened = EngineOptionPreferences(directory)
        assertEquals(listOf(false, false, false, true), choices.map { reopened.read(it) })
        assertEquals(1, directory.walkTopDown().count { it.isFile })
        val failure = runCatching { preferences.write(choices[3], false) }.exceptionOrNull()
        assertTrue(failure is java.io.IOException)
        assertEquals(true, reopened.read(choices[3]))
    }
}
