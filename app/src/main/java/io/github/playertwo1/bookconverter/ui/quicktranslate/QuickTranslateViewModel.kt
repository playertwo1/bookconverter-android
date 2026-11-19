package io.github.playertwo1.bookconverter.ui.quicktranslate

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.playertwo1.bookconverter.core.provider.ErrorKind
import io.github.playertwo1.bookconverter.core.provider.Provider
import io.github.playertwo1.bookconverter.core.provider.ProviderClient
import io.github.playertwo1.bookconverter.core.provider.ProviderError
import io.github.playertwo1.bookconverter.data.shortLabel
import io.github.playertwo1.bookconverter.core.provider.translationModels
import io.github.playertwo1.bookconverter.core.translate.Glossary
import io.github.playertwo1.bookconverter.core.translate.GlossaryTerm
import io.github.playertwo1.bookconverter.core.translate.Prompt
import io.github.playertwo1.bookconverter.core.translate.TextExporter
import io.github.playertwo1.bookconverter.data.AppDatabase
import io.github.playertwo1.bookconverter.data.PdfExporter
import io.github.playertwo1.bookconverter.data.ProviderSettings
import io.github.playertwo1.bookconverter.data.Series
import io.github.playertwo1.bookconverter.data.label
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

enum class ExportFormat(val label: String, val extension: String, val mimeType: String) {
    TXT("TXT", "txt", "text/plain"),
    DOC("DOC (.docx)", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    PDF("PDF", "pdf", "application/pdf"),
    MD("MD", "md", "text/markdown"),
}

data class QuickTranslateUiState(
    val title: String = "",
    val sourceText: String = "",
    val translatedText: String = "",
    val selectedSeriesId: Long? = null,
    val seriesList: List<Series> = emptyList(),
    val providerLabel: String = "",
    val hasCredentials: Boolean = false,
    val isTranslating: Boolean = false,
    val progressText: String? = null,
    val tokensSummary: String? = null,
    val errorMessage: String? = null,
    val statusMessage: String? = null,
)

typealias QuickTranslateState = QuickTranslateUiState

class QuickTranslateViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = ProviderSettings(app)
    private val database = AppDatabase.get(app)

    private val _uiState = MutableStateFlow(QuickTranslateUiState())
    val state: StateFlow<QuickTranslateUiState> = _uiState.asStateFlow()
    val uiState: StateFlow<QuickTranslateUiState> get() = state

    private var translationJob: Job? = null

    init {
        refreshSettings()
        viewModelScope.launch {
            database.series().observeAll().collect { list ->
                _uiState.update { it.copy(seriesList = list) }
            }
        }
    }

    fun setTitle(title: String) {
        _uiState.update { it.copy(title = title) }
    }

    fun setSourceText(text: String) {
        _uiState.update { it.copy(sourceText = text, errorMessage = null) }
    }

    fun setTranslatedText(text: String) {
        _uiState.update { it.copy(translatedText = text) }
    }

    fun selectSeries(seriesId: Long?) {
        _uiState.update { it.copy(selectedSeriesId = seriesId) }
    }

    fun clearSourceText() {
        _uiState.update { it.copy(sourceText = "", errorMessage = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun clearStatus() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    fun cancelTranslation() {
        translationJob?.cancel()
        _uiState.update { it.copy(isTranslating = false, progressText = null) }
    }

    fun refreshSettings() {
        val available = Provider.entries.filter { settings.hasKey(it) }
        val currentProvider = settings.lastProvider?.takeIf { settings.hasKey(it) } ?: available.firstOrNull()

        if (currentProvider != null) {
            val model = settings.lastModel(currentProvider)
            val label = if (!model.isNullOrBlank()) {
                "${currentProvider.shortLabel} ($model)"
            } else {
                currentProvider.shortLabel
            }
            _uiState.update {
                it.copy(
                    providerLabel = label,
                    hasCredentials = true,
                )
            }
            if (model.isNullOrBlank()) {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val client = settings.client(currentProvider)
                        val models = client?.let { translationModels(it.listModels()) }
                        val first = models?.firstOrNull()
                        if (!first.isNullOrBlank()) {
                            settings.setLastModel(currentProvider, first)
                            _uiState.update {
                                it.copy(providerLabel = "${currentProvider.shortLabel} ($first)")
                            }
                        }
                    } catch (_: Exception) {
                        // Keep current provider label without model
                    }
                }
            }
        } else {
            _uiState.update {
                it.copy(
                    providerLabel = "Nenhum provedor configurado",
                    hasCredentials = false,
                )
            }
        }
    }

    fun translate() {
        val current = _uiState.value
        val rawSource = current.sourceText.trim()
        if (rawSource.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Digite ou cole um texto para traduzir.") }
            return
        }

        val provider = settings.lastProvider?.takeIf { settings.hasKey(it) }
            ?: Provider.entries.firstOrNull { settings.hasKey(it) }

        if (provider == null) {
            _uiState.update {
                it.copy(errorMessage = "Nenhum provedor configurado. Configure uma chave ou login em Ajustes.")
            }
            return
        }

        val client: ProviderClient = try {
            settings.client(provider)
        } catch (_: Exception) {
            null
        } ?: run {
            _uiState.update {
                it.copy(errorMessage = "Credencial ausente ou expirada para ${provider.label}. Configure em Ajustes.")
            }
            return
        }

        val blocks = splitIntoBlocks(rawSource, maxChars = 3000)
        if (blocks.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Texto vazio.") }
            return
        }

        translationJob?.cancel()
        translationJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isTranslating = true,
                    progressText = if (blocks.size > 1) "Traduzindo parte 1/${blocks.size}…" else "Traduzindo…",
                    errorMessage = null,
                    statusMessage = null,
                )
            }

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
                    }
                    settings.setLastModel(provider, model)
                }

                val seriesId = _uiState.value.selectedSeriesId
                val (glossaryTerms, styleNotes) = withContext(Dispatchers.IO) {
                    if (seriesId != null) {
                        val seriesDao = database.series()
                        val s = seriesDao.byId(seriesId)
                        val entries = seriesDao.glossaryForSeries(seriesId)
                        val terms = entries.map {
                            GlossaryTerm(source = it.sourceTerm, target = it.targetTerm, notes = it.notes)
                        }
                        terms to s?.styleNotes?.takeIf { it.isNotBlank() }
                    } else {
                        emptyList<GlossaryTerm>() to null
                    }
                }

                var currentEffort: String? = null
                val translatedBlocks = mutableListOf<String>()
                var totalInputTokens = 0L
                var totalOutputTokens = 0L

                for ((index, block) in blocks.withIndex()) {
                    _uiState.update {
                        it.copy(
                            progressText = if (blocks.size > 1) {
                                "Traduzindo parte ${index + 1}/${blocks.size}…"
                            } else {
                                "Traduzindo…"
                            }
                        )
                    }

                    val matched = if (glossaryTerms.isNotEmpty()) {
                        Glossary.matchTerms(block, glossaryTerms)
                    } else {
                        emptyList()
                    }

                    val systemPrompt = Prompt.systemPrompt(
                        glossary = matched,
                        styleNotes = styleNotes,
                        basePrompt = BASE_TRANSLATE_PROMPT,
                    )

                    val translation = withContext(Dispatchers.IO) {
                        try {
                            client.translate(
                                model = model,
                                system = systemPrompt,
                                text = block,
                                effort = currentEffort,
                            )
                        } catch (e: ProviderError) {
                            if (e.kind == ErrorKind.EFFORT_REJECTED && currentEffort != null) {
                                currentEffort = null
                                settings.rejectEffort(provider, model, "effort")
                                client.translate(
                                    model = model,
                                    system = systemPrompt,
                                    text = block,
                                    effort = null,
                                )
                            } else {
                                throw e
                            }
                        }
                    }

                    val cleaned = translation.text.replace(MARKER_REGEX, "").trim()
                    translatedBlocks.add(cleaned)
                    translation.inputTokens?.let { totalInputTokens += it }
                    translation.outputTokens?.let { totalOutputTokens += it }
                }

                val fullText = translatedBlocks.joinToString("\n\n")
                val tokensText = if (totalInputTokens > 0 || totalOutputTokens > 0) {
                    "Tokens: $totalInputTokens entrada · $totalOutputTokens saída (${totalInputTokens + totalOutputTokens} total)"
                } else {
                    null
                }

                _uiState.update {
                    it.copy(
                        translatedText = fullText,
                        isTranslating = false,
                        progressText = null,
                        tokensSummary = tokensText,
                        errorMessage = null,
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: ProviderError) {
                _uiState.update {
                    it.copy(
                        isTranslating = false,
                        progressText = null,
                        errorMessage = e.userMessage,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isTranslating = false,
                        progressText = null,
                        errorMessage = "Erro na tradução: ${e.message ?: "Falha desconhecida"}",
                    )
                }
            }
        }
    }

    fun exportToUri(uri: Uri, format: ExportFormat) {
        val textToExport = _uiState.value.translatedText
        if (textToExport.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Nenhum texto traduzido para exportar.") }
            return
        }
        val titleToExport = _uiState.value.title.trim().takeIf { it.isNotEmpty() }

        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    when (format) {
                        ExportFormat.TXT -> TextExporter.toTxt(textToExport)
                        ExportFormat.DOC -> TextExporter.toDocx(textToExport, titleToExport)
                        ExportFormat.PDF -> PdfExporter.toPdfBytes(textToExport, titleToExport)
                        ExportFormat.MD -> TextExporter.toMarkdown(textToExport, titleToExport)
                    }
                }
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(bytes)
                        stream.flush()
                    } ?: throw IOException("Falha ao abrir URI para gravação")
                }
                _uiState.update {
                    it.copy(
                        statusMessage = "Exportado em ${format.label} com sucesso!",
                        errorMessage = null,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        errorMessage = "Erro ao exportar: ${e.message ?: "Falha ao gravar arquivo"}",
                        statusMessage = null,
                    )
                }
            }
        }
    }

    companion object {
        private val MARKER_REGEX = Regex("""^⟦\d+⟧\s*""")

        private const val BASE_TRANSLATE_PROMPT =
            "You are a literary translator. Translate the text the user sends into Brazilian Portuguese (pt-BR) " +
            "for publication: natural, fluent prose that keeps the author's tone, register, rhythm and meaning, " +
            "with no omissions, additions or explanations. " +
            "Preserve paragraph breaks and proper names unless a glossary says otherwise. " +
            "Reply with the translation only: no preamble, notes or code fences."

        fun splitIntoBlocks(text: String, maxChars: Int = 3000): List<String> {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return emptyList()
            if (trimmed.length <= maxChars) return listOf(trimmed)

            val paragraphs = trimmed.split(Regex("\n\n+"))
            val blocks = mutableListOf<String>()
            val current = StringBuilder()

            for (para in paragraphs) {
                val p = para.trim()
                if (p.isEmpty()) continue

                if (p.length > maxChars) {
                    if (current.isNotEmpty()) {
                        blocks.add(current.toString().trim())
                        current.clear()
                    }
                    val lines = p.split("\n")
                    val subCurrent = StringBuilder()
                    for (line in lines) {
                        val l = line.trim()
                        if (l.isEmpty()) continue
                        if (l.length > maxChars) {
                            if (subCurrent.isNotEmpty()) {
                                blocks.add(subCurrent.toString().trim())
                                subCurrent.clear()
                            }
                            var start = 0
                            while (start < l.length) {
                                val end = (start + maxChars).coerceAtMost(l.length)
                                blocks.add(l.substring(start, end).trim())
                                start = end
                            }
                        } else {
                            if (subCurrent.isNotEmpty() && subCurrent.length + l.length + 1 > maxChars) {
                                blocks.add(subCurrent.toString().trim())
                                subCurrent.clear()
                            }
                            if (subCurrent.isNotEmpty()) subCurrent.append("\n")
                            subCurrent.append(l)
                        }
                    }
                    if (subCurrent.isNotEmpty()) {
                        blocks.add(subCurrent.toString().trim())
                    }
                } else {
                    if (current.isNotEmpty() && current.length + p.length + 2 > maxChars) {
                        blocks.add(current.toString().trim())
                        current.clear()
                    }
                    if (current.isNotEmpty()) {
                        current.append("\n\n")
                    }
                    current.append(p)
                }
            }
            if (current.isNotEmpty()) {
                blocks.add(current.toString().trim())
            }
            return blocks.ifEmpty { listOf(trimmed) }
        }
    }
}
