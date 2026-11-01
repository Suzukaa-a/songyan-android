package app.songyan.ime.rime

import android.content.res.AssetManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Learning data of one user directory merged into another by the real engine: what
 * each directory learned is found in the target afterwards, merging the same data again (a start
 * interrupted before the old directory was deleted) counts nothing twice, and an unreadable source
 * reports failure without touching the target.
 */
@RunWith(AndroidJUnit4::class)
class RimeLearningMergeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var root: File
    private lateinit var source: File
    private lateinit var compiled: File

    @Before
    fun deployBuiltin() =
        runBlocking {
            root = File(instrumentation.targetContext.cacheDir, "learning-merge").also { it.deleteRecursively() }
            source = File(root, "source")
            copyAssetTree(instrumentation.targetContext.assets, "rime/builtin", source)
            val deployment = RimeDeployment.deploy(source, File(root, "deploy"), appVersion = "test")
            assertTrue("deployment failed: ${deployment.lastError}", deployment.success)
            compiled = deployment.compiledDir
        }

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun bothDirectoriesLearningArrivesInTheTargetAndARepeatedMergeCountsNothingTwice() =
        runBlocking {
            val builtin = learn("songyan-builtin", "nihao", "你好")
            val dictionary = learn("user-pinyin", "shi", "试")
            val target = learn("pinyin", "shi", "实")

            assertTrue(RimeLearningMerge.merge(source, compiled, builtin, target, appVersion = "test"))
            assertTrue(RimeLearningMerge.merge(source, compiled, dictionary, target, appVersion = "test"))

            val merged = exported(target)
            for (word in listOf("你好", "试", "实")) {
                assertNotNull("$word missing after the merge:\n$merged", commits(merged, word))
            }
            val before = commits(merged, "你好")

            // A start killed after the merge but before the old directory was deleted merges it again.
            assertTrue(RimeLearningMerge.merge(source, compiled, builtin, target, appVersion = "test"))
            assertEquals("a repeated merge must not add commits", before, commits(exported(target), "你好"))
            assertTrue("the source stays readable until the caller deletes it", builtin.isDirectory)
        }

    @Test
    fun anUnreadableSourceFailsAndLeavesTheTargetAsItWas() =
        runBlocking {
            val target = learn("pinyin", "shi", "实")
            val broken = File(root, "user/songyan-builtin").also { it.mkdirs() }
            // Named apart from the schemas' dictionaries so no session tries to repair it first.
            File(broken, "broken_dict.userdb").also {
                it.mkdirs()
                File(it, "CURRENT").writeText("MANIFEST-999999\n")
            }
            val before = exported(target)

            assertFalse(RimeLearningMerge.merge(source, compiled, broken, target, appVersion = "test"))

            assertEquals(commits(before, "实"), commits(exported(target), "实"))
            assertTrue(File(broken, "broken_dict.userdb/CURRENT").isFile)
        }

    @Test
    fun aDirectoryWithoutUserdbIsAlreadyMerged() =
        runBlocking {
            val empty = File(root, "user/user-pinyin").also { it.mkdirs() }
            File(empty, "installation.yaml").writeText("installation_id: \"x\"\n")
            assertTrue(RimeLearningMerge.merge(source, compiled, empty, File(root, "user/pinyin"), appVersion = "test"))
        }

    /** Opens an engine on `user/<name>`, commits [word] from [keys] and makes librime write it. */
    private suspend fun learn(
        name: String,
        keys: String,
        word: String,
    ): File {
        val user = File(root, "user/$name")
        val engine = RimeEngine.createPrebuilt(source, user, appVersion = "test", compiledDir = compiled)
        try {
            assertTrue(engine.selectSchema("songyan_quanpin"))
            for (char in keys) engine.processKey(RimeKey(requireNotNull(RimeKey.baseKeycode(char))))
            val candidates = engine.readSnapshot().candidates
            val index = candidates.indexOfFirst { it.text == word }
            assertTrue("$word not offered for $keys: ${candidates.map { it.text }}", index >= 0)
            assertEquals(word, engine.selectCandidate(index)?.commitText)
            // librime writes the commit to the userdb when the next translation starts.
            engine.processKey(RimeKey(requireNotNull(RimeKey.baseKeycode('a'))))
            engine.clearComposition()
        } finally {
            engine.close()
        }
        return user
    }

    /** The target's learning as librime exports it. */
    private suspend fun exported(user: File): String {
        val engine = RimeEngine.createPrebuilt(source, user, appVersion = "test", compiledDir = compiled)
        try {
            val snapshots = requireNotNull(engine.exportUserDictSnapshots())
            return snapshots.joinToString("\n") { it.readText() }
        } finally {
            engine.close()
        }
    }

    /** The commit count librime recorded for [word], or null when the word is absent. */
    private fun commits(
        snapshot: String,
        word: String,
    ): Int? =
        snapshot
            .lineSequence()
            .filter { !it.startsWith("#") }
            .map { it.split('\t') }
            .firstOrNull { it.size >= 3 && it[1] == word }
            ?.get(2)
            ?.split(' ')
            ?.firstOrNull { it.startsWith("c=") }
            ?.removePrefix("c=")
            ?.toIntOrNull()

    private fun copyAssetTree(
        assets: AssetManager,
        assetPath: String,
        target: File,
    ) {
        target.mkdirs()
        for (name in assets.list(assetPath).orEmpty()) {
            val childPath = "$assetPath/$name"
            val child = File(target, name)
            if (assets.list(childPath).orEmpty().isNotEmpty()) {
                copyAssetTree(assets, childPath, child)
            } else {
                assets.open(childPath).use { input -> child.outputStream().use { input.copyTo(it) } }
            }
        }
    }
}
