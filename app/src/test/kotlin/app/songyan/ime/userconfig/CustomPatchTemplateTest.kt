package app.songyan.ime.userconfig

import app.songyan.ime.packages.BoundedYaml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The starting content of a new `xxx.custom.yaml` is a valid file that changes nothing. */
class CustomPatchTemplateTest {
    private val text = CustomPatchTemplate.text("在 patch 下写要改的项。\n改完回到松烟。", "例：每页显示 7 个候选")

    @Test
    fun itParsesAndPatchesNothing() {
        val root = BoundedYaml.load(text) as Map<*, *>
        assertEquals(setOf("patch"), root.keys)
        assertTrue((root["patch"] as Map<*, *>).isEmpty())
        assertNull(UserConfigFiles.check("default.custom.yaml", text.toByteArray()))
    }

    @Test
    fun theHintsAreOneCommentLineEach() {
        val lines = text.lines()
        assertEquals("# 在 patch 下写要改的项。 改完回到松烟。", lines[0])
        assertEquals("# 例：每页显示 7 个候选", lines[1])
        assertEquals("# patch:", lines[2])
        assertEquals("#   menu/page_size: 7", lines[3])
        assertEquals("patch: {}", lines[4])
    }
}
