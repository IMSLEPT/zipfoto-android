package com.zipfoto.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale

/**
 * Riduce una foto: lato lungo = [ReduceMode.longSide] (mai ingrandisce), poi JPEG alla qualità del livello.
 * L'orientamento EXIF viene applicato ai pixel (il JPEG risultante non porta metadati EXIF/GPS).
 */
object ImageReducer {

    /** Le GIF possono essere animate: si lasciano com'erano. */
    fun canReduce(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext != "gif"
    }

    /**
     * Restituisce i byte del JPEG ridotto, oppure null se l'immagine non è decodificabile
     * o la memoria non basta: il chiamante userà allora il file originale.
     * [open] deve restituire ogni volta un nuovo flusso sul file (viene aperto più volte).
     */
    fun reduce(open: () -> InputStream?, mode: ReduceMode): ByteArray? {
        if (!mode.reduces) return null

        val orientation = readOrientation(open)

        // 1) solo dimensioni
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = open() ?: return null
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // 2) decodifica già sottocampionata (potenza di 2) restando >= al lato lungo richiesto
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / (sample * 2) >= mode.longSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        var decoded: Bitmap? = null
        var work: Bitmap? = null
        try {
            val decodeStream = open() ?: return null
            decoded = decodeStream.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

            // 3) scala (solo se serve) e ruota secondo EXIF in un'unica operazione
            val w = decoded.width
            val h = decoded.height
            val scale = mode.longSide.toFloat() / maxOf(w, h)
            val matrix = orientationMatrix(orientation)
            val needScale = scale < 1f
            if (needScale) matrix.postScale(scale, scale)

            var bmp: Bitmap = decoded
            if (needScale || !matrix.isIdentity) {
                bmp = Bitmap.createBitmap(decoded, 0, 0, w, h, matrix, true)
                if (bmp !== decoded) work = bmp
            }

            // 4) il JPEG non ha trasparenza: PNG/WebP con alpha vanno appoggiati su bianco
            if (bmp.hasAlpha()) {
                val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(flat)
                canvas.drawColor(Color.WHITE)
                canvas.drawBitmap(bmp, 0f, 0f, null)
                if (work != null && work !== flat) work.recycle()
                bmp = flat
                work = flat
            }

            val out = ByteArrayOutputStream(512 * 1024)
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, mode.quality, out)) return null
            return out.toByteArray()
        } catch (_: OutOfMemoryError) {
            return null
        } catch (_: Exception) {
            return null
        } finally {
            work?.recycle()
            decoded?.recycle()
        }
    }

    private fun readOrientation(open: () -> InputStream?): Int {
        return try {
            val stream = open() ?: return ExifInterface.ORIENTATION_NORMAL
            stream.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
            }
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    private fun orientationMatrix(orientation: Int): Matrix {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                m.setRotate(180f)
                m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                m.setRotate(90f)
                m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                m.setRotate(-90f)
                m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
            else -> {}
        }
        return m
    }
}
