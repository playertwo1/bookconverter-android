package io.github.playertwo1.bookconverter.ui.series

import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.playertwo1.bookconverter.data.Book
import io.github.playertwo1.bookconverter.data.GlossaryEntry
import io.github.playertwo1.bookconverter.data.Series
import io.github.playertwo1.bookconverter.data.SeriesSummary
import io.github.playertwo1.bookconverter.ui.sizeLabel
import io.github.playertwo1.bookconverter.ui.theme.Accent
import io.github.playertwo1.bookconverter.ui.theme.AccentSoft
import io.github.playertwo1.bookconverter.ui.theme.Danger
import io.github.playertwo1.bookconverter.ui.theme.Field
import io.github.playertwo1.bookconverter.ui.theme.Serif
import io.github.playertwo1.bookconverter.ui.theme.Stroke
import io.github.playertwo1.bookconverter.ui.theme.Surface
import io.github.playertwo1.bookconverter.ui.theme.TextPrimary
import io.github.playertwo1.bookconverter.ui.theme.TextSecondary
import io.github.playertwo1.bookconverter.ui.theme.TextTertiary

@Composable
fun SeriesScreen(
    modifier: Modifier = Modifier,
    vm: SeriesViewModel = viewModel(),
) {
    val selectedSeriesId by vm.selectedSeriesId.collectAsStateWithLifecycle()
    val selectedSeries by vm.selectedSeries.collectAsStateWithLifecycle()
    val selectedGlossary by vm.selectedGlossary.collectAsStateWithLifecycle()
    val seriesList by vm.seriesList.collectAsStateWithLifecycle()
    val allBooks by vm.allBooks.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    var showCreateDialog by remember { mutableStateOf(false) }

    if (selectedSeriesId != null && selectedSeries != null) {
        BackHandler { vm.selectSeries(null) }
        SeriesDetailScreen(
            series = selectedSeries!!,
            glossary = selectedGlossary,
            allBooks = allBooks,
            message = message,
            onDismissMessage = { vm.clearMessage() },
            onBack = { vm.selectSeries(null) },
            onUpdateSeries = { id, name, notes -> vm.updateSeries(id, name, notes) },
            onDeleteSeries = { id -> vm.deleteSeries(id) },
            onToggleBook = { book -> vm.toggleBookSeries(book, selectedSeries!!.id) },
            onSaveTerm = { entryId, src, tgt, notes ->
                vm.saveGlossaryEntry(entryId, selectedSeries!!.id, src, tgt, notes)
            },
            onDeleteTerm = { entryId -> vm.deleteGlossaryEntry(entryId, selectedSeries!!.id) },
            onImportTxt = { uri -> vm.importGlossaryTxt(selectedSeries!!.id, uri) },
            onExportTxt = { uri -> vm.exportGlossaryTxt(selectedSeries!!.id, uri) },
            modifier = modifier,
        )
    } else {
        SeriesListScreen(
            seriesList = seriesList,
            message = message,
            onDismissMessage = { vm.clearMessage() },
            onOpenCreate = { showCreateDialog = true },
            onSelectSeries = { id -> vm.selectSeries(id) },
            modifier = modifier,
        )
    }

    if (showCreateDialog) {
        CreateSeriesDialog(
            onConfirm = { name, notes ->
                showCreateDialog = false
                vm.createSeries(name, notes)
            },
            onDismiss = { showCreateDialog = false },
        )
    }
}

@Composable
private fun SeriesListScreen(
    seriesList: List<SeriesSummary>,
    message: String?,
    onDismissMessage: () -> Unit,
    onOpenCreate: () -> Unit,
    onSelectSeries: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 96.dp),
    ) {
        item {
            Text(
                text = "Séries",
                style = MaterialTheme.typography.displaySmall,
                color = TextPrimary,
            )
            Text(
                text = "Organize sagas, notas de estilo e glossários compartilhados.",
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
            )
        }

        item {
            Button(
                onClick = onOpenCreate,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
            ) {
                Text("+ Nova série", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
            Spacer(Modifier.height(16.dp))
        }

        message?.let { msg ->
            item {
                MessageBanner(message = msg, onDismiss = onDismissMessage)
            }
        }

        if (seriesList.isNotEmpty()) {
            item {
                Text(
                    text = "Suas Séries",
                    color = TextPrimary,
                    fontFamily = Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            items(seriesList, key = { it.series.id }) { summary ->
                SeriesCard(summary = summary, onClick = { onSelectSeries(summary.series.id) })
            }
        } else {
            item {
                M3Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Surface,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Stroke),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "Nenhuma série cadastrada",
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Crie uma série para manter a consistência de termos e estilo entre múltiplos livros da mesma saga.",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesCard(
    summary: SeriesSummary,
    onClick: () -> Unit,
) {
    val series = summary.series
    val booksText = when (summary.bookCount) {
        0 -> "Nenhum livro"
        1 -> "1 livro"
        else -> "${summary.bookCount} livros"
    }
    val termsText = when (summary.termCount) {
        0 -> "Sem termos"
        1 -> "1 termo"
        else -> "${summary.termCount} termos"
    }

    M3Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClickLabel = "Abrir série", role = Role.Button, onClick = onClick),
        color = Surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Stroke),
    ) {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = 72.dp)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Accent)
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = series.name,
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "$booksText · $termsText",
                        color = TextSecondary,
                        fontSize = 13.sp,
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(AccentSoft)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "v${series.glossaryVersion}",
                            color = Accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            Text("›", color = TextSecondary, fontSize = 22.sp, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun SeriesDetailScreen(
    series: Series,
    glossary: List<GlossaryEntry>,
    allBooks: List<Book>,
    message: String?,
    onDismissMessage: () -> Unit,
    onBack: () -> Unit,
    onUpdateSeries: (Long, String, String) -> Unit,
    onDeleteSeries: (Long) -> Unit,
    onToggleBook: (Book) -> Unit,
    onSaveTerm: (Long, String, String, String?) -> Unit,
    onDeleteTerm: (Long) -> Unit,
    onImportTxt: (Uri) -> Unit,
    onExportTxt: (Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showRenameDialog by remember { mutableStateOf(false) }
    var showLinkBooksDialog by remember { mutableStateOf(false) }
    var termToEdit by remember { mutableStateOf<GlossaryEntry?>(null) }
    var showAddTermDialog by remember { mutableStateOf(false) }
    var showDeleteSeriesDialog by remember { mutableStateOf(false) }

    var notesText by remember(series.id, series.styleNotes) { mutableStateOf(series.styleNotes) }
    val isNotesModified = notesText != series.styleNotes

    val linkedBooks = remember(allBooks, series.id) {
        allBooks.filter { it.seriesId == series.id }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onImportTxt(uri)
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) onExportTxt(uri)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 96.dp),
    ) {
        // Navigation & Title Row
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClickLabel = "Voltar", onClick = onBack)
                        .semantics { contentDescription = "Voltar para lista de séries" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("‹", fontSize = 28.sp, color = TextPrimary)
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Série", color = TextSecondary, fontSize = 12.sp)
                    Text(
                        text = series.name,
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OutlinedButton(
                    onClick = { showRenameDialog = true },
                    border = BorderStroke(1.dp, Stroke),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                ) {
                    Text("Renomear", fontSize = 12.sp)
                }
            }
        }

        message?.let { msg ->
            item {
                MessageBanner(message = msg, onDismiss = onDismissMessage)
            }
        }

        // Section: Notas de Estilo
        item {
            M3Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                color = Surface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Stroke),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "Notas de estilo",
                        fontFamily = Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = TextPrimary,
                    )
                    Text(
                        text = "Orientações e diretrizes de tom enviadas ao modelo de tradução.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
                    )
                    OutlinedTextField(
                        value = notesText,
                        onValueChange = { notesText = it },
                        placeholder = {
                            Text("Ex: Tom medieval solene. Manter títulos nobiliárquicos em inglês...")
                        },
                        minLines = 3,
                        maxLines = 8,
                        colors = seriesFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(
                            onClick = { onUpdateSeries(series.id, series.name, notesText) },
                            enabled = isNotesModified,
                            colors = ButtonDefaults.buttonColors(containerColor = Accent),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Salvar notas", fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        // Section: Livros da Série
        item {
            M3Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                color = Surface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Stroke),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                text = "Livros da série",
                                fontFamily = Serif,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = TextPrimary,
                            )
                            Text(
                                text = "${linkedBooks.size} ${if (linkedBooks.size == 1) "livro vinculado" else "livros vinculados"}",
                                color = TextSecondary,
                                fontSize = 12.sp,
                            )
                        }
                        OutlinedButton(
                            onClick = { showLinkBooksDialog = true },
                            border = BorderStroke(1.dp, Stroke),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
                        ) {
                            Text("+ Vincular", fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (linkedBooks.isEmpty()) {
                        Text(
                            text = "Nenhum livro vinculado a esta série. Vincule livros importados da sua biblioteca para compartilhar o glossário e estilo.",
                            color = TextTertiary,
                            fontSize = 13.sp,
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            linkedBooks.forEach { book ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Field)
                                        .border(1.dp, Stroke, RoundedCornerShape(12.dp))
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = book.title,
                                            color = TextPrimary,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        val details = listOfNotNull(book.author, book.format, sizeLabel(book.sizeBytes))
                                            .joinToString(" · ")
                                        Text(details, color = TextSecondary, fontSize = 12.sp)
                                    }
                                    TextButton(
                                        onClick = { onToggleBook(book) },
                                        colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary),
                                    ) {
                                        Text("Desvincular", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Section: Glossário
        item {
            M3Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                color = Surface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Stroke),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = "Glossário",
                                    fontFamily = Serif,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    color = TextPrimary,
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(AccentSoft)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "v${series.glossaryVersion}",
                                        color = Accent,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                            Text(
                                text = "${glossary.size} ${if (glossary.size == 1) "termo cadastrado" else "termos cadastrados"}",
                                color = TextSecondary,
                                fontSize = 12.sp,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { showAddTermDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Accent),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                        ) {
                            Text("+ Termo", fontSize = 12.sp, maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = {
                                importLauncher.launch(arrayOf("text/plain", "*/*"))
                            },
                            border = BorderStroke(1.dp, Stroke),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                            modifier = Modifier.weight(1.1f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                        ) {
                            Text("Importar .txt", fontSize = 12.sp, maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = {
                                val filename = "${sanitizeFileName(series.name)}_glossario.txt"
                                exportLauncher.launch(filename)
                            },
                            enabled = glossary.isNotEmpty(),
                            border = BorderStroke(1.dp, Stroke),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                            modifier = Modifier.weight(1.1f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                        ) {
                            Text("Exportar .txt", fontSize = 12.sp, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    if (glossary.isEmpty()) {
                        Text(
                            text = "Nenhum termo cadastrado. Adicione termos manualmente ou importe um arquivo .txt compatível com desktop (Original → Traduzido).",
                            color = TextTertiary,
                            fontSize = 13.sp,
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            glossary.forEach { entry ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Field)
                                        .border(1.dp, Stroke, RoundedCornerShape(12.dp))
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = entry.sourceTerm,
                                                color = TextPrimary,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            Text(
                                                text = " → ",
                                                color = Accent,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                            Text(
                                                text = entry.targetTerm,
                                                color = TextPrimary,
                                                fontSize = 14.sp,
                                            )
                                        }
                                        if (!entry.notes.isNullOrBlank()) {
                                            Text(
                                                text = entry.notes,
                                                color = TextSecondary,
                                                fontSize = 11.sp,
                                                modifier = Modifier.padding(top = 2.dp),
                                            )
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextButton(
                                            onClick = { termToEdit = entry },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary),
                                        ) {
                                            Text("Editar", fontSize = 12.sp)
                                        }
                                        TextButton(
                                            onClick = { onDeleteTerm(entry.id) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            colors = ButtonDefaults.textButtonColors(contentColor = Danger),
                                        ) {
                                            Text("✕", fontSize = 14.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Section: Zona de Perigo (Excluir Série)
        item {
            M3Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                color = Surface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Stroke),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "Zona de Perigo",
                        fontFamily = Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = Danger,
                    )
                    Text(
                        text = "A exclusão apagará o glossário e as notas de estilo desta série. Os livros da sua biblioteca NÃO serão apagados.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
                    )
                    OutlinedButton(
                        onClick = { showDeleteSeriesDialog = true },
                        border = BorderStroke(1.dp, Danger),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Excluir série", fontSize = 14.sp)
                    }
                }
            }
        }
    }

    if (showRenameDialog) {
        RenameSeriesDialog(
            currentName = series.name,
            onConfirm = { newName ->
                showRenameDialog = false
                onUpdateSeries(series.id, newName, series.styleNotes)
            },
            onDismiss = { showRenameDialog = false },
        )
    }

    if (showLinkBooksDialog) {
        LinkBooksDialog(
            series = series,
            allBooks = allBooks,
            onToggleBook = onToggleBook,
            onDismiss = { showLinkBooksDialog = false },
        )
    }

    if (showAddTermDialog) {
        AddEditTermDialog(
            initialEntry = null,
            onSave = { src, tgt, notes ->
                showAddTermDialog = false
                onSaveTerm(0L, src, tgt, notes)
            },
            onDismiss = { showAddTermDialog = false },
        )
    }

    termToEdit?.let { entry ->
        AddEditTermDialog(
            initialEntry = entry,
            onSave = { src, tgt, notes ->
                termToEdit = null
                onSaveTerm(entry.id, src, tgt, notes)
            },
            onDismiss = { termToEdit = null },
        )
    }

    if (showDeleteSeriesDialog) {
        DeleteSeriesDialog(
            seriesName = series.name,
            onConfirm = {
                showDeleteSeriesDialog = false
                onDeleteSeries(series.id)
            },
            onDismiss = { showDeleteSeriesDialog = false },
        )
    }
}

@Composable
private fun MessageBanner(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    M3Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        color = Field,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Stroke),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = message,
                color = TextPrimary,
                fontSize = 13.sp,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
            )
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = "Fechar", onClick = onDismiss)
                    .padding(4.dp),
            ) {
                Text("✕", color = TextSecondary, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun CreateSeriesDialog(
    onConfirm: (name: String, styleNotes: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var styleNotes by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = {
            Text(
                text = "Nova série",
                fontFamily = Serif,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        showError = false
                    },
                    label = { Text("Nome da série") },
                    placeholder = { Text("Ex: O Senhor dos Anéis") },
                    singleLine = true,
                    colors = seriesFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                    isError = showError && name.isBlank(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = styleNotes,
                    onValueChange = { styleNotes = it },
                    label = { Text("Notas de estilo (opcional)") },
                    placeholder = { Text("Ex: Tom épico e arcaico...") },
                    minLines = 2,
                    maxLines = 4,
                    colors = seriesFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (showError) {
                    Text(
                        text = "O nome da série é obrigatório.",
                        color = Danger,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        onConfirm(name.trim(), styleNotes.trim())
                    } else {
                        showError = true
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Criar")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary),
            ) {
                Text("Cancelar")
            }
        },
    )
}

@Composable
private fun RenameSeriesDialog(
    currentName: String,
    onConfirm: (newName: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(currentName) }
    var showError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = {
            Text(
                text = "Renomear série",
                fontFamily = Serif,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        showError = false
                    },
                    label = { Text("Nome da série") },
                    singleLine = true,
                    colors = seriesFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                    isError = showError && name.isBlank(),
                )
                if (showError) {
                    Text(
                        text = "O nome da série não pode ser vazio.",
                        color = Danger,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        onConfirm(name.trim())
                    } else {
                        showError = true
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Salvar")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary),
            ) {
                Text("Cancelar")
            }
        },
    )
}

@Composable
private fun LinkBooksDialog(
    series: Series,
    allBooks: List<Book>,
    onToggleBook: (Book) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = {
            Text(
                text = "Vincular livros à série",
                fontFamily = Serif,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
        },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                Text(
                    text = "Selecione os livros que pertencem a \"${series.name}\":",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                if (allBooks.isEmpty()) {
                    Text(
                        text = "Nenhum livro importado na biblioteca.",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                        items(allBooks, key = { it.id }) { book ->
                            val isLinked = book.seriesId == series.id
                            val isLinkedOther = book.seriesId != null && book.seriesId != series.id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onToggleBook(book) }
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = isLinked,
                                    onCheckedChange = { onToggleBook(book) },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = Accent,
                                        uncheckedColor = TextSecondary,
                                        checkmarkColor = TextPrimary,
                                    ),
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = book.title,
                                        color = TextPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val statusNote = when {
                                        isLinked -> "Vinculado a esta série"
                                        isLinkedOther -> "Vinculado a outra série (será movido)"
                                        else -> "Não vinculado a nenhuma série"
                                    }
                                    Text(
                                        text = statusNote,
                                        color = if (isLinked) Accent else TextTertiary,
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Concluir")
            }
        },
    )
}

@Composable
private fun AddEditTermDialog(
    initialEntry: GlossaryEntry?,
    onSave: (source: String, target: String, notes: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var source by remember(initialEntry) { mutableStateOf(initialEntry?.sourceTerm.orEmpty()) }
    var target by remember(initialEntry) { mutableStateOf(initialEntry?.targetTerm.orEmpty()) }
    var notes by remember(initialEntry) { mutableStateOf(initialEntry?.notes.orEmpty()) }
    var showError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = {
            Text(
                text = if (initialEntry == null) "Novo termo" else "Editar termo",
                fontFamily = Serif,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = source,
                    onValueChange = {
                        source = it
                        showError = false
                    },
                    label = { Text("Termo original (source)") },
                    placeholder = { Text("Ex: Golden Snitch") },
                    singleLine = true,
                    colors = seriesFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                    isError = showError && source.isBlank(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = target,
                    onValueChange = {
                        target = it
                        showError = false
                    },
                    label = { Text("Tradução (target)") },
                    placeholder = { Text("Ex: Pomo de Ouro") },
                    singleLine = true,
                    colors = seriesFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                    isError = showError && target.isBlank(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notas / Contexto (opcional)") },
                    placeholder = { Text("Ex: Objeto de Quadribol") },
                    singleLine = true,
                    colors = seriesFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (showError) {
                    Text(
                        text = "Preencha o termo original e a tradução.",
                        color = Danger,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (source.isNotBlank() && target.isNotBlank()) {
                        onSave(source.trim(), target.trim(), notes.trim().ifEmpty { null })
                    } else {
                        showError = true
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Salvar")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary),
            ) {
                Text("Cancelar")
            }
        },
    )
}

@Composable
private fun DeleteSeriesDialog(
    seriesName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = {
            Text(
                text = "Excluir série?",
                fontFamily = Serif,
                fontWeight = FontWeight.Bold,
                color = Danger,
            )
        },
        text = {
            Text(
                text = "Tem certeza de que deseja excluir a série \"$seriesName\"?\n\nAs notas de estilo e todo o glossário serão apagados permanentemente.\n\nOs livros da sua biblioteca NÃO serão apagados e continuarão intactos.",
                color = TextSecondary,
                fontSize = 14.sp,
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = Danger, contentColor = TextPrimary),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Excluir série")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary),
            ) {
                Text("Cancelar")
            }
        },
    )
}

@Composable
private fun seriesFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Accent,
    unfocusedBorderColor = Stroke,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedLabelColor = TextSecondary,
    unfocusedLabelColor = TextSecondary,
    focusedPlaceholderColor = TextSecondary,
    unfocusedPlaceholderColor = TextSecondary,
    focusedContainerColor = Field,
    unfocusedContainerColor = Field,
    cursorColor = Accent,
)

private fun sanitizeFileName(name: String): String {
    val clean = name.replace(Regex("[^a-zA-Z0-9._-]"), "_").trim('_')
    return if (clean.isEmpty()) "glossario" else clean
}
