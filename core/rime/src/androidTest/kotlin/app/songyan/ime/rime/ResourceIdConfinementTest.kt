package app.songyan.ime.rime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.rime.EngineTestSupport.candidateTexts
import app.songyan.ime.rime.EngineTestSupport.type
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * A schema names its dictionaries and user dictionary by id. An id that is an absolute path or
 * climbs out with ".." must not make the engine read or write outside its data folders (for
 * example, a plain-text user dictionary in a folder other apps can read); the lookup fails and
 * the rest of the schema keeps working.
 */
@RunWith(AndroidJUnit4::class)
class ResourceIdConfinementTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var root: File

    @Before
    fun prepareRoot() {
        root = File(instrumentation.targetContext.cacheDir, "resource-id").also { it.deleteRecursively() }
    }

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun idsThatLeaveTheDataFoldersAreRefused() =
        runBlocking {
            val shared = File(root, "shared").apply { mkdirs() }
            val user = File(root, "user")
            val outside = File(root, "outside-${UUID.randomUUID()}").apply { mkdirs() }
            // "../x" names root/x.dict.yaml from both the shared and the user folder.
            File(root, "x.dict.yaml").writeText(dictionary("\"../x\"", "外泄"))
            File(shared, "probe.dict.yaml").writeText(dictionary("probe", "你好"))
            File(shared, "default.yaml").writeText("schema_list:\n  - schema: probe\n")
            File(shared, "probe.schema.yaml").writeText(
                """
                schema:
                  schema_id: probe
                  name: probe
                  version: "1"
                engine:
                  processors: [speller, selector, express_editor]
                  segmentors: [abc_segmentor]
                  translators: [table_translator, table_translator@secondary]
                speller:
                  alphabet: abcdefghijklmnopqrstuvwxyz
                translator:
                  dictionary: probe
                  packs: [../x]
                  enable_user_dict: true
                  user_dict: ${File(outside, "leak").absolutePath}
                secondary:
                  dictionary: ../x
                """.trimIndent() + "\n",
            )

            val deployment = RimeDeployment.deploy(shared, user, appVersion = "test")
            assertTrue("deployment failed: ${deployment.lastError}", deployment.success)
            val engine =
                RimeEngine.createPrebuilt(shared, user, appVersion = "test", compiledDir = deployment.compiledDir)
            try {
                assertTrue(engine.selectSchema("probe"))
                type(engine, "nihao")
                val candidates = candidateTexts(engine)
                assertEquals("你好", candidates.firstOrNull())
                assertFalse("candidate read from outside the data folders: $candidates", "外泄" in candidates)
                assertEquals("你好", engine.selectCandidate(0)?.commitText)
                // librime writes the commit to the user dictionary when the next translation starts.
                type(engine, "a")
                engine.clearComposition()
            } finally {
                engine.close()
            }

            assertEquals("written to the absolute user_dict folder", emptyList<String>(), outside.list()!!.toList())
            assertEquals(
                setOf("shared", "user", outside.name, "x.dict.yaml"),
                root.list()!!.toSet(),
            )
            val fromRefusedIds =
                root
                    .walk()
                    .filter { it.name.startsWith("x.") && it.name != "x.dict.yaml" || "refused" in it.name }
                    .map { it.path }
                    .toList()
            assertEquals("files created from refused ids", emptyList<String>(), fromRefusedIds)
        }

    private fun dictionary(
        name: String,
        word: String,
    ) = "---\nname: $name\nversion: \"1\"\nsort: original\n...\n$word\tnihao\n"
}
