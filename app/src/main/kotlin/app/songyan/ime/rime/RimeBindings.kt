package app.songyan.ime.rime

import app.songyan.ime.deploy.DeployOutcome
import app.songyan.ime.deploy.DeployedEngine
import app.songyan.ime.deploy.RimeDeployRunner
import app.songyan.ime.engine.EngineHandle
import app.songyan.ime.engine.PublishedEngineFactory
import app.songyan.ime.learning.LearningEngine
import app.songyan.ime.learning.LearningEngineException
import app.songyan.ime.learning.LearningEngineFailure
import app.songyan.ime.learning.LearningSnapshot
import app.songyan.ime.storage.LearningMerger
import java.io.File

/**
 * The app's only direct dependency on :core:rime's native engine entry points.
 * Everything else in the app talks to the abstractions in app.engine/app.deploy.
 */
object RimeBindings {
    /** librime app_name (must start with "rime."). */
    const val APP_NAME = "rime.songyan"

    /** The librime version compiled tables are built for; part of every generation's build stamp. */
    const val ENGINE_VERSION: String = RimeEngine.EXPECTED_RIME_VERSION

    /** Full deployment for the :deploy process. */
    fun deployRunner(): RimeDeployRunner =
        object : RimeDeployRunner {
            override suspend fun deployFull(
                sharedDataDir: File,
                userDataDir: File,
                appName: String,
                appVersion: String,
                afterDeploy: (suspend (DeployedEngine) -> Unit)?,
            ): DeployOutcome {
                val result =
                    RimeDeployment.deploy(
                        sharedDataDir = sharedDataDir,
                        userDataDir = userDataDir,
                        appName = appName,
                        appVersion = appVersion,
                        afterDeploy =
                            afterDeploy?.let { step ->
                                { engine -> step(deployedEngine(engine, userDataDir)) }
                            },
                    )
                return DeployOutcome(
                    success = result.success,
                    compiledDir = result.compiledDir,
                    engineVersion = result.engineVersion,
                    deployedSchemaIds = result.schemaIds,
                    lastError = result.lastError,
                )
            }
        }

    private fun deployedEngine(
        engine: RimeEngine,
        userDataDir: File,
    ): DeployedEngine =
        object : DeployedEngine {
            override val compiledDir = File(userDataDir, "build")

            override suspend fun sourceProjection(schemaId: String): ByteArray? =
                engine.sourceProjectionJson(schemaId)?.toByteArray(Charsets.UTF_8)

            override suspend fun schemaValue(
                schemaId: String,
                key: String,
            ): String? = engine.schemaValue(schemaId, key)

            override suspend fun deploySchema(file: File): Boolean = engine.deploySchemaFile(file)
        }

    /**
     * Merges legacy learning data with engines over one published generation (IME process, before
     * its own engine opens).
     */
    fun learningMerger(
        generationDir: File,
        appName: String,
        appVersion: String,
    ): LearningMerger =
        LearningMerger { from, into ->
            RimeLearningMerge.merge(
                sharedDataDir = File(generationDir, "resources"),
                compiledDir = File(generationDir, "build"),
                from = from,
                into = into,
                appName = appName,
                appVersion = appVersion,
            )
        }

    /** Learning backup and merge over one published generation (IME process, engine ownership held). */
    fun learningEngine(
        appName: String,
        appVersion: String,
    ): LearningEngine =
        object : LearningEngine {
            override suspend fun export(
                generationDir: File,
                userDataDir: File,
            ): LearningEngine.Export {
                val export =
                    translated {
                        RimeLearningBackup.export(
                            sharedDataDir = File(generationDir, "resources"),
                            compiledDir = File(generationDir, "build"),
                            userDataDir = userDataDir,
                            appName = appName,
                            appVersion = appVersion,
                        )
                    }
                return LearningEngine.Export(
                    export.engineVersion,
                    export.snapshots.map { LearningSnapshot(it.name, it.data) },
                )
            }

            override suspend fun merge(
                generationDir: File,
                userDataDir: File,
                snapshots: List<LearningSnapshot>,
            ) {
                translated {
                    RimeLearningBackup.merge(
                        sharedDataDir = File(generationDir, "resources"),
                        compiledDir = File(generationDir, "build"),
                        userDataDir = userDataDir,
                        snapshots = snapshots.map { RimeLearningSnapshot(it.name, it.data) },
                        appName = appName,
                        appVersion = appVersion,
                    )
                }
            }

            override suspend fun declaredDictionaries(
                generationDir: File,
                userDataDir: File,
            ): Set<String> =
                translated {
                    RimeLearningBackup.declaredDictionaries(
                        sharedDataDir = File(generationDir, "resources"),
                        compiledDir = File(generationDir, "build"),
                        userDataDir = userDataDir,
                        appName = appName,
                        appVersion = appVersion,
                    )
                }

            override suspend fun mergeSnapshotFiles(
                generationDir: File,
                userDataDir: File,
                snapshots: List<RimeSnapshotFile>,
            ) {
                translated {
                    RimeLearningBackup.mergeSnapshotFiles(
                        sharedDataDir = File(generationDir, "resources"),
                        compiledDir = File(generationDir, "build"),
                        userDataDir = userDataDir,
                        snapshots = snapshots,
                        appName = appName,
                        appVersion = appVersion,
                    )
                }
            }
        }

    private inline fun <T> translated(block: () -> T): T =
        try {
            block()
        } catch (e: RimeLearningException) {
            throw LearningEngineException(
                when (e.kind) {
                    RimeLearningException.Kind.INVALID_SNAPSHOT -> LearningEngineFailure.INVALID
                    RimeLearningException.Kind.IDENTITY_MISMATCH -> LearningEngineFailure.OTHER_SOURCE
                    RimeLearningException.Kind.UNSUPPORTED -> LearningEngineFailure.UNSUPPORTED
                    else -> LearningEngineFailure.FAILED
                },
                e,
            )
        } catch (e: RimeException) {
            throw LearningEngineException(LearningEngineFailure.FAILED, e)
        }

    /** IME-process engine factory over one immutable published generation. */
    fun engineFactory(optionsDir: File): PublishedEngineFactory =
        PublishedEngineFactory { shared, user, compiled, appName, appVersion ->
            // The learning namespace names the source (built-in and dictionary share one), as the settings app does.
            val backend =
                RimeEngineBackend.create(
                    sharedDataDir = shared,
                    userDataDir = user,
                    compiledDir = compiled,
                    appName = appName,
                    appVersion = appVersion,
                    optionPreferences = EngineOptionPreferences(optionsDir),
                    optionResourceID = user.name,
                )
            object : EngineHandle {
                override val backend = backend

                override suspend fun close() {
                    backend.shutdown()
                }
            }
        }
}
