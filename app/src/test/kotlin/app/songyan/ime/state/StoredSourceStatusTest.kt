package app.songyan.ime.state

import app.songyan.ime.packages.EngineFeatureSettings
import app.songyan.ime.settings.state.StoredSourceStatus
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.EngineFeatureStore
import app.songyan.ime.storage.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StoredSourceStatusTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun anInstalledModelsOriginalNameSatisfiesTheRequirementUntilItsFileIsGone() {
        val storage = AppStorage(temp.newFolder("files"))
        val source = storage.sourceDir("test").also { it.mkdirs() }
        File(
            source,
            "test.schema.yaml",
        ).writeText("schema: {schema_id: test}\ngrammar: {language: wanxiang-lts-zh-hans}\n")
        val required = listOf("wanxiang-lts-zh-hans.gram")
        assertEquals(required, missingGrammarModels(storage, source))
        val file = storage.grammarModelFile("songyan-model-123.gram")
        file.parentFile!!.mkdirs()
        file.writeText("model fixture")
        val features = EngineFeatureStore(storage)
        features.lock {
            features.write(
                EngineFeatureSettings(
                    grammarModels =
                        listOf(
                            EngineFeatureSettings.StoredFile(file.name, file.length(), "123", required.single()),
                        ),
                ),
            )
        }
        assertEquals(emptyList<String>(), missingGrammarModels(storage, source))
        assertEquals(emptyMap<String, EngineFeatureSettings.GrammarChoice>(), features.read().grammar)
        file.delete()
        assertEquals(required, missingGrammarModels(storage, source))
    }

    private fun status(
        kind: SourceKind = SourceKind.SCHEME,
        intact: Boolean = true,
        stored: String = "abc",
        published: String? = null,
    ) = storedSourceStatus(kind, intact, stored, published)

    @Test
    fun aSourceTheKeyboardDoesNotUseIsNotInUse() = assertEquals(StoredSourceStatus.NOT_IN_USE, status())

    @Test
    fun aGenerationBuiltFromTheStoredFilesIsInUse() {
        assertEquals(StoredSourceStatus.IN_USE, status(published = "abc+overlay1"))
        assertEquals(StoredSourceStatus.IN_USE, status(published = "abc"))
    }

    @Test
    fun filesNewerThanTheRunningGenerationOfferTheNewVersion() {
        assertEquals(StoredSourceStatus.UPDATE_AVAILABLE, status(stored = "def", published = "abc+overlay1"))
        // A longer revision that merely begins with the stored one is another revision.
        assertEquals(StoredSourceStatus.UPDATE_AVAILABLE, status(stored = "abc", published = "abcd+overlay1"))
    }

    @Test
    fun missingFilesMakeAPackageDamagedWhetherOrNotItIsInUse() {
        assertEquals(StoredSourceStatus.DAMAGED, status(intact = false))
        assertEquals(StoredSourceStatus.DAMAGED, status(intact = false, published = "abc+x"))
    }

    @Test
    fun theBuiltInSourceIsNeverDamagedOrOutdated() {
        assertEquals(StoredSourceStatus.IN_USE, status(kind = SourceKind.BUILTIN, intact = false, published = "zzz"))
    }
}
