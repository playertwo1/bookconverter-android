package io.github.playertwo1.bookconverter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.playertwo1.bookconverter.ui.AppScaffold
import io.github.playertwo1.bookconverter.ui.theme.BookconverterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BookconverterTheme { AppScaffold() } }
    }
}
