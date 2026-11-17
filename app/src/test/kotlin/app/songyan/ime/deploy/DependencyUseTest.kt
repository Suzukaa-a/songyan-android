package app.songyan.ime.deploy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DependencyUseTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun tree(
        name: String,
        vararg files: Pair<String, String>,
    ): File =
        folder.newFolder(name).also { dir ->
            for ((path, text) in files) File(dir, path).apply { parentFile?.mkdirs() }.writeText(text)
        }

    @Test
    fun aPackageWhoseFileIsProvidedToTheOneInUseIsUsed() {
        val user = tree("user", "user.schema.yaml" to "a")
        val essay = tree("essay", "essay.txt" to "words")

        assertTrue(DependencyUse.reads(user, listOf(File(essay, "essay.txt")), essay))
        assertFalse("without a provided file it is idle", DependencyUse.reads(user, emptyList(), essay))
    }

    @Test
    fun aPackageWhoseCopiedDictionaryTheOneInUseCarriesIsUsed() {
        val user = tree("user", "terra_pinyin.dict.yaml" to "rows")
        val terra = tree("terra", "terra_pinyin.dict.yaml" to "rows", "terra_pinyin.schema.yaml" to "s")

        assertTrue(DependencyUse.reads(user, emptyList(), terra))
        assertFalse(DependencyUse.reads(user, emptyList(), user))
    }
}
