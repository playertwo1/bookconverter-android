package io.github.playertwo1.bookconverter.ui.library

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.playertwo1.bookconverter.core.epub.EpubException
import io.github.playertwo1.bookconverter.data.AppDatabase
import io.github.playertwo1.bookconverter.data.Book
import io.github.playertwo1.bookconverter.data.importEpub
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.get(app).books()

    val books: StateFlow<List<Book>> = dao.all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    fun import(uri: Uri) {
        viewModelScope.launch {
            _importing.value = true
            try {
                val resolver = getApplication<Application>().contentResolver
                val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                } ?: "livro.epub"
                val stream = resolver.openInputStream(uri) ?: error("não consegui abrir o arquivo")
                val result = stream.use {
                    importEpub(it, name, File(getApplication<Application>().filesDir, "books"), dao)
                }
                _message.value = if (result.alreadyInLibrary) {
                    "“${result.book.title}” já está na biblioteca"
                } else {
                    "“${result.book.title}” importado"
                }
            } catch (e: EpubException) {
                _message.value = e.message
            } catch (e: Exception) {
                _message.value = "Falha ao importar: " + (e.message ?: e.javaClass.simpleName)
            } finally {
                _importing.value = false
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}
