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
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The Rime configuration folder in the system Files app and document picker
 * (https://developer.android.com/guide/topics/providers/create-document-provider): one root,
 * 「松烟 Rime 配置」, with the user's own files ([UserConfigFiles], writable) and read-only views
 * of the source in use and of every stored source, so the user can read the schema they patch.
 *
 * Only the system's document UI talks to a documents provider directly (the manifest guards it
 * with `MANAGE_DOCUMENTS`); other apps get single documents or the tree through grants the user
 * makes in the picker. Every path a document id names is checked to stay inside its folder, and
 * the writable folder takes only flat `.yaml` / `.txt` names. A write goes to a temporary file
 * that replaces the document when the writer closes it, and only within the size limits, so a
 * runaway writer never leaves a half-written or oversized configuration behind. Nothing here
 * reaches the running keyboard: the settings screen applies the changes.
 */
class RimeConfigProvider : DocumentsProvider() {
    private val storage: AppStorage by lazy { AppStorage(requireNotNull(context).filesDir) }
    private val closeHandler: Handler by lazy {
        Handler(HandlerThread("rime-config-writes").apply { start() }.looper)
    }

    // Runs on the main thread: nothing here may touch the disk.
    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
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
        if (!node.writable) throw SecurityException("read-only: $documentId")
        return openForWriting(documentId, file, flags)
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        // File managers that ignore the folder flags offer to paste anywhere and show this message as is.
        if (parentDocumentId !=
            EDIT
        ) {
            throw UnsupportedOperationException(message(R.string.rime_config_refused_read_only))
        }
        if (mimeType == Document.MIME_TYPE_DIR) {
            throw UnsupportedOperationException(message(R.string.rime_config_refused_folder))
        }
        val name =
            RimeConfigPaths.newFileName(displayName, mimeType)
                ?: throw IllegalArgumentException(message(R.string.rime_config_refused_type, displayName))
        val dir = storage.userConfigDir.also { it.mkdirs() }
        if (UserConfigFiles.files(dir).size >= UserConfigFiles.MAX_FILES) {
            throw IllegalStateException(message(R.string.rime_config_error_many))
        }
        val unique =
            RimeConfigPaths.unique(name) { File(dir, it).exists() }
                ?: throw IllegalStateException("no free name for $name")
        if (!File(dir, unique).createNewFile()) throw IOException("cannot create $unique")
        notifyEdited()
        return EDIT + "/" + unique
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
        if (!UserConfigFiles.isAllowedName(
                displayName,
            )
        ) {
            throw IllegalArgumentException(message(R.string.rime_config_refused_type, displayName))
        }
        if (displayName == file.name) return documentId
        val target = File(file.parentFile, displayName)
        if (target.exists()) throw IllegalStateException("$displayName exists")
        if (!file.renameTo(target)) throw IOException("cannot rename $documentId")
        notifyEdited()
        return EDIT + "/" + displayName
    }

    override fun deleteDocument(documentId: String) {
        val node = resolve(documentId)
        val file = node.file
        if (!node.writable ||
            file == null
        ) {
            throw UnsupportedOperationException(message(R.string.rime_config_refused_read_only))
        }
        if (!file.delete() && file.exists()) throw IOException("cannot delete $documentId")
        notifyEdited()
    }

    private fun message(
        id: Int,
        vararg args: Any,
    ): String = requireNotNull(context).getString(id, *args)

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
    ): ParcelFileDescriptor {
        val temp = File(storage.configRoot, WRITE_PREFIX + System.nanoTime())
        temp.parentFile?.mkdirs()
        if (flags and ParcelFileDescriptor.MODE_TRUNCATE == 0 && target.isFile) {
            Files.copy(target.toPath(), temp.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } else {
            temp.writeBytes(ByteArray(0))
        }
        return ParcelFileDescriptor.open(temp, flags or ParcelFileDescriptor.MODE_CREATE, closeHandler) { error ->
            try {
                if (error == null && withinLimits(target, temp.length())) {
                    Files.move(
                        temp.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
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

    private fun withinLimits(
        target: File,
        size: Long,
    ): Boolean {
        if (size > UserConfigFiles.MAX_FILE_BYTES) return false
        val others =
            UserConfigFiles
                .files(
                    storage.userConfigDir,
                ).filter { it.name != target.name }
                .sumOf { it.length() }
        return others + size <= UserConfigFiles.MAX_TOTAL_BYTES
    }

    private fun notifyEdited() {
        val context = context ?: return
        context.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(authority(context), EDIT), null)
    }

    /** One document: a virtual folder ([file] null), or a file or folder on disk. */
    private class Node(
        val id: String,
        val displayName: String,
        val file: File?,
        val directory: Boolean,
        val writable: Boolean = false,
        val creatable: Boolean = false,
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
                    val name = path.segments.single()
                    val file = File(storage.userConfigDir, name)
                    if (!file.isFile) throw FileNotFoundException("no document $documentId")
                    Node(documentId, name, file, false, writable = true)
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

    /** A file or folder inside a read-only source tree; never outside it. */
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
        return Node(documentId, file.name, file, file.isDirectory)
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
            EDIT ->
                UserConfigFiles.files(storage.userConfigDir).map {
                    Node(EDIT + "/" + it.name, it.name, it, false, writable = true)
                }
            SOURCES ->
                SourceCatalog(storage).list().map {
                    Node(SOURCES + "/" + it.sourceId, sourceName(it.sourceId), storage.sourceDir(it.sourceId), true)
                }
            else -> {
                val dir = parent.file?.takeIf { it.isDirectory } ?: return emptyList()
                dir
                    .listFiles()
                    .orEmpty()
                    .filter { !RimeConfigPaths.hidden(it.name) && !Files.isSymbolicLink(it.toPath()) }
                    .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name })
                    .map { Node(parent.id + "/" + it.name, it.name, it, it.isDirectory) }
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
        private const val WRITE_PREFIX = ".write-"

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
