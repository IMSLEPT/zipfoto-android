package com.zipfoto.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.zipfoto.app.zip.MediaSniffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** Legge nome, dimensione e data di foto e video scelti, o esplora ricorsivamente una cartella. */
object Scanner {
    private val badChars = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")

    private val imageExtensions = setOf(
        "jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "bmp", "avif", "tif", "tiff",
        "dng", "raw", "arw", "cr2", "cr3", "nef", "orf", "rw2"
    )

    private val videoExtensions = setOf(
        "mp4", "mov", "m4v", "3gp", "3gpp", "3g2", "mkv", "webm", "avi", "wmv", "flv",
        "mpg", "mpeg", "mts", "m2ts", "mxf"
    )

    fun cleanName(raw: String): String {
        val s = raw.replace(badChars, "_").trim()
        return if (s.isEmpty() || s == "." || s == "..") "file" else s
    }

    private fun extOf(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    fun isVideo(mime: String?, name: String): Boolean =
        (mime != null && mime.startsWith("video/")) || extOf(name) in videoExtensions

    private fun isImage(mime: String?, name: String): Boolean =
        (mime != null && mime.startsWith("image/")) || extOf(name) in imageExtensions

    fun isMedia(mime: String?, name: String): Boolean = isImage(mime, name) || isVideo(mime, name)

    /** true se il nome ha già un'estensione di foto o video conosciuta. */
    fun hasMediaExtension(name: String): Boolean = extOf(name).let { it in imageExtensions || it in videoExtensions }

    /** Tipo MIME vero e proprio (non vuoto e non generico). */
    private fun usableMime(mime: String?): String? =
        mime?.takeIf { it.isNotBlank() && it != "application/octet-stream" && it != "*/*" && it.contains('/') }

    /**
     * Aggiunge l'estensione giusta se il nome non ne ha una da foto/video: senza estensione un video
     * nello ZIP non si aprirebbe con un doppio tocco. [mime] può essere quello dichiarato o quello riconosciuto dai byte.
     */
    fun withMediaExtension(name: String, mime: String?): String {
        if (hasMediaExtension(name)) return name
        val ext = MediaSniffer.extensionFor(mime)
            ?: usableMime(mime)?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            ?: return name
        return if (name.endsWith(".")) name + ext else "$name.$ext"
    }

    /** Primi byte del file, per riconoscerne il formato. Null se non si riesce a leggere. */
    fun readHead(ctx: Context, uri: Uri): ByteArray? = try {
        ctx.contentResolver.openInputStream(uri)?.use { s ->
            val buf = ByteArray(MediaSniffer.HEAD_SIZE)
            var n = 0
            var zeros = 0
            while (n < buf.size && zeros < 3) {
                val r = s.read(buf, n, buf.size - n)
                if (r < 0) break
                if (r == 0) zeros++ else n += r
            }
            buf.copyOf(n)
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Descrive gli URI scelti. Con [onlyMedia] scarta i file che sono chiaramente altro
     * (utile per i "Condividi" con tipo generico: foto e video insieme arrivano come tipo generico).
     */
    suspend fun describe(ctx: Context, uris: List<Uri>, onlyMedia: Boolean = false): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val out = ArrayList<MediaItem>(uris.size)
            for (u in uris) {
                val item = describeOne(ctx, u, onlyMedia) ?: continue
                out.add(item)
            }
            out
        }

    private fun describeOne(ctx: Context, uri: Uri, onlyMedia: Boolean): MediaItem? {
        var name: String? = null
        var size = -1L
        var modified = 0L
        var columnMime: String? = null
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val iName = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val iSize = c.getColumnIndex(OpenableColumns.SIZE)
                    val iMod = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    val iMsMod = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                    // "mime_type" è lo stesso nome di colonna per MediaStore e per i provider di documenti
                    val iMime = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                    if (iName >= 0 && !c.isNull(iName)) name = c.getString(iName)
                    if (iSize >= 0 && !c.isNull(iSize)) size = c.getLong(iSize)
                    if (iMod >= 0 && !c.isNull(iMod)) modified = c.getLong(iMod)
                    // Foto condivise dalla Galleria: la data sta nelle colonne di MediaStore (in secondi).
                    if (modified <= 0L && iMsMod >= 0 && !c.isNull(iMsMod)) modified = c.getLong(iMsMod) * 1000L
                    if (iMime >= 0 && !c.isNull(iMime)) columnMime = c.getString(iMime)
                }
            }
        } catch (_: Exception) {
            // provider che non risponde alla query: useremo valori di ripiego
        }
        var clean = cleanName(name ?: uri.lastPathSegment ?: "file")
        val declared = try {
            ctx.contentResolver.getType(uri)
        } catch (_: Exception) {
            null
        }
        // Il tipo dichiarato vale solo se non è generico; altrimenti si prova la colonna del provider.
        var mime: String? = usableMime(declared) ?: usableMime(columnMime) ?: declared ?: columnMime

        // Né tipo né estensione dicono cosa sia: si guardano i primi byte (succede con alcuni provider cloud).
        if (!isMedia(mime, clean)) {
            val head = readHead(ctx, uri)
            if (head != null && head.isNotEmpty()) {
                val sniffed = MediaSniffer.sniff(head, head.size)
                if (sniffed != null) mime = sniffed
            }
        }
        if (onlyMedia && !isMedia(mime, clean)) {
            // Tipo noto e non multimediale: non è una foto né un video. Tipo ignoto o generico: lo si tiene.
            val generic = mime == null || mime == "application/octet-stream"
            if (!generic) return null
        }
        if (isMedia(mime, clean) && !hasMediaExtension(clean)) clean = withMediaExtension(clean, mime)
        return MediaItem(uri, clean, size, modified, "", isVideo(mime, clean))
    }

    suspend fun scanTree(ctx: Context, treeUri: Uri): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val out = ArrayList<MediaItem>()
            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
            walk(ctx, treeUri, rootId, "", out)
            out
        }

    private fun walk(ctx: Context, tree: Uri, docId: String, path: String, out: MutableList<MediaItem>) {
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
                } else if (isMedia(mime, name)) {
                    out.add(
                        MediaItem(
                            DocumentsContract.buildDocumentUriUsingTree(tree, id),
                            name, size, modified, path, isVideo(mime, name)
                        )
                    )
                }
            }
        }
    }
}
