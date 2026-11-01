package app.songyan.ime.userconfig

import app.songyan.ime.packages.BoundedYaml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The commented grammar parameters appended for the user: harmless until uncommented, valid when they are. */
class GrammarParameterExampleTest {
    private fun headings(heading: GrammarParameterExample.Heading) = "heading " + heading.name

    private fun append(text: String) = GrammarParameterExample.appendedTo(text, ::headings)

    private fun uncomment(block: String) =
        block.lines().joinToString("\n") { line -> if (line.startsWith("#   ")) line.removePrefix("# ") else line }

    @Test
    fun aFileWithoutPatchGetsAHeadingAndItsOwnPatchLine() {
        val result = append("# nothing here\n")

        assertEquals(GrammarParameterExample.Heading.NEW_PATCH, GrammarParameterExample.headingFor("# nothing here\n"))
        assertTrue(
            result.startsWith(
                "# nothing here\n\n# heading NEW_PATCH\n# patch:\n#   grammar/collocation_max_length: 6\n",
            ),
        )
        assertTrue(result.contains("#   translator/max_homophones: 8\n"))
        // Comments only: nothing changes until the user uncomments.
        assertTrue((BoundedYaml.load(result) == null) || (BoundedYaml.load(result) as Map<*, *>).isEmpty())
    }

    @Test
    fun uncommentingTheNewPatchBlockGivesValidYaml() {
        val loaded = BoundedYaml.load(uncomment(append("")).replace("# patch:", "patch:")) as Map<*, *>

        val patch = loaded["patch"] as Map<*, *>
        assertEquals(6, patch["grammar/collocation_max_length"])
        assertEquals(false, patch["translator/contextual_suggestions"])
        assertEquals(8, patch["translator/max_homophones"])
    }

    @Test
    fun anExistingPatchGetsLinesToPutUnderIt() {
        val text = "patch:\n  menu/page_size: 7\n"
        assertEquals(GrammarParameterExample.Heading.UNDER_PATCH, GrammarParameterExample.headingFor(text))

        val result = append(text)

        assertFalse("no second top-level patch", result.contains("# patch:"))
        assertTrue(result.contains("# heading UNDER_PATCH\n#   grammar/collocation_max_length: 6\n"))
        val merged = BoundedYaml.load(uncomment(result)) as Map<*, *>
        val patch = merged["patch"] as Map<*, *>
        assertEquals(7, patch["menu/page_size"])
        assertEquals(-14, patch["grammar/collocation_penalty"])
    }

    @Test
    fun theTemplatesEmptyPatchGetsItsOwnHeading() {
        val template = CustomPatchTemplate.text("hint", "example")
        assertEquals(GrammarParameterExample.Heading.EMPTY_PATCH, GrammarParameterExample.headingFor(template))
        assertTrue(append(template).contains("# heading EMPTY_PATCH\n"))
    }

    @Test
    fun aFileThatHasGrammarParametersOrTheExampleAlreadyIsLeftAlone() {
        val written = "patch:\n  grammar/rear_penalty: -10\n"
        assertNull(GrammarParameterExample.headingFor(written))
        assertEquals(written, append(written))
        val nested = "patch:\n  grammar:\n    rear_penalty: -10\n"
        assertEquals(nested, append(nested))
        val once = append("patch:\n  menu/page_size: 7\n")
        assertEquals("not appended twice", once, append(once))
    }
}
