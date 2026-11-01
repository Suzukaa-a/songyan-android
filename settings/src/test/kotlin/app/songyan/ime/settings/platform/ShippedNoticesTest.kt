package app.songyan.ime.settings.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Builds the licence index from the notice and licence files in the source tree, the same files
 * [LicenseCatalog] reads from the APK assets, so the screen's content is checked as shipped.
 */
class ShippedNoticesTest {
    private val index: LicenceIndex by lazy { buildIndex() }

    @Test
    fun androidOnlyComponentsAreListed() {
        val titles = index.components.map { it.title }
        listOf("AndroidX", "Kotlin", "SnakeYAML Engine", "Okio", "Protocol Buffers").forEach { name ->
            assertTrue("$name is missing from $titles", titles.any { it.contains(name) })
        }
    }

    @Test
    fun iosOnlyComponentsAreNotListed() {
        val everything = index.entries.joinToString("\n") { it.displayTitle + " " + it.role.orEmpty() }
        listOf("ZIPFoundation", "Yams", "LibYAML", "HostApp", "RimeKit").forEach { name ->
            assertFalse("$name must not appear", everything.contains(name))
        }
        assertFalse(index.extraTexts.any { it.label.contains("Yams") || it.label.contains("ZIPFoundation") })
    }

    @Test
    fun everyListedComponentHasItsLicenceText() {
        val without = index.components.filter { it.texts.isEmpty() }.map { it.title }
        assertEquals(emptyList<String>(), without)
    }

    @Test
    fun apacheComponentsReuseTheOneApacheText() {
        val apache = index.components.filter { it.licence == "Apache-2.0" && it.upstream?.contains("androidx") == true }
        assertTrue(apache.isNotEmpty())
        apache.forEach { component ->
            assertTrue(
                component.texts
                    .single()
                    .text
                    .contains("Apache License"),
            )
        }
    }

    private fun buildIndex(): LicenceIndex {
        val module = File(System.getProperty("user.dir"))
        val repo = module.parentFile
        val asset = { path: String -> File(repo, path) }
        val assetRoots =
            listOf(
                "settings/src/main/assets/legal" to "legal",
                "settings/src/main/assets/legal-android" to "legal-android",
                "core/rime/src/main/assets/rime/licenses" to "rime/licenses",
                "app/src/main/assets/rime/starter" to "rime/starter",
                "ime/src/main/assets/emoji" to "emoji",
            )
        val skipped = setOf("NOTICE.md", "ThirdPartyNotices.txt", "AndroidNotices.txt")
        val assets =
            assetRoots.flatMap { (dir, prefix) ->
                val root = asset(dir)
                root
                    .walkTopDown()
                    .filter { it.isFile && it.name !in skipped }
                    .map { LicenceAsset("$prefix/${it.relativeTo(root).path}", it.readText()) }
                    .toList()
            }
        val notices =
            listOf(
                "app/src/main/assets/rime/starter/NOTICE.md",
                "ime/src/main/assets/emoji/NOTICE.md",
                "settings/src/main/assets/legal-android/AndroidNotices.txt",
                "settings/src/main/assets/legal/ThirdPartyNotices.txt",
            ).joinToString("\n\n") { asset(it).readText() }
        return LicenceIndexBuilder.build(notices, assets)
    }
}
