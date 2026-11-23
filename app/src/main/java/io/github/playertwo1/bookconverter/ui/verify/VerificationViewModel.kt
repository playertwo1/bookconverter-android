package io.github.playertwo1.bookconverter.ui.verify

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.playertwo1.bookconverter.core.epub.Epub
import io.github.playertwo1.bookconverter.core.epub.EpubException
import io.github.playertwo1.bookconverter.core.epub.Verification
import io.github.playertwo1.bookconverter.data.AppDatabase
import io.github.playertwo1.bookconverter.data.Book
import io.github.playertwo1.bookconverter.data.Series
import io.github.playertwo1.bookconverter.data.matchExistingSeriesForAuthor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface VerifyState {
    data object Loading : VerifyState
    data class Ready(val book: Book, val v: Verification, val series: Series? = null) : VerifyState
    data class Failed(val message: String) : VerifyState
}

class VerificationViewModel(app: Application, private val bookId: Long) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    private val dao = db.books()
    private val seriesDao = db.series()

    private val _state = MutableStateFlow<VerifyState>(VerifyState.Loading)
    val state: StateFlow<VerifyState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = withContext(Dispatchers.IO) {
                try {
                    val book = dao.byId(bookId) ?: return@withContext VerifyState.Failed("Livro não encontrado")
                    val v = File(book.privatePath).inputStream().buffered().use { Epub.read(it) }.verify()
                    val series = book.seriesId?.let { seriesDao.byId(it) }
                    VerifyState.Ready(book, v, series)
                } catch (e: EpubException) {
                    VerifyState.Failed(e.message ?: "Falha na verificação")
                } catch (e: Exception) {
                    VerifyState.Failed("Falha na verificação: " + (e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }

    fun createSeriesForBook(name: String, author: String?) {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return
        val cleanAuthor = author?.trim()?.ifEmpty { null }
        viewModelScope.launch {
            val linked = withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val matches = seriesDao.findAllByNameIgnoreCase(cleanName)
                val series = matchExistingSeriesForAuthor(matches, cleanAuthor, seriesDao, now)
                    ?: run {
                        val created = Series(
                            name = cleanName,
                            author = cleanAuthor,
                            styleNotes = "",
                            glossaryVersion = 1,
                            createdAt = now,
                            updatedAt = now,
                        )
                        created.copy(id = seriesDao.insertSeries(created))
                    }
                dao.setSeries(bookId, series.id)
                series
            }
            val current = _state.value
            if (current is VerifyState.Ready) {
                _state.value = current.copy(
                    book = current.book.copy(seriesId = linked.id),
                    series = linked,
                )
            }
        }
    }
}
