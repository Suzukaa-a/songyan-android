package app.songyan.ime.deploy

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas
import app.songyan.ime.packages.CompactSchemaAdapter
import app.songyan.ime.packages.EngineFeatureSettings
import app.songyan.ime.rime.AndroidAssetTree
import app.songyan.ime.rime.AssetTree
import app.songyan.ime.rime.BuiltinSourceInstaller
import app.songyan.ime.rime.RimeBindings
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.BuildStamp
import app.songyan.ime.storage.BuiltinSchemes
import app.songyan.ime.storage.EngineFeatureStore
import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.InputSchemeSetup
import app.songyan.ime.storage.PublicationRecord
import app.songyan.ime.storage.SourceCatalog
import app.songyan.ime.storage.SourceIds
import app.songyan.ime.storage.SourceKind
import app.songyan.ime.storage.SourceManifest
import app.songyan.ime.storage.SourceScheme
import app.songyan.ime.userconfig.CompiledConfigGuard
import app.songyan.ime.userconfig.UserConfigException
import app.songyan.ime.userconfig.UserConfigFiles
import app.songyan.ime.userconfig.UserConfigProblem
import app.songyan.ime.userconfig.UserConfigStatus
import app.songyan.ime.userconfig.UserScripts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Owns the deploy side of the publication protocol: keep the stored sources under
 * `rime/sources/`, deploy one with a fresh engine, publish an immutable generation and switch
 * active.json. Only the :deploy process runs this; every operation holds the deploy lock.
 *
 * Sources sit side by side: importing a dictionary or a scheme package adds (or replaces) only
 * that source, and any stored source can be made active again or deleted.
 */
class DeploymentCoordinator(
    private val storage: AppStorage,
    private val assets: AssetTree,
    private val runner: RimeDeployRunner,
    private val appName: String,
    private val stamp: BuildStamp,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val installer = BuiltinSourceInstaller(assets)
    private val publisher = GenerationPublisher(storage)
    private val catalog = SourceCatalog(storage)
    private val builder = SourceBuilder(assets)
    private val overlay = DeployOverlay(storage)
    private val featureStore = EngineFeatureStore(storage)
    private val configStatus = UserConfigStatus.Store(storage)

    /**
     * Set while the user's configuration changes are being applied: a deployment that fails with
     * them must fail, so the keyboard keeps its generation, instead of going ahead without them.
     */
    private var strictConfig = false

    /** The applied configuration's files (name, size, time) and whether they passed [UserConfigFiles.verify]. */
    private var verifiedConfig: Pair<String, Boolean>? = null

    /** The built-in source's install stamp: an app or data update reinstalls it. */
    private val builtinRevision: String = "builtin-" + stamp.data + "-" + stamp.app

    fun activeGeneration(): PublicationRecord? = publisher.readActive()

    /** A complete active generation built by this app, engine and data version. */
    fun hasUsableGeneration(): Boolean = publisher.usablePublication()?.let { it.stamp == stamp } == true

    /**
     * Makes sure some current generation is active: reuses it when it is, rebuilds the active
     * source when it is stale or incomplete. Fresh installs wait for a user-chosen scheme.
     */
    suspend fun ensureDeployed(): Result<PublicationRecord> =
        locked {
            val usable = publisher.usablePublication()
            if (usable != null && usable.stamp == stamp) return@locked usable
            redeployActiveLocked()
        }

    /** Publishes the bundled package; reuses a current built-in generation unless [force]. */
    suspend fun deployBuiltin(force: Boolean = false): Result<PublicationRecord> =
        locked {
            if (!force) reusable(SourceIds.BUILTIN, effectiveRevision(builtinManifest()))?.let { return@locked it }
            deployBuiltinLocked()
        }

    /** Rebuilds whatever source is active (the built-in package when nothing is). */
    suspend fun redeployActive(): Result<PublicationRecord> = locked { redeployActiveLocked() }

    /** Whether the active generation still lacks the layouts of input methods other than the one in use. */
    fun companionsPending(): Boolean = publisher.usablePublication()?.companionsPending == true

    /**
     * Rebuilds the active scheme package with the layouts of every input method, after a deployment
     * published only the one in use. Nothing to do once another source or a complete generation is active.
     */
    suspend fun completeCompanions(): Result<PublicationRecord?> =
        locked {
            val active = publisher.usablePublication()
            if (active == null || !active.companionsPending) return@locked active
            val manifest = catalog.read(active.sourceId)
            if (manifest?.kind != SourceKind.SCHEME) return@locked active
            deployTree(storage.sourceDir(active.sourceId), manifest, allCompanions = true, resume = active)
        }

    /**
     * Installs a staged source tree as source [BuiltSource.manifest]'s id and makes it active. The
     * tree is validated, the previous tree of the same id is kept aside until the new generation
     * published, and any failure leaves active.json and the previous tree untouched.
     *
     * Learning data lives outside every source tree (see LearningData), so nothing here can lose it.
     */
    suspend fun installSource(staged: BuiltSource): Result<PublicationRecord> = locked { installLocked(staged) }

    /** Makes a stored source active, reusing its latest current generation when there is one. */
    suspend fun activateSource(sourceId: String): Result<PublicationRecord> =
        locked {
            requireIdentifier("source", sourceId, SOURCE_ID_PATTERN)
            if (sourceId == SourceIds.BUILTIN) {
                return@locked reusable(SourceIds.BUILTIN, effectiveRevision(builtinManifest())) ?: deployBuiltinLocked()
            }
            val manifest = catalog.read(sourceId) ?: throw DeployException("no stored source $sourceId")
            val revision = effectiveRevision(manifest)
            val active = publisher.usablePublication()
            if (active != null && active.sourceId == sourceId && active.stamp == stamp && active.revision == revision) {
                return@locked active
            }
            reusable(sourceId, revision) ?: redeploySourceLocked(sourceId)
        }

    /**
     * Stacks the dictionary staged in [staged] (written by [SourceBuilder.buildStack]) onto the
     * stored scheme package [targetSourceId] and publishes the package with it. A dictionary
     * stacked earlier is replaced, and put back when the new one cannot be deployed.
     */
    suspend fun stackDictionary(
        targetSourceId: String,
        staged: File,
    ): Result<PublicationRecord> =
        locked {
            requireIdentifier("source", targetSourceId, SOURCE_ID_PATTERN)
            val manifest = catalog.read(targetSourceId)
            if (manifest == null || manifest.kind != SourceKind.SCHEME) {
                throw DeployException("$targetSourceId is not a stored scheme package")
            }
            val target = storage.stackedDictionaryDir(targetSourceId)
            // Always set something aside, even an empty directory: its presence marks the operation
            // as unfinished until the new generation is published.
            val previous = File(storage.stackedDictionariesDir, ".previous-" + targetSourceId + "-" + System.nanoTime())
            if (target.exists()) moveDirectory(target, previous) else previous.mkdirs()
            try {
                moveDirectory(staged, target)
                val record = redeploySourceLocked(targetSourceId)
                previous.deleteRecursively()
                record
            } catch (e: Throwable) {
                target.deleteRecursively()
                if (File(previous, DeployOverlay.STACKED_FILE).isFile) moveDirectory(previous, target)
                previous.deleteRecursively()
                throw e
            }
        }

    /**
     * Removes the dictionary stacked onto [targetSourceId]. When that package is in use it is
     * redeployed without the dictionary first; a failed redeploy keeps the dictionary, so the
     * keyboard and the stored state never disagree.
     */
    suspend fun removeStackedDictionary(targetSourceId: String): Result<PublicationRecord?> =
        locked {
            requireIdentifier("source", targetSourceId, SOURCE_ID_PATTERN)
            val target = storage.stackedDictionaryDir(targetSourceId)
            if (!target.exists()) return@locked publisher.readActive()
            val removed = File(storage.stackedDictionariesDir, ".removed-" + targetSourceId + "-" + System.nanoTime())
            moveDirectory(target, removed)
            try {
                val active = publisher.readActive()
                val record =
                    if (active?.sourceId == targetSourceId && catalog.read(targetSourceId) != null) {
                        redeploySourceLocked(targetSourceId)
                    } else {
                        active
                    }
                removed.deleteRecursively()
                record
            } catch (e: Throwable) {
                moveDirectory(removed, target)
                throw e
            }
        }

    /**
     * Deletes a stored source. Only when it is the active one does the keyboard move to the
     * built-in package first, so active.json never names a deleted tree. A usable source goes to
     * the trash (see [AppStorage.trashDir]) and can come back through [restoreSource]; a damaged
     * one is removed for good. Returns the active record afterwards.
     */
    suspend fun deleteSource(sourceId: String): Result<PublicationRecord?> =
        locked {
            requireIdentifier("source", sourceId, SOURCE_ID_PATTERN)
            if (sourceId == SourceIds.BUILTIN) throw DeployException("the built-in source cannot be deleted")
            var active = publisher.readActive()
            if (active?.sourceId == sourceId) {
                active = reusable(SourceIds.BUILTIN, effectiveRevision(builtinManifest())) ?: deployBuiltinLocked()
            }
            val sourceDir = storage.sourceDir(sourceId)
            val keep = catalog.read(sourceId)?.let(catalog::isIntact) == true
            // One undoable deletion at a time: an older trashed source is gone for good now.
            storage.trashDir.deleteRecursively()
            if (keep && sourceDir.isDirectory) {
                val trashed = storage.trashedSourceDir(sourceId)
                moveDirectory(sourceDir, File(trashed, TRASH_TREE))
                val stacked = storage.stackedDictionaryDir(sourceId)
                if (stacked.isDirectory) moveDirectory(stacked, File(trashed, TRASH_STACKED))
            } else {
                if (sourceDir.exists() && !sourceDir.deleteRecursively()) {
                    throw DeployException("cannot delete source directory: $sourceDir")
                }
                // A stacked dictionary belongs to its package; without the package nothing can use it.
                storage.stackedDictionaryDir(sourceId).deleteRecursively()
            }
            active
        }

    /**
     * Puts a source deleted by [deleteSource] back as a stored source; it is not made active. Its
     * learning data never left, so the words it learned are there again. Fails when the trash no
     * longer holds it or a source with the same id was installed meanwhile.
     */
    suspend fun restoreSource(sourceId: String): Result<PublicationRecord?> =
        locked {
            requireIdentifier("source", sourceId, SOURCE_ID_PATTERN)
            val trashed = storage.trashedSourceDir(sourceId)
            val tree = File(trashed, TRASH_TREE)
            if (!tree.isDirectory) throw DeployException("nothing to restore for $sourceId")
            if (storage.sourceDir(sourceId).exists()) throw DeployException("$sourceId was installed again")
            moveDirectory(tree, storage.sourceDir(sourceId))
            val stacked = File(trashed, TRASH_STACKED)
            if (stacked.isDirectory) moveDirectory(stacked, storage.stackedDictionaryDir(sourceId))
            trashed.deleteRecursively()
            publisher.readActive()
        }

    /**
     * Installs a new version of the stored package [BuiltSource.manifest] and keeps the keyboard on
     * whatever it used before, unless that was the package itself (then it moves to the new version,
     * as [installSource] does). Stacked dictionaries are rebuilt with the package.
     */
    suspend fun updateSource(staged: BuiltSource): Result<PublicationRecord> =
        locked {
            val before = publisher.readActive()
            val record = installLocked(staged)
            if (before == null || before.sourceId == staged.manifest.sourceId) return@locked record
            // The update must not switch the keyboard to another package; the previous source's
            // generation is still stored, so this is a reactivation, not a deploy.
            val manifest = if (before.sourceId == SourceIds.BUILTIN) null else catalog.read(before.sourceId)
            try {
                when {
                    before.sourceId == SourceIds.BUILTIN ->
                        reusable(SourceIds.BUILTIN, effectiveRevision(builtinManifest())) ?: deployBuiltinLocked()
                    manifest != null ->
                        reusable(before.sourceId, before.revision) ?: redeploySourceLocked(before.sourceId)
                    else -> record
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "cannot put the keyboard back on " + before.sourceId + " after an update", e)
                record
            }
        }

    /**
     * Applies the user's configuration changes: checks the files the user edited, makes a copy of
     * them the configuration every deployment uses, and rebuilds the source in use with it. When a
     * file cannot be used, or the source does not deploy with them, nothing changes: the keyboard
     * keeps its generation and the earlier configuration stays the applied one.
     */
    suspend fun applyUserConfig(): Result<PublicationRecord> =
        locked {
            val root = storage.configRoot
            val staging = File(root, CONFIG_STAGING_PREFIX + System.nanoTime())
            val previous = File(root, CONFIG_PREVIOUS_PREFIX + System.nanoTime())
            try {
                UserConfigFiles.snapshot(storage.userConfigDir, staging)
            } catch (e: UserConfigException) {
                staging.deleteRecursively()
                configStatus.write(UserConfigStatus.failed(e.file, e.problem.name))
                throw e
            }
            // The settings screen asked for the user's yes to each script before this request; the files
            // may have changed since, so what is about to be applied is checked again.
            val unconfirmed =
                UserScripts(storage).unconfirmed(staging, publisher.readActive()?.sourceId?.let(storage::sourceDir))
            if (unconfirmed.isNotEmpty()) {
                staging.deleteRecursively()
                val refusal = UserConfigException(unconfirmed.first().id, UserConfigProblem.UNCONFIRMED_SCRIPT)
                configStatus.write(UserConfigStatus.failed(refusal.file, refusal.problem.name))
                throw refusal
            }
            val applied = storage.appliedConfigDir
            // Its presence marks the application as unfinished until the new generation is published.
            if (applied.exists()) moveDirectory(applied, previous) else previous.mkdirs()
            moveDirectory(staging, applied)
            strictConfig = true
            try {
                val active = publisher.readActive()?.sourceId
                val record =
                    if (active == null || active == SourceIds.BUILTIN || catalog.read(active) == null) {
                        deployBuiltinLocked()
                    } else {
                        redeploySourceLocked(active)
                    }
                previous.deleteRecursively()
                configStatus.write(UserConfigStatus.applied(UserConfigFiles.digest(applied)))
                record
            } catch (e: Throwable) {
                applied.deleteRecursively()
                moveDirectory(previous, applied)
                if (e is UserConfigException) {
                    configStatus.write(UserConfigStatus.failed(e.file, e.problem.name))
                } else if (e !is CancellationException) {
                    configStatus.write(UserConfigStatus.failed(null, DEPLOY_PROBLEM))
                }
                throw e
            } finally {
                strictConfig = false
                staging.deleteRecursively()
            }
        }

    private suspend fun <T> locked(block: suspend () -> T): Result<T> =
        withContext(ioDispatcher) {
            runCatching {
                withDeployLock {
                    recoverAppliedConfig()
                    recoverStackedDictionaries()
                    purgeOldTrash()
                    purgeStaleWorkDirs()
                    block()
                }
            }
        }

    /** The published revision of [manifest]: its own plus the identity of its deploy overlay. */
    private fun effectiveRevision(
        manifest: SourceManifest,
        features: EngineFeatureSettings = featureStore.read(),
        config: File? = appliedConfig(),
    ): String = manifest.revision + "+" + overlay.digest(manifest, features, config)

    /** The applied user configuration every deployment layers over its source, or null when there is none. */
    private fun appliedConfig(): File? {
        val dir = storage.appliedConfigDir.takeIf { UserConfigFiles.hasFiles(it) } ?: return null
        // What applying just checked needs no second look; what a restored backup or an older version left does.
        if (strictConfig) return dir
        val signature =
            UserConfigFiles.files(dir).joinToString("|") {
                UserConfigFiles.relativePath(dir, it) + ":" + it.length() + ":" + it.lastModified()
            }
        val known = verifiedConfig
        if (known?.first == signature) return dir.takeIf { known.second }
        val problem = UserConfigFiles.verify(dir)
        if (problem != null) {
            Log.w(TAG, "the applied configuration no longer passes its checks; deploying without it", problem)
            configStatus.write(UserConfigStatus.failed(problem.file, problem.problem.name))
        }
        verifiedConfig = signature to (problem == null)
        return dir.takeIf { problem == null }
    }

    private fun builtinManifest() =
        SourceManifest(
            sourceId = SourceIds.BUILTIN,
            kind = SourceKind.BUILTIN,
            displayName = "",
            schemes = BuiltinSchemes.IDS.map { SourceScheme(it, it) },
            revision = builtinRevision,
        )

    /** The newest complete generation of [sourceId] at [revision] built by this stamp, made active. */
    private fun reusable(
        sourceId: String,
        revision: String,
    ): PublicationRecord? {
        val active = publisher.usablePublication()
        if (active != null && active.sourceId == sourceId && active.revision == revision && active.stamp == stamp) {
            return active
        }
        // A complete generation beats a newer one still waiting for its layouts.
        val stored =
            publisher
                .publishedRecords()
                .filter { it.sourceId == sourceId && it.revision == revision && it.stamp == stamp }
                .minByOrNull { it.companionsPending } ?: return null
        return publisher.reactivate(stored.generation)
    }

    private suspend fun redeployActiveLocked(): PublicationRecord {
        if (InputSchemeSetup.needsDownload(storage)) {
            throw DeployException("download an input scheme before starting the keyboard")
        }
        val sourceId = publisher.readActive()?.sourceId
        if (sourceId == null || sourceId == SourceIds.BUILTIN || !storage.sourceDir(sourceId).isDirectory) {
            return deployBuiltinLocked()
        }
        return try {
            redeploySourceLocked(sourceId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A source that no longer deploys (removed by an older version, corrupt) must not leave
            // the keyboard without any usable generation: fall back to the bundled package.
            Log.e(TAG, "cannot redeploy $sourceId; publishing the built-in package", e)
            deployBuiltinLocked()
        }
    }

    /**
     * Rebuilds a stored source. The dictionary source is regenerated from the originals it keeps,
     * so new built-in templates reach it; a scheme package is deployed from its stored tree.
     */
    private suspend fun redeploySourceLocked(sourceId: String): PublicationRecord {
        if (sourceId == SourceIds.BUILTIN) return deployBuiltinLocked()
        val sourceDir = storage.sourceDir(sourceId)
        val manifest = catalog.read(sourceId) ?: throw DeployException("no readable stored source $sourceId")
        if (manifest.kind == SourceKind.DICTIONARY) {
            val staging = File(storage.importStagingDir, "rebuild-" + System.currentTimeMillis())
            try {
                val rebuilt = builder.rebuildDictionary(sourceDir, staging)
                if (rebuilt != null) return installLocked(rebuilt)
            } finally {
                staging.deleteRecursively()
            }
        }
        return deployTree(sourceDir, manifest)
    }

    private suspend fun deployBuiltinLocked(): PublicationRecord {
        val sourceDir = storage.sourceDir(SourceIds.BUILTIN)
        installer.install(sourceDir, builtinRevision)
        CompiledReuse.stampConfigTimes(sourceDir)
        val manifest = builtinManifest()
        SourceCatalog.write(sourceDir, manifest)
        return deployTree(sourceDir, manifest)
    }

    private suspend fun installLocked(staged: BuiltSource): PublicationRecord {
        val manifest = staged.manifest
        requireIdentifier("source", manifest.sourceId, SOURCE_ID_PATTERN)
        requireIdentifier("revision", manifest.revision, REVISION_PATTERN)
        if (manifest.sourceId ==
            SourceIds.BUILTIN
        ) {
            throw DeployException("an import cannot replace the built-in source")
        }
        manifest.schemaIds.forEach { requireIdentifier("schema", it, SCHEMA_ID_PATTERN) }
        validateStagedSource(staged.directory)
        SourceCatalog.write(staged.directory, manifest)
        // A first install killed after the move must not be mistaken for a restored, previously used source.
        File(staged.directory, InputSchemeSetup.INSTALLING).writeText("")
        CompiledReuse.stampConfigTimes(staged.directory)
        purgeStaleSourceBackups()
        // Installing the package again supersedes its undo copy.
        storage.trashedSourceDir(manifest.sourceId).deleteRecursively()
        val target = storage.sourceDir(manifest.sourceId)
        val previous = supersedeSource(target, manifest.sourceId)
        try {
            moveDirectory(staged.directory, target)
            val record = deployTree(target, manifest)
            previous?.deleteRecursively()
            return record
        } catch (e: Throwable) {
            restoreSource(target, previous)
            throw e
        }
    }

    /**
     * Deploys [sourceDir] into a fresh work directory and publishes it.
     *
     * The work directory starts with copies of the compiled dictionaries of the newest generation
     * of the same source built by this engine ([CompiledReuse]); librime checks each against the
     * checksums of the files it was built from and compiles again whatever changed, so a rebuild
     * with unchanged dictionaries skips the slowest part of a deployment. Nothing else is carried
     * over: configs are always compiled from the current sources.
     *
     * Compact layouts take about a second each to compile, three per input method, so a scheme
     * package with many input methods would keep the user waiting most of a minute. Unless
     * [allCompanions], only the input method in use gets its layouts here; the generation is
     * marked, and its work directory kept, so the deploy process completes it right after
     * ([completeCompanions]) by continuing in the same directory ([resume]).
     */
    private suspend fun deployTree(
        sourceDir: File,
        manifest: SourceManifest,
        allCompanions: Boolean = false,
        resume: PublicationRecord? = null,
    ): PublicationRecord {
        val config = appliedConfig()
        if (config == null || strictConfig) {
            return deployOnce(sourceDir, manifest, allCompanions, resume, config)
        }
        return try {
            deployOnce(sourceDir, manifest, allCompanions, resume, config).also {
                configStatus.deployedWith(manifest.sourceId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            // The configuration was applied with another source, or before an app update changed
            // what it patches. Rime's own behaviour beats no keyboard: deploy without it, and tell.
            Log.w(TAG, "cannot deploy " + manifest.sourceId + " with the user's configuration; deploying without it", e)
            deployOnce(sourceDir, manifest, allCompanions, resume = null, config = null).also {
                configStatus.write(UserConfigStatus.skipped(manifest.sourceId))
            }
        }
    }

    /** [deployTree] with the user's configuration [config], or without it when null. */
    private suspend fun deployOnce(
        sourceDir: File,
        manifest: SourceManifest,
        allCompanions: Boolean,
        resume: PublicationRecord?,
        config: File?,
    ): PublicationRecord {
        val workDir = storage.deployWorkDir(manifest.sourceId)
        val timer = DeployTimer("deploy " + manifest.sourceId + if (allCompanions) " (all layouts)" else "")
        var keepWorkDir = false
        try {
            val done = resume?.takeIf { it.stamp == stamp }?.let { PendingWork.read(workDir, it.generation) }
            PendingWork.clear(workDir)
            // Feature settings changed since the first part: the kept directory no longer matches.
            val kept = done?.let { linkFeatureFiles(workDir) }
            val resumed = kept != null && effectiveRevision(manifest, kept.settings, config) == resume?.revision
            val features =
                if (resumed && kept != null) {
                    kept
                } else {
                    workDir.deleteRecursively()
                    timer.phase("overlay") { overlay.write(sourceDir, manifest, workDir, config) }
                    timer.phase("seed") { seedCompiled(sourceDir, workDir, manifest.sourceId) }
                    linkFeatureFiles(workDir)
                }
            // The user's files are published with the build: librime reads some of them at run time
            // (custom_phrase.txt), and the generation shows what it was built from.
            val configured =
                config
                    ?.let { dir -> UserConfigFiles.files(dir).map { UserConfigFiles.relativePath(dir, it) } }
                    .orEmpty()
                    .associateWith { File(workDir, it) }
                    .filterValues { it.isFile }
            val revision = effectiveRevision(manifest, features.settings, config)
            requireIdentifier("revision", revision, REVISION_PATTERN)
            val companionSchemas =
                if (allCompanions || manifest.kind != SourceKind.SCHEME) {
                    manifest.schemaIds
                } else {
                    listOf(schemaInUse(manifest))
                }
            val companionsPending = companionSchemas.toSet() != manifest.schemaIds.toSet()
            val engineStart = SystemClock.elapsedRealtime()
            var afterDeployStart = 0L
            val outcome =
                runner.deployFull(
                    sharedDataDir = sourceDir,
                    userDataDir = workDir,
                    appName = appName,
                    appVersion = stamp.app + "+" + revision,
                    afterDeploy = { engine ->
                        afterDeployStart = SystemClock.elapsedRealtime()
                        timer.add("engine", afterDeployStart - engineStart)
                        afterDeploy(
                            engine,
                            DeployScope(sourceDir, workDir, manifest, features, companionSchemas, timer),
                            reuseCompanions = if (resumed) done.orEmpty() else null,
                        )
                    },
                )
            if (afterDeployStart == 0L) timer.add("engine", SystemClock.elapsedRealtime() - engineStart)
            if (!outcome.success) {
                throw DeployException("deployment failed: " + (outcome.lastError ?: "unknown error"))
            }
            val compiledDir = outcome.compiledDir
            if (!compiledDir.isDirectory) {
                throw DeployException("deployment produced no build directory: $compiledDir")
            }
            // Whatever the user's patches spelled, no compiled file may name a file outside the data folders.
            if (config != null) {
                val scripts = UserScripts(storage)
                CompiledConfigGuard.check(
                    compiledDir,
                    scripts.shippedComponents(sourceDir),
                    scripts.confirmedComponents(),
                )
            }
            // The layouts still to come may use seeded prisms; the deployment completing them drops the rest.
            if (!companionsPending) CompiledReuse.dropUnusedPrisms(compiledDir, seededFiles(workDir))
            val deployed = outcome.deployedSchemaIds
            // Only input methods the engine actually compiled are offered; the manifest keeps the
            // order, unless the user's configuration changed the list (`default.custom.yaml`).
            val schemaIds =
                if (config != null && UserConfigFiles.changesSchemaList(config) && deployed.isNotEmpty()) {
                    deployed.filter {
                        SCHEMA_ID_PATTERN.matches(
                            it,
                        ) &&
                            !CompactSchemaAdapter.isGeneratedCompanionID(it)
                    }
                } else {
                    manifest.schemaIds.filter { deployed.isEmpty() || it in deployed }
                }
            if (schemaIds.isEmpty()) throw DeployException("none of the source's input methods deployed")
            val record =
                timer.phase("publish") {
                    publisher.publish(
                        resourcesDir = sourceDir,
                        compiledDir = compiledDir,
                        sourceId = manifest.sourceId,
                        schemaId = schemaIds.first(),
                        revision = revision,
                        schemaIds = schemaIds,
                        stamp = stamp,
                        extraResources = features.files.associateBy { it.name } + configured,
                        companionsPending = companionsPending,
                    )
                }
            File(sourceDir, InputSchemeSetup.INSTALLING).delete()
            Log.i(TAG, "published generation " + record.generation + " of " + record.sourceId)
            if (companionsPending) {
                PendingWork.write(workDir, record.generation, companionSchemas)
                keepWorkDir = true
            }
            return record
        } finally {
            // The published generation holds its own copy of the build; only a generation still
            // waiting for its other layouts keeps the directory, for the deployment completing it.
            if (!keepWorkDir) timer.phase("cleanup") { workDir.deleteRecursively() }
            timer.log()
        }
    }

    /**
     * Copies the reusable compiled files of the newest complete generation of [sourceId] that this
     * engine built into [workDir]'s build directory, and lists them in a marker for
     * [seededFiles]. The source's own tree and the overlay are where its dictionaries live.
     */
    private fun seedCompiled(
        sourceDir: File,
        workDir: File,
        sourceId: String,
    ) {
        val previous =
            publisher
                .publishedRecords()
                .firstOrNull { it.sourceId == sourceId && it.stamp?.engine == stamp.engine } ?: return
        val build = File(workDir, BUILD_DIRECTORY)
        val copied =
            CompiledReuse.seed(
                previousBuild = File(storage.generationDir(previous.generation), BUILD_DIRECTORY),
                sourceDirs = listOf(sourceDir, workDir),
                targetBuild = build,
            )
        if (copied.isNotEmpty()) File(workDir, SEEDED_LIST).writeText(copied.joinToString("\n"))
    }

    private fun seededFiles(workDir: File): List<String> =
        runCatching { File(workDir, SEEDED_LIST).readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())

    /** What [afterDeploy] works on: one deployment of one source. */
    private class DeployScope(
        val sourceDir: File,
        val workDir: File,
        val manifest: SourceManifest,
        val features: LinkedFeatures,
        val companionSchemas: List<String>,
        val timer: DeployTimer,
    )

    /** The feature settings of one deployment and the feature files linked into its work directory. */
    private class LinkedFeatures(
        val settings: EngineFeatureSettings,
        val files: List<File>,
        val predictionDatabase: Boolean,
        val grammarModels: Set<String>,
    )

    /**
     * Reads the feature settings and links the files they name into [workDir], under the feature
     * lock, so the settings screen cannot replace or delete them halfway. The links keep the files
     * this deployment publishes even when they are replaced right after.
     */
    private fun linkFeatureFiles(workDir: File): LinkedFeatures =
        featureStore.lock {
            val settings = featureStore.read()
            val files = ArrayList<File>()
            val database =
                featureStore.predictionDatabase(settings)?.let { stored ->
                    File(workDir, EngineFeatureSettings.PREDICTION_DATABASE).also { linkFile(stored, it) }
                }
            database?.let { files += it }
            val models = LinkedHashSet<String>()
            for (name in settings.grammar.values
                .mapNotNull { it.model }
                .distinct()) {
                val stored = featureStore.grammarModel(settings, name) ?: continue
                files += File(workDir, name).also { linkFile(stored, it) }
                models += name
            }
            LinkedFeatures(settings, files, database != null, models)
        }

    private fun linkFile(
        existing: File,
        target: File,
    ) {
        target.parentFile?.mkdirs()
        // A kept work directory still holds the links of its first part.
        target.delete()
        try {
            Files.createLink(target.toPath(), existing.toPath())
        } catch (_: IOException) {
            existing.copyTo(target, overwrite = true)
        } catch (_: UnsupportedOperationException) {
            existing.copyTo(target, overwrite = true)
        }
    }

    /**
     * Runs on the deployment's engine: the feature patches first, so the companions are generated
     * from the patched input methods. A scheme package gets companions for the scope's companion
     * schemas; the bundled input methods ship theirs, which are generated again only when a patch
     * changed the input method they are made from.
     *
     * A deployment continuing a kept work directory ([reuseCompanions] not null) already has the
     * patched input methods, their feature report and the companions of [reuseCompanions]: it only
     * adds the missing companions.
     */
    private suspend fun afterDeploy(
        engine: DeployedEngine,
        scope: DeployScope,
        reuseCompanions: List<String>?,
    ) {
        val manifest = scope.manifest
        val changed =
            if (reuseCompanions != null) {
                emptySet()
            } else {
                scope.timer.phase("features") {
                    EngineFeatures(
                        settings = scope.features.settings,
                        sourceDir = scope.sourceDir,
                        workDir = scope.workDir,
                        schemaIds = manifest.schemaIds,
                        predictionDatabase = scope.features.predictionDatabase,
                        grammarModels = scope.features.grammarModels,
                    ).apply(engine)
                }
            }
        if (manifest.kind == SourceKind.SCHEME) {
            KeyboardCompanions(
                scope.sourceDir,
                scope.workDir,
                scope.companionSchemas,
                packageSchemaIds = manifest.schemaIds,
                timer = scope.timer,
                reuse = reuseCompanions.orEmpty().toSet(),
            ).generate(engine)
            return
        }
        val bundled = changed.filter { it in BuiltinTemplateSchemas.SOURCE_SCHEMA_IDS }
        if (bundled.isNotEmpty()) {
            KeyboardCompanions(scope.sourceDir, scope.workDir, bundled, replaceBundled = true, timer = scope.timer)
                .generate(engine)
        }
    }

    /**
     * The input method the keyboard will use with [manifest]: the one in use when the package has
     * it, otherwise the package's first, which the keyboard switches to for a new package.
     */
    private fun schemaInUse(manifest: SourceManifest): String {
        val inUse = runCatching { storage.keyboardSchemaFile.readText().trim() }.getOrNull()
        return inUse?.takeIf { it in manifest.schemaIds } ?: manifest.schemaIds.first()
    }

    private suspend fun <T> withDeployLock(block: suspend () -> T): T {
        storage.ensureBaseDirectories()
        FileChannel
            .open(
                storage.deployLockFile.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            ).use { channel ->
                channel.lock().use { return block() }
            }
    }

    /**
     * A staged tree must be this app's own staging output: inside the staging root, a real
     * directory with default.yaml, without symbolic links and bounded in size. Everything else is
     * refused before the live source tree is touched.
     */
    private fun validateStagedSource(stagingDir: File) {
        val root = storage.importStagingDir
        val rootPath = root.canonicalFile.toPath()
        val stagingPath = stagingDir.canonicalFile.toPath()
        if (!stagingPath.startsWith(rootPath) || stagingPath == rootPath) {
            throw DeployException("staged source is outside the import staging root: $stagingDir")
        }
        if (!stagingDir.isDirectory) throw DeployException("staged source is not a directory: $stagingDir")
        if (!File(stagingDir, "default.yaml").isFile) throw DeployException("staged source is missing default.yaml")
        var files = 0
        var bytes = 0L
        val pending = ArrayDeque<File>()
        pending.add(stagingDir)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            for (child in current.listFiles().orEmpty()) {
                if (Files.isSymbolicLink(child.toPath())) {
                    throw DeployException("staged source contains a symbolic link: $child")
                }
                if (child.isDirectory) {
                    pending.add(child)
                    continue
                }
                files++
                bytes += child.length()
                if (files > MAX_STAGED_FILES || bytes > MAX_STAGED_BYTES) {
                    throw DeployException("staged source exceeds its size limit")
                }
            }
        }
    }

    /** Moves the live source tree aside (hidden backup) and returns it, or null on first install. */
    private fun supersedeSource(
        target: File,
        sourceId: String,
    ): File? {
        if (!target.exists()) return null
        val previous = storage.previousSourceDir(sourceId, System.currentTimeMillis().toString())
        moveDirectory(target, previous)
        return previous
    }

    /** Puts the previous source tree back after a failed deploy; active.json never changed. */
    private fun restoreSource(
        target: File,
        previous: File?,
    ) {
        target.deleteRecursively()
        if (previous == null || !previous.isDirectory) return
        try {
            moveDirectory(previous, target)
        } catch (e: IOException) {
            Log.e(TAG, "cannot restore the previous source tree", e)
        }
    }

    /**
     * An application of the configuration that died before its generation was published leaves the
     * earlier configuration set aside; it is the one the running generation was built with, so it
     * comes back. Half-made copies are dropped.
     */
    private fun recoverAppliedConfig() {
        val entries = storage.configRoot.listFiles().orEmpty()
        for (entry in entries.filter { it.name.startsWith(CONFIG_STAGING_PREFIX) }) entry.deleteRecursively()
        val previous = entries.filter { it.name.startsWith(CONFIG_PREVIOUS_PREFIX) }.maxByOrNull { it.name } ?: return
        storage.appliedConfigDir.deleteRecursively()
        moveDirectory(previous, storage.appliedConfigDir)
        for (entry in entries.filter { it.name.startsWith(CONFIG_PREVIOUS_PREFIX) && it != previous }) {
            entry.deleteRecursively()
        }
    }

    /** A deploy that died mid-way may leave a backup nobody references; drop it under the lock. */
    private fun purgeStaleSourceBackups() {
        for (entry in storage.sourcesDir.listFiles().orEmpty()) {
            if (entry.name.startsWith(SOURCE_BACKUP_PREFIX)) entry.deleteRecursively()
        }
    }

    /**
     * A stack operation killed mid-way leaves its set-aside copy (`.previous-` while stacking,
     * `.removed-` while removing). The operation did not finish, so the state before it comes back:
     * the stack the published generation was built with stays the one settings shows.
     */
    private fun recoverStackedDictionaries() {
        val pattern = Regex("^\\.(previous|removed)-([a-z0-9][a-z0-9-]{0,39})-[0-9]+$")
        for (entry in storage.stackedDictionariesDir.listFiles().orEmpty()) {
            if (!entry.name.startsWith(".")) continue
            val sourceId = pattern.find(entry.name)?.groupValues?.get(2)
            if (sourceId == null) {
                entry.deleteRecursively()
                continue
            }
            val target = storage.stackedDictionaryDir(sourceId)
            target.deleteRecursively()
            if (File(entry, DeployOverlay.STACKED_FILE).isFile) {
                runCatching { moveDirectory(entry, target) }
            } else {
                entry.deleteRecursively()
            }
        }
    }

    /**
     * Work directories are only kept for the deployment completing the active generation; one left
     * by a deployment that died, or by a source that was replaced or deleted since, is removed.
     */
    private fun purgeStaleWorkDirs() {
        val active = publisher.readActive()?.takeIf { it.companionsPending }
        for (dir in storage.deployRoot.listFiles().orEmpty()) {
            val keep = active != null && dir.name == active.sourceId && PendingWork.read(dir, active.generation) != null
            if (!keep) dir.deleteRecursively()
        }
    }

    private fun purgeOldTrash() {
        val dir = storage.trashDir
        if (dir.isDirectory && System.currentTimeMillis() - dir.lastModified() > TRASH_KEEP_MS) {
            dir.deleteRecursively()
        }
    }

    private fun moveDirectory(
        from: File,
        to: File,
    ) {
        to.parentFile?.mkdirs()
        to.deleteRecursively()
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return
        } catch (_: IOException) {
            // A cross-volume or otherwise refused rename falls back to copy + delete.
        }
        from.copyRecursively(to, overwrite = true)
        from.deleteRecursively()
    }

    private fun requireIdentifier(
        what: String,
        value: String,
        pattern: Regex,
    ) {
        if (!pattern.matches(value)) throw DeployException("invalid $what identifier: $value")
    }

    companion object {
        private const val TAG = "DeploymentCoordinator"
        const val SOURCE_BACKUP_PREFIX = ".previous-"
        private const val BUILD_DIRECTORY = "build"

        private const val CONFIG_STAGING_PREFIX = ".staging-"
        private const val CONFIG_PREVIOUS_PREFIX = ".previous-"

        /** The failure recorded when the configuration was readable but the source did not deploy with it. */
        const val DEPLOY_PROBLEM = "DEPLOY"

        /** In a work directory: the compiled files copied from an earlier generation. */
        private const val SEEDED_LIST = ".songyan-seeded"

        /** In a trashed source's directory: its source tree. */
        const val TRASH_TREE = SourceCatalog.TRASHED_TREE
        private const val TRASH_STACKED = "stacked"

        /** How long a deleted source stays restorable before its files are given back. */
        private const val TRASH_KEEP_MS = 7L * 24 * 60 * 60 * 1000

        /** Above the scheme package caps (2000 entries, 256 MB sources, 1 GB models) plus supplements. */
        private const val MAX_STAGED_FILES = 8192
        private const val MAX_STAGED_BYTES = 1536L * 1024 * 1024
        private val SOURCE_ID_PATTERN = Regex("^[a-z0-9][a-z0-9-]{0,39}$")
        private val SCHEMA_ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,79}$")
        private val REVISION_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9_./+-]{0,159}$")

        fun create(
            context: Context,
            runner: RimeDeployRunner,
        ): DeploymentCoordinator {
            val storage = AppStorage(context.filesDir)
            storage.ensureBaseDirectories()
            return DeploymentCoordinator(
                storage = storage,
                assets = AndroidAssetTree(context.assets, "rime"),
                runner = runner,
                appName = RimeBindings.APP_NAME,
                stamp = BuildStamps.current(context),
            )
        }
    }
}

class DeployException(
    message: String,
) : IOException(message)
