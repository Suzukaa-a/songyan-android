package app.songyan.ime.storage

import java.io.File

/**
 * Every mutable path the app owns. All of it lives in credential-encrypted
 * storage (v1 does not declare directBootAware), so a locked device never reads
 * these directories.
 */
class AppStorage(
    filesDir: File,
) {
    val rimeRoot: File = File(filesDir, "rime")
    val sourcesDir: File = File(rimeRoot, "sources")
    val versionsDir: File = File(rimeRoot, "versions")
    val userRoot: File = File(rimeRoot, "user")
    val deployRoot: File = File(rimeRoot, "deploy")

    /**
     * Dictionaries the user stacked onto an imported scheme package, one directory per target
     * source. Kept outside the source trees so a package update or re-import never loses them.
     */
    val stackedDictionariesDir: File = File(rimeRoot, "stacked-dictionaries")

    /**
     * Engine feature choices (`settings.json`, written by the settings screen, read by the deploy
     * process) and the files they bring: the prediction database and grammar models.
     */
    val featuresDir: File = File(rimeRoot, "features")
    val featureSettingsFile: File = File(featuresDir, "settings.json")
    val grammarModelsDir: File = File(featuresDir, "grammar")

    /**
     * Imported sources that may still be being written. Lives next to (never inside)
     * [sourcesDir]: a half-written tree must not sit where a deploy could pick it up.
     */
    val importStagingDir: File = File(rimeRoot, "import-staging")

    /**
     * The most recently deleted source, kept whole (tree and stacked dictionary) so the user can
     * undo the deletion. At most one entry exists; the next deletion replaces it. Learning data is
     * never in here.
     */
    val trashDir: File = File(rimeRoot, "trash")

    /** One terminal-state record per deploy request, read by whoever is waiting for it. */
    val requestsDir: File = File(rimeRoot, "requests")
    val activeFile: File = File(rimeRoot, "active.json")
    val activeLockFile: File = File(rimeRoot, "active.lock")
    val deployLockFile: File = File(rimeRoot, "deploy.lock")

    /**
     * The input method the keyboard uses, mirrored from the preferences for the deploy process,
     * which cannot read them; a deployment builds that input method's layouts first.
     */
    val keyboardSchemaFile: File = File(rimeRoot, "keyboard-schema")

    /**
     * The user's choice for each schema switch (simplified / traditional output, emoji candidates),
     * written by the keyboard and the settings app and read by the keyboard's engine.
     */
    val optionsDir: File = File(rimeRoot, "options")

    /** Generations the input method is loading or running; pruning never deletes them. */
    val leaseFile: File = File(rimeRoot, "ime-lease.json")

    /** The learning data saved automatically before the last learning-backup merge. */
    val learningBackupDir: File = File(rimeRoot, "learning-backup")

    /**
     * Rotating automatic copies of the learning data (a few learning backups, newest last), so the
     * userdb is never the only copy. Included in device transfer; the live userdb is in every backup.
     */
    val learningSnapshotsDir: File = File(rimeRoot, "learning-snapshots")

    /**
     * The user's own Rime configuration ([app.songyan.ime.userconfig.UserConfigFiles]): `edit/` is
     * what the Files app shows and changes, `applied/` the checked copy every deployment layers
     * over its source; only applying the changes copies one to the other.
     */
    val configRoot: File = File(rimeRoot, "config")
    val userConfigDir: File = File(configRoot, "edit")
    val appliedConfigDir: File = File(configRoot, "applied")

    /** Which edited files replace a file of the source and what the user was told about them ([app.songyan.ime.userconfig.UserOverrides]). */
    val overrideMarksFile: File = File(configRoot, "overrides.json")

    /** The scripts the user confirmed using ([app.songyan.ime.userconfig.UserScripts]); not part of any backup. */
    val scriptsConfirmedFile: File = File(configRoot, "scripts-confirmed.json")

    /** How the last application of the configuration ended, for the settings screen. */
    val configStatusFile: File = File(configRoot, "status.json")

    val layoutsDir: File = File(filesDir, "layouts")
    val appearanceDir: File = File(filesDir, "appearance")
    val contentDir: File = File(filesDir, "content")

    fun sourceDir(sourceId: String): File = File(sourcesDir, sourceId)

    /** Hidden backup of a superseded source tree; deleted once the new deploy published. */
    fun previousSourceDir(
        sourceId: String,
        stamp: String,
    ): File = File(sourcesDir, ".previous-" + sourceId + "-" + stamp)

    /**
     * Learning data (userdb) of one learning namespace, see [LearningData]. Independent of the
     * source and generation lifecycle: deleting or replacing a source never touches it.
     */
    fun learningDir(namespace: String): File = File(userRoot, namespace)

    fun stackedDictionaryDir(sourceId: String): File = File(stackedDictionariesDir, sourceId)

    /** The trashed copy of [sourceId]: its source tree is `tree/`, its stacked dictionary `stacked/`. */
    fun trashedSourceDir(sourceId: String): File = File(trashDir, sourceId)

    /** A stored engine feature file by its [app.songyan.ime.packages.EngineFeatureSettings.StoredFile.name]. */
    fun predictionDatabaseFile(name: String): File = File(featuresDir, name)

    fun grammarModelFile(name: String): File = File(grammarModelsDir, name)

    /** Private work directory for a full deployment; never read by the IME. */
    fun deployWorkDir(sourceId: String): File = File(deployRoot, sourceId)

    /** Raw files a settings request hands to the deploy process (picked documents, a ZIP). */
    fun incomingDir(requestId: String): File = File(importStagingDir, "incoming-" + requestId)

    /**
     * A file the deploy process downloaded for a request that is not an import. The waiting settings
     * code takes it and deletes it; nothing else reads it.
     */
    fun downloadFile(requestId: String): File = File(importStagingDir, "download-$requestId.bin")

    /**
     * Learned-word snapshots (`*.userdb.txt`) scheme packages shipped, one directory per package,
     * kept until the user decides whether to merge them. Outside `user/`, so never live learning data.
     */
    val packageLearningRoot: File = File(rimeRoot, "package-learning")

    fun packageLearningDir(sourceId: String): File = File(packageLearningRoot, sourceId)

    fun requestFile(requestId: String): File = File(requestsDir, "$requestId.json")

    fun generationDir(generationId: String): File = File(versionsDir, generationId)

    fun ensureBaseDirectories() {
        listOf(
            rimeRoot,
            sourcesDir,
            versionsDir,
            userRoot,
            deployRoot,
            stackedDictionariesDir,
            featuresDir,
            grammarModelsDir,
            importStagingDir,
            trashDir,
            requestsDir,
            userConfigDir,
            layoutsDir,
            appearanceDir,
            contentDir,
        ).forEach { it.mkdirs() }
    }
}
