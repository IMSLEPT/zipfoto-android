package com.zipfoto.app.ui

import android.net.Uri
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.zipfoto.app.JobState
import com.zipfoto.app.PhotoItem
import com.zipfoto.app.ReduceMode
import com.zipfoto.app.Session
import java.time.LocalDate
import java.util.Locale

class Actions(
    val onPickPhotos: () -> Unit,
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
fun ZipFotoApp(scanning: Boolean, actions: Actions) {
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
                Screen.Home -> HomeScreen(actions.onPickPhotos, actions.onPickFolder)
                Screen.Select -> SelectionScreen(
                    items = items,
                    scanning = scanning,
                    extra = extra,
                    onExtra = { Session.setExtraCompression(it) },
                    mode = mode,
                    onMode = { Session.setReduceMode(it) },
                    onRemove = { Session.remove(it) },
                    onClear = { actions.onNew() },
                    onAddPhotos = actions.onPickPhotos,
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
private fun HomeScreen(onPhotos: () -> Unit, onFolder: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
    ) {
        Spacer(Modifier.height(40.dp))
        LogoMark(Modifier.size(68.dp))
        Spacer(Modifier.height(24.dp))
        Text("ZipFoto", style = T.Display)
        Spacer(Modifier.height(10.dp))
        Text(
            "Metti centinaia di foto in un solo file ZIP. Qualità originale, oppure ridotte per email e analisi.",
            style = T.Body
        )
        Spacer(Modifier.height(36.dp))

        ActionCard(
            title = "Scegli le foto",
            subtitle = "Anche 500 o più in un colpo solo",
            tint = C.Accent,
            onClick = onPhotos
        ) { PhotoGlyph(Modifier.size(30.dp), C.Accent) }
        Spacer(Modifier.height(14.dp))
        ActionCard(
            title = "Scegli una cartella",
            subtitle = "Tutte le foto, sottocartelle incluse",
            tint = C.Blue,
            onClick = onFolder
        ) { FolderGlyph(Modifier.size(30.dp), C.Blue) }

        Spacer(Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("Originale o ridotte", C.Green)
            Pill("100% offline", C.Blue)
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Suggerimento: dalla Galleria puoi anche usare Condividi → ZipFoto.",
            style = T.Small
        )
        Spacer(Modifier.height(32.dp))
    }
}

// ---------------------------------------------------------------------------------------------
// Selezione
// ---------------------------------------------------------------------------------------------

@Composable
private fun SelectionScreen(
    items: List<PhotoItem>,
    scanning: Boolean,
    extra: Boolean,
    onExtra: (Boolean) -> Unit,
    mode: ReduceMode,
    onMode: (ReduceMode) -> Unit,
    onRemove: (Uri) -> Unit,
    onClear: () -> Unit,
    onAddPhotos: () -> Unit,
    onAddFolder: () -> Unit,
    onCreate: (String) -> Unit
) {
    var name by rememberSaveable { mutableStateOf("Foto_" + LocalDate.now().toString()) }
    val totalBytes = remember(items) { items.sumOf { if (it.size > 0) it.size else 0L } }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 12.dp, top = 20.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("${items.size} foto", style = T.Title)
                Text(if (totalBytes > 0) fmtBytes(totalBytes) + " in totale" else "Calcolo dimensione…", style = T.Small)
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = onClear)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) { Text("Svuota", style = T.Label.copy(color = C.Accent)) }
        }

        if (scanning) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                color = C.Accent,
                trackColor = C.SurfaceHi
            )
            Spacer(Modifier.height(8.dp))
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(92.dp),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(items, key = { it.uri.toString() }) { p -> Thumb(p, onRemove) }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
                .background(C.Surface)
                .padding(horizontal = 20.dp, vertical = 18.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton("+ Foto", Modifier.weight(1f), onAddPhotos)
                GhostButton("+ Cartella", Modifier.weight(1f), onAddFolder)
            }
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(C.SurfaceHi)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = name,
                    onValueChange = { name = it.take(80) },
                    singleLine = true,
                    textStyle = T.Label.copy(fontSize = 15.sp),
                    cursorBrush = SolidColor(C.Accent),
                    modifier = Modifier.weight(1f)
                )
                Text(".zip", style = T.Small)
            }
            Spacer(Modifier.height(12.dp))
            ModeSelector(mode, items.size, onMode)
            if (!mode.reduces) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Compressione extra", style = T.Label)
                        Text(
                            "Spenta = più veloce, qualità identica. Le foto sono già compresse: il guadagno è minimo.",
                            style = T.Small.copy(fontSize = 12.sp, lineHeight = 16.sp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = extra,
                        onCheckedChange = onExtra,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = C.Bg,
                            checkedTrackColor = C.Accent,
                            uncheckedThumbColor = C.Muted,
                            uncheckedTrackColor = C.SurfaceHi,
                            uncheckedBorderColor = C.Line
                        )
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            PrimaryButton(
                text = "Crea ZIP · ${items.size} foto",
                modifier = Modifier.fillMaxWidth(),
                enabled = items.isNotEmpty() && !scanning
            ) { onCreate(name) }
        }
    }
}

/** Scelta tra foto originali e versioni ridotte (per email o per analisi con Copilot). */
@Composable
private fun ModeSelector(mode: ReduceMode, count: Int, onMode: (ReduceMode) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (m in ReduceMode.entries) {
                val selected = m == mode
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) C.Accent else C.SurfaceHi)
                        .clickable { onMode(m) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        m.label,
                        style = T.Label.copy(color = if (selected) C.Bg else C.Text),
                        maxLines = 1
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        val detail = when (mode) {
            ReduceMode.ORIGINAL -> "Foto identiche all'originale, byte per byte."
            ReduceMode.LIGHT -> "Schermate e tabelle con testo grande."
            ReduceMode.MEDIUM -> "Buon compromesso per documenti e schermate."
            ReduceMode.HIGH -> "Per scritte a mano e testo piccolo."
        }
        Text(
            if (mode.reduces) {
                "$detail Lato lungo ${mode.longSide} px, JPEG qualità ${mode.quality}. " +
                    "Stima circa ${fmtBytes(count * mode.avgKb * 1000L)} (dipende dalle foto). Non è senza perdita."
            } else {
                detail
            },
            style = T.Small.copy(fontSize = 12.sp, lineHeight = 16.sp)
        )
    }
}

@Composable
private fun Thumb(p: PhotoItem, onRemove: (Uri) -> Unit) {
    val ctx = LocalContext.current
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .background(C.Surface)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(ctx).data(p.uri).size(280).crossfade(true).build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(5.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(Color(0xB3141413))
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
                    color = Color(0x1FFAF9F5),
                    startAngle = 0f, sweepAngle = 360f, useCenter = false,
                    topLeft = Offset(inset, inset), size = arc,
                    style = Stroke(stroke, cap = StrokeCap.Round)
                )
                rotate(-90f) {
                    drawArc(
                        brush = Brush.sweepGradient(listOf(C.Accent, C.AccentLight, C.Accent)),
                        startAngle = 0f, sweepAngle = 360f * frac, useCenter = false,
                        topLeft = Offset(inset, inset), size = arc,
                        style = Stroke(stroke, cap = StrokeCap.Round)
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${(frac * 100).toInt()}%", style = T.Big)
                Text("${s.done} di ${s.total} foto", style = T.Small)
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
            Text("${s.skipped} foto non leggibili saltate", style = T.Small.copy(color = C.Accent))
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
                .background(C.Green),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = C.Text, modifier = Modifier.size(60.dp))
        }
        Spacer(Modifier.height(28.dp))
        Text("ZIP creato", style = T.Display)
        Spacer(Modifier.height(8.dp))
        Text(
            "${d.files} foto · ${fmtBytes(d.outBytes)} · ${fmtDuration(d.seconds)}",
            style = T.Body,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        if (d.mode.reduces) {
            Pill("Ridotte · ${d.mode.longSide} px · JPEG q${d.mode.quality}", C.Accent)
            Spacer(Modifier.height(14.dp))
            Text(
                "Da ${fmtBytes(d.inBytes)} a ${fmtBytes(d.outBytes)}",
                style = T.Small,
                textAlign = TextAlign.Center
            )
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
                    style = T.Small.copy(color = C.Accent),
                    textAlign = TextAlign.Center
                )
            }
        } else {
            Pill("Originali, byte per byte", C.Green)
        }
        if (d.skipped > 0) {
            Spacer(Modifier.height(14.dp))
            Text("${d.skipped} foto non leggibili sono state saltate", style = T.Small.copy(color = C.Accent))
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
                .background(C.Accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Close, contentDescription = null, tint = C.Accent, modifier = Modifier.size(48.dp))
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

@Composable
private fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier
            .height(58.dp)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(C.AccentLight, C.Accent)), alpha = if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = T.Button)
    }
}

@Composable
private fun GhostButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier
            .height(50.dp)
            .clip(shape)
            .background(C.SurfaceHi)
            .border(1.dp, C.Line, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = T.Label.copy(fontSize = 15.sp))
    }
}

@Composable
private fun ActionCard(title: String, subtitle: String, tint: Color, onClick: () -> Unit, icon: @Composable () -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(C.Surface)
            .border(1.dp, C.Line, shape)
            .clickable(onClick = onClick)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(58.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) { icon() }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = T.Title2)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = T.Small)
        }
    }
}

@Composable
private fun Pill(text: String, tint: Color) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(tint.copy(alpha = 0.14f))
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
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(C.Surface)
            .border(1.dp, C.Line, RoundedCornerShape(18.dp))
            .padding(horizontal = 10.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, style = T.Label.copy(fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        Text(label, style = T.Small.copy(fontSize = 11.sp))
    }
}

@Composable
private fun LogoMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(C.AccentLight, C.Accent)),
            cornerRadius = CornerRadius(w * 0.28f),
            size = Size(w, h)
        )
        val cx = w / 2
        val tw = w * 0.11f
        val th = h * 0.055f
        for (i in 0 until 6) {
            val left = i % 2 == 0
            drawRect(
                color = C.Text,
                topLeft = Offset(if (left) cx - tw else cx, h * 0.14f + i * th * 1.35f),
                size = Size(tw, th)
            )
        }
        drawRoundRect(
            color = C.Text,
            topLeft = Offset(cx - w * 0.12f, h * 0.55f),
            size = Size(w * 0.24f, h * 0.30f),
            cornerRadius = CornerRadius(w * 0.12f)
        )
        drawCircle(color = C.Accent, radius = w * 0.045f, center = Offset(cx, h * 0.66f))
    }
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
