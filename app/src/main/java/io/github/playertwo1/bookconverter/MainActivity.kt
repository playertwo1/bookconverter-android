package io.github.playertwo1.bookconverter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.playertwo1.bookconverter.ui.AppScaffold
import io.github.playertwo1.bookconverter.ui.theme.BookconverterTheme
import io.github.playertwo1.bookconverter.ui.theme.ThemePreferences

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemePreferences.init(applicationContext)
        enableEdgeToEdge()
        setContent {
            val themeMode by ThemePreferences.themeMode.collectAsState()
            BookconverterTheme(themeMode = themeMode) {
                AppScaffold()
            }
        }
    }
}
