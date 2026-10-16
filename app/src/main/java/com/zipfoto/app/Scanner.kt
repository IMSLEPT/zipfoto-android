package com.zipfoto.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** Legge nome, dimensione e data delle foto scelte, o esplora ricorsivamente una cartella. */
object Scanner {
    private val badChars = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")

    private val imageExtensions = setOf(
        "jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "bmp", "avif", "tif", "tiff",
        "dng", "raw", "arw", "cr2", "cr3", "nef", "orf", "rw2"
    )

    fun cleanName(raw: String): String {
        val s = raw.replace(badChars, "_").trim()
        return if (s.isEmpty() || s == "." || s == "..") "foto" else s
    }

    suspend fun describe(ctx: Context, uris: List<Uri>): List<PhotoItem> =
        withContext(Dispatchers.IO) {
            val out = ArrayList<PhotoItem>(uris.size)
            for (u in uris) {
                out.add(describeOne(ctx, u))
            }
            out
        }

    private fun describeOne(ctx: Context, uri: Uri): PhotoItem {
        var name: String? = null
        var size = -1L
        var modified = 0L
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val iName = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val iSize = c.getColumnIndex(OpenableColumns.SIZE)
                    val iMod = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    if (iName >= 0 && !c.isNull(iName)) name = c.getString(iName)
                    if (iSize >= 0 && !c.isNull(iSize)) size = c.getLong(iSize)
                    if (iMod >= 0 && !c.isNull(iMod)) modified = c.getLong(iMod)
                }
            }
        } catch (_: Exception) {
            // provider che non risponde alla query: useremo valori di ripiego
        }
        return PhotoItem(uri, cleanName(name ?: uri.lastPathSegment ?: "foto"), size, modified, "")
    }

    suspend fun scanTree(ctx: Context, treeUri: Uri): List<PhotoItem> =
        withContext(Dispatchers.IO) {
            val out = ArrayList<PhotoItem>()
            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
            walk(ctx, treeUri, rootId, "", out)
            out
        }

    private fun walk(ctx: Context, tree: Uri, docId: String, path: String, out: MutableList<PhotoItem>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        ctx.contentResolver.query(children, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val rawName = c.getString(1) ?: continue
                val mime = c.getString(2) ?: ""
                val size = if (c.isNull(3)) -1L else c.getLong(3)
                val modified = if (c.isNull(4)) 0L else c.getLong(4)
                val name = cleanName(rawName)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walk(ctx, tree, id, if (path.isEmpty()) name else "$path/$name", out)
                } else if (isImage(mime, name)) {
                    out.add(
                        PhotoItem(
                            DocumentsContract.buildDocumentUriUsingTree(tree, id),
                            name, size, modified, path
                        )
                    )
                }
            }
        }
    }

    private fun isImage(mime: String, name: String): Boolean {
        if (mime.startsWith("image/")) return true
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext in imageExtensions
    }
}
