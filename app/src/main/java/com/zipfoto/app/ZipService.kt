package com.zipfoto.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.zipfoto.app.zip.MediaSniffer
import com.zipfoto.app.zip.StreamingZipWriter
import com.zipfoto.app.zip.ZipNames
import com.zipfoto.app.zip.ZipVerifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.PushbackInputStream
import java.nio.ByteBuffer

/**
 * Crea lo ZIP in un servizio in primo piano: continua anche con schermo spento o app in background.
 * I file sono copiati byte per byte (nessuna ricompressione): i video, sempre.
 */
class ZipService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            worker?.cancel()
            return START_NOT_STICKY
        }
        if (worker?.isActive == true) return START_NOT_STICKY

        val out = Session.pendingOutput
        val items = Session.items.value
        if (out == null || items.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, progressNotification(0, items.size, 0),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } catch (e: Exception) {
            Session.setJob(JobState.Failed("Impossibile avviare il servizio: ${e.message ?: e.javaClass.simpleName}"))
            stopSelf()
            return START_NOT_STICKY
        }
        acquireWakeLock()
        worker = scope.launch { runZip(items, out) }
        return START_NOT_STICKY
    }

    private suspend fun runZip(items: List<MediaItem>, out: Uri) {
        val job = currentCoroutineContext()[Job]!!
        val mode = Session.reduceMode.value
        // DEFLATE extra serve solo alle foto originali: i JPEG ridotti e i video non si comprimono ulteriormente.
        val extraLevel = if (!mode.reduces && Session.extraCompression.value) 6 else 0
        val t0 = SystemClock.elapsedRealtime()
        val total = items.size
        val bytesTotal = items.sumOf { if (it.size > 0) it.size else 0L }

        var doneBytes = 0L
        var doneCount = 0
        val problems = ArrayList<Problem>()
        val notes = ArrayList<String>()
        val videoLines = ArrayList<VideoLine>()
        val videoNames = HashSet<String>()
        var reducedCount = 0
        var keptOriginal = 0
        var current = ""
        var outBytes = 0L
        var success = false
        var written = 0
        var infos: List<StreamingZipWriter.EntryInfo> = emptyList()

        var lastPublish = 0L
        var lastNotify = 0L
        var speed = 0.0
        var speedT = t0
        var speedB = 0L

        fun publish(force: Boolean) {
            val now = SystemClock.elapsedRealtime()
            if (!force && now - lastPublish < 250) return
            lastPublish = now
            val dt = now - speedT
            if (dt >= 1000) {
                val inst = (doneBytes - speedB) * 1000.0 / dt
                speed = if (speed == 0.0) inst else speed * 0.6 + inst * 0.4
                speedT = now
                speedB = doneBytes
            }
            val remaining = if (bytesTotal > doneBytes) bytesTotal - doneBytes else 0L
            val eta = if (speed > 1.0) (remaining / speed).toLong() else -1L
            Session.setJob(
                JobState.Running(doneCount, total, doneBytes, bytesTotal, current, speed.toLong(), eta, problems.size)
            )
            if (now - lastNotify >= 1000) {
                lastNotify = now
                val pct = if (bytesTotal > 0) (doneBytes * 100 / bytesTotal).toInt() else doneCount * 100 / total
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIF_ID, progressNotification(doneCount, total, pct.coerceIn(0, 100)))
            }
        }

        try {
            val pfd = contentResolver.openFileDescriptor(out, "wt")
                ?: throw IOException("Impossibile scrivere nella destinazione scelta")
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).buffered(1 shl 20).use { os ->
                val zip = StreamingZipWriter(os, 0)
                val names = ZipNames()
                val listener = StreamingZipWriter.Listener { n ->
                    doneBytes += n
                    job.ensureActive()
                    publish(false)
                }
                val noProgress = StreamingZipWriter.Listener { }
                for (item in items) {
                    job.ensureActive()
                    current = item.name
                    val baseName = if (item.folder.isEmpty()) item.name else item.folder + "/" + item.name

                    // Foto in modalità ridotta: JPEG più leggero. I video non si toccano mai.
                    if (mode.reduces && !item.video) {
                        val bytes = reduceOne(item, mode)
                        // Se la versione ridotta non è più leggera dell'originale, si tiene l'originale.
                        if (bytes != null && (item.size <= 0 || bytes.size < item.size)) {
                            val entry = names.unique(jpgName(baseName))
                            val read = ByteArrayInputStream(bytes).use {
                                zip.addEntry(entry, item.modified, it, noProgress)
                            }
                            doneBytes += if (item.size > 0) item.size else read
                            reducedCount++
                            written++
                            doneCount++
                            publish(false)
                            continue
                        }
                    }

                    val opened = openOriginal(item.uri)
                    val source = opened.opened
                    if (source == null) {
                        problems += Problem(item.name, opened.reason)
                        doneCount++
                        publish(true)
                        continue
                    }

                    // Prima lettura: serve a non scrivere voci vuote e a riconoscere il formato dai byte.
                    val stream = PushbackInputStream(source.stream, MediaSniffer.HEAD_SIZE)
                    val head = ByteArray(MediaSniffer.HEAD_SIZE)
                    var headLen = 0
                    try {
                        var zeros = 0
                        while (headLen < head.size && zeros < 3) {
                            val r = stream.read(head, headLen, head.size - headLen)
                            if (r < 0) break
                            if (r == 0) zeros++ else headLen += r
                        }
                    } catch (e: IOException) {
                        runCatching { stream.close() }
                        problems += Problem(item.name, "lettura interrotta: ${reasonOf(e)}")
                        doneCount++
                        publish(true)
                        continue
                    }
                    if (headLen == 0) {
                        runCatching { stream.close() }
                        problems += Problem(
                            item.name,
                            "il file risulta vuoto: l'app che lo contiene non ha restituito alcun dato"
                        )
                        doneCount++
                        publish(true)
                        continue
                    }
                    stream.unread(head, 0, headLen)

                    // I byte contano più di nome e tipo dichiarato: un video senza estensione resta un video.
                    val sniffed = MediaSniffer.sniff(head, headLen)
                    val isVideo = if (sniffed != null) MediaSniffer.isVideoMime(sniffed) else item.video
                    val entryBase = if (sniffed != null) Scanner.withMediaExtension(baseName, sniffed) else baseName
                    val entryName = names.unique(entryBase)

                    if (mode.reduces && !isVideo) keptOriginal++
                    // Voce Zip64 solo quando serve: dimensione vera >= 2 GB, oppure ignota per un video.
                    val realSize = if (source.length > 0) source.length else item.size
                    val zip64 = if (realSize > 0) StreamingZipWriter.needsZip64Entry(realSize) else isVideo
                    val level = if (isVideo) 0 else extraLevel
                    val read = try {
                        stream.use { zip.addEntry(entryName, item.modified, it, listener, level, zip64) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: IOException) {
                        throw IOException("Interrotto su «${item.name}»: ${reasonOf(e)}", e)
                    }
                    if (realSize > 0 && read != realSize && (isVideo || read < realSize / 2)) {
                        notes += "«${item.name}»: letti ${fmtSize(read)} invece di ${fmtSize(realSize)} dichiarati"
                    }
                    if (isVideo) {
                        videoLines += VideoLine(entryName, read)
                        videoNames += entryName
                    }
                    written++
                    doneCount++
                    publish(false)
                }
                if (zip.entryCount() == 0) {
                    throw IOException(
                        if (problems.isEmpty()) "Nessun file da archiviare."
                        else "Nessun file è stato archiviato. " + problems.first().let { "«${it.name}»: ${it.reason}" }
                    )
                }
                zip.finish()
                outBytes = zip.bytesWritten()
                infos = zip.entryInfos()
            }

            // Rilettura finale: lo ZIP appena scritto viene riaperto e confrontato con quello che si è scritto.
            current = "Controllo finale del file ZIP…"
            publish(true)
            val verification = verifyOutput(out, infos, outBytes, videoNames, job)
            job.ensureActive()

            success = true
            Session.setJob(
                JobState.Done(
                    out, written, videoLines.size, doneBytes, outBytes,
                    (SystemClock.elapsedRealtime() - t0) / 1000, problems.size,
                    mode, reducedCount, keptOriginal,
                    verification, videoLines.toList(), problems.toList(), notes.toList()
                )
            )
        } catch (e: CancellationException) {
            deleteQuietly(out)
            Session.setJob(JobState.Idle)
            throw e
        } catch (e: Throwable) {
            deleteQuietly(out)
            Session.setJob(JobState.Failed(describeError(e)))
        } finally {
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (success) {
                val done = Session.job.value as? JobState.Done
                if (done != null) showDoneNotification(done)
            }
            stopSelf()
        }
    }

    /**
     * Riapre il file ZIP scritto e lo controlla con [ZipVerifier]. Se la destinazione non si può rileggere
     * (file non ad accesso casuale, come alcuni provider cloud) il controllo viene dichiarato "non eseguito":
     * mai spacciato per riuscito.
     */
    private fun verifyOutput(
        out: Uri,
        infos: List<StreamingZipWriter.EntryInfo>,
        expectedBytes: Long,
        videoNames: Set<String>,
        job: Job
    ): Verification {
        val unreadable = Verification(
            VerifyStatus.SKIPPED, infos.size, 0,
            "la destinazione non permette di rileggere il file"
        )
        val pfd: ParcelFileDescriptor? = try {
            contentResolver.openFileDescriptor(out, "r")
        } catch (e: Exception) {
            null
        }
        if (pfd == null) return unreadable
        val size = pfd.statSize
        if (size < 0) {
            // non è un file ad accesso casuale (es. flusso di un provider cloud)
            pfd.close()
            return unreadable
        }
        if (size != expectedBytes) {
            pfd.close()
            return Verification(
                VerifyStatus.FAILED, infos.size, 0,
                "scritti $expectedBytes byte, ma il file ne contiene $size"
            )
        }
        // AutoCloseInputStream chiude il descrittore al termine: nessuna doppia chiusura.
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { fis ->
            val channel = fis.channel
            val src = object : ZipVerifier.Source {
                override fun length(): Long = channel.size()
                override fun readAt(position: Long, buf: ByteArray, off: Int, len: Int): Int {
                    if (!job.isActive) throw InterruptedIOException("annullato")
                    return channel.read(ByteBuffer.wrap(buf, off, len), position)
                }
            }
            val r = ZipVerifier.verify(src, infos, DEEP_VERIFY_BYTES, videoNames)
            when {
                !r.ok -> Verification(VerifyStatus.FAILED, r.entries, r.deepEntries, r.firstProblem())
                r.allDeep() -> Verification(VerifyStatus.FULL, r.entries, r.deepEntries)
                else -> Verification(VerifyStatus.STRUCTURE, r.entries, r.deepEntries)
            }
        }
    }

    /** Versione ridotta (JPEG) della foto, o null se non si può ridurre: si userà l'originale. */
    private fun reduceOne(item: MediaItem, mode: ReduceMode): ByteArray? {
        if (!ImageReducer.canReduce(item.name)) return null
        return ImageReducer.reduce({ openOriginal(item.uri).opened?.stream }, mode)
    }

    /** Nome della voce nello ZIP con estensione .jpg (la foto ridotta è sempre un JPEG). */
    private fun jpgName(path: String): String {
        val slash = path.lastIndexOf('/')
        val dot = path.lastIndexOf('.')
        if (dot > slash + 1) {
            val ext = path.substring(dot + 1).lowercase()
            if (ext == "jpg" || ext == "jpeg") return path
            return path.substring(0, dot) + ".jpg"
        }
        return "$path.jpg"
    }

    private class Opened(val stream: InputStream, val length: Long)

    private class OpenResult(val opened: Opened?, val reason: String)

    /**
     * Apre l'originale; se possibile chiede al sistema la versione non "redatta" e la lunghezza vera.
     * Se non riesce, restituisce il motivo in italiano (mai un fallimento muto).
     */
    private fun openOriginal(uri: Uri): OpenResult {
        val candidates = ArrayList<Uri>(2)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.authority == MediaStore.AUTHORITY) {
            try {
                candidates.add(MediaStore.setRequireOriginal(uri))
            } catch (_: Exception) {
            }
        }
        candidates.add(uri)
        var reason: String? = null
        for (u in candidates) {
            // 1) descrittore con lunghezza dichiarata (serve a scegliere Zip64 e a controllare la lettura)
            try {
                val afd = contentResolver.openAssetFileDescriptor(u, "r")
                if (afd != null) {
                    val len = afd.length
                    return OpenResult(Opened(afd.createInputStream(), len), "")
                }
            } catch (e: Exception) {
                reason = reasonOf(e)
            }
            // 2) flusso semplice, lunghezza ignota
            try {
                val stream = contentResolver.openInputStream(u)
                if (stream != null) return OpenResult(Opened(stream, -1L), "")
            } catch (e: Exception) {
                reason = reasonOf(e)
            }
        }
        return OpenResult(null, reason ?: "l'app che contiene il file non lo ha reso disponibile")
    }

    private fun reasonOf(e: Throwable): String = when {
        e is SecurityException -> "permesso di lettura perso: riseleziona il file"
        e is FileNotFoundException -> "file non trovato (spostato, eliminato o non ancora scaricato da Google Foto/Drive)"
        e.message?.contains("ENOSPC", ignoreCase = true) == true -> "spazio insufficiente nella destinazione"
        !e.message.isNullOrBlank() -> e.message!!
        else -> e.javaClass.simpleName
    }

    private fun fmtSize(b: Long): String = when {
        b >= 1L shl 30 -> String.format(java.util.Locale.getDefault(), "%.2f GB", b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> String.format(java.util.Locale.getDefault(), "%.1f MB", b / (1L shl 20).toDouble())
        else -> "${b / 1024} KB"
    }

    private fun deleteQuietly(uri: Uri) {
        try {
            DocumentsContract.deleteDocument(contentResolver, uri)
        } catch (_: Exception) {
        }
    }

    private fun describeError(e: Throwable): String {
        val msg = e.message.orEmpty()
        return when {
            msg.contains("ENOSPC", ignoreCase = true) || msg.contains("No space", ignoreCase = true) ->
                "Spazio insufficiente nella destinazione scelta."
            e is SecurityException ->
                "Permesso di lettura perso su alcuni file. Riseleziona foto e video e riprova."
            msg.isNotBlank() -> msg
            else -> e.javaClass.simpleName
        }
    }

    // ---- notifiche -------------------------------------------------------------------------

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(CHANNEL, "Creazione ZIP", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Avanzamento della creazione dell'archivio"
            nm.createNotificationChannel(ch)
        }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun progressNotification(done: Int, total: Int, pct: Int) =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Creo lo ZIP…")
            .setContentText("$done di $total file · $pct%")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct, false)
            .setContentIntent(openAppIntent())
            .addAction(
                0, "Annulla",
                PendingIntent.getService(
                    this, 1,
                    Intent(this, ZipService::class.java).setAction(ACTION_CANCEL),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    private fun showDoneNotification(d: JobState.Done) {
        val what = when {
            d.videos == 0 -> "${d.photos} foto"
            d.photos == 0 -> if (d.videos == 1) "1 video" else "${d.videos} video"
            else -> "${d.photos} foto e ${d.videos} video"
        }
        val check = when (d.verification.status) {
            VerifyStatus.FULL -> "controllato"
            VerifyStatus.STRUCTURE -> "struttura controllata"
            VerifyStatus.SKIPPED -> "controllo non possibile"
            VerifyStatus.FAILED -> "CONTROLLO FALLITO"
        }
        val warn = if (d.problems.isNotEmpty()) " · ${d.problems.size} saltati" else ""
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (d.clean) "ZIP pronto" else "ZIP pronto, da controllare")
            .setContentText(
                if (d.mode.reduces) "$what archiviati (foto ridotte, video originali) · $check$warn"
                else "$what archiviati in qualità originale · $check$warn"
            )
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        getSystemService(NotificationManager::class.java).notify(DONE_ID, n)
    }

    // ---- wake lock -------------------------------------------------------------------------

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Zippix:zip").apply {
            setReferenceCounted(false)
            acquire(4 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.zipfoto.app.START"
        const val ACTION_CANCEL = "com.zipfoto.app.CANCEL"
        private const val CHANNEL = "zip"
        private const val NOTIF_ID = 1
        private const val DONE_ID = 2

        /** Quanti byte (compressi) rileggere al massimo, a fine lavoro, per confrontare il contenuto con l'originale. */
        private const val DEEP_VERIFY_BYTES = 4L * 1024 * 1024 * 1024
    }
}
