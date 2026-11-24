package io.github.playertwo1.bookconverter.ui.series

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.playertwo1.bookconverter.core.metadata.AggregatedBookCandidate
import io.github.playertwo1.bookconverter.core.metadata.AggregatedSearchResponse
import io.github.playertwo1.bookconverter.core.metadata.BookMetadataAggregator
import io.github.playertwo1.bookconverter.core.metadata.BookMetadataQuery
import io.github.playertwo1.bookconverter.core.metadata.HardcoverMetadataProvider
import io.github.playertwo1.bookconverter.core.metadata.MetadataSource
import io.github.playertwo1.bookconverter.core.metadata.OpenLibraryMetadataProvider
import io.github.playertwo1.bookconverter.core.metadata.SourceSearchResult
import io.github.playertwo1.bookconverter.core.metadata.WikidataMetadataProvider
import io.github.playertwo1.bookconverter.core.provider.Provider
import io.github.playertwo1.bookconverter.core.provider.ProviderClient
import io.github.playertwo1.bookconverter.core.provider.providerClient
import io.github.playertwo1.bookconverter.core.provider.translationModels
import io.github.playertwo1.bookconverter.core.translate.Glossary
import io.github.playertwo1.bookconverter.core.translate.GlossaryTerm
import io.github.playertwo1.bookconverter.core.translate.SeriesMemoryExtractor
import io.github.playertwo1.bookconverter.data.AppDatabase
import io.github.playertwo1.bookconverter.data.Book
import io.github.playertwo1.bookconverter.data.BookMetadataReviewSelection
import io.github.playertwo1.bookconverter.data.GlossaryEntry
import io.github.playertwo1.bookconverter.data.MemoryCategory
import io.github.playertwo1.bookconverter.data.MemoryStatus
import io.github.playertwo1.bookconverter.data.ProviderSettings
import io.github.playertwo1.bookconverter.data.Series
import io.github.playertwo1.bookconverter.data.SeriesMemoryEntry
import io.github.playertwo1.bookconverter.data.SeriesSummary
import io.github.playertwo1.bookconverter.data.applyMetadataToBook
import io.github.playertwo1.bookconverter.data.applyMetadataToSeries
import io.github.playertwo1.bookconverter.data.buildNewGlossaryEntries
import io.github.playertwo1.bookconverter.data.label
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
data class BookMetadataLookupUiState(
    val isLoading: Boolean = false,
    val hasSearched: Boolean = false,
    val candidates: List<AggregatedBookCandidate> = emptyList(),
    val activeSources: List<MetadataSource> = emptyList(),
    val warnings: List<String> = emptyList(),
    val error: String? = null,
)

internal fun buildMetadataLookupUiState(response: AggregatedSearchResponse): BookMetadataLookupUiState {
    val warnings = response.failedSources.map { "${it.source.displayName}: ${it.message}" }
    val allFailed = response.activeSources.isEmpty() && response.failedSources.isNotEmpty()
    return BookMetadataLookupUiState(
        isLoading = false,
        hasSearched = true,
        candidates = response.candidates,
        activeSources = response.activeSources,
        warnings = warnings,
        error = if (allFailed) warnings.joinToString(" ") else null,
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
class SeriesViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    private val seriesDao = db.series()
    private val bookDao = db.books()
    private val settings = ProviderSettings(app)
    private val appVersion: String = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
    }.getOrDefault("1.0")
    private val metadataAggregator: BookMetadataAggregator by lazy {
        BookMetadataAggregator(
            listOf(
                OpenLibraryMetadataProvider(appVersion = appVersion),
                WikidataMetadataProvider(appVersion = appVersion),
                HardcoverMetadataProvider(
                    tokenProvider = { settings.hardcoverToken() },
                    appVersion = appVersion,
                ),
            )
        )
    }
    private var metadataSearchJob: Job? = null
    private val _metadataLookupState = MutableStateFlow(BookMetadataLookupUiState())
    val metadataLookupState: StateFlow<BookMetadataLookupUiState> = _metadataLookupState.asStateFlow()

    val seriesList: StateFlow<List<SeriesSummary>> = seriesDao.observeAllSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allBooks: StateFlow<List<Book>> = bookDao.all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedSeriesId = MutableStateFlow<Long?>(null)
    val selectedSeriesId: StateFlow<Long?> = _selectedSeriesId.asStateFlow()

    val selectedSeries: StateFlow<Series?> = _selectedSeriesId.flatMapLatest { id ->
        if (id != null) seriesDao.observeById(id) else flowOf(null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val selectedGlossary: StateFlow<List<GlossaryEntry>> = _selectedSeriesId.flatMapLatest { id ->
        if (id != null) seriesDao.observeGlossary(id) else flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedMemoryEntries: StateFlow<List<SeriesMemoryEntry>> = _selectedSeriesId.flatMapLatest { id ->
        if (id != null) seriesDao.observeMemoryEntries(id) else flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isExtractingMemory = MutableStateFlow(false)
    val isExtractingMemory: StateFlow<Boolean> = _isExtractingMemory.asStateFlow()

    private val _extractionProgress = MutableStateFlow<String?>(null)
    val extractionProgress: StateFlow<String?> = _extractionProgress.asStateFlow()

    private var extractionJob: Job? = null

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun selectSeries(seriesId: Long?) {
        _selectedSeriesId.value = seriesId
    }

    fun clearMessage() {
        _message.value = null
    }

    fun createSeries(
        name: String,
        author: String? = null,
        styleNotes: String = "",
        linkBookId: Long? = null,
        summary: String? = null,
        metadataSelection: BookMetadataReviewSelection? = null,
        onCreated: (Long) -> Unit = {},
    ) {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return
        val cleanAuthor = author?.trim()?.ifEmpty { null }
        val cleanSummary = summary?.trim()?.ifEmpty { null }
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val baseSeries = Series(
                    name = trimmedName,
                    author = cleanAuthor,
                    styleNotes = styleNotes.trim(),
                    glossaryVersion = 1,
                    createdAt = now,
                    updatedAt = now,
                    summary = cleanSummary,
                )
                val seriesToInsert = if (metadataSelection != null) {
                    applyMetadataToSeries(baseSeries, metadataSelection, now).copy(
                        name = trimmedName,
                        author = cleanAuthor,
                        summary = cleanSummary,
                    )
                } else {
                    baseSeries
                }
                val id = seriesDao.insertSeries(seriesToInsert)
                if (linkBookId != null) {
                    val existingBook = bookDao.byId(linkBookId)
                    if (existingBook != null && metadataSelection != null) {
                        bookDao.updateBook(applyMetadataToBook(existingBook, metadataSelection, targetSeriesId = id))
                    } else {
                        bookDao.setSeries(linkBookId, id)
                    }
                }
                var addedGlossaryCount = 0
                if (metadataSelection != null) {
                    val newGlossary = buildNewGlossaryEntries(
                        seriesId = id,
                        existingGlossary = emptyList(),
                        charactersForGlossary = metadataSelection.charactersForGlossary,
                        locationsForGlossary = metadataSelection.locationsForGlossary,
                        now = now,
                    )
                    if (newGlossary.isNotEmpty()) {
                        seriesDao.importGlossaryEntriesAndBump(id, newGlossary, now)
                        addedGlossaryCount = newGlossary.size
                    }
                }
                _selectedSeriesId.value = id
                _message.value = if (addedGlossaryCount > 0) {
                    "Série \"$trimmedName\" criada · $addedGlossaryCount termo(s) no glossário."
                } else {
                    "Série \"$trimmedName\" criada."
                }
                onCreated(id)
            } catch (e: Exception) {
                _message.value = "Erro ao criar série: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun updateSeries(
        seriesId: Long,
        name: String,
        author: String?,
        styleNotes: String,
        summary: String? = null,
        keepExistingSummary: Boolean = true,
    ) {
        viewModelScope.launch {
            try {
                val current = seriesDao.byId(seriesId) ?: return@launch
                val trimmedName = name.trim().ifEmpty { current.name }
                val cleanAuthor = author?.trim()?.ifEmpty { null }
                val finalSummary = if (keepExistingSummary) current.summary else summary?.trim()?.ifEmpty { null }
                val now = System.currentTimeMillis()
                seriesDao.updateSeries(
                    current.copy(
                        name = trimmedName,
                        author = cleanAuthor,
                        styleNotes = styleNotes.trim(),
                        summary = finalSummary,
                        updatedAt = now,
                    )
                )
                _message.value = "Alterações salvas."
            } catch (e: Exception) {
                _message.value = "Erro ao salvar alterações: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun searchBookMetadata(isbn: String, title: String, author: String) {
        val query = BookMetadataQuery(isbn = isbn, title = title, author = author)
        if (query.isBlank) {
            _metadataLookupState.value = BookMetadataLookupUiState(
                error = "Informe ISBN, título ou autor para buscar.",
            )
            return
        }
        metadataSearchJob?.cancel()
        _metadataLookupState.value = BookMetadataLookupUiState(isLoading = true)
        metadataSearchJob = viewModelScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    metadataAggregator.search(query)
                }
                _metadataLookupState.value = buildMetadataLookupUiState(response)
            } catch (_: CancellationException) {
                _metadataLookupState.value = _metadataLookupState.value.copy(isLoading = false)
            } catch (e: Exception) {
                _metadataLookupState.value = BookMetadataLookupUiState(
                    isLoading = false,
                    hasSearched = true,
                    error = "Falha na busca: ${e.message ?: e.javaClass.simpleName}",
                )
            }
        }
    }

    fun cancelBookMetadataSearch() {
        metadataSearchJob?.cancel()
        _metadataLookupState.value = _metadataLookupState.value.copy(isLoading = false)
    }

    fun clearBookMetadataSearch() {
        metadataSearchJob?.cancel()
        _metadataLookupState.value = BookMetadataLookupUiState()
    }

    fun applyBookAndSeriesMetadata(
        seriesId: Long,
        bookId: Long?,
        selection: BookMetadataReviewSelection,
    ) {
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                var addedGlossaryCount = 0
                withContext(Dispatchers.IO) {
                    val currentSeries = seriesDao.byId(seriesId)
                    if (currentSeries != null) {
                        seriesDao.updateSeries(applyMetadataToSeries(currentSeries, selection, now))
                    }
                    if (bookId != null) {
                        val currentBook = bookDao.byId(bookId)
                        if (currentBook != null) {
                            bookDao.updateBook(applyMetadataToBook(currentBook, selection, targetSeriesId = seriesId))
                        }
                    }
                    val existingGlossary = seriesDao.glossaryForSeries(seriesId)
                    val newGlossary = buildNewGlossaryEntries(
                        seriesId = seriesId,
                        existingGlossary = existingGlossary,
                        charactersForGlossary = selection.charactersForGlossary,
                        locationsForGlossary = selection.locationsForGlossary,
                        now = now,
                    )
                    if (newGlossary.isNotEmpty()) {
                        seriesDao.importGlossaryEntriesAndBump(seriesId, newGlossary, now)
                        addedGlossaryCount = newGlossary.size
                    }
                }
                _message.value = if (addedGlossaryCount > 0) {
                    "Informações salvas · $addedGlossaryCount termo(s) no glossário."
                } else {
                    "Informações salvas."
                }
            } catch (e: Exception) {
                _message.value = "Erro ao salvar informações: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun deleteSeries(seriesId: Long) {
        viewModelScope.launch {
            try {
                seriesDao.deleteSeries(seriesId)
                if (_selectedSeriesId.value == seriesId) {
                    _selectedSeriesId.value = null
                }
                _message.value = "Série excluída. Os livros foram mantidos na biblioteca."
            } catch (e: Exception) {
                _message.value = "Erro ao excluir série: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun toggleBookSeries(book: Book, targetSeriesId: Long) {
        viewModelScope.launch {
            try {
                val newSeriesId = if (book.seriesId == targetSeriesId) null else targetSeriesId
                bookDao.setSeries(book.id, newSeriesId)
            } catch (e: Exception) {
                _message.value = "Erro ao vincular livro: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun saveGlossaryEntry(
        entryId: Long = 0,
        seriesId: Long,
        source: String,
        target: String,
        notes: String? = null,
    ) {
        val src = source.trim()
        val tgt = target.trim()
        if (src.isEmpty() || tgt.isEmpty()) return
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                seriesDao.saveGlossaryEntryAndBump(
                    GlossaryEntry(
                        id = entryId,
                        seriesId = seriesId,
                        sourceTerm = src,
                        targetTerm = tgt,
                        notes = notes?.trim()?.ifEmpty { null },
                        updatedAt = now,
                    ),
                    now = now,
                )
                _message.value = if (entryId == 0L) "Termo \"$src\" adicionado." else "Termo \"$src\" atualizado."
            } catch (e: Exception) {
                _message.value = "Erro ao salvar termo: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun deleteGlossaryEntry(entryId: Long, seriesId: Long) {
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                seriesDao.deleteGlossaryEntryAndBump(entryId, seriesId, now)
                _message.value = "Termo removido."
            } catch (e: Exception) {
                _message.value = "Erro ao remover termo: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun importGlossaryTxt(seriesId: Long, uri: Uri) {
        viewModelScope.launch {
            try {
                val resolver = getApplication<Application>().contentResolver
                val content = resolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader(Charsets.UTF_8).readText()
                } ?: error("Não foi possível abrir o arquivo.")

                val result = Glossary.parseDesktopTxt(content)
                if (result.terms.isEmpty()) {
                    _message.value = "Nenhum termo válido encontrado no arquivo TXT."
                    return@launch
                }

                val now = System.currentTimeMillis()
                val entries = result.terms.map { term ->
                    GlossaryEntry(
                        seriesId = seriesId,
                        sourceTerm = term.source,
                        targetTerm = term.target,
                        notes = term.notes,
                        updatedAt = now,
                    )
                }

                seriesDao.importGlossaryEntriesAndBump(seriesId, entries, now)

                val countMsg = if (result.terms.size == 1) "1 termo importado" else "${result.terms.size} termos importados"
                val conflictsMsg = if (result.conflicts.isNotEmpty()) {
                    " · ${result.conflicts.size} conflito(s) resolvido(s)"
                } else ""
                val skippedMsg = if (result.skippedLines > 0) {
                    " · ${result.skippedLines} linha(s) ignorada(s)"
                } else ""

                _message.value = "$countMsg$conflictsMsg$skippedMsg com sucesso."
            } catch (e: Exception) {
                _message.value = "Erro na importação: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun exportGlossaryTxt(seriesId: Long, uri: Uri) {
        viewModelScope.launch {
            try {
                val resolver = getApplication<Application>().contentResolver
                val entries = seriesDao.glossaryForSeries(seriesId)
                val terms = entries.map {
                    GlossaryTerm(source = it.sourceTerm, target = it.targetTerm, notes = it.notes)
                }
                val text = Glossary.formatDesktopTxt(terms)
                resolver.openOutputStream(uri)?.use { stream ->
                    stream.write(text.toByteArray(Charsets.UTF_8))
                } ?: error("Não foi possível gravar no arquivo.")
                val countMsg = if (terms.size == 1) "1 termo exportado" else "${terms.size} termos exportados"
                _message.value = "Glossário exportado ($countMsg)."
            } catch (e: Exception) {
                _message.value = "Erro na exportação: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun extractMemoryFromBook(seriesId: Long, book: Book) {
        if (_isExtractingMemory.value) return

        val file = File(book.privatePath)
        if (!file.exists()) {
            _message.value = "Arquivo do livro não encontrado: ${book.title}"
            return
        }

        val provider = settings.lastProvider?.takeIf { settings.hasKey(it) }
            ?: Provider.entries.firstOrNull { settings.hasKey(it) }

        if (provider == null) {
            _message.value = "Nenhum provedor configurado. Configure uma chave ou login em Ajustes."
            return
        }

        val client: ProviderClient = try {
            when (provider) {
                Provider.CHATGPT,
                Provider.OPENAI,
                Provider.ANTHROPIC,
                Provider.GEMINI -> settings.client(provider)
                Provider.OPENROUTER -> {
                    val apiKey = settings.key(provider)
                    val baseUrl = settings.baseUrl(provider)
                    if (apiKey != null) providerClient(Provider.OPENROUTER, apiKey, baseUrl) else null
                }
            }
        } catch (_: Exception) {
            null
        } ?: run {
            _message.value = "Credencial ausente ou expirada para ${provider.label}. Configure em Ajustes."
            return
        }

        extractionJob?.cancel()
        _isExtractingMemory.value = true
        _extractionProgress.value = "Iniciando análise de \"${book.title}\"…"

        extractionJob = viewModelScope.launch {
            try {
                var model = settings.lastModel(provider)
                if (model.isNullOrBlank()) {
                    val availableModels = withContext(Dispatchers.IO) {
                        try {
                            translationModels(client.listModels())
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                    model = availableModels.firstOrNull() ?: when (provider) {
                        Provider.OPENAI -> "gpt-4o-mini"
                        Provider.CHATGPT -> "gpt-4o"
                        Provider.ANTHROPIC -> "claude-3-5-haiku-20241022"
                        Provider.GEMINI -> "gemini-1.5-flash"
                        Provider.OPENROUTER -> "deepseek/deepseek-chat"
                    }
                    settings.setLastModel(provider, model)
                }

                val existingEntries = withContext(Dispatchers.IO) {
                    seriesDao.glossaryForSeries(seriesId)
                }
                val existingTerms = existingEntries.map {
                    GlossaryTerm(source = it.sourceTerm, target = it.targetTerm, notes = it.notes)
                }

                val result = withContext(Dispatchers.IO) {
                    SeriesMemoryExtractor.extractFromEpub(
                        epubFile = file,
                        sourceBookTitle = book.title,
                        client = client,
                        model = model,
                        effort = null,
                        existingTerms = existingTerms,
                        onProgress = { cur, tot ->
                            _extractionProgress.value = "Analisando trecho $cur de $tot…"
                        }
                    )
                }

                val now = System.currentTimeMillis()
                val entries = result.proposals.map { p ->
                    SeriesMemoryEntry(
                        seriesId = seriesId,
                        category = p.category.name,
                        sourceTerm = p.source,
                        targetTerm = p.target,
                        notes = p.notes,
                        excerpt = p.excerpt,
                        sourceBookTitle = p.sourceBookTitle,
                        conflictWarning = p.conflictWarning,
                        status = MemoryStatus.PENDING,
                        createdAt = now,
                        updatedAt = now,
                    )
                }

                if (entries.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        seriesDao.insertMemoryEntries(entries)
                    }
                    val totalTokens = result.inputTokens + result.outputTokens
                    val tokenInfo = if (totalTokens > 0) " (${totalTokens} tokens consumidos)" else ""
                    _message.value = "Memória extraída: ${entries.size} proposta(s) gerada(s) a partir de \"${book.title}\"$tokenInfo."
                } else {
                    _message.value = "Nenhuma proposta de memória gerada a partir de \"${book.title}\"."
                }
            } catch (_: CancellationException) {
                // Cancelled by user
            } catch (e: Exception) {
                _message.value = "Erro ao extrair memória: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                _isExtractingMemory.value = false
                _extractionProgress.value = null
            }
        }
    }

    fun cancelMemoryExtraction() {
        extractionJob?.cancel()
        _isExtractingMemory.value = false
        _extractionProgress.value = null
        _message.value = "Extração de memória cancelada."
    }

    fun approveProposal(entryId: Long, editedTarget: String? = null) {
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                seriesDao.approveMemoryProposal(entryId, editedTarget, now)
                _message.value = "Proposta aprovada."
            } catch (e: Exception) {
                _message.value = "Erro ao aprovar proposta: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun rejectProposal(entryId: Long) {
        viewModelScope.launch {
            try {
                val entry = seriesDao.memoryEntryById(entryId) ?: return@launch
                val now = System.currentTimeMillis()
                seriesDao.updateMemoryStatus(entryId, MemoryStatus.REJECTED, entry.targetTerm, now)
                _message.value = "Proposta rejeitada."
            } catch (e: Exception) {
                _message.value = "Erro ao rejeitar proposta: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun deleteMemoryEntry(entryId: Long) {
        viewModelScope.launch {
            try {
                seriesDao.deleteMemoryEntry(entryId)
                _message.value = "Entrada removida."
            } catch (e: Exception) {
                _message.value = "Erro ao excluir entrada: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun updateMemoryEntry(entryId: Long, editedTarget: String) {
        val trimmedTarget = editedTarget.trim()
        if (trimmedTarget.isEmpty()) return
        viewModelScope.launch {
            try {
                val entry = seriesDao.memoryEntryById(entryId) ?: return@launch
                val now = System.currentTimeMillis()
                seriesDao.updateMemoryStatus(entryId, entry.status, trimmedTarget, now)
                if (entry.status == MemoryStatus.APPROVED && entry.category == MemoryCategory.TERM) {
                    seriesDao.approveMemoryProposal(entryId, trimmedTarget, now)
                }
                _message.value = "Entrada atualizada."
            } catch (e: Exception) {
                _message.value = "Erro ao atualizar entrada: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }
}
