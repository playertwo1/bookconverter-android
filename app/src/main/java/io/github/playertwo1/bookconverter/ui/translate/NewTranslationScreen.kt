package io.github.playertwo1.bookconverter.ui.translate

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.text.Html
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface as M3Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.playertwo1.bookconverter.core.provider.EFFORT_LEVELS
import io.github.playertwo1.bookconverter.core.provider.Provider
import io.github.playertwo1.bookconverter.core.translate.Estimate
import io.github.playertwo1.bookconverter.core.translate.PreviewResult
import io.github.playertwo1.bookconverter.data.label
import io.github.playertwo1.bookconverter.data.privacyUrl
import io.github.playertwo1.bookconverter.data.shortLabel
import io.github.playertwo1.bookconverter.ui.count
import io.github.playertwo1.bookconverter.ui.theme.Accent
import io.github.playertwo1.bookconverter.ui.theme.AccentSoft
import io.github.playertwo1.bookconverter.ui.theme.Background
import io.github.playertwo1.bookconverter.ui.theme.Danger
import io.github.playertwo1.bookconverter.ui.theme.Field
import io.github.playertwo1.bookconverter.ui.theme.Serif
import io.github.playertwo1.bookconverter.ui.theme.Stroke
import io.github.playertwo1.bookconverter.ui.theme.Surface
import io.github.playertwo1.bookconverter.ui.theme.TextPrimary
import io.github.playertwo1.bookconverter.ui.theme.TextSecondary
import io.github.playertwo1.bookconverter.ui.theme.TextTertiary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewTranslationScreen(
    bookId: Long,
    onBack: () -> Unit,
    onOpenProviders: () -> Unit,
    onStarted: () -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as Application
    val vm: NewTranslationViewModel = viewModel(key = "translate-$bookId") { NewTranslationViewModel(app, bookId) }
    val state by vm.state.collectAsStateWithLifecycle()
    // Keys may have been added or removed on the providers screen.
    LifecycleResumeEffect(Unit) {
        vm.refreshProviders()
        onPauseOrDispose { }
    }
    var showSheet by remember { mutableStateOf(false) }
    var showSeriesSheet by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    val context = LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        vm.startTranslation(onStarted = onStarted)
    }

    val onStartClick = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                vm.startTranslation(onStarted = onStarted)
            }
        } else {
            vm.startTranslation(onStarted = onStarted)
        }
    }

    val err = state.loadError
    val book = state.book
    val chunks = state.chunks

    Column(
        Modifier
            .fillMaxSize()
            .background(Background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(48.dp)
                    .clickable(role = Role.Button, onClickLabel = "Voltar", onClick = onBack)
                    .semantics { contentDescription = "Voltar" },
                contentAlignment = Alignment.Center,
            ) {
                Text("‹", fontSize = 28.sp, color = TextPrimary)
            }
            Text("Nova tradução", fontSize = 17.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold)
        }

        if (err != null) {
            Text(err, color = Danger, modifier = Modifier.padding(top = 24.dp))
        } else if (book == null || chunks == null) {
            Column(
                Modifier.fillMaxWidth().padding(top = 96.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = Accent)
                Spacer(Modifier.height(12.dp))
                Text("Lendo o livro…", color = TextSecondary)
            }
        } else {
            Spacer(Modifier.height(16.dp))
            Card {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Accent))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        book.title,
                        color = TextPrimary,
                        fontSize = 17.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "Inglês → Português (Brasil) · ${count(chunks.size)} trechos",
                        color = TextSecondary,
                        fontSize = 13.sp,
                    )
                }
            }

            SectionTitle("Série e Glossário")
            val selectedSeriesSummary = state.selectedSeries
            Card(onClick = { showSeriesSheet = true }) {
                Column(Modifier.weight(1f)) {
                    if (selectedSeriesSummary != null) {
                        Text(
                            selectedSeriesSummary.series.name,
                            color = TextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        val termText = "${count(selectedSeriesSummary.termCount)} ${if (selectedSeriesSummary.termCount == 1) "termo" else "termos"}"
                        val versionText = "v${selectedSeriesSummary.series.glossaryVersion}"
                        val styleText = if (selectedSeriesSummary.series.styleNotes.isNotBlank()) " · com estilo" else ""
                        Text(
                            "$termText · Glossário $versionText$styleText",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    } else {
                        Text(
                            "Sem série",
                            color = TextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Nenhum termo de glossário ou estilo aplicado",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                Text("›", color = TextSecondary, fontSize = 17.sp)
            }

            SectionTitle("Provedor de IA")
            if (state.available.isEmpty()) {
                Card(onClick = onOpenProviders) {
                    Column(Modifier.weight(1f)) {
                        Text("Nenhuma chave salva", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text("Adicione uma chave em Provedores de IA", color = TextSecondary, fontSize = 13.sp)
                    }
                    Text("›", color = TextSecondary, fontSize = 17.sp)
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.available.forEach { p ->
                        Chip(
                            label = p.shortLabel,
                            selected = state.provider == p,
                            enabled = true,
                            onClick = { vm.selectProvider(p) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                TextButton(onClick = onOpenProviders) {
                    Text("Configurar provedores", color = TextSecondary, fontSize = 13.sp)
                }
            }

            val provider = state.provider
            if (provider != null) {
                Text("Modelo", color = TextSecondary, fontSize = 15.sp, modifier = Modifier.padding(top = 16.dp))
                when (val m = state.models) {
                    Models.Idle, Models.Loading -> Row(
                        Modifier.padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = Accent, strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Carregando modelos…", color = TextSecondary, fontSize = 13.sp)
                    }
                    is Models.Failed -> {
                        Text(m.message, color = Danger, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                        TextButton(onClick = vm::retryModels) {
                            Text("Tentar de novo", color = Accent, fontSize = 13.sp)
                        }
                    }
                    is Models.Ready -> if (m.ids.isEmpty()) {
                        Text(
                            "Nenhum modelo de texto disponível para esta chave.",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    } else {
                        Card(onClick = { showSheet = true }) {
                            Text(
                                state.model ?: "Selecionar modelo compatível",
                                color = TextPrimary,
                                fontSize = 17.sp,
                                modifier = Modifier.weight(1f),
                            )
                            Text("›", color = TextSecondary, fontSize = 17.sp)
                        }
                    }
                }
            }

            if (state.model != null) {
                Text("Esforço de tradução", color = TextSecondary, fontSize = 15.sp, modifier = Modifier.padding(top = 16.dp))
                val efforts: List<String?> = listOf(null) + EFFORT_LEVELS
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp).selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    efforts.forEach { e ->
                        Chip(
                            label = effortLabel(e),
                            selected = state.effort == e,
                            enabled = e == null || e !in state.rejectedEfforts,
                            onClick = { vm.selectEffort(e) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Text(
                    "Padrão deixa o provedor decidir. Níveis que o modelo recusar ficam indisponíveis.",
                    color = TextTertiary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )

                Card(
                    modifier = Modifier.padding(top = 16.dp),
                    onClick = if (state.supportsBatch) { { vm.setUseBatchMode(!state.useBatchMode) } } else null,
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            "Modo econômico (Batch API)",
                            color = if (state.supportsBatch) TextPrimary else TextSecondary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Custo menor em lote assíncrono (até 24h). Disponível apenas com chave API (OpenAI, Anthropic, Gemini).",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Switch(
                        checked = state.useBatchMode && state.supportsBatch,
                        onCheckedChange = if (state.supportsBatch) { { vm.setUseBatchMode(it) } } else null,
                        enabled = state.supportsBatch,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = TextPrimary,
                            checkedTrackColor = Accent,
                            uncheckedThumbColor = TextSecondary,
                            uncheckedTrackColor = Field,
                            disabledCheckedTrackColor = Accent.copy(alpha = 0.5f),
                            disabledUncheckedTrackColor = Field.copy(alpha = 0.5f),
                            disabledUncheckedThumbColor = TextTertiary,
                        ),
                        modifier = Modifier.semantics {
                            contentDescription = "Modo econômico (Batch API)"
                        },
                    )
                }
            }

            SectionTitle("Prévia e custo estimado")
            state.sample?.let { sample ->
                Text(
                    "Traduz um trecho de ${count(sample.htmlChars)} caracteres para você conferir a qualidade e estimar os tokens do livro inteiro.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
            }
            AppButton(
                "Gerar prévia",
                enabled = state.canPreview,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) { vm.requestPreview() }
            if (state.preview == PreviewState.Running) {
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Accent, strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Traduzindo a amostra…", color = TextSecondary, fontSize = 13.sp)
                }
            }

            when (val pv = state.preview) {
                PreviewState.Idle, PreviewState.Running -> Unit
                is PreviewState.Failed -> Card {
                    Text("!", color = Danger, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(16.dp))
                    Text(pv.message, color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                }
                is PreviewState.Done -> PreviewDone(
                    result = pv.result,
                    estimate = pv.estimate,
                    budget = state.budget,
                    overBudget = state.overBudget,
                    onBudget = vm::setBudget,
                )
            }

            if (state.startError != null) {
                Text(
                    state.startError ?: "",
                    color = Danger,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            Spacer(Modifier.height(16.dp))
            AppButton(
                label = if (state.starting) "Iniciando…" else "Iniciar tradução",
                enabled = state.canStart,
                modifier = Modifier.fillMaxWidth(),
                onClick = onStartClick,
            )
            Spacer(Modifier.height(24.dp))

            if (state.askConsent && provider != null) {
                val sample = state.sample
                if (sample != null) {
                    AlertDialog(
                        onDismissRequest = vm::dismissConsent,
                        containerColor = Surface,
                        titleContentColor = TextPrimary,
                        textContentColor = TextSecondary,
                        title = { Text("Enviar texto para ${provider.label}?") },
                        text = {
                            Column {
                                Text(
                                    "Para gerar a prévia, o app envia um trecho do livro (${count(sample.htmlChars)} caracteres) " +
                                        "ao provedor ${provider.label} usando a sua " +
                                        (if (provider == Provider.CHATGPT) "conta do ChatGPT" else "chave") +
                                        ". A tradução completa enviaria o livro todo " +
                                        "(≈ ${count(state.bookChars)} caracteres). O provedor processa e pode reter o texto conforme a política dele:",
                                    fontSize = 13.sp,
                                )
                                Text(
                                    provider.privacyUrl,
                                    color = Accent,
                                    fontSize = 13.sp,
                                    textDecoration = TextDecoration.Underline,
                                    modifier = Modifier.padding(top = 8.dp).clickable { uriHandler.openUri(provider.privacyUrl) },
                                )
                                if (provider == Provider.GEMINI) {
                                    Text(
                                        "No nível gratuito da API Gemini, o Google pode usar o conteúdo para melhorar os produtos.",
                                        fontSize = 13.sp,
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = vm::acceptConsent) { Text("Enviar", color = Accent) }
                        },
                        dismissButton = {
                            TextButton(onClick = vm::dismissConsent) { Text("Cancelar", color = TextSecondary) }
                        },
                    )
                }
            }
        }
    }

    val ready = state.models as? Models.Ready
    if (showSheet && ready != null) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }, containerColor = Surface) {
            LazyColumn {
                items(ready.ids) { id ->
                    val selected = id == state.model
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .clickable {
                                vm.selectModel(id)
                                showSheet = false
                            }
                            .padding(horizontal = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            id,
                            color = if (selected) TextPrimary else TextSecondary,
                            fontSize = 15.sp,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected) Text("✓", color = Accent, fontSize = 17.sp)
                    }
                }
            }
        }
    }

    if (showSeriesSheet) {
        ModalBottomSheet(onDismissRequest = { showSeriesSheet = false }, containerColor = Surface) {
            LazyColumn(Modifier.padding(bottom = 24.dp)) {
                item {
                    val isNoneSelected = state.selectedSeriesId == null
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .clickable {
                                vm.selectSeries(null)
                                showSeriesSheet = false
                            }
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Sem série",
                                color = if (isNoneSelected) TextPrimary else TextSecondary,
                                fontSize = 15.sp,
                                fontWeight = if (isNoneSelected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            Text(
                                "Tradução sem glossário ou estilo de série",
                                color = TextTertiary,
                                fontSize = 12.sp,
                            )
                        }
                        if (isNoneSelected) Text("✓", color = Accent, fontSize = 17.sp)
                    }
                }

                items(state.seriesList) { summary ->
                    val selected = summary.series.id == state.selectedSeriesId
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .clickable {
                                vm.selectSeries(summary.series.id)
                                showSeriesSheet = false
                            }
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                summary.series.name,
                                color = if (selected) TextPrimary else TextSecondary,
                                fontSize = 15.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            val termText = "${count(summary.termCount)} ${if (summary.termCount == 1) "termo" else "termos"}"
                            val versionText = "v${summary.series.glossaryVersion}"
                            Text(
                                "$termText · Glossário $versionText",
                                color = TextTertiary,
                                fontSize = 12.sp,
                            )
                        }
                        if (selected) Text("✓", color = Accent, fontSize = 17.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewDone(
    result: PreviewResult,
    estimate: Estimate?,
    budget: String,
    overBudget: Boolean,
    onBudget: (String) -> Unit,
) {
    if (result.effortRejected) {
        Card(Modifier.padding(top = 12.dp)) {
            Text("!", color = Danger, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(16.dp))
            Text(
                "O modelo recusou o esforço escolhido; a prévia rodou sem ele e o nível ficou indisponível.",
                color = TextPrimary,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
        }
    }
    Text(
        "Modelo usado: ${result.model}" + (result.effortApplied?.let { " · esforço $it" } ?: ""),
        color = TextSecondary,
        fontSize = 13.sp,
        modifier = Modifier.padding(top = 12.dp),
    )
    Text("Amostra", color = TextSecondary, fontSize = 15.sp, modifier = Modifier.padding(top = 16.dp))
    val blocks = result.sample.blocks
    for (i in blocks.indices.take(6)) {
        Card(Modifier.padding(top = 8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(blocks[i].text, color = TextSecondary, fontSize = 13.sp)
                Text(
                    Html.fromHtml(result.translated.getOrElse(i) { "" }, Html.FROM_HTML_MODE_COMPACT).toString().trim(),
                    color = TextPrimary,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
    if (blocks.size > 6) {
        Text(
            "+ ${count(blocks.size - 6)} parágrafos na amostra",
            color = TextTertiary,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    val inTok = result.inputTokens
    val outTok = result.outputTokens
    Card(Modifier.padding(top = 12.dp)) {
        Text(
            if (inTok != null && outTok != null) {
                "Amostra: ${count(inTok)} de entrada · ${count(outTok)} de saída"
            } else {
                "O provedor não informou tokens"
            },
            color = TextSecondary,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
    }

    if (estimate != null) {
        Card(Modifier.padding(top = 8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Livro inteiro (estimado)", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "≈ ${count(estimate.total)} tokens",
                    color = TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "Entre ${count(estimate.low.total)} e ${count(estimate.high.total)} · ${count(estimate.calls)} chamadas",
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
                Text(
                    "${count(estimate.inputTokens)} de entrada · ${count(estimate.outputTokens)} de saída (inclui raciocínio)",
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
            }
        }
    } else {
        Text(
            "Sem dados de uso para estimar o livro.",
            color = TextSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    OutlinedTextField(
        value = budget,
        onValueChange = onBudget,
        label = { Text("Teto de tokens") },
        supportingText = { Text("A tradução para quando atingir o teto.") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
    )
    if (overBudget) {
        Text(
            "A estimativa alta passa do teto: a tradução pode parar antes do fim.",
            color = Danger,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = TextPrimary,
        fontFamily = Serif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        modifier = Modifier.padding(top = 24.dp, bottom = 12.dp),
    )
}

private fun effortLabel(effort: String?): String = when (effort) {
    null -> "Padrão"
    "low" -> "Rápido"
    "medium" -> "Médio"
    else -> "Alto"
}

@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) AccentSoft else Field)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                label,
                fontSize = 15.sp,
                color = when {
                    !enabled -> TextTertiary
                    selected -> TextPrimary
                    else -> TextSecondary
                },
            )
            if (!enabled) Text("indisponível", fontSize = 11.sp, color = TextTertiary)
        }
    }
}

@Composable
private fun AppButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = 56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Accent,
            contentColor = TextPrimary,
            disabledContainerColor = Field,
            disabledContentColor = TextTertiary,
        ),
    ) {
        Text(label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Accent,
    unfocusedBorderColor = Stroke,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedLabelColor = TextSecondary,
    unfocusedLabelColor = TextSecondary,
    focusedPlaceholderColor = TextSecondary,
    unfocusedPlaceholderColor = TextSecondary,
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
)

@Composable
private fun Card(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val base = if (onClick != null) modifier.clip(shape).clickable(role = Role.Button, onClick = onClick) else modifier
    M3Surface(
        modifier = base.fillMaxWidth(),
        color = Surface,
        shape = shape,
        border = BorderStroke(1.dp, Stroke),
    ) {
        Row(
            Modifier.defaultMinSize(minHeight = 64.dp).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}
