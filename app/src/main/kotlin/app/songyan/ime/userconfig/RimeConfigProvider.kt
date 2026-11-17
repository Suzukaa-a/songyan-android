package app.songyan.ime.userconfig

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.util.Log
import android.webkit.MimeTypeMap
import app.songyan.ime.R
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.SourceCatalog
import app.songyan.ime.storage.SourceIds
import app.songyan.ime.storage.SourceKind
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Rime configuration folder in the system Files app and document picker
 * (https://developer.android.com/guide/topics/providers/create-document-provider): one root,
 * 「松烟 Rime 配置」, with the user's own files ([UserConfigFiles], writable) and views of the source
 * in use and of every stored source, so the user can read the schema they patch. The user's files
 * are kept per source ([AppStorage.userConfigDir]): 「我的配置」 holds one folder per stored source,
 * named as the source is, so it is clear which scheme package a file belongs to. A text file of
 * a source is writable too, but the package is never changed: a write lands in the user's copy of
 * that file in that source's folder ([UserOverrides]; through 「正在使用」, the folder of the source
 * in use), which is what the file shows from then on.
 *
 * Only the system's document UI talks to a documents provider directly (the manifest guards it
 * with `MANAGE_DOCUMENTS`); other apps get single documents or the tree through grants the user
 * makes in the picker. Every path a document id names is checked to stay inside its folder, and
 * the writable folder takes only `.yaml` / `.txt` / `.lua` names, in folders that mirror a source's.
 * A write goes to a temporary file
 * that replaces the document when the writer closes it, and only within the size limits, so a
 * runaway writer never leaves a half-written or oversized configuration behind. Nothing here
 * reaches the running keyboard: the settings screen applies the changes.
 */
class RimeConfigProvider : DocumentsProvider() {
    private val storage: AppStorage by lazy { AppStorage(requireNotNull(context).filesDir) }
    private val openTemps: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val swept = AtomicBoolean(false)
    private val closeHandler: Handler by lazy {
        Handler(HandlerThread("rime-config-writes").apply { start() }.looper)
    }

    // Runs on the main thread: nothing here may touch the disk.
    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        sweepOnce()
        val context = requireNotNull(context)
        val cursor = MatrixCursor(projection ?: ROOT_COLUMNS)
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT)
            add(Root.COLUMN_TITLE, context.getString(R.string.rime_config_root_title))
            add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD)
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(Root.COLUMN_MIME_TYPES, "*/*")
        }
        return cursor
    }

    override fun queryDocument(
        documentId: String,
        projection: Array<out String>?,
    ): Cursor {
        migrate()
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        addRow(cursor, resolve(documentId))
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val context = requireNotNull(context)
        migrate()
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        for (child in children(resolve(parentDocumentId))) addRow(cursor, child)
        cursor.setNotificationUri(
            context.contentResolver,
            DocumentsContract.buildChildDocumentsUri(authority(context), parentDocumentId),
        )
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        migrate()
        val node = resolve(documentId)
        val file = node.file ?: throw FileNotFoundException("not a file: $documentId")
        if (node.directory) throw FileNotFoundException("a folder: $documentId")
        val flags = ParcelFileDescriptor.parseMode(mode)
        // MODE_READ_WRITE is MODE_READ_ONLY | MODE_WRITE_ONLY: the write bit says whether anything is written.
        if (flags and ParcelFileDescriptor.MODE_WRITE_ONLY == 0) {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        if (node.writable && node.scope != null) return openForWriting(documentId, file, flags, node.scope)
        val packageFile = node.packageFile
        val path = node.overridePath
        val scope = node.scope
        if (packageFile == null || path == null || scope == null) {
            throw SecurityException(message(R.string.rime_config_refused_read_only))
        }
        val target =
            try {
                overrides(scope).target(packageFile, path)
            } catch (e: UserConfigException) {
                throw refusal(e)
            }
        return openForWriting(documentId, target, flags, scope, packageFile, path)
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        migrate()
        // File managers that ignore the folder flags offer to paste anywhere and show this message as is.
        val parsed = RimeConfigPaths.parse(parentDocumentId)
        if (parsed?.area != RimeConfigPaths.Area.EDIT || parsed.segments.isEmpty()) {
            throw UnsupportedOperationException(message(R.string.rime_config_refused_read_only))
        }
        val scope = parsed.segments.first()
        if (!isScope(scope)) throw FileNotFoundException("no document $parentDocumentId")
        val edit = storage.userConfigDir(scope)
        // Within the source's folder from here on.
        val parent = parsed.copy(segments = parsed.segments.drop(1))
        val parentPath = parent.segments.joinToString("/")
        val inScripts = parentPath.isNotEmpty() && UserConfigFiles.isScriptFolder(parentPath)
        val dir =
            if (parent.segments.isEmpty()) {
                edit.also { it.mkdirs() }
            } else {
                RimeConfigPaths.inside(edit, parent.segments)?.takeIf { it.isDirectory }
                    ?: throw FileNotFoundException("no document $parentDocumentId")
            }
        val names = dir.list().orEmpty()

        // A name that differs only in letter case counts as taken: Files apps on other systems merge them.
        fun taken(name: String) = names.any { it.equals(name, ignoreCase = true) }
        if (mimeType == Document.MIME_TYPE_DIR) {
            // Folders exist for scripts only: `lua/`, and folders inside it.
            val name = displayName.trim()
            val path = if (parentPath.isEmpty()) name else "$parentPath/$name"
            if (!(parentPath.isEmpty() && name == "lua") && !(inScripts && UserConfigFiles.isAllowedFolderPath(path))) {
                throw UnsupportedOperationException(message(R.string.rime_config_refused_folder))
            }
            if (taken(name)) return "$parentDocumentId/" + names.first { it.equals(name, ignoreCase = true) }
            if (!File(dir, name).mkdir()) throw IOException("cannot create $name")
            notifyEdited(scope, parentDocumentId)
            return "$parentDocumentId/$name"
        }
        val unique =
            if (inScripts) {
                val name = displayName.trim()
                if (!UserConfigFiles.isAllowedName(name) || !name.endsWith(".lua")) {
                    throw IllegalArgumentException(message(R.string.rime_config_refused_script_type, displayName))
                }
                RimeConfigPaths.unique(name, UserConfigFiles::isAllowedName, ::taken)
            } else if (parent.segments.isEmpty() && displayName.trim() == "rime.lua") {
                // The one script Rime reads from the top: a second one has no other name to take.
                if (taken("rime.lua")) throw IllegalStateException("rime.lua exists")
                "rime.lua"
            } else if (parent.segments.isEmpty()) {
                val name =
                    RimeConfigPaths.newFileName(displayName, mimeType)
                        ?: throw IllegalArgumentException(message(R.string.rime_config_refused_type, displayName))
                RimeConfigPaths.unique(name, taken = ::taken)
            } else {
                throw UnsupportedOperationException(message(R.string.rime_config_refused_folder))
            } ?: throw IllegalStateException("no free name for $displayName")
        if (UserConfigFiles.files(edit.also { it.mkdirs() }).size >= UserConfigFiles.MAX_FILES) {
            throw IllegalStateException(message(R.string.rime_config_error_many))
        }
        if (!File(dir, unique).createNewFile()) throw IOException("cannot create $unique")
        notifyEdited(scope, parentDocumentId)
        return "$parentDocumentId/$unique"
    }

    override fun renameDocument(
        documentId: String,
        displayName: String,
    ): String {
        migrate()
        val node = resolve(documentId)
        val file = node.file
        val scope = node.scope
        if (!node.writable || file == null || scope == null) {
            throw UnsupportedOperationException(message(R.string.rime_config_refused_read_only))
        }
        val edit = storage.userConfigDir(scope)
        // Only a flat file a person made, or a script they keep in `lua/`, can be renamed; a copy of a
        // source's file is named by its source.
        val parentPath = file.parentFile?.let { UserConfigFiles.relativePath(edit, it) }.orEmpty()
        val flat = file.parentFile?.canonicalFile == edit.canonicalFile
        val script = parentPath.isNotEmpty() && UserConfigFiles.isScriptFolder(parentPath)
        val allowed =
            when {
                flat -> UserConfigFiles.isCreatableName(file.name) && UserConfigFiles.isCreatableName(displayName)
                script ->
                    file.name.endsWith(".lua") &&
                        UserConfigFiles.isAllowedName(displayName) &&
                        displayName.endsWith(".lua")
                else -> false
            }
        if (!flat && !script) throw UnsupportedOperationException(message(R.string.rime_config_refused_read_only))
        if (!allowed) throw IllegalArgumentException(message(R.string.rime_config_refused_type, displayName))
        if (displayName == file.name) return documentId
        val target = File(file.parentFile, displayName)
        if (file.parentFile
                ?.list()
                .orEmpty()
                .any { it != file.name && it.equals(displayName, ignoreCase = true) }
        ) {
            throw IllegalStateException("$displayName exists")
        }
        val old = UserConfigFiles.relativePath(edit, file)
        if (!file.renameTo(target)) throw IOException("cannot rename $documentId")
        overrides(scope).forget(old)
        notifyEdited(scope)
        return documentId.substringBeforeLast('/') + "/" + displayName
    }

    override fun deleteDocument(documentId: String) {
        migrate()
        val node = resolve(documentId)
        val file = node.file
        val scope = node.scope
        if (!node.writable || file == null || scope == null) {
            throw UnsupportedOperationException(message(R.string.rime_config_refused_read_only))
        }
        if (!overrides(scope).restore(UserConfigFiles.relativePath(storage.userConfigDir(scope), file))) {
            throw IOException("cannot delete $documentId")
        }
        notifyEdited(scope)
    }

    private fun message(
        id: Int,
        vararg args: Any,
    ): String = requireNotNull(context).getString(id, *args)

    /** The exception a client sees for a file that cannot be the user's copy of a source file. */
    private fun refusal(e: UserConfigException): Exception =
        when (e.problem) {
            UserConfigProblem.TOO_LARGE ->
                IllegalStateException(
                    message(R.string.rime_config_refused_large, e.file.orEmpty()),
                )
            UserConfigProblem.TOO_MANY -> IllegalStateException(message(R.string.rime_config_error_many))
            else -> SecurityException(message(R.string.rime_config_refused_read_only))
        }

    override fun isChildDocument(
        parentDocumentId: String,
        documentId: String,
    ): Boolean = RimeConfigPaths.isChild(parentDocumentId, documentId)

    override fun getDocumentType(documentId: String): String {
        migrate()
        return resolve(documentId).mimeType
    }

    /**
     * A write lands in a temporary file in the configuration root, which replaces the document
     * when the writer closes it. Without truncation the writer starts from the current content, as
     * it would on the file itself.
     */
    private fun openForWriting(
        documentId: String,
        target: File,
        flags: Int,
        scope: String,
        packageFile: File? = null,
        packagePath: String? = null,
    ): ParcelFileDescriptor {
        sweepOnce()
        if (openTemps.size >= WriteTemps.MAX_OPEN) throw FileNotFoundException("too many files are being written")
        val temp = File(storage.configRoot, WRITE_PREFIX + System.nanoTime())
        openTemps += temp.name
        temp.parentFile?.mkdirs()
        // Without a user's copy yet, a writer that keeps the content starts from the package's file.
        val seed = target.takeIf { it.isFile } ?: packageFile
        if (flags and ParcelFileDescriptor.MODE_TRUNCATE == 0 && seed != null) {
            Files.copy(seed.toPath(), temp.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } else {
            temp.writeBytes(ByteArray(0))
        }
        val over = AtomicBoolean(false)
        val closed = AtomicBoolean(false)
        watchSize(
            temp,
            over,
            closed,
            WriteWatch(UserConfigFiles.MAX_FILE_BYTES, WriteTemps.MAX_OPEN_MS, System.currentTimeMillis()),
        )
        return ParcelFileDescriptor.open(temp, flags or ParcelFileDescriptor.MODE_CREATE, closeHandler) { error ->
            closed.set(true)
            openTemps -= temp.name
            try {
                if (over.get()) {
                    Log.w(TAG, "discarded a write to $documentId: over the size limit")
                    return@open
                }
                if (error == null && packageFile != null && !target.isFile && sameContent(temp, packageFile)) {
                    // Opened for writing, closed without a change: no copy of the package's file is worth keeping.
                    return@open
                }
                if (error == null && withinLimits(scope, target, temp.length())) {
                    target.parentFile?.mkdirs()
                    Files.move(
                        temp.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                    if (packageFile != null && packagePath != null) {
                        overrides(scope).recordBase(packagePath, packageFile)
                    }
                    notifyEdited(scope)
                } else {
                    Log.w(TAG, "discarded a write to $documentId: " + (error?.message ?: "over the size limit"))
                }
            } catch (e: IOException) {
                Log.w(TAG, "cannot save $documentId", e)
            } finally {
                temp.delete()
            }
        }
    }

    /**
     * A writer holds the temporary file itself, so a runaway one is cut off from here: past the
     * per-file limit the file is emptied on every check (its space is given back) and the write is
     * discarded when the writer closes. Disk use stays at what one writer fits in between two checks.
     */
    private fun watchSize(
        temp: File,
        over: AtomicBoolean,
        closed: AtomicBoolean,
        watch: WriteWatch,
    ) {
        closeHandler.postDelayed(
            {
                if (closed.get()) return@postDelayed
                val verdict = watch.check(temp.length(), System.currentTimeMillis())
                if (over.get() || verdict != WriteWatch.Verdict.FINE) {
                    over.set(true)
                    try {
                        RandomAccessFile(temp, "rw").use { it.setLength(0) }
                    } catch (_: IOException) {
                        // Gone already: the writer closed.
                    }
                }
                if (verdict == WriteWatch.Verdict.TOO_LONG) {
                    // A writer that never closes stops counting, and stops being watched.
                    temp.delete()
                    openTemps -= temp.name
                    return@postDelayed
                }
                watchSize(temp, over, closed, watch)
            },
            SIZE_CHECK_MS,
        )
    }

    /** The first use after a start removes what dead writers left; on the writer thread, never the main one. */
    private fun sweepOnce() {
        if (swept.compareAndSet(false, true)) {
            closeHandler.post {
                WriteTemps.sweep(storage.configRoot, openTemps)
                for (scope in storage.configScopesDir
                    .list()
                    .orEmpty()
                    .filter(SourceIds::isValid)) {
                    WriteTemps.sweepTree(storage.userConfigDir(scope), STALE_EDIT_TEMP_MS)
                }
            }
        }
    }

    private fun sameContent(
        a: File,
        b: File,
    ): Boolean = a.length() == b.length() && a.readBytes().contentEquals(b.readBytes())

    private fun withinLimits(
        scope: String,
        target: File,
        size: Long,
    ): Boolean {
        if (size > UserConfigFiles.MAX_FILE_BYTES) return false
        val dir = storage.userConfigDir(scope)
        val files = UserConfigFiles.files(dir)
        val own = UserConfigFiles.relativePath(dir, target)
        val others = files.filter { UserConfigFiles.relativePath(dir, it) != own }
        if (others.size >= UserConfigFiles.MAX_FILES) return false
        return others.sumOf { it.length() } + size <= UserConfigFiles.MAX_TOTAL_BYTES
    }

    /** Tells the Files app that [parentId], in [scope]'s folder, changed, and the folder with it. */
    private fun notifyEdited(
        scope: String,
        parentId: String = RimeConfigPaths.editId(scope),
    ) {
        val context = context ?: return
        val folder = RimeConfigPaths.editId(scope)
        for (id in listOf(parentId, folder).distinct()) {
            context.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(authority(context), id), null)
        }
    }

    /** One document: a virtual folder ([file] null), or a file or folder on disk. */
    private class Node(
        val id: String,
        val displayName: String,
        val file: File?,
        val directory: Boolean,
        val writable: Boolean = false,
        val creatable: Boolean = false,
        /** A text file of a source: writes go to the user's copy of [packageFile], known as [overridePath]. */
        val packageFile: File? = null,
        val overridePath: String? = null,
        /** The source whose configuration a write goes into. */
        val scope: String? = null,
    ) {
        val mimeType: String get() = if (directory) Document.MIME_TYPE_DIR else mimeTypeOf(displayName)
    }

    private fun addRow(
        cursor: MatrixCursor,
        node: Node,
    ) {
        var flags = 0
        if (node.writable) {
            flags =
                flags or Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        }
        // A source's text file: writable through the user's copy, but neither deleted nor renamed.
        if (node.overridePath != null) flags = flags or Document.FLAG_SUPPORTS_WRITE
        if (node.creatable) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, node.id)
            add(Document.COLUMN_DISPLAY_NAME, node.displayName)
            add(Document.COLUMN_MIME_TYPE, node.mimeType)
            add(Document.COLUMN_FLAGS, flags)
            add(Document.COLUMN_SIZE, if (node.directory || node.file == null) null else node.file.length())
            add(Document.COLUMN_LAST_MODIFIED, node.file?.lastModified()?.takeIf { it > 0 })
        }
    }

    private fun resolve(documentId: String): Node {
        val context = requireNotNull(context)
        val path = RimeConfigPaths.parse(documentId) ?: throw FileNotFoundException("no document $documentId")
        return when (path.area) {
            RimeConfigPaths.Area.ROOT ->
                Node(
                    ROOT_DOCUMENT,
                    context.getString(R.string.rime_config_root_title),
                    null,
                    true,
                )
            RimeConfigPaths.Area.EDIT -> {
                if (path.segments.isEmpty()) {
                    return Node(EDIT, context.getString(R.string.rime_config_folder_edit), null, true)
                }
                val scope = path.segments.first()
                if (!isScope(scope)) throw FileNotFoundException("no document $documentId")
                val inScope = path.segments.drop(1)
                if (inScope.isEmpty()) return scopeNode(scope)
                val file =
                    RimeConfigPaths.inside(storage.userConfigDir(scope), inScope)
                        ?: throw FileNotFoundException("no document $documentId")
                when {
                    file.isDirectory ->
                        Node(
                            documentId,
                            file.name,
                            file,
                            true,
                            creatable = UserConfigFiles.isScriptFolder(inScope.joinToString("/")),
                            scope = scope,
                        )
                    file.isFile && UserConfigFiles.isAllowedName(file.name) ->
                        Node(documentId, file.name, file, false, writable = true, scope = scope)
                    else -> throw FileNotFoundException("no document $documentId")
                }
            }
            RimeConfigPaths.Area.ACTIVE -> {
                val sourceId = activeSourceId()
                if (path.segments.isEmpty()) {
                    Node(ACTIVE, activeTitle(sourceId), sourceId?.let(storage::sourceDir), true)
                } else {
                    fileNode(documentId, sourceId?.let(storage::sourceDir), path.segments, activeScope())
                }
            }
            RimeConfigPaths.Area.SOURCES ->
                when (path.segments.size) {
                    0 -> Node(SOURCES, context.getString(R.string.rime_config_folder_sources), null, true)
                    else -> {
                        val sourceId = path.segments.first()
                        val dir = storage.sourceDir(sourceId).takeIf { SourceIds.isValid(sourceId) && it.isDirectory }
                        if (path.segments.size == 1) {
                            if (dir == null) throw FileNotFoundException("no document $documentId")
                            Node(documentId, sourceName(sourceId), dir, true)
                        } else {
                            fileNode(documentId, dir, path.segments.drop(1), sourceId)
                        }
                    }
                }
        }
    }

    /**
     * A file or folder inside a source tree; never outside it. [segments] are relative to [base].
     * A text file shows the user's copy when there is one, and takes writes through it.
     */
    private fun fileNode(
        documentId: String,
        base: File?,
        segments: List<String>,
        scope: String,
    ): Node {
        val file =
            base?.let { RimeConfigPaths.inside(it, segments) } ?: throw FileNotFoundException("no document $documentId")
        if (!file.exists() ||
            RimeConfigPaths.hidden(segments.last())
        ) {
            throw FileNotFoundException("no document $documentId")
        }
        return packageNode(documentId, file, segments, scope)
    }

    /** A file of a source; [scope] is the source whose configuration holds the user's copy of it. */
    private fun packageNode(
        documentId: String,
        file: File,
        segments: List<String>,
        scope: String,
    ): Node {
        if (file.isDirectory) return Node(documentId, file.name, file, true)
        val path = segments.joinToString("/")
        if (!UserConfigFiles.isAllowedPath(path)) return Node(documentId, file.name, file, false)
        return Node(
            documentId,
            file.name,
            overrides(scope).overrideFile(path) ?: file,
            false,
            packageFile = file,
            overridePath = path,
            scope = scope,
        )
    }

    /** [scope]'s folder of the user's files, named as the source is. */
    private fun scopeNode(scope: String): Node =
        Node(
            RimeConfigPaths.editId(scope),
            sourceName(scope),
            storage.userConfigDir(scope),
            true,
            creatable = true,
            scope = scope,
        )

    /**
     * The files and the folders with files of [scope]'s folder [dir], which is [parentPath] within it
     * (`lua/` is shown empty: that is where scripts go).
     */
    private fun editChildren(
        parentId: String,
        dir: File?,
        scope: String,
        parentPath: String,
    ): List<Node> {
        fun pathOf(child: File) = if (parentPath.isEmpty()) child.name else "$parentPath/${child.name}"
        return dir
            ?.listFiles()
            .orEmpty()
            .filter { !RimeConfigPaths.hidden(it.name) && !Files.isSymbolicLink(it.toPath()) }
            .filter {
                if (it.isDirectory) {
                    UserConfigFiles.isAllowedFolderPath(pathOf(it)) &&
                        (UserConfigFiles.hasFiles(it) || UserConfigFiles.isScriptFolder(pathOf(it)))
                } else {
                    UserConfigFiles.isAllowedPath(pathOf(it))
                }
            }.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name })
            .map {
                Node(
                    "$parentId/${it.name}",
                    it.name,
                    it,
                    it.isDirectory,
                    writable = it.isFile,
                    creatable = it.isDirectory && UserConfigFiles.isScriptFolder(pathOf(it)),
                    scope = scope,
                )
            }
    }

    private fun children(parent: Node): List<Node> {
        val context = requireNotNull(context)
        return when (parent.id) {
            ROOT_DOCUMENT ->
                listOf(
                    resolve(EDIT),
                    Node(ACTIVE, activeTitle(activeSourceId()), null, true),
                    Node(SOURCES, context.getString(R.string.rime_config_folder_sources), null, true),
                )
            EDIT -> scopeIds().map(::scopeNode)
            SOURCES ->
                SourceCatalog(storage).list().map {
                    Node(SOURCES + "/" + it.sourceId, sourceName(it.sourceId), storage.sourceDir(it.sourceId), true)
                }
            else -> {
                val dir = parent.file?.takeIf { it.isDirectory } ?: return emptyList()
                val ids = parent.id.split('/')
                if (parent.id.startsWith("$EDIT/")) {
                    return editChildren(parent.id, dir, ids[1], ids.drop(2).joinToString("/"))
                }
                // The path of a child within its source: below `active/`, or below `sources/<id>/`.
                val inSources = parent.id.startsWith("$SOURCES/")
                val inSource = ids.drop(if (inSources) 2 else 1)
                val scope = if (inSources) ids[1] else activeScope()
                dir
                    .listFiles()
                    .orEmpty()
                    .filter { !RimeConfigPaths.hidden(it.name) && !Files.isSymbolicLink(it.toPath()) }
                    .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name })
                    .map { packageNode(parent.id + "/" + it.name, it, inSource + it.name, scope) }
            }
        }
    }

    private fun activeSourceId(): String? =
        GenerationPublisher(storage).readActive()?.sourceId?.takeIf {
            storage.sourceDir(it).isDirectory
        }

    private fun activeTitle(sourceId: String?): String {
        val context = requireNotNull(context)
        return sourceId?.let { context.getString(R.string.rime_config_folder_active, sourceName(it)) }
            ?: context.getString(R.string.rime_config_folder_active_none)
    }

    private fun overrides(scope: String) = UserOverrides(storage, scope)

    /** The single folder of earlier versions goes into the per-source folders before any of them is read. */
    private fun migrate() {
        UserConfigMigration.run(storage)
    }

    /** The source whose configuration the source in use edits (see [UserConfigScope]). */
    private fun activeScope(): String = UserConfigScope.active(storage)

    /** The sources with a folder in 「我的配置」: every stored one, and the one being edited even before it is stored. */
    private fun scopeIds(): List<String> {
        val stored = SourceCatalog(storage).list().map { it.sourceId }
        val active = activeScope()
        return if (active in stored) stored else listOf(active) + stored
    }

    private fun isScope(sourceId: String): Boolean =
        SourceIds.isValid(sourceId) && (SourceCatalog(storage).read(sourceId) != null || sourceId == activeScope())

    private fun sourceName(sourceId: String): String {
        val context = requireNotNull(context)
        val manifest = SourceCatalog(storage).read(sourceId)
        return when {
            sourceId == SourceIds.BUILTIN -> context.getString(R.string.source_builtin_name)
            manifest == null -> sourceId
            manifest.kind == SourceKind.BUILTIN -> context.getString(R.string.source_builtin_name)
            manifest.displayName.isNotBlank() -> manifest.displayName
            else -> manifest.primarySchemaId
        }
    }

    companion object {
        private const val TAG = "RimeConfigProvider"
        const val ROOT_ID = "rime"
        const val ROOT_DOCUMENT = RimeConfigPaths.ROOT
        const val EDIT = RimeConfigPaths.EDIT
        const val ACTIVE = RimeConfigPaths.ACTIVE
        const val SOURCES = RimeConfigPaths.SOURCES
        private const val WRITE_PREFIX = WriteTemps.PREFIX
        private const val SIZE_CHECK_MS = 100L
        private const val STALE_EDIT_TEMP_MS = 60_000L

        private val ROOT_COLUMNS =
            arrayOf(
                Root.COLUMN_ROOT_ID,
                Root.COLUMN_DOCUMENT_ID,
                Root.COLUMN_TITLE,
                Root.COLUMN_FLAGS,
                Root.COLUMN_ICON,
                Root.COLUMN_MIME_TYPES,
            )
        private val DOCUMENT_COLUMNS =
            arrayOf(
                Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE,
                Document.COLUMN_FLAGS,
                Document.COLUMN_SIZE,
                Document.COLUMN_LAST_MODIFIED,
            )

        fun authority(context: Context): String = context.packageName + ".rimeconfig"

        /** The root, for opening the folder in the Files app. */
        fun rootUri(context: Context): Uri = DocumentsContract.buildRootUri(authority(context), ROOT_ID)

        /** [sourceId]'s folder of the user's files as a document; the folder of all of them when null. */
        fun editFolderUri(
            context: Context,
            sourceId: String?,
        ): Uri =
            DocumentsContract.buildDocumentUri(
                authority(context),
                sourceId?.let { RimeConfigPaths.editId(it) } ?: EDIT,
            )

        /** Configuration and source text opens in any text editor; everything else by its extension. */
        private fun mimeTypeOf(name: String): String {
            val extension = name.substringAfterLast('.', "").lowercase()
            if (extension in TEXT_EXTENSIONS) return "text/plain"
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        }

        private val TEXT_EXTENSIONS = setOf("yaml", "yml", "txt", "lua", "json", "md", "csv", "tsv")
    }
}
