package io.github.playertwo1.bookconverter.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Surface as M3Surface
import io.github.playertwo1.bookconverter.ui.activity.ActivityScreen
import io.github.playertwo1.bookconverter.ui.library.LibraryScreen
import io.github.playertwo1.bookconverter.ui.quicktranslate.QuickTranslateScreen
import io.github.playertwo1.bookconverter.ui.series.SeriesScreen
import io.github.playertwo1.bookconverter.ui.settings.ProvidersScreen
import io.github.playertwo1.bookconverter.ui.translate.NewTranslationScreen
import io.github.playertwo1.bookconverter.ui.verify.VerificationScreen
import io.github.playertwo1.bookconverter.ui.theme.AccentSoft
import io.github.playertwo1.bookconverter.ui.theme.Background
import io.github.playertwo1.bookconverter.ui.theme.Stroke
import io.github.playertwo1.bookconverter.ui.theme.Surface
import io.github.playertwo1.bookconverter.ui.theme.TextPrimary
import io.github.playertwo1.bookconverter.ui.theme.TextSecondary

enum class Tab(val label: String) {
    Biblioteca("Biblioteca"),
    Series("Séries"),
    Atividade("Atividade"),
    Ajustes("Ajustes"),
}

@Composable
fun AppScaffold() {
    var tab by rememberSaveable { mutableStateOf(Tab.Biblioteca) }
    var openBookId by rememberSaveable { mutableStateOf<Long?>(null) }
    var translateBookId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showProviders by rememberSaveable { mutableStateOf(false) }
    var showQuickTranslate by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Background)) {
        val verifying = openBookId
        val translating = translateBookId
        when {
            showQuickTranslate -> {
                BackHandler { showQuickTranslate = false }
                QuickTranslateScreen(onBack = { showQuickTranslate = false })
            }
            showProviders -> {
                BackHandler { showProviders = false }
                ProvidersScreen(onBack = { showProviders = false })
            }
            translating != null -> {
                BackHandler { translateBookId = null }
                NewTranslationScreen(
                    translating,
                    onBack = { translateBookId = null },
                    onOpenProviders = { showProviders = true },
                    onStarted = {
                        translateBookId = null
                        openBookId = null
                        tab = Tab.Atividade
                    },
                )
            }
            verifying != null -> {
                // Back from the translation returns here, since openBookId is kept.
                BackHandler { openBookId = null }
                VerificationScreen(
                    verifying,
                    onBack = { openBookId = null },
                    onStartTranslation = { translateBookId = it },
                )
            }
            else -> {
                when (tab) {
                    Tab.Biblioteca -> LibraryScreen(
                        onOpenBook = { openBookId = it },
                        onOpenQuickTranslate = { showQuickTranslate = true },
                    )
                    Tab.Series -> SeriesScreen()
                    Tab.Atividade -> ActivityScreen()
                    Tab.Ajustes -> SettingsScreen(onOpenProviders = { showProviders = true })
                }
                FloatingNav(selected = tab, onSelect = { tab = it }, modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

@Composable
private fun FloatingNav(selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    // 24dp side margins come from the wrapper; the inner Row keeps the pills off the capsule edge.
    M3Surface(
        modifier = modifier
            .navigationBarsPadding()
            .padding(start = 24.dp, end = 24.dp, bottom = 8.dp)
            .fillMaxWidth()
            .height(64.dp),
        shape = RoundedCornerShape(50),
        color = Surface,
        border = BorderStroke(1.dp, Stroke),
    ) {
        Row(Modifier.fillMaxSize().padding(6.dp).selectableGroup()) {
            Tab.entries.forEach { tab ->
                val isSelected = tab == selected
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .defaultMinSize(minHeight = 48.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (isSelected) AccentSoft else Color.Transparent)
                        .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(tab) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = tab.label,
                        color = if (isSelected) TextPrimary else TextSecondary,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}
