package com.zipfoto.app.ui

import android.net.Uri
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import com.zipfoto.app.JobState
import com.zipfoto.app.MediaItem
import com.zipfoto.app.R
import com.zipfoto.app.ReduceMode
import com.zipfoto.app.Session
import java.time.LocalDate
import java.util.Locale

class Actions(
    val onPickLibrary: () -> Unit,
    val onPickFiles: () -> Unit,
    val onPickFolder: () -> Unit,
    val onCreate: (String) -> Unit,
    val onCancel: () -> Unit,
    val onShare: (Uri) -> Unit,
    val onNew: () -> Unit,
    val onBackToSelection: () -> Unit
)

private enum class Screen { Home, Select, Running, Done, Failed }

/** Gmail: 25 MB per email in totale; gli allegati occupano ~37% in più (codifica), quindi ~18 MB di file. */
private const val EMAIL_SAFE_BYTES = 18_000_000L

@Composable
fun ZippixApp(scanning: Boolean, actions: Actions) {
    val items by Session.items.collectAsState()
    val job by Session.job.collectAsState()
    val extra by Session.extraCompression.collectAsState()
    val mode by Session.reduceMode.collectAsState()

    val screen = when (job) {
        is JobState.Running -> Screen.Running
        is JobState.Done -> Screen.Done
        is JobState.Failed -> Screen.Failed
        JobState.Idle -> if (items.isEmpty() && !scanning) Screen.Home else Screen.Select
    }

    AppBackground {
        Crossfade(targetState = screen, animationSpec = tween(250), label = "screen") { s ->
            when (s) {
                Screen.Home -> HomeScreen(actions.onPickLibrary, actions.onPickFiles, actions.onPickFolder)
                Screen.Select -> SelectionScreen(
                    items = items,
                    scanning = scanning,
                    extra = extra,
                    onExtra = { Session.setExtraCompression(it) },
                    mode = mode,
                    onMode = { Session.setReduceMode(it) },
                    onRemove = { Session.remove(it) },
                    onClear = { actions.onNew() },
                    onAddLibrary = actions.onPickLibrary,
                    onAddFiles = actions.onPickFiles,
                    onAddFolder = actions.onPickFolder,
                    onCreate = actions.onCreate
                )
                Screen.Running -> (job as? JobState.Running)?.let { ProgressScreen(it, actions.onCancel) }
                Screen.Done -> (job as? JobState.Done)?.let { DoneScreen(it, actions.onShare, actions.onNew) }
                Screen.Failed -> (job as? JobState.Failed)?.let { FailedScreen(it.message, actions.onBackToSelection) }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Home
// ---------------------------------------------------------------------------------------------

@Composable
private fun HomeScreen(onLibrary: () -> Unit, onFiles: () -> Unit, onFolder: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
    ) {
        Spacer(Modifier.height(36.dp))
        LogoMark(Modifier.size(84.dp))
        Spacer(Modifier.height(24.dp))
        Text("Zippix", style = T.Display)
        Spacer(Modifier.height(10.dp))
        Text(
            "Metti foto e video in un solo file ZIP, byte per byte: nessuna perdita di qualità. " +
                "Oppure riduci le foto per email e analisi.",
            style = T.Body
        )
        Spacer(Modifier.height(32.dp))

        ActionCard(
            title = "Foto e video",
            subtitle = "Dalla Galleria e da Google Foto",
            tile = B.Media,
            onClick = onLibrary
        ) { PhotoGlyph(Modifier.size(30.dp), Color.White) }
        Spacer(Modifier.height(14.dp))
        ActionCard(
            title = "Dai file",
            subtitle = "Drive, Download e altre app di archiviazione",
            tile = B.Violet,
            onClick = onFiles
        ) { FileGlyph(Modifier.size(30.dp), Color.White) }
        Spacer(Modifier.height(14.dp))
        ActionCard(
            title = "Una cartella",
            subtitle = "Tutto il contenuto, sottocartelle incluse",
            tile = B.Primary,
            onClick = onFolder
        ) { FolderGlyph(Modifier.size(30.dp), Color.White) }

        Spacer(Modifier.height(30.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("Qualità originale", C.Cyan)
            Pill("Nessun upload", C.Amber)
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Suggerimento: dalla Galleria puoi anche usare Condividi → Zippix.",
            style = T.Small
        )
        Spacer(Modifier.height(32.dp))
    }
}

// ---------------------------------------------------------------------------------------------
// Selezione
// ---------------------------------------------------------------------------------------------

private fun countLabel(photos: Int, videos: Int): String = when {
    videos == 0 -> "$photos foto"
    photos == 0 -> "$videos video"
    else -> "$photos foto · $videos video"
}

private fun defaultName(items: List<MediaItem>): String {
    val videos = items.count { it.video }
    val prefix = when {
        items.isEmpty() || videos == 0 -> "Foto_"
        videos == items.size -> "Video_"
        else -> "Foto_Video_"
    }
    return prefix + LocalDate.now().toString()
}

@Composable
private fun SelectionScreen(
    items: List<MediaItem>,
    scanning: Boolean,
    extra: Boolean,
    onExtra: (Boolean) -> Unit,
    mode: ReduceMode,
    onMode: (ReduceMode) -> Unit,
    onRemove: (Uri) -> Unit,
    onClear: () -> Unit,
    onAddLibrary: () -> Unit,
    onAddFiles: () -> Unit,
    onAddFolder: () -> Unit,
    onCreate: (String) -> Unit
) {
    // Finché l'utente non scrive, il nome segue il contenuto (foto, video o entrambi).
    var edited by rememberSaveable { mutableStateOf<String?>(null) }
    val name = edited ?: defaultName(items)
    val totalBytes = remember(items) { items.sumOf { if (it.size > 0) it.size else 0L } }
    val videoCount = remember(items) { items.count { it.video } }
    val photoCount = items.size - videoCount

    val ctx = LocalContext.current
    // Un solo caricatore di miniature, con il decodificatore dei fotogrammi video.
    val loader = remember(ctx) {
        ImageLoader.Builder(ctx).components { add(VideoFrameDecoder.Factory()) }.build()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 12.dp, top = 20.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(countLabel(photoCount, videoCount), style = T.Title)
                Text(if (totalBytes > 0) fmtBytes(totalBytes) + " in totale" else "Calcolo dimensione…", style = T.Small)
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(C.Glass)
                    .border(1.dp, C.Line, RoundedCornerShape(50))
                    .clickable(onClick = onClear)
                    .padding(horizontal = 16.dp, vertical = 9.dp)
            ) { Text("Svuota", style = T.Label) }
        }

        if (scanning) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                color = C.Cyan,
                trackColor = C.Glass
            )
            Spacer(Modifier.height(8.dp))
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(92.dp),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(items, key = { it.uri.toString() }) { p -> Thumb(p, loader, onRemove) }
        }

        val sheetShape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(sheetShape)
                .background(B.Paper)
                .border(2.dp, Brush.verticalGradient(listOf(Color.White, Color.Transparent)), sheetShape)
                .padding(horizontal = 20.dp, vertical = 18.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftButton("+ Foto e video", Modifier.weight(1.5f), onAddLibrary)
                SoftButton("+ File", Modifier.weight(1f), onAddFiles)
                SoftButton("+ Cartella", Modifier.weight(1.2f), onAddFolder)
            }
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .border(1.dp, C.PaperLow, RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = name,
                    onValueChange = { edited = it.take(80) },
                    singleLine = true,
                    textStyle = T.LabelInk.copy(fontSize = 15.sp),
                    cursorBrush = SolidColor(C.Blue),
                    modifier = Modifier.weight(1f)
                )
                Text(".zip", style = T.SmallInk)
            }
            Spacer(Modifier.height(12.dp))
            ModeSelector(mode, photoCount, videoCount, onMode)
            if (!mode.reduces) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Compressione extra", style = T.LabelInk)
                        Text(
                            "Spenta = più veloce, qualità identica. Foto e video sono già compressi: " +
                                "il guadagno è minimo e vale solo per le foto.",
                            style = T.SmallInk.copy(fontSize = 12.sp, lineHeight = 16.sp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = extra,
                        onCheckedChange = onExtra,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = C.Blue,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = C.PaperLow,
                            uncheckedBorderColor = C.InkMuted.copy(alpha = 0.35f)
                        )
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            PrimaryButton(
                text = "Crea ZIP · " + countLabel(photoCount, videoCount),
                modifier = Modifier.fillMaxWidth(),
                enabled = items.isNotEmpty() && !scanning
            ) { onCreate(name) }
        }
    }
}

/** Scelta tra foto originali e versioni ridotte (per email o per analisi con Copilot). I video restano originali. */
@Composable
private fun ModeSelector(mode: ReduceMode, photos: Int, videos: Int, onMode: (ReduceMode) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (m in ReduceMode.entries) {
                val selected = m == mode
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .then(
                            if (selected) Modifier.background(B.Primary)
                            else Modifier.background(Color.White.copy(alpha = 0.75f))
                        )
                        .clickable { onMode(m) }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        m.label,
                        style = T.Label.copy(color = if (selected) Color.White else C.Ink),
                        maxLines = 1
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        val detail = when (mode) {
            ReduceMode.ORIGINAL -> "Foto e video identici all'originale, byte per byte."
            ReduceMode.LIGHT -> "Schermate e tabelle con testo grande."
            ReduceMode.MEDIUM -> "Buon compromesso per documenti e schermate."
            ReduceMode.HIGH -> "Per scritte a mano e testo piccolo."
        }
        Text(
            if (mode.reduces) {
                val estimate = if (photos > 0) {
                    " Stima circa ${fmtBytes(photos * mode.avgKb * 1000L)} per le foto (dipende dai soggetti)."
                } else {
                    ""
                }
                val videoNote = if (videos > 0) " I video restano originali." else ""
                "$detail Lato lungo ${mode.longSide} px, JPEG qualità ${mode.quality}.$estimate$videoNote Non è senza perdita."
            } else {
                detail
            },
            style = T.SmallInk.copy(fontSize = 12.sp, lineHeight = 16.sp)
        )
    }
}

@Composable
private fun Thumb(p: MediaItem, loader: ImageLoader, onRemove: (Uri) -> Unit) {
    val ctx = LocalContext.current
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(C.Tile)
            .border(1.5.dp, Color.White.copy(alpha = 0.55f), shape)
    ) {
        if (p.video) {
            // Simbolo di riserva: se la miniatura non si carica, resta riconoscibile come video.
            PlayGlyph(Modifier.align(Alignment.Center).size(34.dp), Color.White.copy(alpha = 0.35f))
        }
        AsyncImage(
            model = ImageRequest.Builder(ctx).data(p.uri).size(280).crossfade(true).build(),
            imageLoader = loader,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (p.video) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(B.Media)
                    .border(1.5.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center
            ) { PlayGlyph(Modifier.size(12.dp), Color.White) }
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(5.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(Color(0xB3010E3A))
                .clickable { onRemove(p.uri) },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Close, contentDescription = "Rimuovi", tint = C.Text, modifier = Modifier.size(14.dp))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Avanzamento
// ---------------------------------------------------------------------------------------------

@Composable
private fun ProgressScreen(s: JobState.Running, onCancel: () -> Unit) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val target = when {
        s.bytesTotal > 0 -> (s.bytesDone.toFloat() / s.bytesTotal).coerceIn(0f, 1f)
        s.total > 0 -> (s.done.toFloat() / s.total).coerceIn(0f, 1f)
        else -> 0f
    }
    val frac by animateFloatAsState(target, animationSpec = tween(350), label = "progress")

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(36.dp))
        Text("Creo il tuo ZIP", style = T.Title)
        Spacer(Modifier.height(6.dp))
        Text("Puoi bloccare lo schermo: il lavoro continua in background.", style = T.Small, textAlign = TextAlign.Center)
        Spacer(Modifier.weight(1f))

        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(250.dp)) {
                val stroke = 24.dp.toPx()
                val inset = stroke / 2
                val arc = Size(size.width - stroke, size.height - stroke)
                drawArc(
                    color = Color(0x26FFFFFF),
                    startAngle = 0f, sweepAngle = 360f, useCenter = false,
                    topLeft = Offset(inset, inset), size = arc,
                    style = Stroke(stroke, cap = StrokeCap.Round)
                )
                rotate(-90f) {
                    drawArc(
                        brush = Brush.sweepGradient(listOf(C.Cyan, C.Blue, C.Purple, C.Magenta, C.Orange)),
                        startAngle = 0f, sweepAngle = 360f * frac, useCenter = false,
                        topLeft = Offset(inset, inset), size = arc,
                        style = Stroke(stroke, cap = StrokeCap.Round)
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${(frac * 100).toInt()}%", style = T.Big)
                Text("${s.done} di ${s.total} file", style = T.Small)
            }
        }

        Spacer(Modifier.height(28.dp))
        Text(
            s.current,
            style = T.Small,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatBox(
                "Dati",
                if (s.bytesTotal > 0) fmtBytes(s.bytesDone) + " / " + fmtBytes(s.bytesTotal) else fmtBytes(s.bytesDone),
                Modifier.weight(1.4f)
            )
            StatBox("Velocità", if (s.speedBps > 0) fmtBytes(s.speedBps) + "/s" else "—", Modifier.weight(1f))
            StatBox("Mancano", fmtEta(s.etaSec), Modifier.weight(1f))
        }
        if (s.skipped > 0) {
            Spacer(Modifier.height(12.dp))
            Text("${s.skipped} file non leggibili saltati", style = T.Small.copy(color = C.Amber))
        }
        Spacer(Modifier.weight(1f))
        GhostButton("Annulla", Modifier.fillMaxWidth(), onCancel)
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------------------------------------
// Fine
// ---------------------------------------------------------------------------------------------

@Composable
private fun DoneScreen(d: JobState.Done, onShare: (Uri) -> Unit, onNew: () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val sc by animateFloatAsState(
        if (shown) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 300f),
        label = "check"
    )

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(72.dp))
        Box(
            Modifier
                .size(112.dp)
                .scale(sc)
                .clip(CircleShape)
                .background(B.Primary)
                .border(3.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(60.dp))
        }
        Spacer(Modifier.height(28.dp))
        Text("ZIP creato", style = T.Display)
        Spacer(Modifier.height(8.dp))
        Text(
            "${countLabel(d.photos, d.videos)} · ${fmtBytes(d.outBytes)} · ${fmtDuration(d.seconds)}",
            style = T.Body,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        if (d.mode.reduces) {
            Pill("Foto ridotte · ${d.mode.longSide} px · JPEG q${d.mode.quality}", C.Amber)
            Spacer(Modifier.height(14.dp))
            Text(
                "Da ${fmtBytes(d.inBytes)} a ${fmtBytes(d.outBytes)}",
                style = T.Small,
                textAlign = TextAlign.Center
            )
            if (d.videos > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "${d.videos} video inclusi in originale, senza modifiche",
                    style = T.Small,
                    textAlign = TextAlign.Center
                )
            }
            if (d.keptOriginal > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "${d.keptOriginal} foto lasciate in originale (già leggere o non riducibili)",
                    style = T.Small,
                    textAlign = TextAlign.Center
                )
            }
            if (d.outBytes > EMAIL_SAFE_BYTES) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Sopra ~18 MB: Gmail ha il limite di 25 MB per email e oltre trasforma gli allegati in link Drive.",
                    style = T.Small.copy(color = C.Amber),
                    textAlign = TextAlign.Center
                )
            }
        } else {
            Pill(if (d.videos > 0) "Foto e video originali, byte per byte" else "Originali, byte per byte", C.Cyan)
        }
        if (d.skipped > 0) {
            Spacer(Modifier.height(14.dp))
            Text("${d.skipped} file non leggibili sono stati saltati", style = T.Small.copy(color = C.Amber))
        }
        Spacer(Modifier.height(48.dp))
        PrimaryButton("Condividi ZIP", Modifier.fillMaxWidth()) { onShare(d.output) }
        Spacer(Modifier.height(12.dp))
        GhostButton("Nuovo ZIP", Modifier.fillMaxWidth(), onNew)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun FailedScreen(message: String, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(C.Magenta.copy(alpha = 0.20f))
                .border(2.dp, C.Magenta.copy(alpha = 0.7f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Close, contentDescription = null, tint = C.Magenta, modifier = Modifier.size(48.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text("Qualcosa è andato storto", style = T.Title)
        Spacer(Modifier.height(10.dp))
        Text(message, style = T.Body, textAlign = TextAlign.Center)
        Spacer(Modifier.height(36.dp))
        PrimaryButton("Torna alla selezione", Modifier.fillMaxWidth(), onClick = onBack)
    }
}

// ---------------------------------------------------------------------------------------------
// Componenti
// ---------------------------------------------------------------------------------------------

/** Pulsante principale: blu zip con riflesso e bordo bianco, come i riquadri dell'icona. */
@Composable
private fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier
            .height(58.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape)
            .background(B.Primary)
            .drawWithContent {
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.30f), Color.Transparent),
                        startY = 0f,
                        endY = size.height * 0.55f
                    ),
                    size = Size(size.width, size.height * 0.55f)
                )
                drawContent()
            }
            .border(1.5.dp, Color.White.copy(alpha = 0.6f), shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = T.Button, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Pulsante in vetro, per lo sfondo blu notte. */
@Composable
private fun GhostButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier
            .height(52.dp)
            .clip(shape)
            .background(C.Glass)
            .border(1.dp, C.Line, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = T.Label.copy(fontSize = 15.sp))
    }
}

/** Pulsante secondario per le superfici "carta" chiare. */
@Composable
private fun SoftButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier
            .height(48.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.8f))
            .border(1.dp, C.PaperLow, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = T.LabelInk.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ActionCard(title: String, subtitle: String, tile: Brush, onClick: () -> Unit, icon: @Composable () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(B.Paper)
            .border(2.dp, Color.White, shape)
            .clickable(onClick = onClick)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(19.dp))
                .background(tile)
                .border(2.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(19.dp)),
            contentAlignment = Alignment.Center
        ) { icon() }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = T.Title2Ink)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = T.SmallInk)
        }
    }
}

@Composable
private fun Pill(text: String, tint: Color) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(tint.copy(alpha = 0.16f))
            .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(tint)
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = T.Label.copy(color = tint))
    }
}

@Composable
private fun StatBox(label: String, value: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .clip(shape)
            .background(C.Glass)
            .border(1.dp, C.Line, shape)
            .padding(horizontal = 10.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, style = T.Label.copy(fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        Text(label, style = T.Small.copy(fontSize = 11.sp))
    }
}

/** L'icona dell'app, con gli angoli arrotondati e il bordo bianco. */
@Composable
private fun LogoMark(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(24.dp)
    Image(
        painter = painterResource(R.drawable.app_logo),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .clip(shape)
            .border(2.dp, Color.White.copy(alpha = 0.7f), shape)
    )
}

@Composable
private fun PhotoGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.085f
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.10f, h * 0.16f),
            size = Size(w * 0.80f, h * 0.68f),
            cornerRadius = CornerRadius(w * 0.14f),
            style = Stroke(sw)
        )
        drawCircle(color, radius = w * 0.075f, center = Offset(w * 0.35f, h * 0.38f))
        val p = Path().apply {
            moveTo(w * 0.16f, h * 0.76f)
            lineTo(w * 0.42f, h * 0.52f)
            lineTo(w * 0.58f, h * 0.66f)
            lineTo(w * 0.70f, h * 0.56f)
            lineTo(w * 0.86f, h * 0.76f)
        }
        drawPath(p, color, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun FileGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.085f
        val p = Path().apply {
            moveTo(w * 0.22f, h * 0.10f)
            lineTo(w * 0.60f, h * 0.10f)
            lineTo(w * 0.80f, h * 0.30f)
            lineTo(w * 0.80f, h * 0.90f)
            lineTo(w * 0.22f, h * 0.90f)
            close()
            moveTo(w * 0.60f, h * 0.10f)
            lineTo(w * 0.60f, h * 0.30f)
            lineTo(w * 0.80f, h * 0.30f)
        }
        drawPath(p, color, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun FolderGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.085f
        val p = Path().apply {
            moveTo(w * 0.10f, h * 0.24f)
            lineTo(w * 0.38f, h * 0.24f)
            lineTo(w * 0.48f, h * 0.35f)
            lineTo(w * 0.90f, h * 0.35f)
            lineTo(w * 0.90f, h * 0.80f)
            lineTo(w * 0.10f, h * 0.80f)
            close()
        }
        drawPath(p, color, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Triangolo "play" arrotondato, come nel ciak dell'icona. */
@Composable
private fun PlayGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val p = Path().apply {
            moveTo(w * 0.26f, h * 0.14f)
            lineTo(w * 0.86f, h * 0.50f)
            lineTo(w * 0.26f, h * 0.86f)
            close()
        }
        drawPath(p, color)
        drawPath(p, color, style = Stroke(w * 0.10f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

// ---------------------------------------------------------------------------------------------
// Formattazione
// ---------------------------------------------------------------------------------------------

internal fun fmtBytes(b: Long): String {
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    val v = b.toDouble()
    return when {
        v >= gb -> String.format(Locale.getDefault(), "%.2f GB", v / gb)
        v >= mb -> String.format(Locale.getDefault(), "%.0f MB", v / mb)
        v >= kb -> String.format(Locale.getDefault(), "%.0f KB", v / kb)
        else -> "$b B"
    }
}

private fun fmtEta(sec: Long): String = when {
    sec < 0 -> "—"
    sec < 60 -> "$sec s"
    else -> "${sec / 60} min ${sec % 60} s"
}

private fun fmtDuration(sec: Long): String = when {
    sec < 1 -> "meno di 1 s"
    sec < 60 -> "$sec s"
    else -> "${sec / 60} min ${sec % 60} s"
}
