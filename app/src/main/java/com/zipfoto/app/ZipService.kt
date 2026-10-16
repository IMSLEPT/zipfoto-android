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
import com.zipfoto.app.zip.StreamingZipWriter
import com.zipfoto.app.zip.ZipNames
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.InputStream

/**
 * Crea lo ZIP in un servizio in primo piano: continua anche con schermo spento o app in background.
 * I file sono copiati byte per byte (nessuna ricompressione dell'immagine).
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

    private suspend fun runZip(items: List<PhotoItem>, out: Uri) {
        val job = currentCoroutineContext()[Job]!!
        val level = if (Session.extraCompression.value) 6 else 0
        val t0 = SystemClock.elapsedRealtime()
        val total = items.size
        val bytesTotal = items.sumOf { if (it.size > 0) it.size else 0L }

        var doneBytes = 0L
        var doneCount = 0
        var skipped = 0
        var current = ""
        var outBytes = 0L
        var success = false

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
                JobState.Running(doneCount, total, doneBytes, bytesTotal, current, speed.toLong(), eta, skipped)
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
                ?: throw java.io.IOException("Impossibile scrivere nella destinazione scelta")
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).buffered(1 shl 20).use { os ->
                val zip = StreamingZipWriter(os, level)
                val names = ZipNames()
                val listener = StreamingZipWriter.Listener { n ->
                    doneBytes += n
                    job.ensureActive()
                    publish(false)
                }
                for (item in items) {
                    job.ensureActive()
                    current = item.name
                    val input = openOriginal(item.uri)
                    if (input == null) {
                        skipped++
                        doneCount++
                        publish(true)
                        continue
                    }
                    val entryName = if (item.folder.isEmpty()) item.name else item.folder + "/" + item.name
                    input.use { zip.addEntry(names.unique(entryName), item.modified, it, listener) }
                    doneCount++
                    publish(false)
                }
                if (zip.entryCount() == 0) {
                    throw java.io.IOException("Nessuna foto leggibile: controlla i permessi e riseleziona le foto.")
                }
                zip.finish()
                outBytes = zip.bytesWritten()
            }
            success = true
            Session.setJob(
                JobState.Done(
                    out, doneCount - skipped, doneBytes, outBytes,
                    (SystemClock.elapsedRealtime() - t0) / 1000, skipped
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
            if (success) showDoneNotification(doneCount - skipped)
            stopSelf()
        }
    }

    /** Apre l'originale; se possibile chiede al sistema la versione non "redatta". */
    private fun openOriginal(uri: Uri): InputStream? {
        val candidates = ArrayList<Uri>(2)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.authority == MediaStore.AUTHORITY) {
            try {
                candidates.add(MediaStore.setRequireOriginal(uri))
            } catch (_: Exception) {
            }
        }
        candidates.add(uri)
        for (u in candidates) {
            try {
                val stream = contentResolver.openInputStream(u)
                if (stream != null) return stream
            } catch (_: Exception) {
            }
        }
        return null
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
                "Permesso di lettura perso su alcune foto. Riseleziona le foto e riprova."
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
            .setContentText("$done di $total foto · $pct%")
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

    private fun showDoneNotification(files: Int) {
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("ZIP pronto")
            .setContentText("$files foto archiviate in qualità originale")
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        getSystemService(NotificationManager::class.java).notify(DONE_ID, n)
    }

    // ---- wake lock -------------------------------------------------------------------------

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ZipFoto:zip").apply {
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
    }
}
