package app.songyan.ime.content

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import app.songyan.ime.R
import app.songyan.ime.deploy.DeployContract
import app.songyan.ime.deploy.DeployMode
import app.songyan.ime.deploy.DeployRequest
import app.songyan.ime.deploy.RequestState
import app.songyan.ime.packages.EngineFeatureReport
import app.songyan.ime.packages.EngineFeatureSettings
import app.songyan.ime.packages.FuzzyPinyin
import app.songyan.ime.packages.Prediction
import app.songyan.ime.packages.RecipeResourceBudget
import app.songyan.ime.settings.state.EngineFeaturesGateway
import app.songyan.ime.settings.state.EngineFeaturesSnapshot
import app.songyan.ime.settings.state.FeatureChangeResult
import app.songyan.ime.settings.state.GrammarSelection
import app.songyan.ime.settings.state.LuaComponentInfo
import app.songyan.ime.settings.state.SettingsAppStateHolder
import app.songyan.ime.settings.state.StoredFeatureFile
import app.songyan.ime.settings.state.TransferProgress
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.EngineFeatureStore
import app.songyan.ime.storage.GenerationPublisher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestOutputStream
import java.security.MessageDigest

/**
 * The settings side of the engine features: what the active build reports, and changes that
 * store the new choice and redeploy the source in use. A change the deployment cannot publish is
 * undone, so the stored choices never claim what the keyboard does not do.
 */
internal class EngineFeaturesGatewayImpl(
    context: Context,
    private val storage: AppStorage,
    private val client: ImportDeployClient = ImportDeployClient(context.applicationContext, storage),
) : EngineFeaturesGateway {
    private val appContext = context.applicationContext
    private val store = EngineFeatureStore(storage)
    private val publisher = GenerationPublisher(storage)

    /** One change at a time: each is a read-modify-write of the settings plus a deployment. */
    private val changes = Mutex()

    override val available: Boolean = true

    override suspend fun load(schemaId: String): EngineFeaturesSnapshot? =
        withContext(Dispatchers.IO) {
            val active = publisher.readActive() ?: return@withContext null
            val build = File(storage.generationDir(active.generation), "build")
            val report =
                EngineFeatureReport.decode(
                    File(build, EngineFeatureReport.FILE_NAME).takeIf { it.isFile }?.readText(Charsets.UTF_8),
                ) ?: return@withContext null
            val schema = report.schemas[schemaId] ?: EngineFeatureReport.SchemaFeatures()
            val settings = store.read()
            EngineFeaturesSnapshot(
                schemaId = schemaId,
                typoCorrection = schema.typoCorrection,
                typoCorrectionOn = schema.typoCorrectionOn,
                fuzzyPinyin = schema.fuzzyPinyin,
                fuzzyPairs = schema.fuzzyPairs.toSet(),
                prediction = schema.prediction,
                predictionOn = schema.predictionOn,
                predictionDatabase = settings.predictionDatabase?.let(::info),
                lua = schema.lua,
                luaComponents =
                    schema.luaComponents.map {
                        LuaComponentInfo(it.prescription, it.displayName, it.list, it.enabled)
                    },
                grammar = schema.grammar,
                grammarModels = settings.grammarModels.map(::info),
                grammarChoice =
                    when (val choice = settings.grammar[schemaId]) {
                        null -> GrammarSelection.SchemeDefault
                        else -> choice.model?.let { GrammarSelection.Model(it) } ?: GrammarSelection.Off
                    },
            )
        }

    override suspend fun setTypoCorrection(enabled: Boolean) = change { it.copy(typoCorrection = enabled) }

    override suspend fun setFuzzyPinyin(
        schemaId: String,
        pairs: Set<String>,
    ): FeatureChangeResult {
        val known = FuzzyPinyin.allPairs.map { it.id }.filter { it in pairs }
        return change { settings ->
            val map = settings.fuzzyPinyin.toMutableMap()
            if (known.isEmpty()) map.remove(schemaId) else map[schemaId] = known
            settings.copy(fuzzyPinyin = map)
        }
    }

    override suspend fun setPrediction(enabled: Boolean) = change { it.copy(prediction = enabled) }

    override suspend fun setLuaComponent(
        schemaId: String,
        prescription: String,
        enabled: Boolean,
    ) = change { settings ->
        val map = settings.disabledLua.toMutableMap()
        val disabled = map[schemaId].orEmpty().toMutableSet()
        if (enabled) disabled.remove(prescription) else disabled.add(prescription)
        if (disabled.isEmpty()) map.remove(schemaId) else map[schemaId] = disabled.sorted()
        settings.copy(disabledLua = map)
    }

    override suspend fun setGrammar(
        schemaId: String,
        selection: GrammarSelection,
    ) = change { settings ->
        val map = settings.grammar.toMutableMap()
        when (selection) {
            GrammarSelection.SchemeDefault -> map.remove(schemaId)
            GrammarSelection.Off -> map[schemaId] = EngineFeatureSettings.GrammarChoice(null)
            is GrammarSelection.Model -> map[schemaId] = EngineFeatureSettings.GrammarChoice(selection.name)
        }
        settings.copy(grammar = map)
    }

    override suspend fun importPredictionDatabase(uri: Uri): FeatureChangeResult =
        storePrediction(importFile(uriSource(uri), displayName(uri), storage.featuresDir, ::predictionName, PREDICTION))

    override suspend fun importGrammarModel(uri: Uri): FeatureChangeResult =
        storeGrammar(importFile(uriSource(uri), displayName(uri), storage.grammarModelsDir, ::grammarName, GRAMMAR))

    override suspend fun importPredictionDatabaseFromUrl(
        url: String,
        progress: (TransferProgress) -> Unit,
    ): FeatureChangeResult =
        storePrediction(downloaded(url, storage.featuresDir, ::predictionName, PREDICTION, progress))

    override suspend fun importGrammarModelFromUrl(
        url: String,
        progress: (TransferProgress) -> Unit,
    ): FeatureChangeResult = storeGrammar(downloaded(url, storage.grammarModelsDir, ::grammarName, GRAMMAR, progress))

    override fun cancelRunning() = client.cancelCurrent()

    private suspend fun storePrediction(imported: Imported): FeatureChangeResult =
        when (imported) {
            is Imported.Refused -> imported.result
            is Imported.Stored -> change { it.copy(predictionDatabase = imported.file) }
        }

    private suspend fun storeGrammar(imported: Imported): FeatureChangeResult =
        when (imported) {
            is Imported.Refused -> imported.result
            is Imported.Stored ->
                change { settings ->
                    settings.copy(
                        grammarModels =
                            settings.grammarModels.filter { it.name != imported.file.name } + imported.file,
                    )
                }.also { result ->
                    if (result is FeatureChangeResult.Success) SettingsAppStateHolder.current.refresh()
                }
        }

    /** What a kind of feature file is checked against before it is stored. */
    private class FileKind(
        val invalidMessage: Int,
        val validate: (File) -> Unit,
    )

    private fun uriSource(uri: Uri): () -> InputStream =
        { appContext.contentResolver.openInputStream(uri) ?: throw IOException("unreadable") }

    /**
     * Has the deploy process download [url] (only that process may use the network), then imports
     * the file like a picked one. A failed download says why, and offers a retry when trying again
     * can help.
     */
    private suspend fun downloaded(
        url: String,
        directory: File,
        name: (String) -> String,
        kind: FileKind,
        progress: (TransferProgress) -> Unit,
    ): Imported =
        client.detached {
            val request = DeployRequest(DeployContract.newRequestId(), DeployMode.DOWNLOAD_FILE, url = url)
            val file = storage.downloadFile(request.requestId)
            try {
                when (val outcome = client.await(request, progress)) {
                    is DeployWait.Finished ->
                        if (outcome.status.state == RequestState.SUCCEEDED) {
                            // The download is complete; what follows is the install.
                            val size = withContext(Dispatchers.IO) { file.length() }
                            progress(TransferProgress(size, size))
                            importFile(
                                { FileInputStream(file) },
                                url.substringAfterLast('/').substringBefore('?'),
                                directory,
                                name,
                                kind,
                            )
                        } else {
                            val problem =
                                DictionaryImportProblems.kindNamed(outcome.status.problem)
                                    ?: DictionaryImportProblemKind.DOWNLOAD_FAILED
                            Imported.Refused(downloadFailure(problem, outcome.status.paths))
                        }

                    DeployWait.TimedOut ->
                        Imported.Refused(downloadFailure(DictionaryImportProblemKind.DEPLOY_TIMEOUT, emptyList()))

                    DeployWait.Unavailable ->
                        Imported.Refused(downloadFailure(DictionaryImportProblemKind.DEPLOY_UNAVAILABLE, emptyList()))
                }
            } finally {
                file.delete()
            }
        }

    private fun downloadFailure(
        problem: DictionaryImportProblemKind,
        paths: List<String>,
    ): FeatureChangeResult.Failure =
        FeatureChangeResult.Failure(
            // The dictionary size limit is not this file's limit, so a too-large file has its own words.
            message =
                if (problem == DictionaryImportProblemKind.FILE_TOO_LARGE) {
                    appContext.getString(R.string.features_error_too_large)
                } else {
                    DictionaryImportMessages.format(appContext, problem, paths)
                },
            retryable = DictionaryImportProblems.retryable(problem),
        )

    private sealed interface Imported {
        data class Stored(
            val file: EngineFeatureSettings.StoredFile,
        ) : Imported

        data class Refused(
            val result: FeatureChangeResult,
        ) : Imported
    }

    /** Copies [open]'s stream into [directory] under the name [name] gives its digest, once [kind] accepted it. */
    private suspend fun importFile(
        open: () -> InputStream,
        displayName: String?,
        directory: File,
        name: (String) -> String,
        kind: FileKind,
    ): Imported =
        client.detached {
            withContext(Dispatchers.IO) {
                directory.mkdirs()
                val temp = File(directory, ".import-" + System.nanoTime() + ".tmp")
                try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    val size =
                        open().use { stream ->
                            DigestOutputStream(FileOutputStream(temp), digest).use { output ->
                                DictionaryImportFiles.copyCapped(
                                    stream,
                                    output,
                                    RecipeResourceBudget.MODEL_FILE.toLong(),
                                )
                            }
                        }
                    try {
                        kind.validate(temp)
                    } catch (e: Exception) {
                        Log.i(TAG, "rejected feature file: " + e.message)
                        return@withContext refused(kind.invalidMessage)
                    }
                    val sha = digest.digest().joinToString("") { "%02x".format(it) }
                    val stored = EngineFeatureSettings.StoredFile(name(sha), size, sha, displayName)
                    // Under the feature lock: a deployment never sees a half-moved file.
                    store.lock {
                        Files.move(
                            temp.toPath(),
                            File(directory, stored.name).toPath(),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    }
                    Imported.Stored(stored)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: DictionaryImportProblemException) {
                    refused(R.string.features_error_too_large)
                } catch (e: Exception) {
                    Log.w(TAG, "cannot read the feature file", e)
                    refused(R.string.features_error_read)
                } finally {
                    temp.delete()
                }
            }
        }

    private fun refused(message: Int) = Imported.Refused(FeatureChangeResult.Failure(appContext.getString(message)))

    /**
     * Stores the settings [update] makes and redeploys the source in use; on failure the previous
     * settings come back. Files no setting names any more are deleted either way.
     */
    private suspend fun change(update: (EngineFeatureSettings) -> EngineFeatureSettings): FeatureChangeResult =
        client.detached {
            changes.withLock {
                val previous = withContext(Dispatchers.IO) { store.lock { store.read() } }
                val next = update(previous)
                if (next == previous) return@withLock FeatureChangeResult.Success
                withContext(Dispatchers.IO) { store.lock { store.write(next) } }
                val outcome =
                    client.await(DeployRequest(DeployContract.newRequestId(), DeployMode.REDEPLOY_ACTIVE))
                val succeeded = outcome is DeployWait.Finished && outcome.status.state == RequestState.SUCCEEDED
                withContext(Dispatchers.IO) {
                    store.lock {
                        // Undo only our own change: a later write would have moved on from [next].
                        if (!succeeded && store.read() == next) store.write(previous)
                        store.deleteUnnamed(store.read())
                    }
                }
                when {
                    succeeded -> FeatureChangeResult.Success
                    outcome is DeployWait.Unavailable ->
                        FeatureChangeResult.Failure(
                            appContext.getString(R.string.scheme_import_error_deploy_unavailable),
                        )
                    else -> FeatureChangeResult.Failure(appContext.getString(R.string.features_error_save))
                }
            }
        }

    private fun info(stored: EngineFeatureSettings.StoredFile) =
        StoredFeatureFile(stored.name, stored.displayName ?: stored.name, stored.size)

    private fun predictionName(sha: String) = "predict-" + sha.take(NAME_DIGEST) + ".db"

    private fun grammarName(sha: String) = "songyan-model-" + sha.take(NAME_DIGEST) + ".gram"

    private fun displayName(uri: Uri): String? =
        runCatching {
            appContext.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()

    companion object {
        private const val TAG = "EngineFeatures"
        private const val NAME_DIGEST = 16

        private val PREDICTION =
            FileKind(R.string.features_error_prediction_file) { Prediction.validateDatabase(it.toPath()) }

        private val GRAMMAR =
            FileKind(R.string.features_error_grammar_file) {
                if (it.length() ==
                    0L
                ) {
                    throw IOException("empty grammar model")
                }
            }
    }
}
