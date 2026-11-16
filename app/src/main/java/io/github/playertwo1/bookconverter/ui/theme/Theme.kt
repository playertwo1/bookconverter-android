package io.github.playertwo1.bookconverter.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import io.github.playertwo1.bookconverter.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Tokens from docs/spec/04_DESIGN/TOKENS.json. The amber highlight is deliberately absent.
val Background = Color(0xFF000000)
val Surface = Color(0xFF161618)
val Field = Color(0xFF202024)
val Stroke = Color(0x1AFFFFFF)
val Accent = Color(0xFF6366F1)
val AccentSoft = Color(0x2E6366F1)
val TextPrimary = Color(0xFFF5F5F7)
val TextSecondary = Color(0xFF98989D)
val TextTertiary = Color(0xFF636366)
val Positive = Color(0xFF35B77B)
val Danger = Color(0xFFF36D7A)

private val AppColors = darkColorScheme(
    primary = Accent,
    onPrimary = TextPrimary,
    background = Background,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = Field,
    onSurfaceVariant = TextSecondary,
    outline = Stroke,
    error = Danger,
)

// Bundled (OFL, licence in the TTF metadata): Samsung's font setting replaces the system FontFamily.Serif with a sans.
val Serif = FontFamily(Font(R.font.noto_serif_bold, FontWeight.Bold))

private val AppTypography = Typography(
    displaySmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 32.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 13.sp),
)

@Composable
fun BookconverterTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AppColors, typography = AppTypography, content = content)
}
