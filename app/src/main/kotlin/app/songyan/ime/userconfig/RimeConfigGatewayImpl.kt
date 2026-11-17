package app.songyan.ime.userconfig

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import app.songyan.ime.R
import app.songyan.ime.content.DeployWait
import app.songyan.ime.content.ImportDeployClient
import app.songyan.ime.deploy.DeployContract
import app.songyan.ime.deploy.DeployMode
import app.songyan.ime.deploy.DeployPipeline
import app.songyan.ime.deploy.DeployRequest
import app.songyan.ime.deploy.RequestState
import app.songyan.ime.settings.state.RimeConfigApplyResult
import app.songyan.ime.settings.state.RimeConfigEditResult
import app.songyan.ime.settings.state.RimeConfigFileResult
import app.songyan.ime.settings.state.RimeConfigGateway
import app.songyan.ime.settings.state.RimeConfigInfo
import app.songyan.ime.settings.state.RimeConfigSaveResult
import app.songyan.ime.settings.state.RimeEditTarget
import app.songyan.ime.settings.state.RimeOverrideFile
import app.songyan.ime.settings.state.RimeScriptCreateResult
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.SourceCatalog
import app.songyan.ime.storage.SourceIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * The settings side of the user's Rime configuration: the folder's state, read off the main
 * thread, and applying it through the :deploy process ([DeployMode.APPLY_USER_CONFIG]). Everything
 * here works on the configuration of the source in use ([UserConfigScope.active]); another source's
 * stays as it is.
 */
internal class RimeConfigGatewayImpl(
    context: Context,
    private val storage: AppStorage,
    private val client: ImportDeployClient = ImportDeployClient(context.applicationContext, storage),
) : RimeConfigGateway {
    private val appContext = context.applicationContext
    private val scripts = UserScripts(storage)

    /** The scope [info] last read, for the folder intent, which is built on the main thread. */
    @Volatile private var lastScope: String? = null

    /** The scope everything edits now, once the single folder of earlier versions is moved into the scopes. */
    private class Scope(
        val sourceId: String,
        storage: AppStorage,
    ) {
        val overrides = UserOverrides(storage, sourceId)
        val editor = UserConfigEditor(storage, sourceId, overrides)
        val status = UserConfigStatus.Store(storage, sourceId)
        val edit: File = storage.userConfigDir(sourceId)
        val applied: File = storage.appliedConfigDir(sourceId)
        val sourceDir: File? = storage.sourceDir(sourceId).takeIf { it.isDirectory }
    }

    /** Reads the disk: on IO only. */
    private fun scope(): Scope {
        UserConfigMigration.run(storage)
        return Scope(UserConfigScope.active(storage), storage).also { lastScope = it.sourceId }
    }

    /** File names only; the scope does not matter for them. */
    private val names = UserConfigEditor(storage, SourceIds.BUILTIN)

    override val available: Boolean = true

    /**
     * The Files app opens a provider's root for `vnd.android.document/root`; some versions only
     * open a folder document. Both are tried in this order by the screen.
     */
    override fun openFolderIntents(): List<Intent> =
        listOf(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(RimeConfigProvider.rootUri(appContext), DocumentsContract.Root.MIME_TYPE_ITEM)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(
                    RimeConfigProvider.editFolderUri(appContext, lastScope),
                    DocumentsContract.Document.MIME_TYPE_DIR,
                ).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )

    override suspend fun info(): RimeConfigInfo =
        withContext(Dispatchers.IO) {
            val scope = scope()
            val files = UserConfigFiles.files(scope.edit)
            val edited = UserConfigFiles.digest(scope.edit)
            val applied = UserConfigFiles.digest(scope.applied)
            val status = scope.status.read()
            val active = GenerationPublisher(storage).readActive()?.sourceId
            val sourceDir = scope.sourceDir
            val editedFiles = scope.overrides.entries(sourceDir)
            RimeConfigInfo(
                overrides = editedFiles.map { RimeOverrideFile(it.path, it.updated) },
                unacknowledged = scope.overrides.unacknowledged(sourceDir, editedFiles.map { it.path }),
                unusedScripts =
                    scripts
                        .unconfirmed(scope.applied, sourceDir)
                        .map { it.path }
                        .distinct(),
                editTargets =
                    active
                        ?.let { SourceCatalog(storage).read(it) }
                        ?.schemes
                        ?.map { RimeEditTarget(it.schemaId, it.name) }
                        .orEmpty(),
                fileCount = files.size,
                pending = edited != applied,
                // A failure only matters while the files that caused it are still the edited ones.
                failure =
                    status?.takeIf { it.state == UserConfigStatus.State.FAILED && edited != applied }?.let(
                        ::message,
                    ),
                notice =
                    status
                        ?.takeIf { it.state == UserConfigStatus.State.SKIPPED && it.sourceId == scope.sourceId }
                        ?.let { appContext.getString(R.string.rime_config_skipped) },
            )
        }

    override suspend fun apply(confirmed: List<String>): RimeConfigApplyResult =
        client.detached {
            val unconfirmed =
                withContext(Dispatchers.IO) {
                    val scope = scope()
                    scripts.unconfirmed(scope.edit, scope.sourceDir)
                }
            if (unconfirmed.isNotEmpty()) {
                // Only what the user was shown counts: a script that turned up since is asked about too.
                if (!unconfirmed.all { it.key in confirmed }) {
                    val firstTime = withContext(Dispatchers.IO) { !scripts.riskAccepted() }
                    return@detached RimeConfigApplyResult.NeedsConfirmation(
                        unconfirmed.map { it.id },
                        firstTime,
                        unconfirmed.map { it.key },
                    )
                }
                // The deployment checks the files it applies against this record, so a change after now is refused.
                withContext(Dispatchers.IO) { scripts.accept(unconfirmed) }
            }
            val request = DeployRequest(DeployContract.newRequestId(), DeployMode.APPLY_USER_CONFIG)
            when (val outcome = client.await(request)) {
                is DeployWait.Finished ->
                    if (outcome.status.state == RequestState.SUCCEEDED) {
                        RimeConfigApplyResult.Applied
                    } else {
                        val problem = outcome.status.problem?.removePrefix(DeployPipeline.CONFIG_PREFIX)
                        RimeConfigApplyResult.Failure(
                            message(problem, outcome.status.paths.firstOrNull(), outcome.status.detail),
                        )
                    }
                DeployWait.TimedOut, DeployWait.Unavailable ->
                    RimeConfigApplyResult.Failure(appContext.getString(R.string.rime_config_error_unavailable))
            }
        }

    override suspend fun restore(path: String) {
        withContext(Dispatchers.IO) { scope().overrides.restore(path) }
    }

    override suspend fun acknowledge(paths: List<String>) {
        withContext(Dispatchers.IO) { scope().overrides.acknowledge(paths) }
    }

    override suspend fun loadFile(
        key: String,
        grammarExample: Boolean,
    ): RimeConfigFileResult =
        withContext(Dispatchers.IO) {
            val name = names.fileName(key) ?: return@withContext RimeConfigFileResult.Failure(null)
            try {
                val scope = scope()
                val loaded = scope.editor.load(name, scope.sourceDir) { templateFor(name) }
                val text =
                    if (grammarExample) {
                        GrammarParameterExample.appendedTo(loaded) { appContext.getString(grammarHeading(it)) }
                    } else {
                        loaded
                    }
                RimeConfigFileResult.Loaded(titleOf(key), name, text)
            } catch (e: UserConfigException) {
                RimeConfigFileResult.Failure(failureText(e))
            } catch (_: IOException) {
                RimeConfigFileResult.Failure(null)
            }
        }

    override suspend fun createScript(name: String): RimeScriptCreateResult =
        withContext(Dispatchers.IO) {
            val key = RimeEditTarget.scriptKey(name)
            val path = names.fileName(key) ?: return@withContext RimeScriptCreateResult.Failure(null)
            try {
                val scope = scope()
                // A name the source's own scripts use would replace that script: not by accident.
                val own = scope.sourceDir?.let { UserOverrides.packageFile(it, path) }
                if (own != null) {
                    return@withContext RimeScriptCreateResult.Failure(
                        appContext.getString(R.string.rime_config_script_taken, name),
                    )
                }
                scope.editor.load(path, null) {
                    ScriptTemplate.text(name, appContext.getString(R.string.rime_config_script_template_hint, name))
                }
                RimeScriptCreateResult.Created(key)
            } catch (e: UserConfigException) {
                RimeScriptCreateResult.Failure(failureText(e))
            } catch (_: IOException) {
                RimeScriptCreateResult.Failure(null)
            }
        }

    override suspend fun saveFile(
        key: String,
        text: String,
    ): RimeConfigSaveResult =
        withContext(Dispatchers.IO) {
            val name = names.fileName(key) ?: return@withContext RimeConfigSaveResult.Failure(null)
            try {
                val scope = scope()
                val refusal = scope.editor.save(name, text)
                when {
                    refusal == null -> {
                        scope.sourceDir
                            ?.let { UserOverrides.packageFile(it, name) }
                            ?.let { scope.overrides.recordBase(name, it) }
                        RimeConfigSaveResult.Saved
                    }
                    else ->
                        RimeConfigSaveResult.Failure(
                            message(refusal.problem.name, refusal.file, refusal.detail ?: refusal.message),
                        )
                }
            } catch (e: IOException) {
                RimeConfigSaveResult.Failure(message(null, name, e.message))
            }
        }

    override suspend fun externalEditIntents(key: String): RimeConfigEditResult =
        withContext(Dispatchers.IO) {
            val name = names.fileName(key) ?: return@withContext RimeConfigEditResult.Failure(null)
            val scope = scope()
            try {
                val sourceDir = scope.sourceDir
                val loaded = scope.editor.load(name, sourceDir) { templateFor(name) }
                if (scope.overrides.overrideFile(name) == null) {
                    scope.editor.save(name, loaded)?.let {
                        return@withContext RimeConfigEditResult.Failure(failureText(it))
                    }
                    sourceDir
                        ?.let { UserOverrides.packageFile(it, name) }
                        ?.let { scope.overrides.recordBase(name, it) }
                }
            } catch (e: UserConfigException) {
                return@withContext RimeConfigEditResult.Failure(failureText(e))
            } catch (_: IOException) {
                return@withContext RimeConfigEditResult.Failure(null)
            }
            val uri =
                DocumentsContract.buildDocumentUri(
                    RimeConfigProvider.authority(appContext),
                    RimeConfigPaths.editId(scope.sourceId, name),
                )
            RimeConfigEditResult.Ready(
                listOf(Intent.ACTION_EDIT, Intent.ACTION_VIEW).map {
                    Intent(it)
                        .setDataAndType(uri, "text/plain")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                },
            )
        }

    private fun titleOf(key: String): String? =
        if (RimeEditTarget.scriptName(key) != null) {
            names.fileName(key)
        } else if (key == RimeEditTarget.GLOBAL_KEY) {
            null
        } else {
            GenerationPublisher(storage)
                .readActive()
                ?.sourceId
                ?.let { SourceCatalog(storage).read(it) }
                ?.schemes
                ?.firstOrNull { it.schemaId == key }
                ?.name
        }

    private fun templateFor(name: String): String =
        if (name.endsWith(".lua")) {
            val script = name.removePrefix("lua/").removeSuffix(".lua")
            ScriptTemplate.text(script, appContext.getString(R.string.rime_config_script_template_hint, script))
        } else {
            template()
        }

    /** A new file that patches nothing yet, with a hint on how to. */
    private fun template(): String =
        CustomPatchTemplate.text(
            appContext.getString(R.string.rime_config_template_hint),
            appContext.getString(R.string.rime_config_template_example),
        )

    private fun grammarHeading(heading: GrammarParameterExample.Heading): Int =
        when (heading) {
            GrammarParameterExample.Heading.NEW_PATCH -> R.string.rime_config_grammar_example_new
            GrammarParameterExample.Heading.UNDER_PATCH -> R.string.rime_config_grammar_example_under
            GrammarParameterExample.Heading.EMPTY_PATCH -> R.string.rime_config_grammar_example_empty
        }

    private fun failureText(e: UserConfigException): String =
        when (e.problem) {
            UserConfigProblem.TOO_LARGE -> appContext.getString(R.string.rime_config_refused_large, e.file.orEmpty())
            UserConfigProblem.UNSAFE_REFERENCE -> appContext.getString(R.string.rime_editor_unsafe_reference)
            else -> message(e.problem.name, e.file, e.detail ?: e.message)
        }

    private fun message(status: UserConfigStatus): String = message(status.problem, status.file, status.detail)

    private fun message(
        problem: String?,
        file: String?,
        detail: String?,
    ): String =
        if (detail != null) {
            if (file != null) appContext.getString(R.string.rime_config_error_detail, file, detail) else detail
        } else {
            appContext.getString(
                if (problem != null) R.string.rime_config_error_deploy else R.string.rime_config_error_unavailable,
            )
        }
}
