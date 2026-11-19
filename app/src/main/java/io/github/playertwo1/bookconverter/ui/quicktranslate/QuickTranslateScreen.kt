package io.github.playertwo1.bookconverter.ui.quicktranslate

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface as M3Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.playertwo1.bookconverter.ui.theme.Accent
import io.github.playertwo1.bookconverter.ui.theme.Background
import io.github.playertwo1.bookconverter.ui.theme.Danger
import io.github.playertwo1.bookconverter.ui.theme.Positive
import io.github.playertwo1.bookconverter.ui.theme.Stroke
import io.github.playertwo1.bookconverter.ui.theme.Surface
import io.github.playertwo1.bookconverter.ui.theme.TextPrimary
import io.github.playertwo1.bookconverter.ui.theme.TextSecondary
import io.github.playertwo1.bookconverter.ui.theme.TextTertiary

@Composable
fun QuickTranslateScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: QuickTranslateViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboardManager = LocalClipboardManager.current

    LifecycleResumeEffect(Unit) {
        viewModel.refreshSettings()
        onPauseOrDispose { }
    }

    val txtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportFormat.TXT.mimeType)
    ) { uri ->
        uri?.let { viewModel.exportToUri(it, ExportFormat.TXT) }
    }

    val docLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportFormat.DOC.mimeType)
    ) { uri ->
        uri?.let { viewModel.exportToUri(it, ExportFormat.DOC) }
    }

    val pdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportFormat.PDF.mimeType)
    ) { uri ->
        uri?.let { viewModel.exportToUri(it, ExportFormat.PDF) }
    }

    val mdLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportFormat.MD.mimeType)
    ) { uri ->
        uri?.let { viewModel.exportToUri(it, ExportFormat.MD) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .clickable(role = Role.Button, onClickLabel = "Voltar", onClick = onBack)
                    .semantics { contentDescription = "Voltar" },
                contentAlignment = Alignment.Center,
            ) {
                Text("‹", fontSize = 28.sp, color = TextPrimary)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "Traduzir texto",
                fontSize = 17.sp,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // Título (opcional)
        OutlinedTextField(
            value = state.title,
            onValueChange = viewModel::setTitle,
            label = { Text("Título (opcional)") },
            placeholder = { Text("Ex.: Capítulo 1 ou Artigo") },
            singleLine = true,
            colors = fieldColors(),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        // Texto original
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Texto original",
                color = TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Row {
                TextButton(
                    onClick = {
                        val text = clipboardManager.getText()?.text.orEmpty()
                        if (text.isNotBlank()) {
                            viewModel.setSourceText(text)
                        }
                    },
                ) {
                    Text("Colar", color = Accent, fontSize = 13.sp)
                }
                if (state.sourceText.isNotEmpty()) {
                    TextButton(onClick = viewModel::clearSourceText) {
                        Text("Limpar", color = TextTertiary, fontSize = 13.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = state.sourceText,
            onValueChange = viewModel::setSourceText,
            placeholder = { Text("Cole ou digite aqui o texto em inglês…") },
            minLines = 5,
            maxLines = 12,
            colors = fieldColors(),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.sourceText.isNotEmpty()) {
            Text(
                "${state.sourceText.length} caracteres",
                color = TextTertiary,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp),
            )
        }

        // Seletor compacto de Série + Indicação do Provedor
        Spacer(Modifier.height(16.dp))
        M3Surface(
            shape = RoundedCornerShape(16.dp),
            color = Surface,
            border = BorderStroke(1.dp, Stroke),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Provedor ativo", color = TextSecondary, fontSize = 13.sp)
                    Text(
                        text = state.providerLabel.ifBlank { "Nenhum provedor" },
                        color = if (state.hasCredentials) TextPrimary else Danger,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }

                Spacer(Modifier.height(12.dp))

                var seriesMenuExpanded by remember { mutableStateOf(false) }
                val selectedSeries = state.seriesList.find { it.id == state.selectedSeriesId }

                Box {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { seriesMenuExpanded = true }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text("Série (glossário/estilo)", color = TextSecondary, fontSize = 13.sp)
                            Text(
                                text = selectedSeries?.name ?: "Nenhuma série selecionada",
                                color = if (selectedSeries != null) Accent else TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Text("Trocar ▾", color = Accent, fontSize = 13.sp)
                    }

                    DropdownMenu(
                        expanded = seriesMenuExpanded,
                        onDismissRequest = { seriesMenuExpanded = false },
                        modifier = Modifier.background(Surface),
                    ) {
                        DropdownMenuItem(
                            text = { Text("Nenhuma (sem glossário/estilo)", color = TextPrimary) },
                            onClick = {
                                viewModel.selectSeries(null)
                                seriesMenuExpanded = false
                            },
                        )
                        state.seriesList.forEach { s ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(s.name, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                                        if (s.styleNotes.isNotBlank()) {
                                            Text(
                                                s.styleNotes.take(40) + if (s.styleNotes.length > 40) "…" else "",
                                                color = TextSecondary,
                                                fontSize = 11.sp,
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    viewModel.selectSeries(s.id)
                                    seriesMenuExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        }

        // Botão Traduzir
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = viewModel::translate,
            enabled = !state.isTranslating && state.hasCredentials && state.sourceText.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Accent,
                disabledContainerColor = Accent.copy(alpha = 0.3f),
            ),
        ) {
            if (state.isTranslating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = TextPrimary,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    state.progressText ?: "Traduzindo…",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            } else {
                Text(
                    "Traduzir",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        if (!state.hasCredentials) {
            Text(
                "Configure um provedor de IA em Ajustes para traduzir.",
                color = Danger,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp, start = 4.dp),
            )
        }

        // Mensagens de erro e status
        state.errorMessage?.let { errorMsg ->
            Spacer(Modifier.height(12.dp))
            M3Surface(
                shape = RoundedCornerShape(12.dp),
                color = Danger.copy(alpha = 0.15f),
                border = BorderStroke(1.dp, Danger.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = errorMsg,
                        color = Danger,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::clearError) {
                        Text("✕", color = Danger, fontSize = 14.sp)
                    }
                }
            }
        }

        state.statusMessage?.let { statusMsg ->
            Spacer(Modifier.height(12.dp))
            M3Surface(
                shape = RoundedCornerShape(12.dp),
                color = Positive.copy(alpha = 0.15f),
                border = BorderStroke(1.dp, Positive.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = statusMsg,
                        color = Positive,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::clearStatus) {
                        Text("✕", color = Positive, fontSize = 14.sp)
                    }
                }
            }
        }

        // Seção Tradução (quando houver tradução)
        if (state.translatedText.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Tradução (pt-BR)",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(state.translatedText))
                    },
                ) {
                    Text("Copiar", color = Accent, fontSize = 13.sp)
                }
            }

            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = state.translatedText,
                onValueChange = viewModel::setTranslatedText,
                minLines = 5,
                maxLines = 15,
                colors = fieldColors(),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            state.tokensSummary?.let { summary ->
                Spacer(Modifier.height(6.dp))
                Text(
                    summary,
                    color = TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "Exportar tradução",
                color = TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ExportButton(
                        label = ExportFormat.TXT.label,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            txtLauncher.launch(suggestedFileName(state.title, ExportFormat.TXT))
                        },
                    )
                    ExportButton(
                        label = ExportFormat.DOC.label,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            docLauncher.launch(suggestedFileName(state.title, ExportFormat.DOC))
                        },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ExportButton(
                        label = ExportFormat.PDF.label,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            pdfLauncher.launch(suggestedFileName(state.title, ExportFormat.PDF))
                        },
                    )
                    ExportButton(
                        label = ExportFormat.MD.label,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            mdLauncher.launch(suggestedFileName(state.title, ExportFormat.MD))
                        },
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ExportButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Stroke),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Surface,
            contentColor = TextPrimary,
        ),
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

private fun suggestedFileName(title: String, format: ExportFormat): String {
    val base = title.trim().ifBlank { "traducao" }
        .replace(Regex("""[\\/:*?"<>|]"""), "_")
    return "$base.${format.extension}"
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Accent,
    unfocusedBorderColor = Stroke,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedLabelColor = TextSecondary,
    unfocusedLabelColor = TextSecondary,
    focusedPlaceholderColor = TextTertiary,
    unfocusedPlaceholderColor = TextTertiary,
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    cursorColor = Accent,
)
