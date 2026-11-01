package app.songyan.ime.userconfig

import app.songyan.ime.storage.AppStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** A script has to be agreed to once, and again whenever it changes; a source's own scripts are not the user's to agree to. */
class UserScriptsTest {
    private val storage = AppStorage(Files.createTempDirectory("files").toFile()).also { it.ensureBaseDirectories() }
    private val scripts = UserScripts(storage)
    private val source = File(storage.sourcesDir, "scheme-1").also { it.mkdirs() }
    private val edit get() = storage.userConfigDir

    private fun put(
        dir: File,
        path: String,
        text: String,
    ) = File(dir, path).also { it.parentFile?.mkdirs() }.apply { writeText(text) }

    @Test
    fun aLuaFileAndALuaEntryAreScriptsButPlainPatchesAreNot() {
        put(edit, "lua/date.lua", "return 1\n")
        put(edit, "x.custom.yaml", "patch:\n  engine/filters/+: [lua_filter@*date]\n  menu/page_size: 7\n")
        put(edit, "y.custom.yaml", "patch:\n  menu/page_size: 7\n")

        val ids = scripts.entries(edit, source).map { it.id }

        assertEquals(listOf("lua/date.lua", "x.custom.yaml: lua_filter@*date"), ids)
    }

    @Test
    fun whatTheSourceShipsItselfIsNotAskedAbout() {
        put(source, "lua/date.lua", "return 1\n")
        put(source, "p.schema.yaml", "engine:\n  filters:\n    - lua_filter@*date\n")
        // A copy of the source's files, with an edit that touches no script.
        put(edit, "lua/date.lua", "return 1\n")
        put(edit, "p.schema.yaml", "engine:\n  filters:\n    - lua_filter@*date\n    - uniquifier\n")

        assertTrue(scripts.entries(edit, source).isEmpty())

        put(edit, "lua/date.lua", "return 2\n")
        put(edit, "p.schema.yaml", "engine:\n  filters:\n    - lua_filter@*date\n    - lua_filter@*other\n")
        assertEquals(
            listOf("lua/date.lua", "p.schema.yaml: lua_filter@*other"),
            scripts.entries(edit, source).map { it.id },
        )
    }

    @Test
    fun anAgreedScriptStaysAgreedUntilItChanges() {
        put(edit, "lua/date.lua", "return 1\n")
        put(edit, "rime.lua", "date = require('date')\n")
        assertEquals(2, scripts.unconfirmed(edit, source).size)

        scripts.accept(scripts.unconfirmed(edit, source))
        assertTrue(scripts.unconfirmed(edit, source).isEmpty())
        // A new instance (another process, a later launch) knows it too.
        assertTrue(UserScripts(storage).unconfirmed(edit, source).isEmpty())

        put(edit, "lua/date.lua", "os.execute('x')\n")
        assertEquals(listOf("lua/date.lua"), scripts.unconfirmed(edit, source).map { it.id })

        File(edit, "lua/date.lua").delete()
        put(edit, "lua/date.lua", "return 1\n")
        assertTrue("the old content was agreed to", scripts.unconfirmed(edit, source).isEmpty())
    }

    @Test
    fun unreadableYamlIsLeftToTheOtherChecks() {
        put(edit, "bad.custom.yaml", "patch: [lua_filter@x\n")
        put(edit, "deep.custom.yaml", "a: " + "[".repeat(5000) + "\n")

        assertTrue(scripts.entries(edit, source).isEmpty())
    }

    @Test
    fun theRiskIsAcceptedOnceAndOnlyByAcceptingNotByLooking() {
        put(edit, "lua/date.lua", "return 1\n")
        assertFalse(scripts.riskAccepted())

        // Listing what is unconfirmed (the dialog) accepts nothing.
        scripts.unconfirmed(edit, source)
        assertFalse(UserScripts(storage).riskAccepted())

        scripts.accept(scripts.unconfirmed(edit, source))
        assertTrue(UserScripts(storage).riskAccepted())
        assertTrue(scripts.unconfirmed(edit, source).isEmpty())
        put(edit, "lua/date.lua", "return 2\n")
        assertEquals("a changed script is asked about again", 1, scripts.unconfirmed(edit, source).size)
        assertTrue("but the risk is not explained again", scripts.riskAccepted())
    }

    @Test
    fun aDictionaryHeaderIsReadAndItsRowsAreNot() {
        put(
            edit,
            "mine.dict.yaml",
            "---\nname: mine\nfilters:\n  - lua_filter@*from_header\n...\nlua_filter@*in_row\tabc\t1\n",
        )

        assertEquals(listOf("mine.dict.yaml: lua_filter@*from_header"), scripts.entries(edit, source).map { it.id })
    }

    @Test
    fun aBareComponentNameAndATaggedOneAreScriptsToo() {
        put(edit, "x.custom.yaml", "patch:\n  engine/filters/+: [lua_filter, !!null lua_translator@t]\n")

        assertEquals(
            listOf("x.custom.yaml: lua_filter", "x.custom.yaml: lua_translator@t"),
            scripts.entries(edit, source).map { it.id },
        )
    }
}
