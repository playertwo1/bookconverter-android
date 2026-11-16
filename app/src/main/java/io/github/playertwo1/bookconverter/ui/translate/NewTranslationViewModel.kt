package io.github.playertwo1.bookconverter.ui.translate

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.playertwo1.bookconverter.core.epub.Epub
import io.github.playertwo1.bookconverter.core.provider.Provider
import io.github.playertwo1.bookconverter.core.provider.ProviderError
import io.github.playertwo1.bookconverter.core.provider.translationModels
import io.github.playertwo1.bookconverter.core.translate.Chunk
import io.github.playertwo1.bookconverter.core.translate.Estimate
import io.github.playertwo1.bookconverter.core.translate.PreviewResult
import io.github.playertwo1.bookconverter.core.translate.chunk
import io.github.playertwo1.bookconverter.core.translate.estimate
import io.github.playertwo1.bookconverter.core.translate.preview
import io.github.playertwo1.bookconverter.core.translate.sampleChunk
import io.github.playertwo1.bookconverter.data.AppDatabase
import io.github.playertwo1.bookconverter.data.Book
import io.github.playertwo1.bookconverter.data.ProviderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Models {
    data object Idle : Models
    data object Loading : Models
    data class Ready(val ids: List<String>) : Models
    data class Failed(val message: String) : Models
}

sealed interface PreviewState {
    data object Idle : PreviewState
    data object Running : PreviewState
    /** [estimate] is null when the provider reported no token usage. */
    data class Done(val result: PreviewResult, val estimate: Estimate?) : PreviewState
    data class Failed(val message: String) : PreviewState
}

data class NewTranslationState(
    val book: Book? = null,
    /** Null while the EPUB is being read. */
    val chunks: List<Chunk>? = null,
    val loadError: String? = null,
    /** Providers with a saved key, in enum order. */
    val available: List<Provider> = emptyList(),
    val provider: Provider? = null,
    val models: Models = Models.Idle,
    val model: String? = null,
    /** null = provider default. */
    val effort: String? = null,
    /** Levels the selected model refused before; shown as unavailable (AC06). */
    val rejectedEfforts: Set<String> = emptySet(),
    val preview: PreviewState = PreviewState.Idle,
    /** Consent dialog open (first send to this provider). */
    val askConsent: Boolean = false,
    /** Token ceiling typed by the user (D11/D12); digits only. Stored on the job in P2. */
    val budget: String = "",
) {
    val sample: Chunk? get() = chunks?.let { if (it.isEmpty()) null else sampleChunk(it) }
    val bookChars: Int get() = chunks?.sumOf { it.htmlChars } ?: 0
    val canPreview: Boolean get() = provider != null && model != null && sample != null && preview != PreviewState.Running
    /** Upper estimate above the ceiling: the screen warns before the user starts. */
    val overBudget: Boolean
        get() {
            val high = ((preview as? PreviewState.Done)?.estimate ?: return false).high.total
            return budget.toLongOrNull()?.let { high > it } ?: false
        }
}

class NewTranslationViewModel(app: Application, private val bookId: Long) : AndroidViewModel(app) {
    private val settings = ProviderSettings(app)
    private val _state = MutableStateFlow(NewTranslationState())
    val state: StateFlow<NewTranslationState> = _state.asStateFlow()
    private var modelsJob: Job? = null

    init {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                try {
                    val book = AppDatabase.get(app).books().byId(bookId) ?: return@withContext Result.failure(Exception("Livro não encontrado"))
                    val blocks = File(book.privatePath).inputStream().buffered().use { Epub.read(it) }.blocks()
                    Result.success(book to chunk(blocks))
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            loaded.onSuccess { (book, chunks) -> _state.update { it.copy(book = book, chunks = chunks) } }
                .onFailure { e -> _state.update { it.copy(loadError = "Falha ao ler o livro: " + (e.message ?: e.javaClass.simpleName)) } }
        }
        val available = Provider.entries.filter(settings::hasKey)
        _state.update { it.copy(available = available) }
        (settings.lastProvider?.takeIf { it in available } ?: available.firstOrNull())?.let(::selectProvider)
    }

    /** Call when returning from the providers screen: a key may have been added or removed. */
    fun refreshProviders() {
        val available = Provider.entries.filter(settings::hasKey)
        val current = _state.value.provider
        _state.update { it.copy(available = available) }
        if (current == null || current !in available) (available.firstOrNull())?.let(::selectProvider)
            ?: _state.update { it.copy(provider = null, models = Models.Idle, model = null) }
    }

    fun selectProvider(p: Provider) {
        settings.lastProvider = p
        _state.update { it.copy(provider = p, models = Models.Loading, model = null, effort = null, rejectedEfforts = emptySet(), preview = PreviewState.Idle) }
        loadModels(p)
    }

    fun retryModels() = _state.value.provider?.let(::loadModels)

    private fun loadModels(p: Provider) {
        modelsJob?.cancel()
        modelsJob = viewModelScope.launch {
            _state.update { it.copy(models = Models.Loading) }
            val result = withContext(Dispatchers.IO) {
                try {
                    val client = settings.client(p) ?: return@withContext Models.Failed("Chave não encontrada. Salve a chave de novo em Ajustes.")
                    Models.Ready(translationModels(client.listModels()))
                } catch (e: ProviderError) {
                    Models.Failed(e.userMessage)
                } catch (e: Exception) {
                    Models.Failed("Falha ao listar modelos: " + (e.message ?: e.javaClass.simpleName))
                }
            }
            if (_state.value.provider != p) return@launch
            _state.update { it.copy(models = result) }
            if (result is Models.Ready) settings.lastModel(p)?.takeIf { it in result.ids }?.let(::selectModel)
        }
    }

    fun selectModel(id: String) {
        val p = _state.value.provider ?: return
        settings.setLastModel(p, id)
        val rejected = settings.rejectedEfforts(p, id)
        _state.update { it.copy(model = id, rejectedEfforts = rejected, effort = it.effort?.takeUnless { e -> e in rejected }, preview = PreviewState.Idle) }
    }

    fun selectEffort(effort: String?) {
        if (effort != null && effort in _state.value.rejectedEfforts) return
        _state.update { it.copy(effort = effort, preview = PreviewState.Idle) }
    }

    fun setBudget(text: String) = _state.update { it.copy(budget = text.filter(Char::isDigit).take(12)) }

    /** Asks for consent the first time a provider would receive book text; otherwise sends the sample. */
    fun requestPreview() {
        val p = _state.value.provider ?: return
        if (!settings.consented(p)) _state.update { it.copy(askConsent = true) } else runPreview()
    }

    fun acceptConsent() {
        _state.value.provider?.let(settings::setConsented)
        _state.update { it.copy(askConsent = false) }
        runPreview()
    }

    fun dismissConsent() = _state.update { it.copy(askConsent = false) }

    private fun runPreview() {
        val s = _state.value
        val p = s.provider ?: return
        val model = s.model ?: return
        val sample = s.sample ?: return
        val chunks = s.chunks ?: return
        if (!s.canPreview) return
        _state.update { it.copy(preview = PreviewState.Running) }
        viewModelScope.launch {
            val next = withContext(Dispatchers.IO) {
                try {
                    val client = settings.client(p) ?: return@withContext PreviewState.Failed("Chave não encontrada. Salve a chave de novo em Ajustes.")
                    val r = preview(client, model, s.effort, sample)
                    if (r.effortRejected && s.effort != null) settings.rejectEffort(p, model, s.effort)
                    PreviewState.Done(r, estimate(r, chunks))
                } catch (e: ProviderError) {
                    PreviewState.Failed(e.userMessage)
                } catch (e: Exception) {
                    PreviewState.Failed("Falha na prévia: " + (e.message ?: e.javaClass.simpleName))
                }
            }
            _state.update { st ->
                // Selection changed while waiting: drop the stale answer.
                if (st.provider != p || st.model != model) return@update st
                val done = next as? PreviewState.Done
                val rejected = if (done?.result?.effortRejected == true) settings.rejectedEfforts(p, model) else st.rejectedEfforts
                st.copy(
                    preview = next,
                    rejectedEfforts = rejected,
                    effort = if (done?.result?.effortRejected == true) null else st.effort,
                    budget = st.budget.ifEmpty { done?.estimate?.high?.total?.toString().orEmpty() },
                )
            }
        }
    }
}
