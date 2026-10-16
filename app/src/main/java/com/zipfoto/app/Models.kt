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
        val skipped: Int
    ) : JobState

    data class Failed(val message: String) : JobState
}
