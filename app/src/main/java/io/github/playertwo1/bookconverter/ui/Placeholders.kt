package io.github.playertwo1.bookconverter.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.playertwo1.bookconverter.ui.theme.TextPrimary
import io.github.playertwo1.bookconverter.ui.theme.TextSecondary

@Composable
fun SeriesScreen() = PlaceholderScreen("Séries", "Séries e glossários chegam na fase P3.")

@Composable
fun ActivityScreen() = PlaceholderScreen("Atividade", "A fila de traduções chega na fase P2.")

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    PlaceholderScreen("Ajustes", "Bookconverter $version · provedores de IA chegam na fase P1.")
}

@Composable
private fun PlaceholderScreen(title: String, line: String) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp)) {
        Text(title, style = MaterialTheme.typography.displaySmall, color = TextPrimary)
        Text(line, color = TextSecondary, modifier = Modifier.padding(top = 12.dp))
    }
}
