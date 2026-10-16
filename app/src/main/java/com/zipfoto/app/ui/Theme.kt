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

/** Palette: Dark #141413, Light #FAF9F5, Orange #D97757, Blue #6A9BCC, Green #788C5D, Gray #B0AEA5. */
object C {
    val Bg = Color(0xFF141413)
    val Surface = Color(0xFF1E1D1B)
    val SurfaceHi = Color(0xFF2A2927)
    val Text = Color(0xFFFAF9F5)
    val Muted = Color(0xFFB0AEA5)
    val Accent = Color(0xFFD97757)
    val AccentLight = Color(0xFFE9926F)
    val Blue = Color(0xFF6A9BCC)
    val Green = Color(0xFF788C5D)
    val Line = Color(0x1FFAF9F5)
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
    val Button = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = C.Bg)
    val Big = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 54.sp, color = C.Text)
}

@Composable
fun ZipFotoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.Accent,
            onPrimary = C.Bg,
            background = C.Bg,
            onBackground = C.Text,
            surface = C.Surface,
            onSurface = C.Text
        )
    ) {
        CompositionLocalProvider(LocalTextStyle provides T.Body.copy(color = C.Text)) {
            content()
        }
    }
}

/** Sfondo scuro con un alone arancione in alto a destra. */
@Composable
fun AppBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(C.Bg)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(C.Accent.copy(alpha = 0.20f), Color.Transparent),
                        center = Offset(size.width * 0.9f, -size.width * 0.05f),
                        radius = size.width * 1.15f
                    )
                )
            }
            .systemBarsPadding(),
        content = content
    )
}
