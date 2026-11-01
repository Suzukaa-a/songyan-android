package app.songyan.ime.userconfig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The compiled build files are the last place a file name can be caught, whatever patch produced it. */
class CompiledConfigGuardTest {
    private val build = Files.createTempDirectory("build").toFile()

    private fun refusal(): UserConfigException? =
        try {
            CompiledConfigGuard.check(build)
            null
        } catch (e: UserConfigException) {
            e
        }

    @Test
    fun ordinaryCompiledFilesPass() {
        File(build, "default.yaml").writeText(
            "schema_list:\n  - schema: songyan_quanpin\nmenu:\n  page_size: 5\n",
        )
        File(build, "songyan_quanpin.schema.yaml").writeText(
            "schema:\n  schema_id: songyan_quanpin\n  dependencies: [luna]\n" +
                "translator:\n  dictionary: songyan_basic\n" +
                "  prism: songyan_quanpin\n  user_dict: songyan_shared\n  db_class: userdb\n",
        )
        File(build, "songyan_quanpin.prism.bin").writeBytes(byteArrayOf(0, 1, 2))

        assertEquals(null, refusal())
    }

    @Test
    fun anUnsafeValueIsNamedWithItsFile() {
        File(build, "default.yaml").writeText("menu:\n  page_size: 5\n")
        File(
            build,
            "x.schema.yaml",
        ).writeText("translator:\n  user_dict: /data/user/0/app/files/rime/config/edit/leak\n")

        val error = refusal()

        assertNotNull(error)
        assertEquals("x.schema.yaml", error?.file)
        assertEquals(UserConfigProblem.UNSAFE_REFERENCE, error?.problem)
    }

    @Test
    fun aValueBehindATagOrAnOperatorSpellingIsStillFound() {
        File(build, "a.schema.yaml").writeText("translator:\n  dictionary: !!null ../../x\n")
        assertEquals("a.schema.yaml", refusal()?.file)
        File(build, "a.schema.yaml").delete()
        File(build, "b.schema.yaml").writeText("translator/user_dict/=: ../x\n")
        assertEquals("b.schema.yaml", refusal()?.file)
    }

    @Test
    fun unreadableCompiledYamlFailsClosed() {
        File(build, "bad.schema.yaml").writeText("a: [1\n")
        assertEquals(UserConfigProblem.INVALID_YAML, refusal()?.problem)
    }

    @Test
    fun aLuaComponentMustBeShippedOrConfirmed() {
        File(
            build,
            "a.schema.yaml",
        ).writeText("engine:\n  filters:\n    - lua_filter@*shipped\n    - lua_filter@*mine\n")
        assertEquals(UserConfigProblem.UNCONFIRMED_SCRIPT, refusal()?.problem)

        CompiledConfigGuard.check(build, shipped = setOf("lua_filter@*shipped"), confirmed = setOf("lua_filter@*mine"))
        val partial =
            try {
                CompiledConfigGuard.check(build, shipped = setOf("lua_filter@*shipped"))
                null
            } catch (e: UserConfigException) {
                e
            }
        assertEquals("a.schema.yaml", partial?.file)
    }
}
