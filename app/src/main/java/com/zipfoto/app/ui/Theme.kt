package com.zipfoto.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.zipfoto.app.R

/**
 * Palette ricavata dall'icona dell'app: blu notte di fondo, zip in cobalto/ciano,
 * "carta" chiara con bordo bianco, riquadri foto/video con gradiente arancio → magenta → viola.
 */
object C {
    // sfondi
    val Bg = Color(0xFF021A5F)
    val BgDeep = Color(0xFF010E3A)
    val Tile = Color(0xFF0B2F86)

    // testo su sfondo scuro
    val Text = Color(0xFFF4F8FF)
    val Muted = Color(0xFFA9BCEB)

    // "carta" chiara e testo su carta
    val Paper = Color(0xFFEAF2FD)
    val PaperLow = Color(0xFFC3D8FC)
    val Ink = Color(0xFF0A1F5C)
    val InkMuted = Color(0xFF4A5F96)

    // blu della zip
    val Cyan = Color(0xFF01B7FE)
    val Blue = Color(0xFF0086FD)
    val Cobalt = Color(0xFF004EDB)

    // gradiente foto/video
    val Amber = Color(0xFFFEB303)
    val Orange = Color(0xFFFE9218)
    val Magenta = Color(0xFFEF4B72)
    val Purple = Color(0xFF9D34D2)
    val Indigo = Color(0xFF2F26D2)

    // vetro e bordi su sfondo scuro
    val Glass = Color(0x1AFFFFFF)
    val Line = Color(0x33FFFFFF)
}

/** Gradienti ricorrenti. */
object B {
    val Primary = Brush.verticalGradient(listOf(C.Cyan, C.Blue, C.Cobalt))
    val Media = Brush.linearGradient(listOf(C.Amber, C.Orange, C.Magenta, C.Purple, C.Indigo))
    val Violet = Brush.linearGradient(listOf(C.Purple, C.Indigo))
    val Warn = Brush.verticalGradient(listOf(C.Amber, C.Orange))
    val Paper = Brush.verticalGradient(listOf(C.Paper, C.PaperLow))
}

val Poppins = FontFamily(
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_semibold, FontWeight.SemiBold)
)

object T {
    val Display = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 36.sp, lineHeight = 42.sp, color = C.Text)
    val Title = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, color = C.Text)
    val Title2 = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, color = C.Text)
    val Body = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 23.sp, color = C.Muted)
    val Small = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp, color = C.Muted)
    val Label = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp, color = C.Text)
    val Button = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Color.White)
    val Big = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 54.sp, color = C.Text)

    // stesse voci, per testo scuro sulle superfici "carta"
    val Title2Ink = Title2.copy(color = C.Ink)
    val LabelInk = Label.copy(color = C.Ink)
    val SmallInk = Small.copy(color = C.InkMuted)
}

@Composable
fun ZippixTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.Blue,
            onPrimary = Color.White,
            background = C.Bg,
            onBackground = C.Text,
            surface = C.Bg,
            onSurface = C.Text
        )
    ) {
        CompositionLocalProvider(LocalTextStyle provides T.Body.copy(color = C.Text)) {
            content()
        }
    }
}

/** Sfondo blu notte con un alone ciano in alto a sinistra e uno viola in basso a destra. */
@Composable
fun AppBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(C.Bg, C.BgDeep)))
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(C.Cyan.copy(alpha = 0.22f), Color.Transparent),
                        center = Offset(size.width * 0.1f, -size.width * 0.05f),
                        radius = size.width * 1.1f
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(C.Purple.copy(alpha = 0.22f), Color.Transparent),
                        center = Offset(size.width * 0.95f, size.height * 0.98f),
                        radius = size.width * 1.0f
                    )
                )
            }
            .systemBarsPadding(),
        content = content
    )
}
