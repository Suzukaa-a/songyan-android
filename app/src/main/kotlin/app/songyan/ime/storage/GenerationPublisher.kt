package app.songyan.ime.storage

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Publishes a deployed resource directory as an immutable generation, then
 * switches [AppStorage.activeFile] atomically. Readers either see the previous
 * generation or the new one, never a mixture.
 *
 * Every generation carries a manifest (`generation.json`) listing each file with its size and the
 * publication record it was published with. The manifest is written last and synced, so a
 * generation whose files do not all match it was interrupted and is never used.
 */
class GenerationPublisher(
    private val storage: AppStorage,
    /** The sources whose newest generation survives pruning; see [prune]. Read when pruning. */
    private val keptSources: () -> Set<String> = { emptySet() },
) {
    fun readActive(): PublicationRecord? =
        try {
            if (storage.activeFile.isFile) {
                PublicationRecord.parse(storage.activeFile.readText())
            } else {
                null
            }
        } catch (e: IOException) {
            Log.w(TAG, "unreadable active.json", e)
            null
        }

    /**
     * Copies [resourcesDir] and [compiledDir] into a fresh generation directory
     * and makes it active. Returns the new record. Callers hold the deploy lock.
     *
     * [extraResources] join the resources by relative path (`lua/foo.lua`), in place of a source
     * file of the same path; they are hard-linked when possible, since they can be large and never change in place.
     *
     * A prebuilt table in [resourcesDir]'s own `build/` that the deployment's [compiledDir] still
     * holds byte for byte is hard-linked too: the source tree is replaced whole rather than edited,
     * so the link is as immutable as the copy and saves one full table per generation.
     */
    fun publish(
        resourcesDir: File,
        compiledDir: File,
        sourceId: String,
        schemaId: String,
        revision: String,
        schemaIds: List<String> = listOf(schemaId),
        stamp: BuildStamp? = null,
        extraResources: Map<String, File> = emptyMap(),
        companionsPending: Boolean = false,
        configDigest: String? = null,
        beforeActivate: () -> Unit = {},
    ): PublicationRecord {
        require(resourcesDir.isDirectory) { "resources directory missing: $resourcesDir" }
        storage.ensureBaseDirectories()
        purgeInterruptedPublications()

        val generation = newGenerationId(sourceId)
        val temp = File(storage.versionsDir, TEMP_PREFIX + generation)
        temp.deleteRecursively()
        try {
            val files = ArrayList<Pair<String, Long>>()
            val skipPaths = extraResources.keys.map { "$RESOURCES/$it" }.toSet()
            copyTree(
                resourcesDir,
                File(temp, RESOURCES),
                RESOURCES,
                files,
                skipNames = setOf("build", InputSchemeSetup.INSTALLING),
                skipPaths = skipPaths,
            )
            for ((path, extra) in extraResources) {
                val destination = File(File(temp, RESOURCES), path)
                destination.parentFile?.mkdirs()
                linkOrCopy(extra, destination)
                files += (RESOURCES + "/" + path) to destination.length()
            }
            if (extraResources.isNotEmpty()) syncDirectory(File(temp, RESOURCES))
            val prebuilt = File(resourcesDir, BUILD)
            val shared: ((File, File, File?) -> Unit)? =
                if (prebuilt.listFiles().orEmpty().any { it.isFile && PrebuiltResourceStore.isBin(it.name) }) {
                    PrebuiltResourceStore(storage).let { store ->
                        { work: File, destination: File, input: File? ->
                            store.publish(resourcesDir, input, work, destination, generation, sourceId, revision)
                        }
                    }
                } else {
                    null
                }
            copyBuild(compiledDir, prebuilt, File(temp, BUILD), files, shared)
            val record =
                PublicationRecord(
                    version = PublicationRecord.CURRENT_VERSION,
                    generation = generation,
                    sourceId = sourceId,
                    schemaId = schemaId,
                    revision = revision,
                    createdAtEpochMs = System.currentTimeMillis(),
                    sequence = nextSequence(),
                    schemaIds = schemaIds.ifEmpty { listOf(schemaId) },
                    stamp = stamp,
                    companionsPending = companionsPending,
                    configDigest = configDigest,
                )
            writeSynced(File(temp, MANIFEST), manifestJson(record, files).toByteArray(Charsets.UTF_8))
            syncDirectory(temp)
            val finalDir = storage.generationDir(generation)
            Files.move(temp.toPath(), finalDir.toPath(), StandardCopyOption.ATOMIC_MOVE)
            syncDirectory(storage.versionsDir)
            try {
                beforeActivate()
            } catch (e: Exception) {
                finalDir.deleteRecursively()
                throw e
            }
            activate(record)
            prune(record)
            return record
        } finally {
            temp.deleteRecursively()
        }
    }

    /**
     * Makes an already published, complete generation active again (switching back to a stored
     * source without rebuilding it). Returns null when the generation is gone or incomplete.
     */
    fun reactivate(generation: String): PublicationRecord? {
        val published = publishedRecord(generation) ?: return null
        if (!isComplete(published)) return null
        val record =
            published.copy(
                sequence = nextSequence(),
                createdAtEpochMs = System.currentTimeMillis(),
            )
        activate(record)
        prune(record)
        return record
    }

    /** Every complete generation's own record, newest first. */
    fun publishedRecords(): List<PublicationRecord> =
        generationDirectories()
            .mapNotNull { publishedRecord(it.name) }
            .filter { isComplete(it) }
            .sortedByDescending { it.sequence }

    /** The record a generation was published with, from its manifest. */
    fun publishedRecord(generation: String): PublicationRecord? {
        val manifest = readManifest(storage.generationDir(generation)) ?: return null
        val record = manifest["record"] as? JsonObject ?: return null
        return PublicationRecord.parse(record.toString())
    }

    /** Writes active.json under an exclusive file lock. */
    private fun activate(record: PublicationRecord) {
        FileChannel
            .open(
                storage.activeLockFile.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            ).use { channel ->
                channel.lock().use {
                    val temp = File(storage.rimeRoot, "active.json.tmp")
                    writeSynced(temp, record.toJson().toByteArray(Charsets.UTF_8))
                    Files.move(
                        temp.toPath(),
                        storage.activeFile.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                    syncDirectory(storage.rimeRoot)
                }
            }
    }

    /**
     * Whether [record]'s generation directory holds everything the IME needs to start: every file
     * its manifest lists, at the listed size, plus the source tree's `default.yaml` and librime's
     * compiled tables.
     *
     * `active.json` alone is not proof of usability: a publish that was interrupted (storage full,
     * process death) can leave a generation directory without some of its files, and every engine
     * start would then fail on the same data until a manual force-deploy. A generation published
     * before manifests existed only gets the structural check; its record has no build stamp, so it
     * is rebuilt as soon as the keyboard starts.
     */
    fun isComplete(record: PublicationRecord): Boolean {
        val generationDir = storage.generationDir(record.generation)
        val resources = File(generationDir, RESOURCES)
        val compiled = File(generationDir, BUILD)
        if (!File(resources, "default.yaml").isFile) return false
        if (!compiled.isDirectory || compiled.listFiles().orEmpty().isEmpty()) return false
        val manifest = readManifest(generationDir) ?: return !File(generationDir, MANIFEST).exists()
        val files = manifest["files"] as? JsonArray ?: return false
        for (entry in files) {
            val item = entry as? JsonObject ?: return false
            val path = (item["path"] as? JsonPrimitive)?.contentOrNull ?: return false
            val size = (item["size"] as? JsonPrimitive)?.longOrNull ?: return false
            val file = File(generationDir, path)
            if (!file.isFile || file.length() != size) return false
        }
        return true
    }

    /** The active publication, but only when its generation is actually usable. */
    fun usablePublication(): PublicationRecord? = readActive()?.takeIf { isComplete(it) }

    /**
     * Keeps the active generation, the newest previous one (by publish sequence, not by name),
     * every generation the input method holds a lease on and, for each source in [keepSources], that
     * source's newest generation; deletes the rest.
     *
     * The per-source generation is what lets the keyboard menu switch to another installed package
     * without compiling it again. Callers pass the packages that still offer an input method there;
     * a removed package, or one whose input methods the user all hid, is not in the set.
     *
     * The previous one is there so switching back is instant. An older build of the very input
     * method in use (an option change or a package update rebuilt it) is never switched back to, and
     * its own compiled tables can be as large as the build itself, so it does not count as previous.
     */
    fun prune(
        active: PublicationRecord? = readActive(),
        keepSources: Set<String> = keptSources(),
    ) {
        if (active == null) return
        val generations = generationDirectories().sortedByDescending { sequenceOf(it) }
        val keep = mutableSetOf(active.generation)
        keep += GenerationLease(storage).leased()
        generations
            .firstOrNull { it.name != active.generation && !rebuildsTheSameInput(it.name, active) }
            ?.let { keep += it.name }
        val sourcesKept = mutableSetOf(active.sourceId)
        for (dir in generations) {
            val record = publishedRecord(dir.name) ?: continue
            val sourceId = record.sourceId
            // A build from another app version is never reused, so keeping it only costs space.
            if (record.stamp != active.stamp || sourceId in sourcesKept || sourceId !in keepSources) continue
            sourcesKept += sourceId
            keep += dir.name
        }
        for (dir in generations) {
            if (dir.name !in keep) {
                dir.deleteRecursively()
            }
        }
        PrebuiltResourceStore(storage).prune()
    }

    private fun rebuildsTheSameInput(
        generation: String,
        active: PublicationRecord,
    ): Boolean {
        val record = publishedRecord(generation) ?: return false
        return record.sourceId == active.sourceId &&
            record.schemaId == active.schemaId &&
            record.schemaIds == active.schemaIds
    }

    /** A publish that died mid-copy leaves a `.tmp-*` tree; callers hold the deploy lock. */
    private fun purgeInterruptedPublications() {
        for (entry in storage.versionsDir.listFiles().orEmpty()) {
            if (entry.name.startsWith(TEMP_PREFIX)) entry.deleteRecursively()
        }
    }

    private fun generationDirectories(): List<File> =
        storage.versionsDir
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") }

    private fun sequenceOf(dir: File): Long {
        val manifest = readManifest(dir) ?: return -1L
        val record = manifest["record"] as? JsonObject ?: return -1L
        return (record["sequence"] as? JsonPrimitive)?.longOrNull ?: -1L
    }

    private fun nextSequence(): Long {
        val published = generationDirectories().maxOfOrNull { sequenceOf(it) } ?: 0L
        val active = readActive()?.sequence ?: 0L
        return maxOf(published, active, 0L) + 1
    }

    private fun readManifest(generationDir: File): JsonObject? {
        val file = File(generationDir, MANIFEST)
        if (!file.isFile || file.length() > MAX_MANIFEST_BYTES) return null
        return try {
            Json.parseToJsonElement(file.readText()) as? JsonObject
        } catch (_: Exception) {
            null
        }
    }

    private fun manifestJson(
        record: PublicationRecord,
        files: List<Pair<String, Long>>,
    ): String =
        buildJsonObject {
            put("record", Json.parseToJsonElement(record.toJson()))
            put(
                "files",
                JsonArray(
                    files.map { (path, size) ->
                        buildJsonObject {
                            put("path", path)
                            put("size", size)
                        }
                    },
                ),
            )
        }.toString()

    private fun newGenerationId(sourceId: String): String {
        val timestamp = System.currentTimeMillis()
        val random = (0..0xFFFF).random().toString(16).padStart(4, '0')
        return "$timestamp-${sourceId.filter { it.isLetterOrDigit() || it == '-' }.take(24)}-$random"
    }

    /** Copies one tree, syncing every file and directory, and records each file's path and size. */
    private fun copyTree(
        source: File,
        target: File,
        prefix: String,
        files: MutableList<Pair<String, Long>>,
        skipNames: Set<String> = emptySet(),
        skipPaths: Set<String> = emptySet(),
    ) {
        target.mkdirs()
        for (child in source.listFiles().orEmpty().sortedBy { it.name }) {
            if (child.name in skipNames) continue
            val destination = File(target, child.name)
            val path = prefix + "/" + child.name
            if (path in skipPaths) continue
            if (child.isDirectory) {
                copyTree(child, destination, path, files, skipPaths = skipPaths)
            } else {
                copyFileSynced(child, destination)
                files += path to destination.length()
            }
        }
        syncDirectory(target)
    }

    /**
     * Copies the deployment's build into the generation, sharing the source's prebuilt tables.
     *
     * A compiled table that is byte for byte one of the tables the source ships in its own
     * `build/` is hard-linked instead of copied. The source tree is never written in place (a new
     * package replaces it whole), so the published generation cannot change through that link, and
     * every generation of a package that ships prebuilt tables then shares one copy on disk. The
     * work build is always copied, even when its bytes match: librime rewrites it in place while
     * deploying, and a link would let those writes reach a published generation.
     */
    private fun copyBuild(
        compiledDir: File,
        prebuiltDir: File,
        target: File,
        files: MutableList<Pair<String, Long>>,
        shared: ((File, File, File?) -> Unit)?,
    ) {
        target.mkdirs()
        copyBuildEntries(compiledDir, prebuiltDir, target, BUILD, files, shared)
        syncDirectory(target)
    }

    private fun copyBuildEntries(
        source: File,
        prebuiltDir: File,
        target: File,
        prefix: String,
        files: MutableList<Pair<String, Long>>,
        shared: ((File, File, File?) -> Unit)?,
    ) {
        target.mkdirs()
        for (child in source.listFiles().orEmpty().sortedBy { it.name }) {
            val destination = File(target, child.name)
            val path = prefix + "/" + child.name
            if (child.isDirectory) {
                copyBuildEntries(child, prebuiltDir, destination, path, files, shared)
            } else {
                val prebuilt = prebuiltTable(prebuiltDir, prefix, child.name)
                if (shared != null && prefix == BUILD && isPrebuiltTable(child.name)) {
                    shared(child, destination, prebuilt)
                } else if (prebuilt != null && sameContent(child, prebuilt)) {
                    linkOrCopy(prebuilt, destination)
                } else {
                    copyFileSynced(child, destination)
                }
                files += path to destination.length()
            }
        }
        syncDirectory(target)
    }

    /** The source's own prebuilt table for one build entry, when that entry may share it. */
    private fun prebuiltTable(
        prebuiltDir: File,
        prefix: String,
        name: String,
    ): File? {
        // Only the source's top-level build holds prebuilt tables; anything the deployment put
        // below it is its own output and stays private to the generation.
        if (prefix != BUILD || !isPrebuiltTable(name)) return null
        val file = File(prebuiltDir, name)
        return file.takeIf { it.isFile }
    }

    private fun isPrebuiltTable(name: String): Boolean =
        PREBUILT_SUFFIXES.any { name.endsWith(it) && name.length > it.length }

    /** Whether two files hold the same bytes, compared as streams so a table never enters the heap. */
    private fun sameContent(
        a: File,
        b: File,
    ): Boolean {
        if (a.length() != b.length()) return false
        return a.inputStream().use { left ->
            b.inputStream().use { right -> streamsEqual(left, right) }
        }
    }

    private fun streamsEqual(
        left: InputStream,
        right: InputStream,
    ): Boolean {
        val leftChunk = ByteArray(BUFFER_BYTES)
        val rightChunk = ByteArray(BUFFER_BYTES)
        var read = left.read(leftChunk)
        while (read >= 0) {
            val other = right.read(rightChunk)
            if (other != read) return false
            val equal =
                if (read == leftChunk.size) {
                    leftChunk.contentEquals(rightChunk)
                } else {
                    leftChunk.copyOf(read).contentEquals(rightChunk.copyOf(read))
                }
            if (!equal) return false
            read = left.read(leftChunk)
        }
        return right.read(rightChunk) < 0
    }

    private fun linkOrCopy(
        source: File,
        destination: File,
    ) {
        val linked =
            try {
                Files.createLink(destination.toPath(), source.toPath())
                true
            } catch (_: IOException) {
                false
            } catch (_: UnsupportedOperationException) {
                false
            }
        if (linked) return
        copyFileSynced(source, destination)
    }

    private fun copyFileSynced(
        source: File,
        destination: File,
    ) {
        source.inputStream().use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output, BUFFER_BYTES)
                output.flush()
                output.fd.sync()
            }
        }
    }

    private fun writeSynced(
        file: File,
        data: ByteArray,
    ) {
        FileOutputStream(file).use { out ->
            out.write(data)
            out.flush()
            out.fd.sync()
        }
    }

    private fun syncDirectory(dir: File) {
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (_: IOException) {
            // Some file systems refuse to open a directory for sync; file contents are synced above.
        }
    }

    companion object {
        private const val TAG = "GenerationPublisher"
        private const val TEMP_PREFIX = ".tmp-"
        private const val RESOURCES = "resources"
        private const val BUILD = "build"
        const val MANIFEST = "generation.json"
        private const val MAX_MANIFEST_BYTES = 4L * 1024 * 1024
        private const val BUFFER_BYTES = 64 * 1024
        private val PREBUILT_SUFFIXES = listOf(".table.bin", ".reverse.bin", ".prism.bin")
    }
}
