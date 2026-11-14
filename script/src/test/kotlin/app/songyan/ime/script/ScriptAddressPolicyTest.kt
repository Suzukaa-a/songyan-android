package app.songyan.ime.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The proxy is the only place a script's network reach is decided on Android, and it runs the
 *  Kotlin copy of the shared rule. This replays the shared table synced from the iOS repository,
 *  so both implementations answer every line the same way. */
class ScriptAddressPolicyTest {
    private fun table(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            val candidate = File(dir, "src/androidTest/assets/script-address-cases.txt")
            if (candidate.isFile) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("cannot locate the synced address table")
    }

    @Test
    fun everyLineOfTheSharedTableHoldsHere() {
        val failures = mutableListOf<String>()
        var count = 0
        for (line in table().readLines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val columns = trimmed.split('|')
            assertEquals("malformed line: $trimmed", 3, columns.size)
            val head = columns[0].trim().split(Regex("\\s+"))
            val expected = head[0] == "allowed"
            val url = head[1]
            val declared = columns[1].trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val typed = columns[2].trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val actual = ScriptAddressPolicy.authorize(url, declared, typed).allowed
            if (actual != expected) failures += "$url: ${if (actual) "allowed" else "denied"}, expected ${head[0]}"
            count++
        }
        assertTrue("the table is empty", count > 0)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun aSettingValueIsValidWhenTheRuleWouldReachIt() {
        assertTrue(ScriptAddressPolicy.isReachableSettingUrl("https://llm.example.com/v1"))
        assertTrue(ScriptAddressPolicy.isReachableSettingUrl("http://192.168.1.20:11434"))
        assertEquals(false, ScriptAddressPolicy.isReachableSettingUrl("http://llm.example.com/v1"))
        assertEquals(false, ScriptAddressPolicy.isReachableSettingUrl("llm.example.com"))
    }
}
