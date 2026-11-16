package io.github.playertwo1.bookconverter.ui.verify

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.playertwo1.bookconverter.core.epub.Epub
import io.github.playertwo1.bookconverter.core.epub.EpubException
import io.github.playertwo1.bookconverter.core.epub.Verification
import io.github.playertwo1.bookconverter.data.AppDatabase
import io.github.playertwo1.bookconverter.data.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface VerifyState {
    data object Loading : VerifyState
    data class Ready(val book: Book, val v: Verification) : VerifyState
    data class Failed(val message: String) : VerifyState
}

class VerificationViewModel(app: Application, private val bookId: Long) : AndroidViewModel(app) {
    private val dao = AppDatabase.get(app).books()

    private val _state = MutableStateFlow<VerifyState>(VerifyState.Loading)
    val state: StateFlow<VerifyState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = withContext(Dispatchers.IO) {
                try {
                    val book = dao.byId(bookId) ?: return@withContext VerifyState.Failed("Livro não encontrado")
                    val v = File(book.privatePath).inputStream().buffered().use { Epub.read(it) }.verify()
                    VerifyState.Ready(book, v)
                } catch (e: EpubException) {
                    VerifyState.Failed(e.message ?: "Falha na verificação")
                } catch (e: Exception) {
                    VerifyState.Failed("Falha na verificação: " + (e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }
}
