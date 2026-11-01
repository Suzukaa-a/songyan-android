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
import app.songyan.ime.deploy.DeploymentCoordinator
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * The settings side of the user's Rime configuration: the folder's state, read off the main
 * thread, and applying it through the :deploy process ([DeployMode.APPLY_USER_CONFIG]).
 */
internal class RimeConfigGatewayImpl(
    context: Context,
    private val storage: AppStorage,
    private val client: ImportDeployClient = ImportDeployClient(context.applicationContext, storage),
) : RimeConfigGateway {
    private val appContext = context.applicationContext
    private val statusStore = UserConfigStatus.Store(storage)
    private val overrides = UserOverrides(storage)
    private val editor = UserConfigEditor(storage, overrides)
    private val scripts = UserScripts(storage)

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
                .setDataAndType(RimeConfigProvider.editFolderUri(appContext), DocumentsContract.Document.MIME_TYPE_DIR)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )

    override suspend fun info(): RimeConfigInfo =
        withContext(Dispatchers.IO) {
            val files = UserConfigFiles.files(storage.userConfigDir)
            val edited = UserConfigFiles.digest(storage.userConfigDir)
            val applied = UserConfigFiles.digest(storage.appliedConfigDir)
            val status = statusStore.read()
            val active = GenerationPublisher(storage).readActive()?.sourceId
            val sourceDir = active?.let(storage::sourceDir)?.takeIf { it.isDirectory }
            RimeConfigInfo(
                overrides = overrides.entries(sourceDir).map { RimeOverrideFile(it.path, it.updated) },
                unacknowledged = overrides.unacknowledged(sourceDir),
                unusedScripts =
                    scripts
                        .unconfirmed(storage.appliedConfigDir, sourceDir)
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
                        ?.takeIf { it.state == UserConfigStatus.State.SKIPPED && it.sourceId == active }
                        ?.let { appContext.getString(R.string.rime_config_skipped) },
            )
        }

    override suspend fun apply(confirmed: List<String>): RimeConfigApplyResult =
        client.detached {
            val unconfirmed =
                withContext(Dispatchers.IO) { scripts.unconfirmed(storage.userConfigDir, activeSourceDir()) }
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
                        RimeConfigApplyResult.Failure(message(problem, outcome.status.paths.firstOrNull()))
                    }
                DeployWait.TimedOut, DeployWait.Unavailable ->
                    RimeConfigApplyResult.Failure(appContext.getString(R.string.rime_config_error_unavailable))
            }
        }

    override suspend fun restore(path: String) {
        withContext(Dispatchers.IO) { overrides.restore(path) }
    }

    override suspend fun acknowledge(paths: List<String>) {
        withContext(Dispatchers.IO) { overrides.acknowledge(paths) }
    }

    override suspend fun loadFile(
        key: String,
        grammarExample: Boolean,
    ): RimeConfigFileResult =
        withContext(Dispatchers.IO) {
            val name = editor.fileName(key) ?: return@withContext RimeConfigFileResult.Failure(null)
            try {
                val loaded = editor.load(name, activeSourceDir()) { templateFor(name) }
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
            val path = editor.fileName(key) ?: return@withContext RimeScriptCreateResult.Failure(null)
            try {
                // A name the source's own scripts use would replace that script: not by accident.
                val own = activeSourceDir()?.let { UserOverrides.packageFile(it, path) }
                if (own != null) {
                    return@withContext RimeScriptCreateResult.Failure(
                        appContext.getString(R.string.rime_config_script_taken, name),
                    )
                }
                editor.load(path, null) {
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
            val name = editor.fileName(key) ?: return@withContext RimeConfigSaveResult.Failure(null)
            try {
                val refusal = editor.save(name, text)
                when {
                    refusal == null -> RimeConfigSaveResult.Saved
                    refusal.problem == UserConfigProblem.INVALID_YAML -> RimeConfigSaveResult.Invalid(refusal.line)
                    else -> RimeConfigSaveResult.Failure(failureText(refusal))
                }
            } catch (_: IOException) {
                RimeConfigSaveResult.Failure(null)
            }
        }

    override suspend fun externalEditIntents(key: String): RimeConfigEditResult =
        withContext(Dispatchers.IO) {
            val name = editor.fileName(key) ?: return@withContext RimeConfigEditResult.Failure(null)
            try {
                editor.load(name, activeSourceDir()) { templateFor(name) }
            } catch (e: UserConfigException) {
                return@withContext RimeConfigEditResult.Failure(failureText(e))
            } catch (_: IOException) {
                return@withContext RimeConfigEditResult.Failure(null)
            }
            val uri = DocumentsContract.buildDocumentUri(RimeConfigProvider.authority(appContext), "edit/$name")
            RimeConfigEditResult.Ready(
                listOf(Intent.ACTION_EDIT, Intent.ACTION_VIEW).map {
                    Intent(it)
                        .setDataAndType(uri, "text/plain")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                },
            )
        }

    private fun activeSourceDir(): File? =
        GenerationPublisher(storage)
            .readActive()
            ?.sourceId
            ?.let(storage::sourceDir)
            ?.takeIf { it.isDirectory }

    private fun titleOf(key: String): String? =
        if (RimeEditTarget.scriptName(key) != null) {
            editor.fileName(key)
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
            else -> message(e.problem.name, e.file)
        }

    private fun message(status: UserConfigStatus): String = message(status.problem, status.file)

    private fun message(
        problem: String?,
        file: String?,
    ): String {
        val kind = UserConfigProblem.entries.firstOrNull { it.name == problem }
        return when {
            kind == UserConfigProblem.INVALID_YAML && file != null ->
                appContext.getString(
                    R.string.rime_config_error_yaml,
                    file,
                )
            kind == UserConfigProblem.NOT_TEXT && file != null ->
                appContext.getString(
                    R.string.rime_config_error_text,
                    file,
                )
            kind == UserConfigProblem.TOO_LARGE && file != null ->
                appContext.getString(
                    R.string.rime_config_error_large,
                    file,
                )
            kind == UserConfigProblem.UNCONFIRMED_SCRIPT -> appContext.getString(R.string.rime_config_error_script)
            kind == UserConfigProblem.UNSAFE_REFERENCE && file != null ->
                appContext.getString(R.string.rime_config_error_reference, file)
            kind == UserConfigProblem.TOO_MANY -> appContext.getString(R.string.rime_config_error_many)
            problem == DeploymentCoordinator.DEPLOY_PROBLEM || kind != null ->
                appContext.getString(
                    R.string.rime_config_error_deploy,
                )
            else -> appContext.getString(R.string.rime_config_error_unavailable)
        }
    }
}
