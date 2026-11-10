package app.songyan.ime.deploy

/**
 * One pinned archive of a recommended package: where to download it and the SHA-256 its bytes must
 * have. [bytes] is the catalogue's size hint, used for the download progress; the digest, never the
 * size, decides whether the archive is the one the catalogue names.
 */
data class PinnedArchive(
    val url: String,
    val sha256: String,
    val bytes: Long = 0,
)

/** What the :deploy process should do for one request. */
enum class DeployMode(
    val wireName: String,
) {
    /** Materialise the bundled sources; reuses a current generation of them unless forced. */
    BUILTIN("builtin"),

    /** Rebuild whatever source is active (after an app or engine update, or the keyboard's retry). */
    REDEPLOY_ACTIVE("redeploy-active"),

    /** Turn the dictionary files in the request's incoming directory into the dictionary source. */
    IMPORT_DICTIONARY("import-dictionary"),

    /** Download a dictionary (and what it imports) from an HTTPS link, then import it. */
    IMPORT_DICTIONARY_URL("import-dictionary-url"),

    /** Check and install the scheme package ZIP in the request's incoming directory. */
    IMPORT_SCHEME("import-scheme"),

    /**
     * Download a scheme package ZIP from an HTTPS link, then check and install it like
     * [IMPORT_SCHEME]. With a source id the package is an update of that stored package: it keeps
     * that id (and so its learning data) and the keyboard keeps using what it used.
     */
    IMPORT_SCHEME_URL("import-scheme-url"),

    /** Make a stored source the active one. */
    ACTIVATE_SOURCE("activate-source"),

    /** Delete a stored source; its learning data stays. */
    DELETE_SOURCE("delete-source"),

    /**
     * Download a file from an HTTPS link into [app.songyan.ime.storage.AppStorage.downloadFile]; the
     * caller imports it. The download runs here because only this process may use the network.
     */
    DOWNLOAD_FILE("download-file"),

    /** Put the source deleted last back as a stored source (not active). */
    RESTORE_SOURCE("restore-source"),

    /** Remove the dictionary stacked onto a scheme package and redeploy it when in use. */
    REMOVE_DICTIONARY("remove-dictionary"),

    /** Give every input method of the active scheme package its compact layouts, after a deployment built only the one in use. */
    COMPLETE_COMPANIONS("complete-companions"),

    /** Check the user's edited Rime configuration, make it the applied one and rebuild the active source with it. */
    APPLY_USER_CONFIG("apply-user-config"),

    ;

    companion object {
        fun fromWire(value: String?): DeployMode? = entries.firstOrNull { it.wireName == value }
    }
}

/**
 * One deployment request. Requests run one after another in arrival order; each one ends with its
 * own terminal status under [requestId], so a waiter is never answered with another request's
 * result.
 */
data class DeployRequest(
    val requestId: String,
    val mode: DeployMode,
    val force: Boolean = false,
    /**
     * The source to activate, delete or restore; for a scheme link import, the package it updates;
     * for a dictionary import or removal, the scheme package the
     * dictionary is stacked onto (null: the built-in input methods).
     */
    val sourceId: String? = null,
    /** The link of [DeployMode.IMPORT_DICTIONARY_URL], [DeployMode.IMPORT_SCHEME_URL] or [DeployMode.DOWNLOAD_FILE]. */
    val url: String? = null,
    /** The most [DeployMode.DOWNLOAD_FILE] may fetch, when the caller wants less than a model file. */
    val maxBytes: Long? = null,
    /**
     * The pinned archives of a recommended entry, the entry package first. Empty for a link the user
     * typed: that one is downloaded without a digest, because the user chose the link itself.
     */
    val archives: List<PinnedArchive> = emptyList(),
    /** Picked ZIPs the settings process already staged in the request's incoming directory, entry first. */
    val packageFiles: List<String> = emptyList(),
    /**
     * Input methods a recommended entry offers. Empty keeps whatever the package's own
     * default.yaml lists; a package without one would otherwise offer every root schema it ships.
     */
    val advertisedSchemas: List<String> = emptyList(),
) {
    /**
     * Whether running this request right after [earlier] would redo the same work: identical
     * rebuilds merge (a retry tapped during a slow deploy), anything carrying user content never does.
     */
    fun mergesInto(earlier: DeployRequest): Boolean =
        when (mode) {
            DeployMode.BUILTIN -> earlier.mode == DeployMode.BUILTIN && earlier.force == force
            DeployMode.REDEPLOY_ACTIVE -> earlier.mode == DeployMode.REDEPLOY_ACTIVE
            DeployMode.COMPLETE_COMPANIONS -> earlier.mode == DeployMode.COMPLETE_COMPANIONS
            else -> false
        }

    companion object {
        private val REQUEST_ID = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val SCHEMA_ID = Regex("[A-Za-z0-9_.-]+")
        private val PACKAGE_FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

        fun isValidRequestId(value: String?): Boolean = value != null && REQUEST_ID.matches(value)

        /** The pinned archives as one line per archive ("url", digest and size in tab-separated columns). */
        fun archiveExtra(archives: List<PinnedArchive>): String =
            archives.joinToString("\n") { it.url + "\t" + it.sha256 + "\t" + it.bytes }

        /** Decodes [DeployContract.EXTRA_ARCHIVES]; empty when absent, null when the value is malformed. */
        private fun pinnedArchives(stringExtra: (String) -> String?): List<PinnedArchive>? {
            val raw = stringExtra(DeployContract.EXTRA_ARCHIVES)?.takeIf { it.isNotBlank() } ?: return emptyList()
            val decoded = ArrayList<PinnedArchive>()
            for (line in raw.split('\n')) {
                val columns = line.split('\t')
                if (columns.size != 3) return null
                val url = columns[0]
                if (!url.startsWith("https://") || url.any { it.isWhitespace() }) return null
                if (!SHA256.matches(columns[1])) return null
                val bytes = columns[2].toLongOrNull()?.takeIf { it >= 0 } ?: return null
                decoded += PinnedArchive(url, columns[1], bytes)
            }
            return decoded
        }

        /** A newline-separated list of values matching [pattern]; empty when absent, null when invalid. */
        private fun lines(
            stringExtra: (String) -> String?,
            name: String,
            pattern: Regex,
        ): List<String>? {
            val raw = stringExtra(name)?.takeIf { it.isNotBlank() } ?: return emptyList()
            val values = raw.split('\n')
            return values.takeIf { list -> list.all { pattern.matches(it) } }
        }

        private fun target(stringExtra: (String) -> String?): String? =
            stringExtra(DeployContract.EXTRA_SOURCE_ID)?.takeIf { it.isNotBlank() }

        /**
         * Decodes a request without depending on Android: [stringExtra] reads one extra by key.
         * Returns null for a request the deploy process cannot identify; it never guesses at a
         * source tree or a link.
         */
        fun from(
            requestId: String?,
            mode: String?,
            force: Boolean,
            stringExtra: (String) -> String?,
        ): DeployRequest? {
            if (!isValidRequestId(requestId)) return null
            val id = requestId!!
            val pinnedArchives = pinnedArchives(stringExtra) ?: return null
            val advertised = lines(stringExtra, DeployContract.EXTRA_SCHEMA_IDS, SCHEMA_ID) ?: return null
            return when (val parsed = DeployMode.fromWire(mode) ?: return null) {
                DeployMode.BUILTIN -> DeployRequest(id, parsed, force = force)
                DeployMode.IMPORT_SCHEME -> {
                    val packageFiles =
                        lines(stringExtra, DeployContract.EXTRA_PACKAGE_FILES, PACKAGE_FILE_NAME) ?: return null
                    DeployRequest(id, parsed, packageFiles = packageFiles, advertisedSchemas = advertised)
                }
                DeployMode.REDEPLOY_ACTIVE,
                DeployMode.COMPLETE_COMPANIONS,
                DeployMode.APPLY_USER_CONFIG,
                -> DeployRequest(id, parsed)

                DeployMode.IMPORT_DICTIONARY -> DeployRequest(id, parsed, sourceId = target(stringExtra))

                DeployMode.DOWNLOAD_FILE ->
                    stringExtra(DeployContract.EXTRA_URL)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { url ->
                            val limit = stringExtra(DeployContract.EXTRA_MAX_BYTES)?.toLongOrNull()?.takeIf { it > 0 }
                            DeployRequest(id, parsed, url = url, maxBytes = limit)
                        }

                DeployMode.IMPORT_SCHEME_URL -> {
                    val url = stringExtra(DeployContract.EXTRA_URL)?.takeIf { it.isNotBlank() }
                    if (url == null && pinnedArchives.isEmpty()) return null
                    DeployRequest(
                        id,
                        parsed,
                        sourceId = target(stringExtra),
                        url = url,
                        archives = pinnedArchives,
                        advertisedSchemas = advertised,
                    )
                }

                DeployMode.IMPORT_DICTIONARY_URL ->
                    stringExtra(DeployContract.EXTRA_URL)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { DeployRequest(id, parsed, sourceId = target(stringExtra), url = it) }

                DeployMode.ACTIVATE_SOURCE,
                DeployMode.DELETE_SOURCE,
                DeployMode.RESTORE_SOURCE,
                DeployMode.REMOVE_DICTIONARY,
                ->
                    stringExtra(DeployContract.EXTRA_SOURCE_ID)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { DeployRequest(id, parsed, sourceId = it) }
            }
        }
    }
}

/** A queued request plus every later identical request that merged into it. */
data class QueuedDeploy(
    val request: DeployRequest,
    /** Every request id this run answers, the request's own first. */
    val requestIds: List<String>,
)

/**
 * Requests waiting for the deploy process, oldest first. Pure so the ordering and merge rules are
 * unit-testable; [DeployService] guards it with its own lock.
 */
class DeployQueue {
    private val pending = ArrayDeque<DeployRequest>()
    private val merged = HashMap<String, MutableList<String>>()

    /** Adds [request]; returns false when it merged into the identical rebuild queued last. */
    fun offer(request: DeployRequest): Boolean {
        val last = pending.lastOrNull()
        if (last != null && request.mergesInto(last)) {
            merged.getOrPut(last.requestId) { mutableListOf() } += request.requestId
            return false
        }
        pending.addLast(request)
        return true
    }

    fun poll(): QueuedDeploy? {
        val request = pending.removeFirstOrNull() ?: return null
        return QueuedDeploy(request, listOf(request.requestId) + merged.remove(request.requestId).orEmpty())
    }

    val size: Int get() = pending.size

    /** Every waiting request id of [mode], merged ones included. */
    fun waiting(mode: DeployMode): List<String> =
        pending.filter { it.mode == mode }.flatMap { listOf(it.requestId) + merged[it.requestId].orEmpty() }

    /** Whether [requestId] still waits, on its own or merged into a queued rebuild. */
    fun contains(requestId: String): Boolean =
        pending.any { it.requestId == requestId } || merged.values.any { requestId in it }
}

/**
 * The deploy service's worker bookkeeping: the queue, whether a worker drains it and the newest
 * start command. Not thread-safe; the service calls it under one lock.
 */
class DeployWorkerState {
    val queue = DeployQueue()
    private var running = false
    private var lastStartId = 0

    /** Records start command [startId]; true when work waits and no worker runs, so one must start. */
    fun started(startId: Int): Boolean {
        lastStartId = startId
        val launch = !running && queue.size > 0
        if (launch) running = true
        return launch
    }

    /**
     * Work added without a start command (the deploy job); true when no worker runs, so one must
     * start. The service's newest start id stays as it is.
     */
    fun woken(): Boolean {
        val launch = !running && queue.size > 0
        if (launch) running = true
        return launch
    }

    /**
     * A worker stopped before it emptied the queue (it was cancelled); true when requests wait,
     * so a new worker must take them over.
     */
    fun interrupted(): Boolean {
        running = queue.size > 0
        return running
    }

    /** The worker's next request; null ends the worker. */
    fun next(): QueuedDeploy? =
        queue.poll() ?: run {
            running = false
            null
        }

    val busy: Boolean get() = running || queue.size > 0

    /**
     * The start id an ending worker may stop the service with, or null when it must not: a start
     * command that arrived after the worker found the queue empty launched a worker of its own, and
     * stopping now would drop the service out of the foreground while that worker runs (the system
     * then freezes the process with the deployment half done).
     */
    fun idleStartId(): Int? = if (busy) null else lastStartId
}
