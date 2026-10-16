package com.zipfoto.app

import android.net.Uri

/** Una foto selezionata. [folder] è il percorso relativo dentro lo ZIP (vuoto = radice). */
data class PhotoItem(
    val uri: Uri,
    val name: String,
    val size: Long,
    val modified: Long,
    val folder: String = ""
)

/**
 * Modalità di archiviazione. ORIGINAL copia i file byte per byte. Le altre ridimensionano il lato
 * lungo e ricodificano in JPEG (perdita di qualità voluta, per email e per l'analisi con Copilot).
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
        val inBytes: Long,
        val outBytes: Long,
        val seconds: Long,
        val skipped: Int,
        val mode: ReduceMode = ReduceMode.ORIGINAL,
        val reducedCount: Int = 0,
        val keptOriginal: Int = 0
    ) : JobState

    data class Failed(val message: String) : JobState
}
