package io.github.playertwo1.bookconverter.ui.review

import android.app.Application
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface as M3Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.playertwo1.bookconverter.ui.theme.Accent
import io.github.playertwo1.bookconverter.ui.theme.AccentSoft
import io.github.playertwo1.bookconverter.ui.theme.Background
import io.github.playertwo1.bookconverter.ui.theme.Danger
import io.github.playertwo1.bookconverter.ui.theme.Field
import io.github.playertwo1.bookconverter.ui.theme.Positive
import io.github.playertwo1.bookconverter.ui.theme.Stroke
import io.github.playertwo1.bookconverter.ui.theme.Surface
import io.github.playertwo1.bookconverter.ui.theme.TextPrimary
import io.github.playertwo1.bookconverter.ui.theme.TextSecondary
import io.github.playertwo1.bookconverter.ui.theme.TextTertiary
import kotlinx.coroutines.launch

private val WarningSoft = Color(0x2EF59E0B)
private val WarningText = Color(0xFFFBBF24)
private val PositiveSoft = Color(0x2E35B77B)
private val DangerSoft = Color(0x2EF36D7A)

@Composable
fun ReviewExportScreen(
    jobId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val vm: ReviewExportViewModel = viewModel(key = "review-$jobId") {
        ReviewExportViewModel(context.applicationContext as Application).apply {
            load(jobId)
        }
    }

    LaunchedEffect(jobId) {
        vm.load(jobId)
    }

    val uiState by vm.uiState.collectAsStateWithLifecycle()

    var filter by rememberSaveable { mutableStateOf(ReviewFilter.TODOS) }

    var editingChunkId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editingBlockIndex by rememberSaveable { mutableIntStateOf(0) }
    var editingGlobalIndex by rememberSaveable { mutableIntStateOf(0) }
    var editingText by rememberSaveable { mutableStateOf("") }

    val safeTitle = remember(uiState.book?.title) {
        (uiState.book?.title ?: "livro")
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .trim('_')
            .take(50)
            .ifBlank { "livro" }
    }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/epub+zip")
    ) { uri ->
        if (uri != null) {
            vm.exportToUri(uri)
        }
    }

    val filteredBlocks = remember(uiState.blocks, filter) {
        when (filter) {
            ReviewFilter.TODOS -> uiState.blocks
            ReviewFilter.PENDENTES -> uiState.blocks.filter { it.translatedText.isNullOrBlank() }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Top Bar
            item(key = "top_bar") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable(role = Role.Button, onClickLabel = "Voltar", onClick = onBack)
                            .semantics { contentDescription = "Voltar" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("‹", fontSize = 28.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
                    }

                    Spacer(Modifier.width(8.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = uiState.book?.title ?: "Revisão e Exportação",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!uiState.book?.author.isNullOrBlank()) {
                            Text(
                                text = uiState.book?.author.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            // Export Card (D7)
            item(key = "export_card") {
                M3Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = Surface,
                    border = BorderStroke(1.dp, Stroke),
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Exportar EPUB pt-BR (D7)",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary,
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(AccentSoft)
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    text = "pt-BR",
                                    fontSize = 11.sp,
                                    color = Accent,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        val total = uiState.totalBlocksCount
                        val translated = uiState.translatedBlocksCount
                        val progressFraction = if (total > 0) translated.toFloat() / total.toFloat() else 0f
                        val percentage = (progressFraction * 100).toInt()

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "$translated / $total blocos",
                                fontSize = 13.sp,
                                color = TextSecondary,
                            )
                            Text(
                                text = "$percentage%",
                                fontSize = 13.sp,
                                color = if (progressFraction >= 1f) Positive else Accent,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        LinearProgressIndicator(
                            progress = { progressFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = Accent,
                            trackColor = Field,
                        )

                        Spacer(Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Button(
                                onClick = {
                                    createDocumentLauncher.launch("$safeTitle-pt-BR.epub")
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 48.dp)
                                    .semantics { contentDescription = "Salvar EPUB" },
                                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                                shape = RoundedCornerShape(10.dp),
                                enabled = !uiState.isExporting,
                            ) {
                                Text("Salvar EPUB", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                            }

                            OutlinedButton(
                                onClick = {
                                    coroutineScope.launch {
                                        val shareResult = vm.prepareShareExport()
                                        if (shareResult != null) {
                                            val (uri, _) = shareResult
                                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "application/epub+zip"
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            val chooser = Intent.createChooser(sendIntent, "Compartilhar EPUB pt-BR").apply {
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(chooser)
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 48.dp)
                                    .semantics { contentDescription = "Compartilhar EPUB" },
                                border = BorderStroke(1.dp, Stroke),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                                shape = RoundedCornerShape(10.dp),
                                enabled = !uiState.isExporting,
                            ) {
                                Text("Compartilhar", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        // Validation Report Banner
                        uiState.exportReport?.let { report ->
                            Spacer(Modifier.height(14.dp))
                            if (report.valid) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(PositiveSoft)
                                        .border(1.dp, Positive, RoundedCornerShape(8.dp))
                                        .padding(12.dp),
                                ) {
                                    Text(
                                        text = "EPUB válido · ${report.spineCountExported}/${report.spineCountOriginal} capítulos · ${report.translatedBlocks} blocos traduzidos · original intacto",
                                        color = Positive,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(DangerSoft)
                                        .border(1.dp, Danger, RoundedCornerShape(8.dp))
                                        .padding(12.dp),
                                ) {
                                    Text(
                                        text = "Aviso na validação do EPUB: ${report.issues.joinToString("; ")}",
                                        color = Danger,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Status message banner
            uiState.statusMessage?.let { status ->
                item(key = "status_banner") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(PositiveSoft)
                            .border(1.dp, Positive, RoundedCornerShape(10.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(status, color = Positive, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clickable(role = Role.Button, onClickLabel = "Fechar") { vm.clearStatusMessage() }
                                    .semantics { contentDescription = "Fechar mensagem de status" },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("✕", color = Positive, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }

            // Error message banner
            uiState.errorMessage?.let { error ->
                item(key = "error_banner") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(DangerSoft)
                            .border(1.dp, Danger, RoundedCornerShape(10.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(error, color = Danger, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clickable(role = Role.Button, onClickLabel = "Fechar") { vm.clearErrorMessage() }
                                    .semantics { contentDescription = "Fechar mensagem de erro" },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("✕", color = Danger, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }

            // Filters
            item(key = "filter_row") {
                val pendingCount = uiState.totalBlocksCount - uiState.translatedBlocksCount
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FilterPill(
                        label = "Todos (${uiState.totalBlocksCount})",
                        selected = filter == ReviewFilter.TODOS,
                        onClick = { filter = ReviewFilter.TODOS },
                        modifier = Modifier.weight(1f),
                    )

                    FilterPill(
                        label = "Pendentes ($pendingCount)",
                        selected = filter == ReviewFilter.PENDENTES,
                        onClick = { filter = ReviewFilter.PENDENTES },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // Blocks list
            if (filteredBlocks.isEmpty()) {
                item(key = "empty_blocks") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (filter == ReviewFilter.PENDENTES) "Nenhum bloco pendente!" else "Nenhum bloco disponível.",
                            color = TextSecondary,
                            fontSize = 14.sp,
                        )
                    }
                }
            } else {
                items(
                    items = filteredBlocks,
                    key = { "${it.chunkId}_${it.blockInChunkIndex}" },
                ) { blockItem ->
                    ReviewBlockCard(
                        item = blockItem,
                        isRetranslating = uiState.retranslatingBlockKey == "${blockItem.chunkId}:${blockItem.blockInChunkIndex}",
                        onEdit = {
                            editingChunkId = blockItem.chunkId
                            editingBlockIndex = blockItem.blockInChunkIndex
                            editingGlobalIndex = blockItem.globalIndex
                            editingText = blockItem.translatedText ?: blockItem.originalText
                        },
                        onRetranslate = {
                            vm.retranslateBlock(blockItem.chunkId, blockItem.blockInChunkIndex)
                        },
                    )
                }
            }
        }

        // Edit Dialog
        if (editingChunkId != null) {
            AlertDialog(
                onDismissRequest = { editingChunkId = null },
                containerColor = Surface,
                titleContentColor = TextPrimary,
                textContentColor = TextPrimary,
                shape = RoundedCornerShape(16.dp),
                title = {
                    Text(
                        text = "Editar Bloco #$editingGlobalIndex",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                    )
                },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Ajuste o texto traduzido (pt-BR):",
                            fontSize = 12.sp,
                            color = TextSecondary,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = editingText,
                            onValueChange = { editingText = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 140.dp, max = 260.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedBorderColor = Accent,
                                unfocusedBorderColor = Stroke,
                                focusedContainerColor = Field,
                                unfocusedContainerColor = Field,
                                cursorColor = Accent,
                            ),
                            shape = RoundedCornerShape(10.dp),
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            editingChunkId?.let { chunkId ->
                                vm.saveEditedBlock(chunkId, editingBlockIndex, editingText)
                            }
                            editingChunkId = null
                        },
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .semantics { contentDescription = "Salvar edição do bloco" },
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("Salvar", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { editingChunkId = null },
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .semantics { contentDescription = "Cancelar edição do bloco" },
                    ) {
                        Text("Cancelar", color = TextSecondary)
                    }
                },
            )
        }
    }
}

@Composable
private fun FilterPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) AccentSoft else Field)
            .border(1.dp, if (selected) Accent else Stroke, RoundedCornerShape(12.dp))
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = if (selected) TextPrimary else TextSecondary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun ReviewBlockCard(
    item: ReviewBlockItem,
    isRetranslating: Boolean,
    onEdit: () -> Unit,
    onRetranslate: () -> Unit,
) {
    val isTranslated = !item.translatedText.isNullOrBlank()

    M3Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Surface,
        border = BorderStroke(1.dp, Stroke),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: Block Index and Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Bloco #${item.globalIndex}",
                    fontSize = 14.sp,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )

                if (isTranslated) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(PositiveSoft)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(
                            text = "Traduzido",
                            fontSize = 11.sp,
                            color = Positive,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(WarningSoft)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(
                            text = "Pendente",
                            fontSize = 11.sp,
                            color = WarningText,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Original Block Section
            Text(
                text = "ORIGINAL",
                fontSize = 11.sp,
                color = TextTertiary,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
            )
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Field)
                    .border(1.dp, Stroke, RoundedCornerShape(8.dp))
                    .padding(12.dp),
            ) {
                Text(
                    text = item.originalText,
                    fontSize = 13.sp,
                    color = TextPrimary,
                    lineHeight = 18.sp,
                )
            }

            Spacer(Modifier.height(10.dp))

            // pt-BR Translation Section
            Text(
                text = "TRADUÇÃO (PT-BR)",
                fontSize = 11.sp,
                color = TextTertiary,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
            )
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Field)
                    .border(1.dp, Stroke, RoundedCornerShape(8.dp))
                    .padding(12.dp),
            ) {
                if (isTranslated) {
                    Text(
                        text = item.translatedText.orEmpty(),
                        fontSize = 13.sp,
                        color = TextPrimary,
                        lineHeight = 18.sp,
                    )
                } else {
                    Text(
                        text = "(Bloco ainda não traduzido)",
                        fontSize = 13.sp,
                        color = TextTertiary,
                        fontStyle = FontStyle.Italic,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // Action Buttons Row (minHeight >= 48dp, contentDescription on both)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onEdit,
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .semantics { contentDescription = "Editar bloco #${item.globalIndex}" },
                    border = BorderStroke(1.dp, Stroke),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("Editar", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }

                Button(
                    onClick = onRetranslate,
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .semantics { contentDescription = "Retraduzir bloco #${item.globalIndex}" },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    shape = RoundedCornerShape(8.dp),
                    enabled = !isRetranslating,
                ) {
                    if (isRetranslating) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = TextPrimary,
                            )
                            Text("Retraduzindo…", fontSize = 12.sp, color = TextPrimary)
                        }
                    } else {
                        Text("Retraduzir", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
