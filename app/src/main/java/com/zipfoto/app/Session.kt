package com.zipfoto.app

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Stato condiviso tra interfaccia e servizio (stesso processo).
 * Le liste di URI non passano da Intent: con 500+ foto supererebbero il limite del Binder.
 */
object Session {
    private val _items = MutableStateFlow<List<PhotoItem>>(emptyList())
    val items: StateFlow<List<PhotoItem>> = _items.asStateFlow()

    private val _job = MutableStateFlow<JobState>(JobState.Idle)
    val job: StateFlow<JobState> = _job.asStateFlow()

    private val _extraCompression = MutableStateFlow(false)
    val extraCompression: StateFlow<Boolean> = _extraCompression.asStateFlow()

    @Volatile
    var pendingOutput: Uri? = null

    /** Cartelle scelte con permesso persistente (da rilasciare a fine lavoro). Solo thread principale. */
    val grantedRoots = mutableListOf<Uri>()

    fun addAll(list: List<PhotoItem>) {
        _items.update { current ->
            val seen = HashSet<Uri>(current.size + list.size)
            current.forEach { seen.add(it.uri) }
            current + list.filter { seen.add(it.uri) }
        }
    }

    fun remove(uri: Uri) {
        _items.update { current -> current.filterNot { it.uri == uri } }
    }

    fun clearItems() {
        _items.value = emptyList()
    }

    fun setExtraCompression(value: Boolean) {
        _extraCompression.value = value
    }

    fun setJob(state: JobState) {
        _job.value = state
    }
}
