package app.songyan.ime.packages

import app.songyan.ime.keyboard.engine.SchemaKeyProfile
import app.songyan.ime.keyboard.engine.SchemaKeyProfile.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/** The profile of each shared fixture schema equals the hand-checked expectation both platforms read. */
class SchemaKeyProfileTest {
    private fun resource(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/package-fixtures/key-profiles/$name")) { name }
            .readBytes()
            .toString(Charsets.UTF_8)

    @Test
    fun everyFixtureSchemaGetsItsExpectedProfile() {
        for (name in FIXTURES) {
            val expected = requireNotNull(SchemaKeyProfile.decodeOne(resource("$name.profile.json"))) { name }
            assertEquals(name, expected, SchemaKeyProfiles.compute(resource("$name.schema.yaml")))
        }
    }

    @Test
    fun kindsFollowThePrimaryTranslator() {
        assertEquals(Kind.SHAPE_CODE, SchemaKeyProfiles.compute(resource("wubi86.schema.yaml")).kind)
        assertEquals(Kind.STROKES, SchemaKeyProfiles.compute(resource("stroke.schema.yaml")).kind)
        assertEquals(Kind.SYLLABIC, SchemaKeyProfiles.compute(resource("rime_ice.schema.yaml")).kind)
        assertEquals(Kind.UNKNOWN, SchemaKeyProfiles.compute(resource("lua_only.schema.yaml")).kind)
    }

    @Test
    fun anUnreadableSchemaIsUnknown() {
        assertEquals(SchemaKeyProfile(Kind.UNKNOWN), SchemaKeyProfiles.compute("{{{ not yaml"))
        assertEquals(SchemaKeyProfile(Kind.UNKNOWN), SchemaKeyProfiles.compute(""))
    }

    @Test
    fun theFileRoundTripsAndABuildWithoutOneIsComputed() {
        val dir = File.createTempFile("profiles", "").also { it.delete() }.also { it.mkdirs() }
        try {
            File(dir, "wubi86.schema.yaml").writeText(resource("wubi86.schema.yaml"))
            File(dir, "stroke.schema.yaml").writeText(resource("stroke.schema.yaml"))
            val computed = SchemaKeyProfiles.read(dir, listOf("wubi86", "stroke", "missing"))
            assertEquals(Kind.SHAPE_CODE, computed.getValue("wubi86").kind)
            assertEquals(Kind.UNKNOWN, computed.getValue("missing").kind)
            SchemaKeyProfiles.write(dir, listOf("wubi86", "stroke"))
            val text = File(dir, SchemaKeyProfile.FILE_NAME).readText()
            assertNotNull(SchemaKeyProfile.decode(text))
            assertEquals(computed.filterKeys { it != "missing" }, SchemaKeyProfile.decode(text))
            // The file wins over the schema once written.
            File(dir, "wubi86.schema.yaml").delete()
            assertEquals(Kind.SHAPE_CODE, SchemaKeyProfiles.read(dir, listOf("wubi86")).getValue("wubi86").kind)
        } finally {
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
        }
    }

    private companion object {
        val FIXTURES =
            listOf(
                "bopomofo",
                "cangjie5",
                "double_pinyin_flypy",
                "jyut6ping3",
                "lua_only",
                "luna_pinyin",
                "melt_eng",
                "rime_ice",
                "stroke",
                "terra_pinyin",
                "thai_kedmanee",
                "wubi86",
            )
    }
}
