package app.songyan.ime.rime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.rime.EngineTestSupport.candidateTexts
import app.songyan.ime.rime.EngineTestSupport.type
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A Lua script from an imported scheme or the user's configuration runs inside the keyboard: it
 * must not start processes, open pipes or load native code, any of which could carry what the
 * user types out of the app.
 */
@RunWith(AndroidJUnit4::class)
class LuaSandboxTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var root: File

    @Before
    fun prepareRoot() {
        root = File(instrumentation.targetContext.cacheDir, "lua-sandbox").also { it.deleteRecursively() }
    }

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun aScriptCannotLaunchProcessesOpenPipesOrLoadNativeCode() =
        runBlocking {
            val shared = File(root, "shared")
            EngineTestSupport.copyAssetTree(
                instrumentation.context.assets,
                "${EngineTestSupport.FIXTURE_ASSET_ROOT}/lua-sandbox",
                shared,
            )
            val deployment = RimeDeployment.deploy(shared, File(root, "user"), appVersion = "test")
            assertTrue("deployment failed: ${deployment.lastError}", deployment.success)
            val engine =
                RimeEngine.createPrebuilt(
                    shared,
                    File(root, "user"),
                    appVersion = "test",
                    compiledDir = deployment.compiledDir,
                )
            try {
                assertTrue(engine.selectSchema("sandbox_probe"))
                type(engine, "ab")
                assertEquals(
                    listOf("ran=true shell=false exec=false popen=false loadlib=false cmodule=false"),
                    candidateTexts(engine),
                )
            } finally {
                engine.close()
            }
        }
}
