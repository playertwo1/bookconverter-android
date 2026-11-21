package io.github.playertwo1.bookconverter.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.playertwo1.bookconverter.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppThemeMode(val label: String) {
    AMOLED("AMOLED Escuro"),
    LIGHT("Claro"),
    SYSTEM("Sistema")
}

object ThemePreferences {
    private const val PREFS_NAME = "ui_prefs"
    private const val KEY_THEME_MODE = "theme_mode"

    private val _themeMode = MutableStateFlow(AppThemeMode.AMOLED)
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    @Volatile
    var isDark: Boolean = true
        internal set

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_THEME_MODE, AppThemeMode.AMOLED.name)
        val mode = try {
            AppThemeMode.valueOf(saved ?: AppThemeMode.AMOLED.name)
        } catch (_: Exception) {
            AppThemeMode.AMOLED
        }
        _themeMode.value = mode
        isDark = (mode != AppThemeMode.LIGHT)
    }

    fun setMode(context: Context, mode: AppThemeMode) {
        _themeMode.value = mode
        isDark = (mode != AppThemeMode.LIGHT)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_MODE, mode.name)
            .apply()
    }
}

// AMOLED Dark Tokens
val DarkBackground = Color(0xFF000000)
val DarkSurface = Color(0xFF161618)
val DarkField = Color(0xFF202024)
val DarkStroke = Color(0x1AFFFFFF)
val DarkAccent = Color(0xFF6366F1)
val DarkAccentSoft = Color(0x2E6366F1)
val DarkTextPrimary = Color(0xFFF5F5F7)
val DarkTextSecondary = Color(0xFF98989D)
val DarkTextTertiary = Color(0xFF636366)
val DarkPositive = Color(0xFF35B77B)
val DarkDanger = Color(0xFFF36D7A)

// Light Tokens
val LightBackground = Color(0xFFF8FAFC)
val LightSurface = Color(0xFFFFFFFF)
val LightField = Color(0xFFF1F5F9)
val LightStroke = Color(0x1E0F172A)
val LightAccent = Color(0xFF4F46E5)
val LightAccentSoft = Color(0x1F4F46E5)
val LightTextPrimary = Color(0xFF0F172A)
val LightTextSecondary = Color(0xFF64748B)
val LightTextTertiary = Color(0xFF94A3B8)
val LightPositive = Color(0xFF16A34A)
val LightDanger = Color(0xFFDC2626)

val DarkColorScheme = darkColorScheme(
    primary = DarkAccent,
    onPrimary = DarkTextPrimary,
    background = DarkBackground,
    onBackground = DarkTextPrimary,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkField,
    onSurfaceVariant = DarkTextSecondary,
    outline = DarkStroke,
    error = DarkDanger,
)

val LightColorScheme = lightColorScheme(
    primary = LightAccent,
    onPrimary = Color.White,
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightField,
    onSurfaceVariant = LightTextSecondary,
    outline = LightStroke,
    error = LightDanger,
)

// Dynamic token accessors preserving existing public API
val Background: Color
    get() = if (ThemePreferences.isDark) DarkBackground else LightBackground

val Surface: Color
    get() = if (ThemePreferences.isDark) DarkSurface else LightSurface

val Field: Color
    get() = if (ThemePreferences.isDark) DarkField else LightField

val Stroke: Color
    get() = if (ThemePreferences.isDark) DarkStroke else LightStroke

val Accent: Color
    get() = if (ThemePreferences.isDark) DarkAccent else LightAccent

val AccentSoft: Color
    get() = if (ThemePreferences.isDark) DarkAccentSoft else LightAccentSoft

val TextPrimary: Color
    get() = if (ThemePreferences.isDark) DarkTextPrimary else LightTextPrimary

val TextSecondary: Color
    get() = if (ThemePreferences.isDark) DarkTextSecondary else LightTextSecondary

val TextTertiary: Color
    get() = if (ThemePreferences.isDark) DarkTextTertiary else LightTextTertiary

val Positive: Color
    get() = if (ThemePreferences.isDark) DarkPositive else LightPositive

val Danger: Color
    get() = if (ThemePreferences.isDark) DarkDanger else LightDanger

// Bundled (OFL, licence in the TTF metadata): Samsung's font setting replaces the system FontFamily.Serif with a sans.
val Serif = FontFamily(Font(R.font.noto_serif_bold, FontWeight.Bold))

private val AppTypography = Typography(
    displaySmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 32.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 13.sp),
)

@Composable
fun BookconverterTheme(
    themeMode: AppThemeMode = ThemePreferences.themeMode.collectAsState().value,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        AppThemeMode.AMOLED -> true
        AppThemeMode.LIGHT -> false
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    ThemePreferences.isDark = isDark
    val colors = if (isDark) DarkColorScheme else LightColorScheme

    MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
}
