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
 * in use and of every stored source, so the user can read the schema they patch. A text file of
 * a source is writable too, but the package is never changed: a write lands in the user's copy of
 * that file ([UserOverrides]), which is what the file shows from then on.
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
    private val overrides: UserOverrides by lazy { UserOverrides(storage) }
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
        val node = resolve(documentId)
        val file = node.file ?: throw FileNotFoundException("not a file: $documentId")
        if (node.directory) throw FileNotFoundException("a folder: $documentId")
        val flags = ParcelFileDescriptor.parseMode(mode)
        // MODE_READ_WRITE is MODE_READ_ONLY | MODE_WRITE_ONLY: the write bit says whether anything is written.
        if (flags and ParcelFileDescriptor.MODE_WRITE_ONLY == 0) {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        if (node.writable) return openForWriting(documentId, file, flags)
        val packageFile = node.packageFile
        val path = node.overridePath
        if (packageFile == null || path == null) {
            throw SecurityException(message(R.string.rime_config_refused_read_only))
        }
        val target =
            try {
                overrides.target(packageFile, path)
            } catch (e: UserConfigException) {
                throw refusal(e)
            }
        return openForWriting(documentId, target, flags, packageFile, path)
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        // File managers that ignore the folder flags offer to paste anywhere and show this message as is.
        val parent = RimeConfigPaths.parse(parentDocumentId)
        if (parent?.area != RimeConfigPaths.Area.EDIT) {
            throw UnsupportedOperationException(message(R.string.rime_config_refused_read_only))
        }
        val parentPath = parent.segments.joinToString("/")
        val inScripts = parentPath.isNotEmpty() && UserConfigFiles.isScriptFolder(parentPath)
        val dir =
            if (parent.segments.isEmpty()) {
                storage.userConfigDir.also { it.mkdirs() }
            } else {
                RimeConfigPaths.inside(storage.userConfigDir, parent.segments)?.takeIf { it.isDirectory }
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
            notifyEdited(parentDocumentId)
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
        if (UserConfigFiles.files(storage.userConfigDir.also { it.mkdirs() }).size >= UserConfigFiles.MAX_FILES) {
            throw IllegalStateException(message(R.string.rime_config_error_many))
        }
        if (!File(dir, unique).createNewFile()) throw IOException("cannot create $unique")
        notifyEdited(parentDocumentId)
        return "$parentDocumentId/$unique"
    }

    override fun renameDocument(
        documentId: String,
        displayName: String,
    ): String {
        val node = resolve(documentId)
        val file = node.file
        if (!node.writable ||
            file == null
        ) {
            throw UnsupportedOperationException(message(R.string.rime_config_refused_read_only))
        }
        // Only a flat file a person made, or a script they keep in `lua/`, can be renamed; a copy of a
        // source's file is named by its source.
        val parentPath = file.parentFile?.let { UserConfigFiles.relativePath(storage.userConfigDir, it) }.orEmpty()
        val flat = file.parentFile?.canonicalFile == storage.userConfigDir.canonicalFile
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
        val old = UserConfigFiles.relativePath(storage.userConfigDir, file)
        if (!file.renameTo(target)) throw IOException("cannot rename $documentId")
        overrides.forget(old)
        notifyEdited()
        return documentId.substringBeforeLast('/') + "/" + displayName
    }

    override fun deleteDocument(documentId: String) {
        val node = resolve(documentId)
        val file = node.file
        if (!node.writable ||
            file == null
        ) {
            throw UnsupportedOperationException(message(R.string.rime_config_refused_read_only))
        }
        if (!overrides.restore(UserConfigFiles.relativePath(storage.userConfigDir, file))) {
            throw IOException("cannot delete $documentId")
        }
        notifyEdited()
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

    override fun getDocumentType(documentId: String): String = resolve(documentId).mimeType

    /**
     * A write lands in a temporary file in the configuration root, which replaces the document
     * when the writer closes it. Without truncation the writer starts from the current content, as
     * it would on the file itself.
     */
    private fun openForWriting(
        documentId: String,
        target: File,
        flags: Int,
        packageFile: File? = null,
        packagePath: String? = null,
    ): ParcelFileDescriptor {
        sweepOnce()
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
        watchSize(temp, over, closed)
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
                if (error == null && withinLimits(target, temp.length())) {
                    target.parentFile?.mkdirs()
                    Files.move(
                        temp.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                    if (packageFile != null && packagePath != null) overrides.recordBase(packagePath, packageFile)
                    notifyEdited()
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
    ) {
        closeHandler.postDelayed(
            {
                if (closed.get()) return@postDelayed
                if (over.get() || temp.length() > UserConfigFiles.MAX_FILE_BYTES) {
                    over.set(true)
                    try {
                        RandomAccessFile(temp, "rw").use { it.setLength(0) }
                    } catch (_: IOException) {
                        // Gone already: the writer closed.
                    }
                }
                watchSize(temp, over, closed)
            },
            SIZE_CHECK_MS,
        )
    }

    /** The first use after a start removes what dead writers left; on the writer thread, never the main one. */
    private fun sweepOnce() {
        if (swept.compareAndSet(false, true)) {
            closeHandler.post { WriteTemps.sweep(storage.configRoot, openTemps) }
        }
    }

    private fun sameContent(
        a: File,
        b: File,
    ): Boolean = a.length() == b.length() && a.readBytes().contentEquals(b.readBytes())

    private fun withinLimits(
        target: File,
        size: Long,
    ): Boolean {
        if (size > UserConfigFiles.MAX_FILE_BYTES) return false
        val dir = storage.userConfigDir
        val files = UserConfigFiles.files(dir)
        val own = UserConfigFiles.relativePath(dir, target)
        val others = files.filter { UserConfigFiles.relativePath(dir, it) != own }
        if (others.size >= UserConfigFiles.MAX_FILES) return false
        return others.sumOf { it.length() } + size <= UserConfigFiles.MAX_TOTAL_BYTES
    }

    private fun notifyEdited(parentId: String = EDIT) {
        val context = context ?: return
        context.contentResolver.notifyChange(
            DocumentsContract.buildChildDocumentsUri(authority(context), parentId),
            null,
        )
        if (parentId != EDIT) {
            context.contentResolver.notifyChange(
                DocumentsContract.buildChildDocumentsUri(authority(context), EDIT),
                null,
            )
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
            RimeConfigPaths.Area.EDIT ->
                if (path.segments.isEmpty()) {
                    Node(
                        EDIT,
                        context.getString(R.string.rime_config_folder_edit),
                        storage.userConfigDir,
                        true,
                        creatable = true,
                    )
                } else {
                    val file =
                        RimeConfigPaths.inside(storage.userConfigDir, path.segments)
                            ?: throw FileNotFoundException("no document $documentId")
                    when {
                        file.isDirectory ->
                            Node(
                                documentId,
                                file.name,
                                file,
                                true,
                                creatable = UserConfigFiles.isScriptFolder(path.segments.joinToString("/")),
                            )
                        file.isFile && UserConfigFiles.isAllowedName(file.name) ->
                            Node(documentId, file.name, file, false, writable = true)
                        else -> throw FileNotFoundException("no document $documentId")
                    }
                }
            RimeConfigPaths.Area.ACTIVE -> {
                val sourceId = activeSourceId()
                if (path.segments.isEmpty()) {
                    Node(ACTIVE, activeTitle(sourceId), sourceId?.let(storage::sourceDir), true)
                } else {
                    fileNode(documentId, sourceId?.let(storage::sourceDir), path.segments)
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
                            fileNode(documentId, dir, path.segments.drop(1))
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
    ): Node {
        val file =
            base?.let { RimeConfigPaths.inside(it, segments) } ?: throw FileNotFoundException("no document $documentId")
        if (!file.exists() ||
            RimeConfigPaths.hidden(segments.last())
        ) {
            throw FileNotFoundException("no document $documentId")
        }
        return packageNode(documentId, file, segments)
    }

    private fun packageNode(
        documentId: String,
        file: File,
        segments: List<String>,
    ): Node {
        if (file.isDirectory) return Node(documentId, file.name, file, true)
        val path = segments.joinToString("/")
        if (!UserConfigFiles.isAllowedPath(path)) return Node(documentId, file.name, file, false)
        return Node(
            documentId,
            file.name,
            overrides.overrideFile(path) ?: file,
            false,
            packageFile = file,
            overridePath = path,
        )
    }

    /** The files and the folders with files of the user's folder [dir] (`lua/` is shown empty: that is where scripts go). */
    private fun editChildren(
        parentId: String,
        dir: File?,
    ): List<Node> {
        val parentPath = parentId.removePrefix(EDIT).removePrefix("/")

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
            EDIT -> editChildren(EDIT, storage.userConfigDir)
            SOURCES ->
                SourceCatalog(storage).list().map {
                    Node(SOURCES + "/" + it.sourceId, sourceName(it.sourceId), storage.sourceDir(it.sourceId), true)
                }
            else -> {
                val dir = parent.file?.takeIf { it.isDirectory } ?: return emptyList()
                if (parent.id.startsWith("$EDIT/")) return editChildren(parent.id, dir)
                // The path of a child within its source: below `active/`, or below `sources/<id>/`.
                val inSource = parent.id.split('/').drop(if (parent.id.startsWith("$SOURCES/")) 2 else 1)
                dir
                    .listFiles()
                    .orEmpty()
                    .filter { !RimeConfigPaths.hidden(it.name) && !Files.isSymbolicLink(it.toPath()) }
                    .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name })
                    .map { packageNode(parent.id + "/" + it.name, it, inSource + it.name) }
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

    private fun sourceName(sourceId: String): String {
        val context = requireNotNull(context)
        val manifest = SourceCatalog(storage).read(sourceId)
        return when {
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

        /** The writable folder as a document. */
        fun editFolderUri(context: Context): Uri = DocumentsContract.buildDocumentUri(authority(context), EDIT)

        /** Configuration and source text opens in any text editor; everything else by its extension. */
        private fun mimeTypeOf(name: String): String {
            val extension = name.substringAfterLast('.', "").lowercase()
            if (extension in TEXT_EXTENSIONS) return "text/plain"
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        }

        private val TEXT_EXTENSIONS = setOf("yaml", "yml", "txt", "lua", "json", "md", "csv", "tsv")
    }
}
