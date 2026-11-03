package com.zipfoto.app

import android.net.Uri

/**
 * Un file multimediale selezionato (foto o video).
 * [folder] è il percorso relativo dentro lo ZIP (vuoto = radice).
 */
data class MediaItem(
    val uri: Uri,
    val name: String,
    val size: Long,
    val modified: Long,
    val folder: String = "",
    val video: Boolean = false
)

/**
 * Modalità di archiviazione. ORIGINAL copia i file byte per byte. Le altre ridimensionano il lato
 * lungo delle foto e le ricodificano in JPEG (perdita di qualità voluta, per email e per l'analisi con Copilot).
 * I video restano sempre originali, in qualsiasi modalità.
 * [avgKb] è il peso medio per foto misurato su 49 foto di prova (stima, dipende dal soggetto).
 */
enum class ReduceMode(val label: String, val longSide: Int, val quality: Int, val avgKb: Int) {
    ORIGINAL("Originale", 0, 0, 0),
    LIGHT("Leggera", 1400, 75, 195),
    MEDIUM("Media", 1600, 80, 285),
    HIGH("Alta", 2000, 85, 510);

    val reduces: Boolean get() = longSide > 0
}

sealed interface JobState {
    data object Idle : JobState

    data class Running(
        val done: Int,
        val total: Int,
        val bytesDone: Long,
        val bytesTotal: Long,
        val current: String,
        val speedBps: Long,
        val etaSec: Long,
        val skipped: Int
    ) : JobState

    data class Done(
        val output: Uri,
        val files: Int,
        val videos: Int,
        val inBytes: Long,
        val outBytes: Long,
        val seconds: Long,
        val skipped: Int,
        val mode: ReduceMode = ReduceMode.ORIGINAL,
        val reducedCount: Int = 0,
        val keptOriginal: Int = 0
    ) : JobState {
        val photos: Int get() = files - videos
    }

    data class Failed(val message: String) : JobState
}
