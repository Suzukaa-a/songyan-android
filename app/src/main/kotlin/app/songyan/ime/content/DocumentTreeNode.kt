package app.songyan.ime.content

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.songyan.ime.learning.PickedNode
import java.io.IOException
import java.io.InputStream

/** A folder the user picked with the system's open-tree picker, read through [DocumentsContract]. */
internal class DocumentTreeNode private constructor(
    private val context: Context,
    private val treeUri: Uri,
    private val documentId: String,
    override val name: String,
    override val isDirectory: Boolean,
) : PickedNode {
    override fun children(): List<PickedNode> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val columns =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
        val cursor = context.contentResolver.query(children, columns, null, null, null) ?: return emptyList()
        return cursor.use {
            val nodes = ArrayList<PickedNode>()
            while (it.moveToNext()) {
                val id = it.getString(0) ?: continue
                val name = it.getString(1) ?: continue
                val directory = it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                nodes += DocumentTreeNode(context, treeUri, id, name, directory)
            }
            nodes
        }
    }

    override fun open(): InputStream {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        return context.contentResolver.openInputStream(uri) ?: throw IOException("no stream")
    }

    companion object {
        fun root(
            context: Context,
            treeUri: Uri,
        ): PickedNode =
            DocumentTreeNode(
                context.applicationContext,
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
                name = "",
                isDirectory = true,
            )
    }
}
