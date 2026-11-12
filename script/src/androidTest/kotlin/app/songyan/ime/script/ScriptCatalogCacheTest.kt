package app.songyan.ime.script

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The keyboard process reads the catalog without binding: the snapshot must reflect every
 *  mutation and stay empty (not broken) before anything is installed. */
@RunWith(AndroidJUnit4::class)
class ScriptCatalogCacheTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val registry = ScriptProxyRegistry(context)
    private val cache = ScriptCatalogCache(context)

    @Test
    fun cacheFollowsMutationsWithoutBinding() =
        runBlocking {
            // Other suites in this APK share the app's storage; start from a clean state so the
            // empty-cache assertion is about the cache, not about test order.
            registry.reload()
            registry.entries.forEach { registry.delete(it.id) }
            assertTrue("empty cache is empty, not broken", cache.entries().isEmpty())

            val quote = '\u0022'
            val script =
                listOf(
                    "// @api 1",
                    "// @name 缓存测试",
                    "export default async ({ text }) => text;",
                ).joinToString("\n").toByteArray(Charsets.UTF_8)

            val entry = registry.add(script, ScriptSourceOrigin.Pasted)
            registry.confirm(entry.id, emptyMap())
            registry.setEnabled(entry.id, true)

            // A fresh reader (as a cold IME process would construct) sees the enabled entry.
            val fresh = ScriptCatalogCache(context)
            assertEquals(listOf("缓存测试"), fresh.enabledEntries().map { it.name })

            registry.setEnabled(entry.id, false)
            assertEquals(
                "disabling removes it from the toolbar list",
                emptyList<String>(),
                ScriptCatalogCache(
                    context,
                ).enabledEntries().map {
                    it.name
                },
            )

            registry.delete(entry.id)
            assertTrue(ScriptCatalogCache(context).entries().isEmpty())
        }
}
