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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import io.github.playertwo1.bookconverter.core.metadata.MetadataField
import io.github.playertwo1.bookconverter.data.Book
import io.github.playertwo1.bookconverter.data.BookMetadataReviewSelection
import io.github.playertwo1.bookconverter.data.GlossaryEntry
import io.github.playertwo1.bookconverter.data.MemoryCategory
import io.github.playertwo1.bookconverter.data.MemoryStatus
import io.github.playertwo1.bookconverter.data.Series
import io.github.playertwo1.bookconverter.data.SeriesMemoryEntry
import io.github.playertwo1.bookconverter.data.SeriesSummary
import io.github.playertwo1.bookconverter.data.formatSourceAttribution
import io.github.playertwo1.bookconverter.data.suggestSeriesName
import io.github.playertwo1.bookconverter.ui.sizeLabel
import io.github.playertwo1.bookconverter.ui.theme.Accent
import io.github.playertwo1.bookconverter.ui.theme.AccentSoft
import io.github.playertwo1.bookconverter.ui.theme.Danger
import io.github.playertwo1.bookconverter.ui.theme.Field
import io.github.playertwo1.bookconverter.ui.theme.Positive
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
    val selectedMemoryEntries by vm.selectedMemoryEntries.collectAsStateWithLifecycle()
    val isExtractingMemory by vm.isExtractingMemory.collectAsStateWithLifecycle()
    val extractionProgress by vm.extractionProgress.collectAsStateWithLifecycle()
    val seriesList by vm.seriesList.collectAsStateWithLifecycle()
    val allBooks by vm.allBooks.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val metadataLookupState by vm.metadataLookupState.collectAsStateWithLifecycle()

    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var pendingBookForSeries by remember { mutableStateOf<Book?>(null) }
    val unlinkedBooks = remember(allBooks) { allBooks.filter { it.seriesId == null } }

    if (selectedSeriesId != null && selectedSeries != null) {
        BackHandler { vm.selectSeries(null) }
        SeriesDetailScreen(
            series = selectedSeries!!,
            glossary = selectedGlossary,
            memoryEntries = selectedMemoryEntries,
            isExtractingMemory = isExtractingMemory,
            extractionProgress = extractionProgress,
            allBooks = allBooks,
            message = message,
            metadataLookupState = metadataLookupState,
            onDismissMessage = { vm.clearMessage() },
            onBack = { vm.selectSeries(null) },
            onUpdateSeries = { id, name, author, notes, summary, keepSummary ->
                vm.updateSeries(id, name, author, notes, summary, keepSummary)
            },
            onDeleteSeries = { id -> vm.deleteSeries(id) },
            onToggleBook = { book -> vm.toggleBookSeries(book, selectedSeries!!.id) },
            onSaveTerm = { entryId, src, tgt, notes ->
                vm.saveGlossaryEntry(entryId, selectedSeries!!.id, src, tgt, notes)
            },
            onDeleteTerm = { entryId -> vm.deleteGlossaryEntry(entryId, selectedSeries!!.id) },
            onImportTxt = { uri -> vm.importGlossaryTxt(selectedSeries!!.id, uri) },
            onExportTxt = { uri -> vm.exportGlossaryTxt(selectedSeries!!.id, uri) },
            onExtractMemory = { book -> vm.extractMemoryFromBook(selectedSeries!!.id, book) },
            onCancelExtraction = { vm.cancelMemoryExtraction() },
            onApproveProposal = { entryId, editedTarget -> vm.approveProposal(entryId, editedTarget) },
            onRejectProposal = { entryId -> vm.rejectProposal(entryId) },
            onDeleteMemoryEntry = { entryId -> vm.deleteMemoryEntry(entryId) },
            onUpdateMemoryEntry = { entryId, newTarget -> vm.updateMemoryEntry(entryId, newTarget) },
            onSearchBookMetadata = vm::searchBookMetadata,
            onCancelBookMetadataSearch = vm::cancelBookMetadataSearch,
            onClearBookMetadataSearch = vm::clearBookMetadataSearch,
            onApplyBookAndSeriesMetadata = { bookId, selection ->
                vm.applyBookAndSeriesMetadata(selectedSeries!!.id, bookId, selection)
            },
            modifier = modifier,
        )
    } else {
        SeriesListScreen(
            seriesList = seriesList,
            unlinkedBooks = unlinkedBooks,
            message = message,
            onDismissMessage = { vm.clearMessage() },
            onOpenCreate = {
                pendingBookForSeries = null
                showCreateDialog = true
            },
            onCreateFromBook = { book ->
                pendingBookForSeries = book
                showCreateDialog = true
            },
            onSelectSeries = { id -> vm.selectSeries(id) },
            modifier = modifier,
        )
    }

    if (showCreateDialog) {
        val targetBook = pendingBookForSeries
        CreateSeriesDialog(
            initialName = targetBook?.let { suggestSeriesName(it.title) }.orEmpty(),
            initialAuthor = targetBook?.author.orEmpty(),
            allBooks = allBooks,
            initialBook = targetBook,
            lookupState = metadataLookupState,
            onSearchBookMetadata = vm::searchBookMetadata,
            onCancelBookMetadataSearch = vm::cancelBookMetadataSearch,
            onClearBookMetadataSearch = vm::clearBookMetadataSearch,
            onConfirm = { name, author, notes, summary, bookId, selection ->
                showCreateDialog = false
                pendingBookForSeries = null
                vm.clearBookMetadataSearch()
                vm.createSeries(
                    name = name,
                    author = author,
                    styleNotes = notes,
                    linkBookId = bookId ?: targetBook?.id,
                    summary = summary,
                    metadataSelection = selection,
                )
            },
            onDismiss = {
                showCreateDialog = false
                pendingBookForSeries = null
                vm.clearBookMetadataSearch()
            },
        )
    }
}

@Composable
private fun SeriesListScreen(
    seriesList: List<SeriesSummary>,
    unlinkedBooks: List<Book>,
    message: String?,
    onDismissMessage: () -> Unit,
    onOpenCreate: () -> Unit,
    onCreateFromBook: (Book) -> Unit,
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

        if (unlinkedBooks.isNotEmpty()) {
            item {
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "Livros sem série",
                    color = TextPrimary,
                    fontFamily = Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                )
                Text(
                    text = "Crie uma série aproveitando título e autor do livro.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
                )
            }
            items(unlinkedBooks, key = { "unlinked-${it.id}" }) { book ->
                M3Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                    color = Surface,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Stroke),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 64.dp)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = book.title,
                                color = TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val meta = listOfNotNull(book.author, book.format).joinToString(" · ")
                            if (meta.isNotEmpty()) {
                                Text(
                                    text = meta,
                                    color = TextSecondary,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        OutlinedButton(
                            onClick = { onCreateFromBook(book) },
                            border = BorderStroke(1.dp, Stroke),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                        ) {
                            Text("Criar série", fontSize = 12.sp)
                        }
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
                if (!series.author.isNullOrBlank()) {
                    Text(
                        text = series.author,
                        color = TextSecondary,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
    memoryEntries: List<SeriesMemoryEntry>,
    isExtractingMemory: Boolean,
    extractionProgress: String?,
    allBooks: List<Book>,
    message: String?,
    metadataLookupState: BookMetadataLookupUiState,
    onDismissMessage: () -> Unit,
    onBack: () -> Unit,
    onUpdateSeries: (Long, String, String?, String, String?, Boolean) -> Unit,
    onDeleteSeries: (Long) -> Unit,
    onToggleBook: (Book) -> Unit,
    onSaveTerm: (Long, String, String, String?) -> Unit,
    onDeleteTerm: (Long) -> Unit,
    onImportTxt: (Uri) -> Unit,
    onExportTxt: (Uri) -> Unit,
    onExtractMemory: (Book) -> Unit,
    onCancelExtraction: () -> Unit,
    onApproveProposal: (Long, String?) -> Unit,
    onRejectProposal: (Long) -> Unit,
    onDeleteMemoryEntry: (Long) -> Unit,
    onUpdateMemoryEntry: (Long, String) -> Unit,
    onSearchBookMetadata: (String, String, String) -> Unit,
    onCancelBookMetadataSearch: () -> Unit,
    onClearBookMetadataSearch: () -> Unit,
    onApplyBookAndSeriesMetadata: (Long?, BookMetadataReviewSelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showRenameDialog by rememberSaveable { mutableStateOf(false) }
    var showLinkBooksDialog by rememberSaveable { mutableStateOf(false) }
    var showMetadataLookup by rememberSaveable { mutableStateOf(false) }
    var lookupInitialBookId by rememberSaveable { mutableStateOf<Long?>(null) }
    var termToEdit by remember { mutableStateOf<GlossaryEntry?>(null) }
    var showAddTermDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteSeriesDialog by rememberSaveable { mutableStateOf(false) }

    var showPickBookDialog by rememberSaveable { mutableStateOf(false) }
    var entryToEdit by remember { mutableStateOf<SeriesMemoryEntry?>(null) }
    var memoryFilter by rememberSaveable { mutableStateOf("PENDING") }

    var notesText by remember(series.id, series.styleNotes) { mutableStateOf(series.styleNotes) }
    val isNotesModified = notesText != series.styleNotes

    val linkedBooks = remember(allBooks, series.id) {
        allBooks
            .filter { it.seriesId == series.id }
            .sortedWith(
                compareBy<Book> { it.seriesPosition?.toDoubleOrNull() ?: Double.MAX_VALUE }
                    .thenBy { it.title.lowercase() }
            )
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
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClickLabel = "Voltar", onClick = onBack)
                        .semantics { contentDescription = "Voltar" },
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
                    if (!series.author.isNullOrBlank()) {
                        Text(
                            text = series.author,
                            color = TextSecondary,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                OutlinedButton(
                    onClick = { showRenameDialog = true },
                    border = BorderStroke(1.dp, Stroke),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                    Text("Editar", fontSize = 12.sp)
                }
            }
        }

        message?.let { msg ->
            item {
                MessageBanner(message = msg, onDismiss = onDismissMessage)
            }
        }

        // Resumo breve da série e atribuição (quando salvo)
        val seriesAttribution = formatSourceAttribution(series.metadataSourcesJson)
        if (!series.summary.isNullOrBlank() || seriesAttribution != null) {
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
                            text = "Sobre a série",
                            fontFamily = Serif,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = TextPrimary,
                        )
                        if (!series.summary.isNullOrBlank()) {
                            Text(
                                text = series.summary,
                                color = TextSecondary,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        if (seriesAttribution != null) {
                            Text(
                                text = seriesAttribution,
                                color = TextTertiary,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
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
                            onClick = { onUpdateSeries(series.id, series.name, series.author, notesText, null, true) },
                            enabled = isNotesModified,
                            colors = ButtonDefaults.buttonColors(containerColor = Accent),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
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
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                        ) {
                            Text("+ Vincular", fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            onClearBookMetadataSearch()
                            lookupInitialBookId = linkedBooks.firstOrNull()?.id
                            showMetadataLookup = true
                        },
                        border = BorderStroke(1.dp, Accent),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp),
                    ) {
                        Text("Buscar informações do livro", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
                                val bookAttr = formatSourceAttribution(book.metadataSourcesJson)
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Field)
                                        .border(1.dp, Stroke, RoundedCornerShape(12.dp))
                                        .padding(12.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            val displayTitle = if (!book.seriesPosition.isNullOrBlank()) {
                                                "Livro ${book.seriesPosition} · ${book.title}"
                                            } else {
                                                book.title
                                            }
                                            Text(
                                                text = displayTitle,
                                                color = TextPrimary,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            val details = listOfNotNull(
                                                book.author,
                                                book.publishedDate,
                                                book.publisher,
                                                book.isbn?.let { "ISBN $it" },
                                                book.format,
                                                sizeLabel(book.sizeBytes),
                                            ).joinToString(" · ")
                                            Text(details, color = TextSecondary, fontSize = 12.sp)
                                        }
                                        TextButton(
                                            onClick = { onToggleBook(book) },
                                            colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary),
                                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                                        ) {
                                            Text("Desvincular", fontSize = 12.sp)
                                        }
                                    }
                                    if (!book.summary.isNullOrBlank()) {
                                        Text(
                                            text = book.summary,
                                            color = TextSecondary,
                                            fontSize = 12.sp,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 6.dp),
                                        )
                                    }
                                    if (bookAttr != null) {
                                        Text(
                                            text = bookAttr,
                                            color = TextTertiary,
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(top = 4.dp),
                                        )
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
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                        ) {
                            Text("+ Termo", fontSize = 12.sp, maxLines = 2)
                        }
                        OutlinedButton(
                            onClick = {
                                importLauncher.launch(arrayOf("text/plain", "*/*"))
                            },
                            border = BorderStroke(1.dp, Stroke),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                            modifier = Modifier
                                .weight(1.1f)
                                .defaultMinSize(minHeight = 48.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                        ) {
                            Text("Importar .txt", fontSize = 12.sp, maxLines = 2)
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
                            modifier = Modifier
                                .weight(1.1f)
                                .defaultMinSize(minHeight = 48.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                        ) {
                            Text("Exportar .txt", fontSize = 12.sp, maxLines = 2)
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
                                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary),
                                        ) {
                                            Text("Editar", fontSize = 12.sp)
                                        }
                                        TextButton(
                                            onClick = { onDeleteTerm(entry.id) },
                                            modifier = Modifier
                                                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                                .semantics { contentDescription = "Excluir termo" },
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

        // Section: Memória por IA (D2)
        item {
            val pendingCount = remember(memoryEntries) { memoryEntries.count { it.status == MemoryStatus.PENDING } }
            val approvedCount = remember(memoryEntries) { memoryEntries.count { it.status == MemoryStatus.APPROVED } }
            val allCount = memoryEntries.size

            val filteredEntries = remember(memoryEntries, memoryFilter) {
                when (memoryFilter) {
                    "PENDING" -> memoryEntries.filter { it.status == MemoryStatus.PENDING }
                    "APPROVED" -> memoryEntries.filter { it.status == MemoryStatus.APPROVED }
                    else -> memoryEntries
                }
            }

            M3Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                color = Surface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Stroke),
            ) {
                Column(Modifier.padding(16.dp)) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Memória por IA (D2)",
                                fontFamily = Serif,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = TextPrimary,
                            )
                            Text(
                                text = "Extraia termos, personagens e estilo de livros da biblioteca com IA.",
                                color = TextSecondary,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Button to extract
                    Button(
                        onClick = { showPickBookDialog = true },
                        enabled = !isExtractingMemory,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp),
                    ) {
                        Text(
                            text = if (isExtractingMemory) "Extraindo com IA…" else "+ Extrair de um livro",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    // Progress indicator if extracting
                    if (isExtractingMemory) {
                        Spacer(Modifier.height(12.dp))
                        M3Surface(
                            color = Field,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Accent.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = Accent,
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = extractionProgress ?: "Analisando livro com IA…",
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(8.dp))
                                TextButton(
                                    onClick = onCancelExtraction,
                                    colors = ButtonDefaults.textButtonColors(contentColor = Danger),
                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                                ) {
                                    Text("Cancelar", fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Filters
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val filterOptions = listOf(
                            "PENDING" to "Pendentes ($pendingCount)",
                            "APPROVED" to "Aprovados ($approvedCount)",
                            "ALL" to "Todos ($allCount)",
                        )
                        filterOptions.forEach { (key, label) ->
                            val selected = memoryFilter == key
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (selected) AccentSoft else Field)
                                    .border(
                                        width = 1.dp,
                                        color = if (selected) Accent else Stroke,
                                        shape = RoundedCornerShape(12.dp),
                                    )
                                    .clickable(
                                        role = Role.RadioButton,
                                        onClickLabel = "Filtrar por $label",
                                        onClick = { memoryFilter = key },
                                    )
                                    .semantics { contentDescription = "Filtro $label" },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = label,
                                    color = if (selected) Accent else TextSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 4.dp),
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    if (filteredEntries.isEmpty()) {
                        val emptyMsg = when {
                            memoryEntries.isEmpty() -> "Nenhuma proposta de memória extraída ainda. Toque em \"+ Extrair de um livro\" para que a IA analise personagens, termos e estilo a partir de qualquer livro importado."
                            memoryFilter == "PENDING" -> "Nenhuma proposta pendente de revisão."
                            memoryFilter == "APPROVED" -> "Nenhuma proposta aprovada nesta série."
                            else -> "Nenhuma entrada encontrada."
                        }
                        Text(
                            text = emptyMsg,
                            color = TextTertiary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            filteredEntries.forEach { entry ->
                                M3Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = Field,
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, Stroke),
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                    ) {
                                        // Badges row
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(AccentSoft)
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = memoryCategoryLabel(entry.category),
                                                    color = Accent,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                )
                                            }
                                            val (statusBg, statusColor) = when (entry.status) {
                                                MemoryStatus.APPROVED -> Color(0x2E35B77B) to Positive
                                                MemoryStatus.REJECTED -> Color(0x2EF36D7A) to Danger
                                                else -> Color(0x2EF59E0B) to Color(0xFFF59E0B)
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(statusBg)
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = memoryStatusLabel(entry.status),
                                                    color = statusColor,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                )
                                            }
                                            Spacer(Modifier.width(2.dp))
                                            Text(
                                                text = "· ${entry.sourceBookTitle}",
                                                color = TextSecondary,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f),
                                            )
                                        }

                                        Spacer(Modifier.height(8.dp))

                                        // Term mapping
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = entry.sourceTerm,
                                                color = TextPrimary,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            Text(
                                                text = " → ",
                                                color = Accent,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                            Text(
                                                text = entry.targetTerm,
                                                color = TextPrimary,
                                                fontSize = 15.sp,
                                            )
                                        }

                                        if (!entry.notes.isNullOrBlank()) {
                                            Text(
                                                text = entry.notes,
                                                color = TextSecondary,
                                                fontSize = 12.sp,
                                                modifier = Modifier.padding(top = 2.dp),
                                            )
                                        }

                                        // Excerpt & Source Book
                                        Spacer(Modifier.height(8.dp))
                                        M3Surface(
                                            color = Surface,
                                            shape = RoundedCornerShape(8.dp),
                                            border = BorderStroke(1.dp, Stroke),
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Column(Modifier.padding(10.dp)) {
                                                Text(
                                                    text = "Trecho original em \"${entry.sourceBookTitle}\":",
                                                    color = TextTertiary,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Medium,
                                                )
                                                Spacer(Modifier.height(3.dp))
                                                Text(
                                                    text = "“${entry.excerpt}”",
                                                    color = TextSecondary,
                                                    fontSize = 12.sp,
                                                    fontStyle = FontStyle.Italic,
                                                )
                                            }
                                        }

                                        // Conflict Warning Banner
                                        if (!entry.conflictWarning.isNullOrBlank()) {
                                            Spacer(Modifier.height(8.dp))
                                            M3Surface(
                                                color = Danger.copy(alpha = 0.12f),
                                                shape = RoundedCornerShape(8.dp),
                                                border = BorderStroke(1.dp, Danger.copy(alpha = 0.45f)),
                                                modifier = Modifier.fillMaxWidth(),
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(8.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                ) {
                                                    Text("⚠", color = Danger, fontSize = 13.sp)
                                                    Text(
                                                        text = entry.conflictWarning,
                                                        color = Danger,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Medium,
                                                    )
                                                }
                                            }
                                        }

                                        // Action buttons (>= 48dp touch targets)
                                        Spacer(Modifier.height(10.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            if (entry.status == MemoryStatus.PENDING) {
                                                Button(
                                                    onClick = { onApproveProposal(entry.id, null) },
                                                    colors = ButtonDefaults.buttonColors(containerColor = Positive),
                                                    shape = RoundedCornerShape(8.dp),
                                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                                ) {
                                                    Text("Aprovar", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                                }
                                                OutlinedButton(
                                                    onClick = { entryToEdit = entry },
                                                    border = BorderStroke(1.dp, Stroke),
                                                    shape = RoundedCornerShape(8.dp),
                                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                                ) {
                                                    Text("Editar", fontSize = 13.sp)
                                                }
                                                TextButton(
                                                    onClick = { onRejectProposal(entry.id) },
                                                    colors = ButtonDefaults.textButtonColors(contentColor = Danger),
                                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                                ) {
                                                    Text("Rejeitar", fontSize = 13.sp)
                                                }
                                            } else if (entry.status == MemoryStatus.APPROVED) {
                                                OutlinedButton(
                                                    onClick = { entryToEdit = entry },
                                                    border = BorderStroke(1.dp, Stroke),
                                                    shape = RoundedCornerShape(8.dp),
                                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                                ) {
                                                    Text("Editar tradução", fontSize = 12.sp)
                                                }
                                            } else if (entry.status == MemoryStatus.REJECTED) {
                                                OutlinedButton(
                                                    onClick = { onApproveProposal(entry.id, null) },
                                                    border = BorderStroke(1.dp, Positive),
                                                    shape = RoundedCornerShape(8.dp),
                                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Positive),
                                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                                ) {
                                                    Text("Reconsiderar", fontSize = 12.sp)
                                                }
                                            }

                                            Spacer(Modifier.weight(1f))

                                            TextButton(
                                                onClick = { onDeleteMemoryEntry(entry.id) },
                                                modifier = Modifier
                                                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                                    .semantics { contentDescription = "Excluir proposta de memória" },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp),
                    ) {
                        Text("Excluir série", fontSize = 14.sp)
                    }
                }
            }
        }
    }

    if (showRenameDialog) {
        EditSeriesDialog(
            currentName = series.name,
            currentAuthor = series.author.orEmpty(),
            currentSummary = series.summary.orEmpty(),
            onOpenLookup = {
                showRenameDialog = false
                onClearBookMetadataSearch()
                lookupInitialBookId = linkedBooks.firstOrNull()?.id
                showMetadataLookup = true
            },
            onConfirm = { newName, newAuthor, newSummary ->
                showRenameDialog = false
                onUpdateSeries(series.id, newName, newAuthor, series.styleNotes, newSummary, false)
            },
            onDismiss = { showRenameDialog = false },
        )
    }

    if (showMetadataLookup) {
        val initialBook = remember(allBooks, lookupInitialBookId, linkedBooks) {
            allBooks.firstOrNull { it.id == lookupInitialBookId } ?: linkedBooks.firstOrNull()
        }
        BookMetadataLookupDialog(
            books = allBooks,
            initialBook = initialBook,
            existingSeries = series,
            lookupState = metadataLookupState,
            onSearch = onSearchBookMetadata,
            onCancelSearch = onCancelBookMetadataSearch,
            onConfirm = { targetBook, selection ->
                showMetadataLookup = false
                onClearBookMetadataSearch()
                onApplyBookAndSeriesMetadata(targetBook?.id, selection)
            },
            onDismiss = {
                showMetadataLookup = false
                onClearBookMetadataSearch()
            },
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

    if (showPickBookDialog) {
        PickBookForExtractionDialog(
            allBooks = allBooks,
            onSelectBook = { book ->
                showPickBookDialog = false
                onExtractMemory(book)
            },
            onDismiss = { showPickBookDialog = false },
        )
    }

    entryToEdit?.let { entry ->
        EditMemoryProposalDialog(
            entry = entry,
            onApproveWithEdit = { entryId, editedTarget ->
                entryToEdit = null
                onApproveProposal(entryId, editedTarget)
            },
            onUpdateOnly = { entryId, newTarget ->
                entryToEdit = null
                onUpdateMemoryEntry(entryId, newTarget)
            },
            onDismiss = { entryToEdit = null },
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
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = "Fechar", onClick = onDismiss)
                    .semantics { contentDescription = "Fechar aviso" }
                    .padding(4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("✕", color = TextSecondary, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun CreateSeriesDialog(
    initialName: String = "",
    initialAuthor: String = "",
    allBooks: List<Book> = emptyList(),
    initialBook: Book? = null,
    lookupState: BookMetadataLookupUiState = BookMetadataLookupUiState(),
    onSearchBookMetadata: (String, String, String) -> Unit = { _, _, _ -> },
    onCancelBookMetadataSearch: () -> Unit = {},
    onClearBookMetadataSearch: () -> Unit = {},
    onConfirm: (
        name: String,
        author: String?,
        styleNotes: String,
        summary: String?,
        targetBookId: Long?,
        metadataSelection: BookMetadataReviewSelection?,
    ) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    var author by rememberSaveable(initialAuthor) { mutableStateOf(initialAuthor) }
    var styleNotes by rememberSaveable { mutableStateOf("") }
    var summary by rememberSaveable { mutableStateOf("") }
    var showError by rememberSaveable { mutableStateOf(false) }
    var showLookup by rememberSaveable { mutableStateOf(false) }
    var pendingTargetBookId by rememberSaveable(initialBook?.id) { mutableStateOf(initialBook?.id) }
    var pendingSelection by remember { mutableStateOf<BookMetadataReviewSelection?>(null) }

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
                OutlinedButton(
                    onClick = {
                        onClearBookMetadataSearch()
                        showLookup = true
                    },
                    border = BorderStroke(1.dp, Accent),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp),
                ) {
                    Text("Buscar informações do livro", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                if (pendingSelection != null) {
                    val sourcesLine = pendingSelection!!.sources.values
                        .distinct()
                        .sortedBy { it.ordinal }
                        .joinToString(", ") { it.displayName }
                    Text(
                        text = if (sourcesLine.isNotEmpty()) {
                            "Dados prontos para aplicar ao criar ($sourcesLine)."
                        } else {
                            "Dados prontos para aplicar ao criar."
                        },
                        color = Positive,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
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
                    value = author,
                    onValueChange = { author = it },
                    label = { Text("Autor (opcional)") },
                    placeholder = { Text("Ex: J.R.R. Tolkien") },
                    singleLine = true,
                    colors = seriesFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = summary,
                    onValueChange = { summary = it },
                    label = { Text("Resumo breve da série (opcional)") },
                    placeholder = { Text("Resumo curto sobre a saga...") },
                    minLines = 2,
                    maxLines = 4,
                    colors = seriesFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
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
                        onConfirm(
                            name.trim(),
                            author.trim().ifEmpty { null },
                            styleNotes.trim(),
                            summary.trim().ifEmpty { null },
                            pendingTargetBookId,
                            pendingSelection,
                        )
                    } else {
                        showError = true
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) {
                Text("Criar")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary),
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) {
                Text("Cancelar")
            }
        },
    )

    if (showLookup) {
        val currentBook = remember(allBooks, pendingTargetBookId, initialBook) {
            allBooks.firstOrNull { it.id == pendingTargetBookId } ?: initialBook
        }
        BookMetadataLookupDialog(
            books = allBooks,
            initialBook = currentBook,
            existingSeries = null,
            currentSeriesName = name,
            currentSeriesAuthor = author,
            currentSeriesSummary = summary,
            lookupState = lookupState,
            onSearch = onSearchBookMetadata,
            onCancelSearch = onCancelBookMetadataSearch,
            onConfirm = { chosenBook, selection ->
                showLookup = false
                onClearBookMetadataSearch()
                pendingTargetBookId = chosenBook?.id
                pendingSelection = selection
                selection.appliedValue(MetadataField.SERIES_NAME)?.let { name = it }
                selection.appliedValue(MetadataField.AUTHOR)?.let { author = it }
                selection.appliedValue(MetadataField.SERIES_SUMMARY)?.let { summary = it }
                showError = false
            },
            onDismiss = {
                showLookup = false
                onClearBookMetadataSearch()
            },
        )
    }
}

@Composable
private fun EditSeriesDialog(
    currentName: String,
    currentAuthor: String,
    currentSummary: String,
    onOpenLookup: () -> Unit,
    onConfirm: (newName: String, newAuthor: String?, newSummary: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(currentName) { mutableStateOf(currentName) }
    var author by rememberSaveable(currentAuthor) { mutableStateOf(currentAuthor) }
    var summary by rememberSaveable(currentSummary) { mutableStateOf(currentSummary) }
    var showError by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = {
            Text(
                text = "Editar série",
                fontFamily = Serif,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onOpenLookup,
                    border = BorderStroke(1.dp, Accent),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp),
                ) {
                    Text("Buscar informações do livro", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(10.dp))
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
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text("Autor (opcional)") },
                    placeholder = { Text("Ex: J.R.R. Tolkien") },
                    singleLine = true,
                    colors = seriesFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = summary,
                    onValueChange = { summary = it },
                    label = { Text("Resumo breve da série (opcional)") },
                    minLines = 2,
                    maxLines = 4,
                    colors = seriesFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
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
                        onConfirm(
                            name.trim(),
                            author.trim().ifEmpty { null },
                            summary.trim().ifEmpty { null },
                        )
                    } else {
                        showError = true
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) {
                Text("Salvar")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary),
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
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
                                    .defaultMinSize(minHeight = 48.dp)
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
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
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
    var showError by rememberSaveable { mutableStateOf(false) }

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
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) {
                Text("Salvar")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary),
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
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
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) {
                Text("Excluir série")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary),
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) {
                Text("Cancelar")
            }
        },
    )
}

@Composable
private fun PickBookForExtractionDialog(
    allBooks: List<Book>,
    onSelectBook: (Book) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = {
            Text(
                text = "Extrair memória de um livro",
                fontFamily = Serif,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
        },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                Text(
                    text = "Escolha um livro da sua biblioteca (original, traduzido no PC ou em português) para extrair termos, personagens e estilo via IA:",
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
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(allBooks, key = { it.id }) { book ->
                            M3Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable(
                                        role = Role.Button,
                                        onClickLabel = "Selecionar ${book.title}",
                                        onClick = { onSelectBook(book) }
                                    ),
                                color = Field,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, Stroke),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .defaultMinSize(minHeight = 56.dp)
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
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
                                        Text(
                                            text = details,
                                            color = TextSecondary,
                                            fontSize = 12.sp,
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text("›", color = Accent, fontSize = 20.sp)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary),
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) {
                Text("Cancelar")
            }
        },
    )
}

@Composable
private fun EditMemoryProposalDialog(
    entry: SeriesMemoryEntry,
    onApproveWithEdit: (Long, String) -> Unit,
    onUpdateOnly: (Long, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var target by rememberSaveable(entry.id) { mutableStateOf(entry.targetTerm) }
    var showError by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = {
            Text(
                text = if (entry.status == MemoryStatus.PENDING) "Editar e aprovar proposta" else "Editar tradução",
                fontFamily = Serif,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(AccentSoft)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = memoryCategoryLabel(entry.category),
                            color = Accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        text = entry.sourceBookTitle,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Original: ${entry.sourceTerm}",
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = target,
                    onValueChange = {
                        target = it
                        showError = false
                    },
                    label = { Text("Tradução (pt-BR)") },
                    singleLine = true,
                    colors = seriesFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                    isError = showError && target.isBlank(),
                )
                if (showError) {
                    Text(
                        text = "A tradução não pode ficar vazia.",
                        color = Danger,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                M3Surface(
                    color = Field,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Stroke),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            text = "Trecho original em \"${entry.sourceBookTitle}\":",
                            color = TextTertiary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "“${entry.excerpt}”",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            fontStyle = FontStyle.Italic,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (entry.status == MemoryStatus.PENDING) {
                Button(
                    onClick = {
                        if (target.isNotBlank()) {
                            onApproveWithEdit(entry.id, target.trim())
                        } else {
                            showError = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Positive),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                    Text("Aprovar", fontWeight = FontWeight.SemiBold)
                }
            } else {
                Button(
                    onClick = {
                        if (target.isNotBlank()) {
                            onUpdateOnly(entry.id, target.trim())
                        } else {
                            showError = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                    Text("Salvar")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary),
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) {
                Text("Cancelar")
            }
        },
    )
}

private fun memoryCategoryLabel(category: String): String = when (category) {
    MemoryCategory.TERM -> "Termo"
    MemoryCategory.CHARACTER -> "Personagem"
    MemoryCategory.PAIR -> "Par EN↔PT"
    else -> category
}

private fun memoryStatusLabel(status: String): String = when (status) {
    MemoryStatus.PENDING -> "Pendente"
    MemoryStatus.APPROVED -> "Aprovado"
    MemoryStatus.REJECTED -> "Rejeitado"
    else -> status
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
