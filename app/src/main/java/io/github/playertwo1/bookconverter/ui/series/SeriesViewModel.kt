package io.github.playertwo1.bookconverter.ui.series

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.playertwo1.bookconverter.core.translate.Glossary
import io.github.playertwo1.bookconverter.core.translate.GlossaryTerm
import io.github.playertwo1.bookconverter.data.AppDatabase
import io.github.playertwo1.bookconverter.data.Book
import io.github.playertwo1.bookconverter.data.GlossaryEntry
import io.github.playertwo1.bookconverter.data.Series
import io.github.playertwo1.bookconverter.data.SeriesSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class SeriesViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    private val seriesDao = db.series()
    private val bookDao = db.books()

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

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun selectSeries(seriesId: Long?) {
        _selectedSeriesId.value = seriesId
    }

    fun clearMessage() {
        _message.value = null
    }

    fun createSeries(name: String, styleNotes: String = "", onCreated: (Long) -> Unit = {}) {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val id = seriesDao.insertSeries(
                    Series(
                        name = trimmedName,
                        styleNotes = styleNotes.trim(),
                        glossaryVersion = 1,
                        createdAt = now,
                        updatedAt = now,
                    )
                )
                _selectedSeriesId.value = id
                _message.value = "Série \"$trimmedName\" criada."
                onCreated(id)
            } catch (e: Exception) {
                _message.value = "Erro ao criar série: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun updateSeries(seriesId: Long, name: String, styleNotes: String) {
        viewModelScope.launch {
            try {
                val current = seriesDao.byId(seriesId) ?: return@launch
                val trimmedName = name.trim().ifEmpty { current.name }
                val now = System.currentTimeMillis()
                seriesDao.updateSeries(
                    current.copy(
                        name = trimmedName,
                        styleNotes = styleNotes.trim(),
                        updatedAt = now,
                    )
                )
                _message.value = "Alterações salvas."
            } catch (e: Exception) {
                _message.value = "Erro ao salvar alterações: ${e.message ?: e.javaClass.simpleName}"
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
}
