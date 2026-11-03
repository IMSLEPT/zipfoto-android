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
 * Modalità di archiviazione. ORIGINAL copia i file byte per byte. Le altre riducono il peso:
 * le foto vengono ridimensionate sul lato lungo e ricodificate in JPEG, i video vengono ricodificati in H.264
 * (lato corto e bitrate sotto). La riduzione comporta una perdita di qualità voluta (per email e analisi con Copilot).
 * [avgKb] è il peso medio per foto misurato su 49 foto di prova (stima, dipende dal soggetto).
 * [videoShortSide] è il lato corto massimo del video (es. 480 per 854x480); un video già più piccolo non viene ingrandito.
 * [videoBitrate] è il bitrate video massimo in bit al secondo.
 */
enum class ReduceMode(
    val label: String,
    val longSide: Int,
    val quality: Int,
    val avgKb: Int,
    val videoShortSide: Int,
    val videoBitrate: Int
) {
    ORIGINAL("Originale", 0, 0, 0, 0, 0),
    LIGHT("Leggera", 1400, 75, 195, 480, 1_200_000),
    MEDIUM("Media", 1600, 80, 285, 720, 2_500_000),
    HIGH("Alta", 2000, 85, 510, 1080, 5_000_000);

    val reduces: Boolean get() = longSide > 0

    /** Peso approssimato di un minuto di video ridotto, in MB (solo video; l'audio aggiunge ~0,7 MB al minuto). */
    val videoMbPerMinute: Int get() = (videoBitrate * 60L / 8 / 1_000_000L).toInt()
}

/** Un file che non è finito nello ZIP, con il motivo in parole semplici. */
data class Problem(val name: String, val reason: String)

/** Un video che è stato davvero scritto nello ZIP: nome nello ZIP, byte scritti e byte dell'originale. */
data class VideoLine(val name: String, val bytes: Long, val originalBytes: Long = bytes) {
    val reduced: Boolean get() = originalBytes > 0 && bytes < originalBytes
}

enum class VerifyStatus {
    /** Struttura rilevata e contenuto di tutti i file riletto e confrontato (CRC). */
    FULL,

    /** Struttura rilevata; il contenuto è stato riletto solo per una parte dei file. */
    STRUCTURE,

    /** Il controllo non era possibile (es. destinazione che non si può rileggere). */
    SKIPPED,

    /** Il controllo ha trovato qualcosa che non va. */
    FAILED
}

/** Esito del controllo fatto rileggendo lo ZIP appena scritto. */
data class Verification(
    val status: VerifyStatus,
    val entries: Int = 0,
    val checkedEntries: Int = 0,
    val detail: String? = null
)

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
        val keptOriginal: Int = 0,
        val reducedVideos: Int = 0,
        val lightVideos: Int = 0,
        val verification: Verification = Verification(VerifyStatus.SKIPPED),
        val videoLines: List<VideoLine> = emptyList(),
        val problems: List<Problem> = emptyList(),
        val notes: List<String> = emptyList()
    ) : JobState {
        val photos: Int get() = files - videos

        /** true solo se tutto è andato bene: nessun file saltato, nessun avviso, controllo non fallito. */
        val clean: Boolean
            get() = problems.isEmpty() && notes.isEmpty() && verification.status != VerifyStatus.FAILED
    }

    data class Failed(val message: String) : JobState
}
