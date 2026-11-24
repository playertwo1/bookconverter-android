package io.github.playertwo1.bookconverter.ui.series

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface as M3Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.playertwo1.bookconverter.core.metadata.AggregatedBookCandidate
import io.github.playertwo1.bookconverter.core.metadata.FieldProposal
import io.github.playertwo1.bookconverter.core.metadata.MetadataField
import io.github.playertwo1.bookconverter.core.metadata.MetadataNamedItem
import io.github.playertwo1.bookconverter.core.metadata.parseMetadataSources
import io.github.playertwo1.bookconverter.core.metadata.MetadataSource
import io.github.playertwo1.bookconverter.data.Book
import io.github.playertwo1.bookconverter.data.BookMetadataReviewSelection
import io.github.playertwo1.bookconverter.data.Series
import io.github.playertwo1.bookconverter.data.defaultAppliedFields
import io.github.playertwo1.bookconverter.data.removedMetadataFields
import io.github.playertwo1.bookconverter.data.formatNamedItemOrigin
import io.github.playertwo1.bookconverter.ui.theme.Accent
import io.github.playertwo1.bookconverter.ui.theme.AccentSoft
import io.github.playertwo1.bookconverter.ui.theme.Background
import io.github.playertwo1.bookconverter.ui.theme.Danger
import io.github.playertwo1.bookconverter.ui.theme.Field
import io.github.playertwo1.bookconverter.ui.theme.Positive
import io.github.playertwo1.bookconverter.ui.theme.Serif
import io.github.playertwo1.bookconverter.ui.theme.Stroke
import io.github.playertwo1.bookconverter.ui.theme.Surface
import io.github.playertwo1.bookconverter.ui.theme.TextPrimary
import io.github.playertwo1.bookconverter.ui.theme.TextSecondary
import io.github.playertwo1.bookconverter.ui.theme.TextTertiary

/**
 * Full-screen dark-mode modal flow (412dp-friendly, 48dp touch targets, pt-BR without em-dashes):
 * 1) Search by ISBN, title, and/or author for a chosen library book (or series only).
 * 2) Pick a candidate from text results with source attribution.
 * 3) Review editable fields, resolve source conflicts, compare against existing book/series values,
 *    check what to apply, and confirm explicitly before anything is written.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BookMetadataLookupDialog(
    books: List<Book>,
    initialBook: Book?,
    existingSeries: Series?,
    currentSeriesName: String = existingSeries?.name.orEmpty(),
    currentSeriesAuthor: String = existingSeries?.author.orEmpty(),
    currentSeriesSummary: String = existingSeries?.summary.orEmpty(),
    lookupState: BookMetadataLookupUiState,
    onSearch: (isbn: String, title: String, author: String) -> Unit,
    onCancelSearch: () -> Unit,
    onConfirm: (targetBook: Book?, selection: BookMetadataReviewSelection) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedBookId by rememberSaveable(initialBook?.id) { mutableStateOf(initialBook?.id) }
    val selectedBook = remember(books, selectedBookId, initialBook) {
        books.firstOrNull { it.id == selectedBookId } ?: initialBook?.takeIf { it.id == selectedBookId }
    }
    var isbnInput by rememberSaveable(initialBook?.id) { mutableStateOf(initialBook?.isbn.orEmpty()) }
    var titleInput by rememberSaveable(initialBook?.id) {
        mutableStateOf(initialBook?.title ?: currentSeriesName)
    }
    var authorInput by rememberSaveable(initialBook?.id) {
        mutableStateOf(initialBook?.author ?: currentSeriesAuthor)
    }
    var pickedCandidate by remember { mutableStateOf<AggregatedBookCandidate?>(null) }

    Dialog(
        onDismissRequest = {
            onCancelSearch()
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Background)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            val candidate = pickedCandidate
            if (candidate == null) {
                LookupSearchStep(
                    books = books,
                    selectedBook = selectedBook,
                    isbnInput = isbnInput,
                    titleInput = titleInput,
                    authorInput = authorInput,
                    lookupState = lookupState,
                    onSelectBook = { book ->
                        selectedBookId = book?.id
                        if (book != null) {
                            isbnInput = book.isbn.orEmpty()
                            titleInput = book.title
                            authorInput = book.author.orEmpty()
                        }
                    },
                    onIsbnChange = { isbnInput = it },
                    onTitleChange = { titleInput = it },
                    onAuthorChange = { authorInput = it },
                    onSearch = { onSearch(isbnInput, titleInput, authorInput) },
                    onCancelSearch = onCancelSearch,
                    onPickCandidate = { pickedCandidate = it },
                    onClose = {
                        onCancelSearch()
                        onDismiss()
                    },
                )
            } else {
                LookupReviewStep(
                    candidate = candidate,
                    existingBook = selectedBook,
                    existingSeries = existingSeries,
                    currentSeriesName = currentSeriesName,
                    currentSeriesAuthor = currentSeriesAuthor,
                    currentSeriesSummary = currentSeriesSummary,
                    onBackToResults = { pickedCandidate = null },
                    onConfirm = { selection -> onConfirm(selectedBook, selection) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LookupSearchStep(
    books: List<Book>,
    selectedBook: Book?,
    isbnInput: String,
    titleInput: String,
    authorInput: String,
    lookupState: BookMetadataLookupUiState,
    onSelectBook: (Book?) -> Unit,
    onIsbnChange: (String) -> Unit,
    onTitleChange: (String) -> Unit,
    onAuthorChange: (String) -> Unit,
    onSearch: () -> Unit,
    onCancelSearch: () -> Unit,
    onPickCandidate: (AggregatedBookCandidate) -> Unit,
    onClose: () -> Unit,
) {
    val fieldColors = lookupFieldColors()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClickLabel = "Fechar", onClick = onClose)
                        .semantics { contentDescription = "Fechar busca" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("‹", fontSize = 28.sp, color = TextPrimary)
                }
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Buscar informações do livro",
                        fontFamily = Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = TextPrimary,
                    )
                    Text(
                        text = "Consulta Open Library, Wikidata e Hardcover (se configurado).",
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
        }

        if (books.isNotEmpty()) {
            item {
                M3Surface(
                    color = Surface,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Stroke),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = "Salvar também no livro:",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            SelectablePill(
                                label = "Só na série",
                                selected = selectedBook == null,
                                onClick = { onSelectBook(null) },
                            )
                            books.forEach { book ->
                                SelectablePill(
                                    label = book.title,
                                    selected = selectedBook?.id == book.id,
                                    onClick = { onSelectBook(book) },
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            M3Surface(
                color = Surface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Stroke),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = isbnInput,
                        onValueChange = onIsbnChange,
                        label = { Text("ISBN (10 ou 13)") },
                        placeholder = { Text("Ex: 9780441172719") },
                        singleLine = true,
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = titleInput,
                        onValueChange = onTitleChange,
                        label = { Text("Título") },
                        placeholder = { Text("Ex: Dune") },
                        singleLine = true,
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = authorInput,
                        onValueChange = onAuthorChange,
                        label = { Text("Autor") },
                        placeholder = { Text("Ex: Frank Herbert") },
                        singleLine = true,
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = onSearch,
                            enabled = !lookupState.isLoading,
                            colors = ButtonDefaults.buttonColors(containerColor = Accent),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp),
                        ) {
                            Text(
                                text = if (lookupState.isLoading) "Buscando..." else "Buscar",
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        if (lookupState.isLoading) {
                            OutlinedButton(
                                onClick = onCancelSearch,
                                border = BorderStroke(1.dp, Stroke),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger),
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                            ) {
                                Text("Cancelar")
                            }
                        }
                    }
                }
            }
        }

        if (lookupState.isLoading) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Accent,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Consultando fontes públicas...", color = TextSecondary, fontSize = 13.sp)
                }
            }
        }

        lookupState.error?.let { err ->
            item {
                M3Surface(
                    color = Danger.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Danger.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = err,
                        color = Danger,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }

        if (lookupState.warnings.isNotEmpty() && lookupState.error == null) {
            item {
                M3Surface(
                    color = Field,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Stroke),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Avisos: ${lookupState.warnings.joinToString(" | ")}",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
        }

        if (lookupState.hasSearched && !lookupState.isLoading && lookupState.candidates.isEmpty() && lookupState.error == null) {
            item {
                Text(
                    text = "Nenhum livro encontrado. Tente ajustar o ISBN, título ou autor.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }

        if (lookupState.candidates.isNotEmpty()) {
            item {
                Text(
                    text = "Resultados (${lookupState.candidates.size})",
                    fontFamily = Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = TextPrimary,
                )
                Text(
                    text = "Toque em um resultado para revisar antes de salvar.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
            }
            items(lookupState.candidates, key = { it.id }) { candidate ->
                CandidateCard(
                    candidate = candidate,
                    onClick = { onPickCandidate(candidate) },
                )
            }
        }
    }
}

@Composable
private fun CandidateCard(
    candidate: AggregatedBookCandidate,
    onClick: () -> Unit,
) {
    M3Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClickLabel = "Revisar ${candidate.displayTitle}", onClick = onClick),
        color = Surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Stroke),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 64.dp)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = candidate.displayTitle,
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val metaLine = listOfNotNull(
                candidate.displayAuthor,
                candidate.displayYear,
                candidate.displayPublisher,
            ).joinToString(" · ")
            if (metaLine.isNotEmpty()) {
                Text(
                    text = metaLine,
                    color = TextSecondary,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val seriesLine = listOfNotNull(
                candidate.displaySeries?.let { "Série: $it" },
                candidate.displayPosition?.let { "Livro $it" },
            ).joinToString(" · ")
            if (seriesLine.isNotEmpty()) {
                Text(
                    text = seriesLine,
                    color = Accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            val entityLine = listOfNotNull(
                candidate.characters.size.takeIf { it > 0 }?.let { n ->
                    if (n == 1) "1 personagem" else "$n personagens"
                },
                candidate.places.size.takeIf { it > 0 }?.let { n ->
                    if (n == 1) "1 local" else "$n locais"
                },
            ).joinToString(" · ")
            if (entityLine.isNotEmpty()) {
                Text(
                    text = entityLine,
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
            }
            val sourceLine = candidate.sources
                .sortedBy { it.ordinal }
                .joinToString(", ") { it.displayName }
            Text(
                text = "Fontes: $sourceLine" + if (candidate.hasAnyConflict) " · revisar diferenças" else "",
                color = TextTertiary,
                fontSize = 11.sp,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LookupReviewStep(
    candidate: AggregatedBookCandidate,
    existingBook: Book?,
    existingSeries: Series?,
    currentSeriesName: String,
    currentSeriesAuthor: String,
    currentSeriesSummary: String,
    onBackToResults: () -> Unit,
    onConfirm: (BookMetadataReviewSelection) -> Unit,
) {
    val fieldValues = remember(candidate.id) {
        mutableStateMapOf<MetadataField, String>().apply {
            MetadataField.entries.forEach { field ->
                candidate.value(field)?.let { put(field, it) }
            }
        }
    }
    val fieldSources = remember(candidate.id) {
        mutableStateMapOf<MetadataField, MetadataSource>().apply {
            MetadataField.entries.forEach { field ->
                candidate.sourceOf(field)?.let { put(field, it) }
            }
        }
    }
    val defaultChecked = remember(candidate.id, existingBook?.id, existingSeries?.id) {
        defaultAppliedFields(
            candidate = candidate,
            existingBook = existingBook,
            existingSeries = existingSeries,
            currentSeriesName = currentSeriesName,
            currentSeriesAuthor = currentSeriesAuthor,
            currentSeriesSummary = currentSeriesSummary,
        )
    }
    val checkedMap = remember(candidate.id, existingBook?.id, existingSeries?.id) {
        mutableStateMapOf<MetadataField, Boolean>().apply {
            MetadataField.entries.forEach { field ->
                put(field, field in defaultChecked)
            }
        }
    }

    var bookAuthorChecked by remember(candidate.id, existingBook?.id) {
        val current = existingBook?.author
        val proposed = candidate.value(MetadataField.AUTHOR)
        val fromSearch = MetadataField.AUTHOR in parseMetadataSources(existingBook?.metadataSourcesJson)
        mutableStateOf(proposed != null && (current.isNullOrBlank() || current.equals(proposed, ignoreCase = true) || fromSearch))
    }

    val characterRows = remember(candidate.id, existingBook?.id, existingSeries?.id) {
        mutableStateListOf<EditableNamedItem>().apply {
            candidate.characters.forEachIndexed { idx, item ->
                add(
                    EditableNamedItem(
                        id = "char-$idx-${item.name}",
                        name = item.name,
                        description = item.description,
                        aliases = item.aliases,
                        source = item.source,
                        additionalSources = item.additionalSources,
                        fromFreeText = item.fromFreeText,
                        selected = true,
                        addToGlossary = false,
                    )
                )
            }
        }
    }
    val locationRows = remember(candidate.id, existingBook?.id, existingSeries?.id) {
        mutableStateListOf<EditableNamedItem>().apply {
            candidate.places.forEachIndexed { idx, item ->
                add(
                    EditableNamedItem(
                        id = "loc-$idx-${item.name}",
                        name = item.name,
                        description = item.description,
                        aliases = item.aliases,
                        source = item.source,
                        additionalSources = item.additionalSources,
                        fromFreeText = item.fromFreeText,
                        selected = true,
                        addToGlossary = false,
                    )
                )
            }
        }
    }

    val seriesFields = listOf(
        MetadataField.SERIES_NAME to currentSeriesName.takeIf { it.isNotBlank() },
        MetadataField.AUTHOR to currentSeriesAuthor.takeIf { it.isNotBlank() },
        MetadataField.SERIES_SUMMARY to currentSeriesSummary.takeIf { it.isNotBlank() },
    )
    val bookFields = if (existingBook != null) {
        listOf(
            MetadataField.TITLE to existingBook.title.takeIf { it.isNotBlank() },
            MetadataField.AUTHOR to existingBook.author,
            MetadataField.SERIES_POSITION to existingBook.seriesPosition?.takeIf { it.isNotBlank() },
            MetadataField.ISBN to existingBook.isbn?.takeIf { it.isNotBlank() },
            MetadataField.PUBLISHER to existingBook.publisher?.takeIf { it.isNotBlank() },
            MetadataField.PUBLISHED_DATE to existingBook.publishedDate?.takeIf { it.isNotBlank() },
            MetadataField.LANGUAGE to existingBook.language?.takeIf { it.isNotBlank() },
            MetadataField.SUMMARY to existingBook.summary?.takeIf { it.isNotBlank() },
        )
    } else {
        emptyList()
    }

    val bookRemovals = removedMetadataFields(existingBook?.metadataSourcesJson, fieldValues.keys)
    val seriesRemovals = removedMetadataFields(existingSeries?.metadataSourcesJson, fieldValues.keys)
    val replacesNamedItems = listOf(
        existingBook?.charactersJson, existingBook?.locationsJson,
        existingSeries?.charactersJson, existingSeries?.locationsJson,
    ).any { !it.isNullOrBlank() }
    val hasRemovals = bookRemovals.isNotEmpty() || seriesRemovals.isNotEmpty() || replacesNamedItems

    val appliedFieldsCount = checkedMap.count { (field, checked) ->
        checked && !fieldValues[field].isNullOrBlank()
    }
    val selectedCharactersCount = characterRows.count { it.selected && it.name.isNotBlank() }
    val selectedLocationsCount = locationRows.count { it.selected && it.name.isNotBlank() }
    val appliedCount = appliedFieldsCount + selectedCharactersCount + selectedLocationsCount +
        (if (bookAuthorChecked && !fieldValues[MetadataField.AUTHOR].isNullOrBlank()) 1 else 0)

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable(role = Role.Button, onClickLabel = "Voltar aos resultados", onClick = onBackToResults)
                            .semantics { contentDescription = "Voltar aos resultados" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("‹", fontSize = 28.sp, color = TextPrimary)
                    }
                    Spacer(Modifier.width(4.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Revisar informações",
                            fontFamily = Serif,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = TextPrimary,
                        )
                        val attr = candidate.sources.sortedBy { it.ordinal }.joinToString(", ") { it.displayName }
                        Text(
                            text = "Fontes: $attr. Marque o que deseja aplicar.",
                            color = TextSecondary,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            item {
                Text(
                    text = "Dados da série",
                    fontFamily = Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = TextPrimary,
                )
            }
            if (hasRemovals) {
                item {
                    val changes = listOfNotNull(
                        bookRemovals.takeIf { it.isNotEmpty() }?.joinToString(prefix = "Livro: ") { it.labelPtBr },
                        seriesRemovals.takeIf { it.isNotEmpty() }?.joinToString(prefix = "Série: ") { it.labelPtBr },
                        "Personagens e locais anteriores serão substituídos pelos selecionados.".takeIf { replacesNamedItems },
                    )
                    Text(
                        text = (listOf("A confirmação remove dados da busca anterior que faltam neste resultado.") + changes).joinToString("\n"),
                        color = TextSecondary,
                    )
                }
            }
            items(seriesFields, key = { "series-${it.first.name}" }) { (field, existingVal) ->
                val proposal = candidate.fields[field]
                ReviewFieldCard(
                    field = field,
                    proposal = proposal,
                    currentValue = existingVal,
                    editedValue = fieldValues[field].orEmpty(),
                    selectedSource = fieldSources[field],
                    checked = checkedMap[field] == true,
                    multiline = field == MetadataField.SERIES_SUMMARY,
                    onCheckedChange = { checkedMap[field] = it },
                    onValueChange = { fieldValues[field] = it },
                    onPickOption = { value, src ->
                        fieldValues[field] = value
                        fieldSources[field] = src
                    },
                )
            }

            if (existingBook != null) {
                item {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Dados do livro: ${existingBook.title}",
                        fontFamily = Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = TextPrimary,
                    )
                    Text(
                        text = "Dados locais diferentes ficam desmarcados.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                }
                items(bookFields, key = { "book-${it.first.name}" }) { (field, existingVal) ->
                    val proposal = candidate.fields[field]
                    ReviewFieldCard(
                        field = field,
                        proposal = proposal,
                        currentValue = existingVal,
                        editedValue = fieldValues[field].orEmpty(),
                        selectedSource = fieldSources[field],
                        checked = if (field == MetadataField.AUTHOR) bookAuthorChecked else checkedMap[field] == true,
                        multiline = field == MetadataField.SUMMARY,
                        onCheckedChange = {
                            if (field == MetadataField.AUTHOR) bookAuthorChecked = it else checkedMap[field] = it
                        },
                        onValueChange = { fieldValues[field] = it },
                        onPickOption = { value, src ->
                            fieldValues[field] = value
                            fieldSources[field] = src
                        },
                    )
                }
            }

            // Section: Personagens
            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Personagens (${characterRows.size})",
                    fontFamily = Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = TextPrimary,
                )
                val hasFreeTextChars = characterRows.any { it.fromFreeText }
                Text(
                    text = when {
                        characterRows.isEmpty() -> "Nenhum personagem encontrado nas fontes consultadas."
                        hasFreeTextChars -> "Itens marcados como texto livre vêm da Open Library e podem conter ruído. Revise antes de salvar."
                        else -> "Marque os personagens que deseja salvar no livro e na série."
                    },
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
            }
            items(characterRows, key = { it.id }) { row ->
                ReviewNamedItemCard(
                    item = row,
                    onSelectedChange = { checked ->
                        val i = characterRows.indexOfFirst { it.id == row.id }
                        if (i >= 0) {
                            characterRows[i] = row.copy(
                                selected = checked,
                                addToGlossary = if (checked) row.addToGlossary else false,
                            )
                        }
                    },
                    onNameChange = { newName ->
                        val i = characterRows.indexOfFirst { it.id == row.id }
                        if (i >= 0) characterRows[i] = row.copy(name = newName)
                    },
                    onAddToGlossaryChange = { addGlossary ->
                        val i = characterRows.indexOfFirst { it.id == row.id }
                        if (i >= 0) characterRows[i] = row.copy(addToGlossary = addGlossary)
                    },
                    onRemove = {
                        characterRows.removeAll { it.id == row.id }
                    },
                )
            }

            // Section: Locais
            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Locais (${locationRows.size})",
                    fontFamily = Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = TextPrimary,
                )
                val hasFreeTextLocs = locationRows.any { it.fromFreeText }
                Text(
                    text = when {
                        locationRows.isEmpty() -> "Nenhum local encontrado nas fontes consultadas."
                        hasFreeTextLocs -> "Itens marcados como texto livre vêm da Open Library e podem conter ruído. Revise antes de salvar."
                        else -> "Marque os locais que deseja salvar no livro e na série."
                    },
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
            }
            items(locationRows, key = { it.id }) { row ->
                ReviewNamedItemCard(
                    item = row,
                    onSelectedChange = { checked ->
                        val i = locationRows.indexOfFirst { it.id == row.id }
                        if (i >= 0) {
                            locationRows[i] = row.copy(
                                selected = checked,
                                addToGlossary = if (checked) row.addToGlossary else false,
                            )
                        }
                    },
                    onNameChange = { newName ->
                        val i = locationRows.indexOfFirst { it.id == row.id }
                        if (i >= 0) locationRows[i] = row.copy(name = newName)
                    },
                    onAddToGlossaryChange = { addGlossary ->
                        val i = locationRows.indexOfFirst { it.id == row.id }
                        if (i >= 0) locationRows[i] = row.copy(addToGlossary = addGlossary)
                    },
                    onRemove = {
                        locationRows.removeAll { it.id == row.id }
                    },
                )
            }
        }

        // Fixed bottom confirmation bar
        M3Surface(
            color = Surface,
            border = BorderStroke(1.dp, Stroke),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onBackToResults,
                    border = BorderStroke(1.dp, Stroke),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                    Text("Voltar")
                }
                Button(
                    onClick = {
                        val applied = checkedMap
                            .filterValues { it }
                            .keys
                            .filter { !fieldValues[it].isNullOrBlank() }
                            .toSet()
                        val confirmedChars = characterRows
                            .filter { it.selected && it.name.isNotBlank() }
                            .map { it.toNamedItem() }
                        val confirmedLocs = locationRows
                            .filter { it.selected && it.name.isNotBlank() }
                            .map { it.toNamedItem() }
                        val glossaryChars = characterRows
                            .filter { it.selected && it.addToGlossary && it.name.isNotBlank() }
                            .map { it.toNamedItem() }
                        val glossaryLocs = locationRows
                            .filter { it.selected && it.addToGlossary && it.name.isNotBlank() }
                            .map { it.toNamedItem() }
                        onConfirm(
                            BookMetadataReviewSelection(
                                values = fieldValues.toMap(),
                                sources = fieldSources.filter { (field, source) ->
                                    val proposal = candidate.fields[field]
                                    proposal?.options?.any { it.source == source && it.value == fieldValues[field] } == true ||
                                        (proposal?.selectedSource == source && proposal.selectedValue == fieldValues[field])
                                },
                                appliedFields = applied,
                                openLibraryKey = candidate.openLibraryKey,
                                wikidataQid = candidate.wikidataQid,
                                hardcoverId = candidate.hardcoverId,
                                seriesOpenLibraryKey = candidate.seriesOpenLibraryKey,
                                seriesWikidataQid = candidate.seriesWikidataQid,
                                seriesHardcoverId = candidate.seriesHardcoverId,
                                characters = confirmedChars,
                                locations = confirmedLocs,
                                charactersForGlossary = glossaryChars,
                                locationsForGlossary = glossaryLocs,
                                bookAuthorApplied = existingBook != null && bookAuthorChecked,
                            )
                        )
                    },
                    enabled = appliedCount > 0 || hasRemovals,
                    colors = ButtonDefaults.buttonColors(containerColor = Positive, contentColor = TextPrimary),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp),
                ) {
                    Text(
                        text = if (appliedCount > 0) "Confirmar e aplicar ($appliedCount)" else if (hasRemovals) "Confirmar remoção" else "Marque ao menos 1 item",
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private data class EditableNamedItem(
    val id: String,
    val name: String,
    val description: String?,
    val aliases: List<String>,
    val source: MetadataSource,
    val additionalSources: Set<MetadataSource>,
    val fromFreeText: Boolean,
    val selected: Boolean,
    val addToGlossary: Boolean = false,
) {
    fun toNamedItem(): MetadataNamedItem = MetadataNamedItem(
        name = name.trim(),
        description = description,
        aliases = aliases,
        source = source,
        additionalSources = additionalSources,
        fromFreeText = fromFreeText,
    )
}

@Composable
private fun ReviewNamedItemCard(
    item: EditableNamedItem,
    onSelectedChange: (Boolean) -> Unit,
    onNameChange: (String) -> Unit,
    onAddToGlossaryChange: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val originLabel = formatNamedItemOrigin(item.toNamedItem())
    M3Surface(
        color = Surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (item.selected) Accent.copy(alpha = 0.6f) else Stroke),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .clickable(role = Role.Checkbox) { onSelectedChange(!item.selected) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = item.selected,
                        onCheckedChange = onSelectedChange,
                        colors = CheckboxDefaults.colors(
                            checkedColor = Accent,
                            uncheckedColor = TextSecondary,
                            checkmarkColor = TextPrimary,
                        ),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Origem: $originLabel",
                            color = if (item.fromFreeText) Accent else TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        if (item.aliases.isNotEmpty()) {
                            Text(
                                text = "Também: ${item.aliases.joinToString(", ")}",
                                color = TextTertiary,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (!item.description.isNullOrBlank()) {
                            Text(
                                text = item.description,
                                color = TextSecondary,
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                TextButton(
                    onClick = onRemove,
                    colors = ButtonDefaults.textButtonColors(contentColor = Danger),
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                    Text("Remover", fontSize = 12.sp)
                }
            }

            OutlinedTextField(
                value = item.name,
                onValueChange = onNameChange,
                label = { Text("Nome") },
                singleLine = true,
                colors = lookupFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            val canAddToGlossary = item.selected && item.name.isNotBlank()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Field)
                    .clickable(enabled = canAddToGlossary, role = Role.Checkbox) {
                        onAddToGlossaryChange(!item.addToGlossary)
                    }
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = item.addToGlossary && canAddToGlossary,
                    onCheckedChange = { if (canAddToGlossary) onAddToGlossaryChange(it) },
                    enabled = canAddToGlossary,
                    colors = CheckboxDefaults.colors(
                        checkedColor = Positive,
                        uncheckedColor = TextSecondary,
                        checkmarkColor = TextPrimary,
                    ),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Adicionar ao glossário da série",
                        color = if (canAddToGlossary) TextPrimary else TextTertiary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "Usa o nome original como termo; edite a tradução depois.",
                        color = TextTertiary,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewFieldCard(
    field: MetadataField,
    proposal: FieldProposal?,
    currentValue: String?,
    editedValue: String,
    selectedSource: MetadataSource?,
    checked: Boolean,
    multiline: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onValueChange: (String) -> Unit,
    onPickOption: (String, MetadataSource) -> Unit,
) {
    val hasDifferentExisting = !currentValue.isNullOrBlank() &&
        editedValue.isNotBlank() &&
        !currentValue.trim().equals(editedValue.trim(), ignoreCase = true)

    M3Surface(
        color = Surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (checked) Accent.copy(alpha = 0.6f) else Stroke),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .clickable(role = Role.Checkbox) { onCheckedChange(!checked) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    colors = CheckboxDefaults.colors(
                        checkedColor = Accent,
                        uncheckedColor = TextSecondary,
                        checkmarkColor = TextPrimary,
                    ),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = field.labelPtBr,
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val originText = selectedSource?.let { "Origem: ${it.displayName}" } ?: "Sem dado encontrado (editável)"
                    Text(
                        text = originText,
                        color = TextSecondary,
                        fontSize = 11.sp,
                    )
                }
            }

            if (!currentValue.isNullOrBlank()) {
                M3Surface(
                    color = Field,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, if (hasDifferentExisting) Danger.copy(alpha = 0.45f) else Stroke),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(8.dp)) {
                        Text(
                            text = if (hasDifferentExisting) "Valor atual (será substituído se marcado):" else "Valor atual:",
                            color = if (hasDifferentExisting) Danger else TextTertiary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = currentValue,
                            color = TextSecondary,
                            fontSize = 12.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (proposal != null && proposal.hasConflict) {
                Text(
                    text = "Conflito entre fontes. Escolha uma opção:",
                    color = Accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    proposal.options.forEach { option ->
                        val isChosen = editedValue == option.value && selectedSource == option.source
                        SelectablePill(
                            label = "${option.source.displayName}: ${option.value.take(48)}",
                            selected = isChosen,
                            onClick = { onPickOption(option.value, option.source) },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = editedValue,
                onValueChange = onValueChange,
                label = { Text(if (hasDifferentExisting) "Novo valor" else "Valor") },
                singleLine = !multiline,
                minLines = if (multiline) 3 else 1,
                maxLines = if (multiline) 6 else 1,
                colors = lookupFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SelectablePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) AccentSoft else Field)
            .border(1.dp, if (selected) Accent else Stroke, RoundedCornerShape(12.dp))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) TextPrimary else TextSecondary,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun lookupFieldColors() = OutlinedTextFieldDefaults.colors(
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
