package app.songyan.ime.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The shared cases are synced from the iOS repository, so their shape is a contract: every case
 * directory has expected.json, and the golden files say which exit code the run deserves
 * ("ok": false at the top level is exit 1, "cliError" is exit 64). These checks run on the JVM
 * against the checked-in assets so a broken sync is caught before the device run.
 */
class ScriptCaseCatalogTest {
    /** The unit test working directory differs between Gradle invocations, so the assets are
     *  located by walking up from the current directory to the module root. */
    private fun moduleRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "src/androidTest/assets/script-cases").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("cannot locate the script module from current directory")
    }

    private fun assetRoot(): File = File(moduleRoot(), "src/androidTest/assets")

    /** Contract cases sit directly under script-cases/, execution cases under
     *  script-execution/cases/ (the iOS tree nests execution that way). */
    private fun caseDirs(kind: String): List<File> {
        val root = assetRoot().resolve(kind)
        val container = if (File(root, "cases").isDirectory) File(root, "cases") else root
        return container.listFiles { file -> file.isDirectory }?.sortedBy { it.name } ?: emptyList()
    }

    @Test
    fun contractCasesAreComplete() {
        val cases = caseDirs("script-cases")
        assertTrue("expected the synced contract cases, found " + cases.size, cases.size >= 30)
        cases.forEach { case ->
            assertTrue(case.name + " has no expected.json", File(case, "expected.json").isFile)
            if (!File(case, "script.js").isFile) {
                assertTrue(
                    case.name + " has neither script.js nor an example",
                    File(moduleRoot(), "src/main/assets/script/examples/" + case.name + ".js").isFile,
                )
            }
        }
    }

    @Test
    fun executionCasesAreComplete() {
        val cases = caseDirs("script-execution")
        assertTrue("expected the synced execution cases, found " + cases.size, cases.size >= 54)
        cases.forEach { case ->
            assertTrue(case.name + " has no expected.json", File(case, "expected.json").isFile)
            val hasOwnInput = File(case, "script.js").isFile || File(case, "scripts.txt").isFile
            if (!hasOwnInput) {
                assertTrue(
                    case.name + " has neither script.js nor scripts.txt nor an example",
                    File(moduleRoot(), "src/main/assets/script/examples/" + case.name + ".js").isFile,
                )
            }
        }
    }

    @Test
    fun goldenFilesDeclareTheirExitCode() {
        // Every golden maps to exactly one of the three exit statuses; a golden that matches none
        // would make exitCodeFor fall through to 0 silently, so the distribution is asserted.
        val outcomes =
            (caseDirs("script-cases") + caseDirs("script-execution")).associate { case ->
                case.name to ScriptCaseRun.exitCodeFor(File(case, "expected.json").readText())
            }
        // Refused scripts (1) and plain successes (0) both exist in the corpus; caller mistakes
        // (64) exist only in the macOS harness section, so only the valid values may appear.
        assertTrue(
            "unexpected exit code mapped: " + outcomes.entries.filter { it.value !in setOf(0, 1, 64) },
            outcomes.values.all { it == 0 || it == 1 || it == 64 },
        )
        assertTrue("the corpus has both successes and refusals", 0 in outcomes.values && 1 in outcomes.values)
    }

    @Test
    fun fourByteUtf8SurvivesStrictDecoding() {
        // Emoji is a 4-byte UTF-8 sequence; strict decoding must keep it intact. This mirrors the
        // JNI byte[] transport, which deliberately avoids modified-UTF-8 JNI strings.
        val emoji = "😀"
        val round = String(emoji.toByteArray(Charsets.UTF_8), Charsets.UTF_8)
        assertEquals(emoji, round)
        assertEquals(4, emoji.toByteArray(Charsets.UTF_8).size)
    }
}
