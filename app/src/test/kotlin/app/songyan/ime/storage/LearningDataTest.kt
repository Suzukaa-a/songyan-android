package app.songyan.ime.storage

import app.songyan.ime.prefs.SchemaReconciliation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Where learning data lives, how earlier installs move into it, and the stored-source list. */
class LearningDataTest {
    private val storage = AppStorage(Files.createTempDirectory("files").toFile()).also { it.ensureBaseDirectories() }

    private fun learned(
        namespace: String,
        text: String,
    ): File =
        File(storage.learningDir(namespace), "songyan_shared.userdb/CURRENT").also {
            it.parentFile.mkdirs()
            it.writeText(text)
        }

    @Test
    fun builtInAndDictionaryShareOneNamespaceAndEveryPackageHasItsOwn() {
        assertEquals(LearningData.PINYIN, LearningData.namespace(SourceIds.BUILTIN))
        assertEquals(LearningData.PINYIN, LearningData.namespace(SourceIds.DICTIONARY))
        assertEquals("scheme-0123456789abcdef", LearningData.namespace("scheme-0123456789abcdef"))
        assertEquals(
            LearningData.directory(storage, SourceIds.BUILTIN),
            LearningData.directory(storage, SourceIds.DICTIONARY),
        )
    }

    /** Records merges and copies the legacy file into the target, as librime's import would add it. */
    private class RecordingMerger(
        var succeed: Boolean = true,
    ) : LearningMerger {
        val merged = mutableListOf<String>()

        override suspend fun merge(
            from: File,
            into: File,
        ): Boolean {
            if (!succeed) return false
            merged += from.name
            File(into, "merged-from-" + from.name).writeText(File(from, "songyan_shared.userdb/CURRENT").readText())
            return true
        }
    }

    private val shared get() = File(storage.learningDir(LearningData.PINYIN), "songyan_shared.userdb/CURRENT")

    @Test
    fun theActiveSourcesDataBecomesTheNamespaceAndTheOtherIsMergedIntoIt() =
        runBlocking {
            learned(SourceIds.BUILTIN, "builtin")
            learned(SourceIds.DICTIONARY, "dictionary")
            val merger = RecordingMerger()

            assertTrue(LearningData.migrate(storage, activeSourceId = SourceIds.DICTIONARY, merger = merger))

            assertEquals("dictionary", shared.readText())
            assertEquals(listOf(SourceIds.BUILTIN), merger.merged)
            assertEquals(
                "builtin",
                File(storage.learningDir(LearningData.PINYIN), "merged-from-songyan-builtin").readText(),
            )
            assertFalse(storage.learningDir(SourceIds.DICTIONARY).exists())
            assertFalse("merged legacy data is deleted", storage.learningDir(SourceIds.BUILTIN).exists())
            assertEquals(listOf(LearningData.PINYIN), storage.userRoot.list()!!.toList())
        }

    @Test
    fun withoutAnActiveLegacySourceTheNewestDataBecomesTheNamespace() =
        runBlocking {
            learned(SourceIds.BUILTIN, "old")
                .parentFile.parentFile
                .walkTopDown()
                .forEach { it.setLastModified(1_000) }
            learned(SourceIds.DICTIONARY, "new")
            val merger = RecordingMerger()

            assertTrue(LearningData.migrate(storage, activeSourceId = "scheme-0123456789abcdef", merger = merger))

            assertEquals("new", shared.readText())
            assertEquals(listOf(SourceIds.BUILTIN), merger.merged)
        }

    @Test
    fun anExistingNamespaceReceivesBothLegacyDirectoriesByMerging() =
        runBlocking {
            learned(LearningData.PINYIN, "current")
            learned(SourceIds.BUILTIN, "builtin")
            learned(SourceIds.DICTIONARY, "dictionary")
            val merger = RecordingMerger()

            assertTrue(LearningData.migrate(storage, activeSourceId = SourceIds.BUILTIN, merger = merger))

            assertEquals("the namespace is never replaced", "current", shared.readText())
            assertEquals(listOf(SourceIds.BUILTIN, SourceIds.DICTIONARY), merger.merged)
            assertFalse(storage.learningDir(SourceIds.BUILTIN).exists())
            assertFalse(storage.learningDir(SourceIds.DICTIONARY).exists())
        }

    @Test
    fun aFailedMergeKeepsTheLegacyDirectoryAndTheNextStartRetries() =
        runBlocking {
            learned(SourceIds.BUILTIN, "builtin")
            learned(SourceIds.DICTIONARY, "dictionary")
            val merger = RecordingMerger(succeed = false)

            assertFalse(LearningData.migrate(storage, activeSourceId = SourceIds.DICTIONARY, merger = merger))
            assertEquals("dictionary", shared.readText())
            assertEquals(
                "builtin",
                File(storage.learningDir(SourceIds.BUILTIN), "songyan_shared.userdb/CURRENT").readText(),
            )

            val throwing = LearningMerger { _, _ -> throw java.io.IOException("engine did not open") }
            assertFalse(LearningData.migrate(storage, activeSourceId = SourceIds.DICTIONARY, merger = throwing))
            assertTrue(storage.learningDir(SourceIds.BUILTIN).isDirectory)

            merger.succeed = true
            assertTrue(LearningData.migrate(storage, activeSourceId = SourceIds.DICTIONARY, merger = merger))
            assertEquals(listOf(SourceIds.BUILTIN), merger.merged)
            assertFalse(storage.learningDir(SourceIds.BUILTIN).exists())
        }

    @Test
    fun withoutAGenerationOnlyTheRenameHappensAndTheMergeWaits() =
        runBlocking {
            learned(SourceIds.BUILTIN, "builtin")
            learned(SourceIds.DICTIONARY, "dictionary")

            assertFalse(LearningData.migrate(storage, activeSourceId = SourceIds.BUILTIN, merger = null))

            assertEquals("builtin", shared.readText())
            assertTrue(storage.learningDir(SourceIds.DICTIONARY).isDirectory)
        }

    @Test
    fun anInterruptedDeletionIsFinishedAndEmptyLegacyDirectoriesAreDropped() =
        runBlocking {
            learned(LearningData.PINYIN, "current")
            // A process killed while deleting a merged directory leaves its renamed remains.
            val remains = File(storage.userRoot, ".retired-songyan-builtin-1").also { File(it, "x.userdb").mkdirs() }
            // A legacy directory without any userdb (the engine only wrote installation.yaml).
            File(storage.learningDir(SourceIds.DICTIONARY), "installation.yaml").also {
                it.parentFile.mkdirs()
                it.writeText("installation_id: x\n")
            }
            val merger = RecordingMerger()

            assertTrue(LearningData.migrate(storage, activeSourceId = null, merger = merger))

            assertFalse(remains.exists())
            assertFalse(storage.learningDir(SourceIds.DICTIONARY).exists())
            assertTrue("nothing to merge", merger.merged.isEmpty())
            assertEquals("current", shared.readText())
        }

    @Test
    fun theSchemaPreferenceFollowsTheActiveGeneration() {
        val builtin = listOf("songyan_quanpin", "songyan_flypy")
        // Same family (built-in <-> dictionary): the user's choice stays.
        assertEquals(
            "songyan_flypy",
            SchemaReconciliation.choose("songyan_flypy", "pinyin", "pinyin", "songyan_quanpin", builtin),
        )
        // Another package: start on its primary input method.
        assertEquals(
            "rime_ice",
            SchemaReconciliation.choose("songyan_flypy", "pinyin", "scheme-1", "rime_ice", listOf("rime_ice")),
        )
        // A choice the generation cannot load falls back to its primary.
        assertEquals(
            "songyan_quanpin",
            SchemaReconciliation.choose("rime_ice", null, "pinyin", "songyan_quanpin", builtin),
        )
        assertEquals(
            "double",
            SchemaReconciliation.choose("double", null, "scheme-1", "rime_ice", listOf("rime_ice", "double")),
        )
    }

    @Test
    fun theCatalogListsStoredSourcesAndReadsLegacyPackages() {
        val scheme = storage.sourceDir("scheme-0123456789abcdef").also { it.mkdirs() }
        File(scheme, "default.yaml").writeText("schema_list: []\n")
        SourceCatalog.write(
            scheme,
            SourceManifest(
                "scheme-0123456789abcdef",
                SourceKind.SCHEME,
                "雾凇",
                listOf(SourceScheme("rime_ice", "雾凇拼音")),
                "r1",
            ),
        )
        val legacy = storage.sourceDir(SourceIds.LEGACY_SCHEME).also { it.mkdirs() }
        File(legacy, "default.yaml").writeText("schema_list: []\n")
        File(legacy, "user-scheme.json").writeText(
            "{\"displayName\": \"旧方案\", \"schemes\": [{\"id\": \"old\", \"name\": \"旧\"}]}",
        )
        storage.sourceDir("not a source").mkdirs()

        val listed = SourceCatalog(storage).list()

        assertEquals(listOf("scheme-0123456789abcdef", SourceIds.LEGACY_SCHEME), listed.map { it.sourceId })
        assertEquals(listOf("rime_ice"), listed[0].schemaIds)
        assertEquals(SourceCatalog.LEGACY_REVISION, listed[1].revision)
        assertEquals("旧方案", listed[1].displayName)
        assertNull(SourceCatalog(storage).read("missing"))
    }
}
