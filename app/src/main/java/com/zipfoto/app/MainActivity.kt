package com.zipfoto.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.zipfoto.app.ui.Actions
import com.zipfoto.app.ui.ZipFotoApp
import com.zipfoto.app.ui.ZipFotoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Selettore documenti a scelta multipla, senza limite di elementi, con permesso persistente. */
class OpenImages : ActivityResultContract<Unit, List<Uri>>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()
        val out = ArrayList<Uri>()
        val clip = intent.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).uri?.let { out.add(it) }
            }
        }
        intent.data?.let { if (!out.contains(it)) out.add(it) }
        return out
    }
}

class MainActivity : ComponentActivity() {

    private val scanning = mutableStateOf(false)
    private var pendingName = "Foto.zip"
    private var askedNotifications = false

    private val pickImages = registerForActivityResult(OpenImages()) { uris ->
        if (uris.isNotEmpty()) addPhotos(uris, persist = true)
    }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) addFolder(uri)
    }

    private val createDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) startZip(uri)
    }

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // concessa o no, si procede: il servizio funziona comunque
        createDoc.launch(pendingName)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        val actions = Actions(
            onPickPhotos = { pickImages.launch(Unit) },
            onPickFolder = { pickFolder.launch(null) },
            onCreate = { name -> requestDestination(name) },
            onCancel = { cancelZip() },
            onShare = { uri -> share(uri) },
            onNew = { resetAll() },
            onBackToSelection = { Session.setJob(JobState.Idle) }
        )
        setContent {
            ZipFotoTheme {
                ZipFotoApp(scanning = scanning.value, actions = actions)
            }
        }
        if (savedInstanceState == null) handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    // ---- ingresso foto ---------------------------------------------------------------------

    private fun handleIncoming(i: Intent?) {
        if (i == null) return
        when (i.action) {
            Intent.ACTION_SEND -> {
                val uri = IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)
                if (uri != null) addPhotos(listOf(uri), persist = false)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java)
                if (list != null && list.isNotEmpty()) addPhotos(list, persist = false)
            }
        }
    }

    private fun addPhotos(uris: List<Uri>, persist: Boolean) {
        lifecycleScope.launch {
            scanning.value = true
            try {
                val items = withContext(Dispatchers.IO) {
                    if (persist) uris.forEach { tryPersist(it) }
                    Scanner.describe(applicationContext, uris)
                }
                Session.addAll(items)
            } finally {
                scanning.value = false
            }
        }
    }

    private fun addFolder(tree: Uri) {
        tryPersist(tree)
        Session.grantedRoots.add(tree)
        lifecycleScope.launch {
            scanning.value = true
            try {
                val items = Scanner.scanTree(applicationContext, tree)
                if (items.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Nessuna foto trovata in questa cartella", Toast.LENGTH_LONG).show()
                } else {
                    Session.addAll(items)
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Impossibile leggere la cartella: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                scanning.value = false
            }
        }
    }

    private fun tryPersist(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
            // non tutti i provider/URI lo permettono: resta il permesso temporaneo
        }
    }

    private fun releaseGrants() {
        for (item in Session.items.value) {
            try {
                contentResolver.releasePersistableUriPermission(item.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
        }
        for (root in Session.grantedRoots) {
            try {
                contentResolver.releasePersistableUriPermission(root, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
        }
        Session.grantedRoots.clear()
    }

    // ---- creazione ZIP ---------------------------------------------------------------------

    private fun requestDestination(rawName: String) {
        val base = rawName.trim()
            .removeSuffix(".zip").removeSuffix(".ZIP")
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
        pendingName = (if (base.isEmpty()) "Foto" else base) + ".zip"

        val needsAsk = Build.VERSION.SDK_INT >= 33 && !askedNotifications &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsAsk) {
            askedNotifications = true
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            createDoc.launch(pendingName)
        }
    }

    private fun startZip(output: Uri) {
        val items = Session.items.value
        if (items.isEmpty()) return
        Session.pendingOutput = output
        Session.setJob(
            JobState.Running(
                0, items.size, 0L,
                items.sumOf { if (it.size > 0) it.size else 0L },
                "", 0L, -1L, 0
            )
        )
        ContextCompat.startForegroundService(
            this, Intent(this, ZipService::class.java).setAction(ZipService.ACTION_START)
        )
    }

    private fun cancelZip() {
        startService(Intent(this, ZipService::class.java).setAction(ZipService.ACTION_CANCEL))
    }

    private fun share(uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Condividi ZIP"))
    }

    private fun resetAll() {
        releaseGrants()
        Session.pendingOutput = null
        Session.setJob(JobState.Idle)
        Session.clearItems()
    }
}
