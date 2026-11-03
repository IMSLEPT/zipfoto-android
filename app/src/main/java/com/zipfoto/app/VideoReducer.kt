package com.zipfoto.app

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Riduce il peso di un video (Leggera / Media / Alta) ricodificandolo in H.264 con il codificatore hardware
 * del telefono (Media3 Transformer): lato corto e bitrate massimi dipendono dalla modalità.
 * Un video più piccolo o già più leggero del livello scelto non viene toccato (né ingrandito).
 * Qualsiasi errore restituisce [Outcome.Failed]: il chiamante tiene l'originale e lo segnala.
 */
object VideoReducer {

    sealed interface Outcome {
        /** Video ricodificato, scritto in [file]. */
        class Reduced(val file: File, val bytes: Long) : Outcome

        /** Già abbastanza leggero per il livello scelto: non serve ricodificarlo. */
        object AlreadyLight : Outcome

        class Failed(val reason: String) : Outcome
    }

    /** Dimensioni "in piedi" (rotazione già applicata), bitrate e durata del video sorgente. */
    class Probe(val width: Int, val height: Int, val bitrate: Int, val durationMs: Long)

    fun probe(ctx: Context, uri: Uri): Probe? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            fun num(key: Int): Long = r.extractMetadata(key)?.toLongOrNull() ?: 0L
            var w = num(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt()
            var h = num(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt()
            val rot = num(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION).toInt()
            if (rot == 90 || rot == 270) {
                val t = w
                w = h
                h = t
            }
            if (w <= 0 || h <= 0) null
            else Probe(w, h, num(MediaMetadataRetriever.METADATA_KEY_BITRATE).toInt(), num(MediaMetadataRetriever.METADATA_KEY_DURATION))
        } catch (_: Exception) {
            null
        } finally {
            try {
                r.release()
            } catch (_: Exception) {
            }
        }
    }

    /** Dimensioni e bitrate di uscita. Con [resize] falso le dimensioni non vengono toccate. */
    class Plan(val width: Int, val height: Int, val bitrate: Int, val resize: Boolean)

    /** @return il piano di riduzione, oppure null se il video è già abbastanza leggero. */
    fun plan(probe: Probe?, mode: ReduceMode): Plan? {
        val wanted = mode.videoBitrate
        if (probe == null) {
            // Dimensioni ignote: si limita solo il bitrate, senza rischiare di ingrandire.
            return Plan(0, 0, wanted, false)
        }
        val shortSide = min(probe.width, probe.height)
        val resize = shortSide > (mode.videoShortSide * 1.05).toInt()
        if (!resize && probe.bitrate > 0 && probe.bitrate <= (wanted * 1.1).toInt()) return null
        val w: Int
        val h: Int
        if (resize) {
            val f = mode.videoShortSide.toDouble() / shortSide
            w = even(probe.width * f)
            h = even(probe.height * f)
        } else {
            w = probe.width
            h = probe.height
        }
        // Il bitrate non supera mai quello del sorgente (ricodificare a più non ha senso) né scende sotto un minimo.
        val bps = if (probe.bitrate > 0) min(wanted, (probe.bitrate * 0.85).toInt()) else wanted
        return Plan(w, h, max(bps, 250_000), resize)
    }

    private fun even(v: Double): Int = max(2, (v.roundToInt() / 2) * 2)

    /**
     * Ricodifica [uri] in [out]. Va chiamata da una coroutine: lavora sul thread principale (come richiede
     * Transformer) e si interrompe, liberando il codec, se la coroutine viene annullata.
     *
     * @param onProgress avanzamento da 0 a 1
     */
    suspend fun reduce(ctx: Context, uri: Uri, mode: ReduceMode, out: File, onProgress: (Float) -> Unit): Outcome {
        val probe = probe(ctx, uri)
        val plan = plan(probe, mode) ?: return Outcome.AlreadyLight
        out.parentFile?.mkdirs()
        if (out.exists()) out.delete()

        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Outcome> { cont ->
                val handler = Handler(Looper.getMainLooper())
                val holder = ProgressHolder()
                var transformer: Transformer? = null
                var finished = false

                val poll = object : Runnable {
                    override fun run() {
                        val t = transformer ?: return
                        if (finished) return
                        if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress((holder.progress / 100f).coerceIn(0f, 1f))
                        }
                        handler.postDelayed(this, 300)
                    }
                }

                fun finish(result: Outcome) {
                    if (finished) return
                    finished = true
                    handler.removeCallbacks(poll)
                    if (cont.isActive) cont.resume(result)
                }

                try {
                    val encoder = DefaultEncoderFactory.Builder(ctx)
                        .setRequestedVideoEncoderSettings(
                            VideoEncoderSettings.Builder().setBitrate(plan.bitrate).build()
                        )
                        .build()
                    val t = Transformer.Builder(ctx)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .setEncoderFactory(encoder)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                finish(Outcome.Reduced(out, out.length()))
                            }

                            override fun onError(
                                composition: Composition,
                                exportResult: ExportResult,
                                exportException: ExportException
                            ) {
                                finish(Outcome.Failed(exportException.message ?: "errore ${exportException.errorCode}"))
                            }
                        })
                        .build()
                    transformer = t

                    val videoEffects = if (plan.resize) {
                        listOf(Presentation.createForWidthAndHeight(plan.width, plan.height, Presentation.LAYOUT_SCALE_TO_FIT))
                    } else {
                        emptyList()
                    }
                    val edited = EditedMediaItem.Builder(Media3Item.fromUri(uri))
                        .setEffects(Effects(emptyList(), videoEffects))
                        .build()

                    cont.invokeOnCancellation {
                        handler.post {
                            finished = true
                            handler.removeCallbacks(poll)
                            try {
                                transformer?.cancel()
                            } catch (_: Exception) {
                            }
                        }
                    }
                    t.start(edited, out.absolutePath)
                    handler.post(poll)
                } catch (e: Exception) {
                    finish(Outcome.Failed(e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }
}
