package io.github.playertwo1.bookconverter.ui.review

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.playertwo1.bookconverter.core.epub.ChunkTranslationInput
import io.github.playertwo1.bookconverter.core.epub.ExportValidationReport
import io.github.playertwo1.bookconverter.core.epub.EpubExporter
import io.github.playertwo1.bookconverter.core.provider.ErrorKind
import io.github.playertwo1.bookconverter.core.provider.Provider
import io.github.playertwo1.bookconverter.core.provider.ProviderClient
import io.github.playertwo1.bookconverter.core.provider.ProviderError
import io.github.playertwo1.bookconverter.core.provider.providerClient
import io.github.playertwo1.bookconverter.core.translate.Glossary
import io.github.playertwo1.bookconverter.core.translate.Prompt
import io.github.playertwo1.bookconverter.core.translate.TextExporter
import io.github.playertwo1.bookconverter.data.AppDatabase
import io.github.playertwo1.bookconverter.data.Book
import io.github.playertwo1.bookconverter.data.ChunkStatus
import io.github.playertwo1.bookconverter.data.PdfExporter
import io.github.playertwo1.bookconverter.data.ProviderSettings
import io.github.playertwo1.bookconverter.data.TranslationJob
import io.github.playertwo1.bookconverter.data.label
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ReviewBlockItem(
    val chunkId: Long,
    val chunkIndex: Int,
    val blockInChunkIndex: Int,
    val globalIndex: Int,
    val originalText: String,
    val translatedText: String?,
    val status: String,
)

enum class ReviewFilter {
    TODOS,
    PENDENTES,
}

data class ReviewExportUiState(
    val isLoading: Boolean = true,
    val book: Book? = null,
    val job: TranslationJob? = null,
    val blocks: List<ReviewBlockItem> = emptyList(),
    val totalBlocksCount: Int = 0,
    val translatedBlocksCount: Int = 0,
    val isExporting: Boolean = false,
    val retranslatingBlockKey: String? = null,
    val exportReport: ExportValidationReport? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
)

class ReviewExportViewModel(private val app: Application) : AndroidViewModel(app) {

    private val database = AppDatabase.get(app)
    private val dao = database.translations()
    private val bookDao = database.books()
    private val settings = ProviderSettings(app)

    private val _uiState = MutableStateFlow(ReviewExportUiState())
    val uiState: StateFlow<ReviewExportUiState> = _uiState.asStateFlow()

    private var currentJobId: Long? = null
    private var chunksJob: Job? = null

    fun load(jobId: Long) {
        if (currentJobId == jobId && chunksJob?.isActive == true) return
        currentJobId = jobId
        chunksJob?.cancel()

        _uiState.update { it.copy(isLoading = true, errorMessage = null) }

        chunksJob = viewModelScope.launch {
            val initialJob = dao.jobById(jobId)
            val initialBook = initialJob?.let { bookDao.byId(it.bookId) }
            _uiState.update { it.copy(job = initialJob, book = initialBook) }

            dao.observeChunksForJob(jobId).collect { chunks ->
                val blockItems = mutableListOf<ReviewBlockItem>()
                var globalIdx = 1
                var translatedCount = 0

                for (chunk in chunks) {
                    val sourceBlocks = EpubExporter.decodeChunkBlocks(chunk.sourceText, chunk.blockCount)
                    val translatedBlocks = EpubExporter.decodeChunkBlocks(chunk.translatedText, chunk.blockCount)
                    val count = maxOf(1, chunk.blockCount)

                    for (blockIndex in 0 until count) {
                        val original = sourceBlocks?.getOrNull(blockIndex) ?: chunk.sourceText
                        val translated = translatedBlocks?.getOrNull(blockIndex)
                        val isBlockTranslated = !translated.isNullOrBlank()
                        if (isBlockTranslated) {
                            translatedCount++
                        }

                        val blockStatus = if (isBlockTranslated) {
                            ChunkStatus.COMPLETED
                        } else {
                            chunk.status
                        }

                        blockItems.add(
                            ReviewBlockItem(
                                chunkId = chunk.id,
                                chunkIndex = chunk.chunkIndex,
                                blockInChunkIndex = blockIndex,
                                globalIndex = globalIdx++,
                                originalText = original,
                                translatedText = translated,
                                status = blockStatus,
                            )
                        )
                    }
                }

                val currentJob = dao.jobById(jobId) ?: initialJob
                val currentBook = currentJob?.let { bookDao.byId(it.bookId) } ?: initialBook

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        job = currentJob,
                        book = currentBook,
                        blocks = blockItems,
                        totalBlocksCount = blockItems.size,
                        translatedBlocksCount = translatedCount,
                    )
                }
            }
        }
    }

    fun saveEditedBlock(chunkId: Long, blockInChunkIndex: Int, newText: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val chunk = dao.chunkById(chunkId) ?: return@launch
                val existingBlocks = EpubExporter.decodeChunkBlocks(chunk.translatedText, chunk.blockCount)
                val sourceBlocks = EpubExporter.decodeChunkBlocks(chunk.sourceText, chunk.blockCount) ?: emptyList()
                val count = maxOf(1, chunk.blockCount)

                val blocks = (0 until count).map { i ->
                    if (i == blockInChunkIndex) {
                        newText.trim()
                    } else {
                        existingBlocks?.getOrNull(i)
                            ?: sourceBlocks.getOrNull(i)
                            ?: ""
                    }
                }

                val encoded = EpubExporter.encodeEditedBlocks(blocks)
                val now = System.currentTimeMillis()
                val targetJobId = _uiState.value.job?.id ?: chunk.jobId
                dao.saveEditedChunkAndSyncJob(targetJobId, chunkId, encoded, now)

                _uiState.update { it.copy(statusMessage = "Bloco atualizado com sucesso!") }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Erro ao salvar alteração: ${e.message}") }
            }
        }
    }

    fun retranslateBlock(chunkId: Long, blockInChunkIndex: Int) {
        val blockKey = "$chunkId:$blockInChunkIndex"
        viewModelScope.launch {
            _uiState.update { it.copy(retranslatingBlockKey = blockKey, errorMessage = null) }
            try {
                withContext(Dispatchers.IO) {
                    val chunk = dao.chunkById(chunkId) ?: error("Bloco não encontrado (chunk $chunkId)")
                    val currentJob = _uiState.value.job ?: dao.jobById(chunk.jobId) ?: error("Tradução não encontrada")

                    val sourceBlocks = EpubExporter.decodeChunkBlocks(chunk.sourceText, chunk.blockCount)
                    val originalBlock = sourceBlocks?.getOrNull(blockInChunkIndex) ?: chunk.sourceText

                    val provider = runCatching { Provider.valueOf(currentJob.provider) }.getOrNull()?.takeIf { settings.hasKey(it) }
                        ?: settings.lastProvider?.takeIf { settings.hasKey(it) }
                        ?: Provider.entries.firstOrNull { settings.hasKey(it) }
                        ?: error("Nenhum provedor configurado. Configure uma chave em Ajustes.")

                    val client: ProviderClient = when (provider) {
                        Provider.CHATGPT -> settings.client(Provider.CHATGPT)
                        Provider.OPENROUTER -> {
                            settings.client(Provider.OPENROUTER)
                                ?: settings.key(Provider.OPENROUTER)?.let { key ->
                                    providerClient(
                                        Provider.OPENROUTER,
                                        key,
                                        settings.baseUrl(Provider.OPENROUTER),
                                    )
                                }
                        }
                        else -> settings.client(provider)
                    } ?: error("Credencial ausente ou sessão expirada para ${provider.label}")

                    var model = currentJob.modelId.takeIf { it.isNotBlank() }
                        ?: settings.lastModel(provider)
                        ?: when (provider) {
                            Provider.OPENAI -> "gpt-4o-mini"
                            Provider.CHATGPT -> "gpt-4o"
                            Provider.ANTHROPIC -> "claude-3-5-haiku-20241022"
                            Provider.GEMINI -> "gemini-1.5-flash"
                            Provider.OPENROUTER -> "openai/gpt-4o-mini"
                        }

                    val jobGlossary = Glossary.parseSnapshot(currentJob.glossarySnapshot)
                    val matched = Glossary.matchTerms(originalBlock, jobGlossary)
                    val systemPrompt = Prompt.systemPrompt(
                        glossary = matched,
                        styleNotes = currentJob.styleNotesSnapshot,
                    )

                    var effort = currentJob.effort
                    val textToSend = "⟦1⟧\n$originalBlock"

                    val reply = try {
                        client.translate(
                            model = model,
                            system = systemPrompt,
                            text = textToSend,
                            effort = effort,
                        )
                    } catch (e: ProviderError) {
                        if (e.kind == ErrorKind.EFFORT_REJECTED && effort != null) {
                            effort = null
                            settings.rejectEffort(provider, model, "effort")
                            client.translate(
                                model = model,
                                system = systemPrompt,
                                text = textToSend,
                                effort = null,
                            )
                        } else {
                            throw e
                        }
                    }

                    val decodedBlock = try {
                        Prompt.decode(reply.text, 1).first()
                    } catch (_: Exception) {
                        reply.text.replace(Regex("^⟦1⟧\\s*"), "").trim()
                    }

                    val existingBlocks = EpubExporter.decodeChunkBlocks(chunk.translatedText, chunk.blockCount)
                    val count = maxOf(1, chunk.blockCount)
                    val updatedBlocks = (0 until count).map { i ->
                        if (i == blockInChunkIndex) {
                            decodedBlock
                        } else {
                            existingBlocks?.getOrNull(i)
                                ?: sourceBlocks?.getOrNull(i)
                                ?: ""
                        }
                    }

                    val encoded = EpubExporter.encodeEditedBlocks(updatedBlocks)
                    val now = System.currentTimeMillis()
                    dao.saveEditedChunkAndSyncJob(currentJob.id, chunkId, encoded, now)
                }

                _uiState.update {
                    it.copy(
                        retranslatingBlockKey = null,
                        statusMessage = "Bloco retraduzido com sucesso!",
                    )
                }
            } catch (e: ProviderError) {
                _uiState.update {
                    it.copy(
                        retranslatingBlockKey = null,
                        errorMessage = e.userMessage,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        retranslatingBlockKey = null,
                        errorMessage = "Falha ao retraduzir: ${e.message ?: e.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    fun exportToUri(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true, errorMessage = null) }
            try {
                val report = withContext(Dispatchers.IO) {
                    val book = _uiState.value.book ?: error("Livro não carregado")
                    val job = _uiState.value.job ?: error("Tradução não carregada")
                    val chunks = dao.chunksForJob(job.id)
                    val originalFile = File(book.privatePath)
                    if (!originalFile.exists()) {
                        error("Arquivo original não encontrado: ${book.privatePath}")
                    }

                    val chunkInputs = chunks.map { c ->
                        ChunkTranslationInput(
                            spineIndex = c.spineIndex,
                            startBlockIndex = c.startBlockIndex,
                            endBlockIndex = c.endBlockIndex,
                            blockCount = c.blockCount,
                            translatedText = c.translatedText,
                        )
                    }

                    val blockMap = EpubExporter.buildBlockMapFromChunks(originalFile, chunkInputs)
                    val exportsDir = File(app.cacheDir, "exports").apply { mkdirs() }
                    val tempOut = File(exportsDir, "tmp_export_${System.currentTimeMillis()}.epub")

                    try {
                        val rep = EpubExporter.exportPtBr(originalFile, tempOut, blockMap)

                        app.contentResolver.openOutputStream(uri)?.use { outStream ->
                            tempOut.inputStream().use { inStream ->
                                inStream.copyTo(outStream)
                            }
                        } ?: error("Falha ao abrir URI para escrita")
                        rep
                    } finally {
                        tempOut.delete()
                    }
                }

                _uiState.update {
                    it.copy(
                        isExporting = false,
                        exportReport = report,
                        statusMessage = "EPUB pt-BR exportado com sucesso!",
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        errorMessage = "Falha ao salvar EPUB: ${e.message ?: e.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    suspend fun prepareShareExport(): Pair<Uri, ExportValidationReport>? {
        _uiState.update { it.copy(isExporting = true, errorMessage = null) }
        return try {
            val result = withContext(Dispatchers.IO) {
                val book = _uiState.value.book ?: error("Livro não carregado")
                val job = _uiState.value.job ?: error("Tradução não carregada")
                val chunks = dao.chunksForJob(job.id)
                val originalFile = File(book.privatePath)
                if (!originalFile.exists()) {
                    error("Arquivo original não encontrado: ${book.privatePath}")
                }

                val chunkInputs = chunks.map { c ->
                    ChunkTranslationInput(
                        spineIndex = c.spineIndex,
                        startBlockIndex = c.startBlockIndex,
                        endBlockIndex = c.endBlockIndex,
                        blockCount = c.blockCount,
                        translatedText = c.translatedText,
                    )
                }

                val blockMap = EpubExporter.buildBlockMapFromChunks(originalFile, chunkInputs)
                val safeTitle = book.title
                    .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    .trim('_')
                    .take(50)
                    .ifBlank { "livro" }

                val exportsDir = File(app.cacheDir, "exports").apply { mkdirs() }
                val outFile = File(exportsDir, "$safeTitle-pt-BR.epub")

                val report = EpubExporter.exportPtBr(originalFile, outFile, blockMap)
                val uri = FileProvider.getUriForFile(
                    app,
                    "${app.packageName}.fileprovider",
                    outFile,
                )
                Pair(uri, report)
            }

            _uiState.update {
                it.copy(
                    isExporting = false,
                    exportReport = result.second,
                    statusMessage = "EPUB gerado e pronto para compartilhamento!",
                )
            }
            result
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    isExporting = false,
                    errorMessage = "Falha ao preparar EPUB para compartilhar: ${e.message ?: e.javaClass.simpleName}",
                )
            }
            null
        }
    }

    fun exportBookAsDocx(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true, errorMessage = null) }
            try {
                withContext(Dispatchers.IO) {
                    val (fullText, bookTitle) = getFullBookTextAndTitle()
                    val docxBytes = TextExporter.toDocx(fullText, bookTitle)
                    app.contentResolver.openOutputStream(uri)?.use { outStream ->
                        outStream.write(docxBytes)
                    } ?: error("Falha ao abrir URI para gravação do DOCX")
                }

                _uiState.update {
                    it.copy(
                        isExporting = false,
                        statusMessage = "Livro exportado em DOCX com sucesso!",
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        errorMessage = "Falha ao salvar DOCX: ${e.message ?: e.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    fun exportBookAsPdf(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true, errorMessage = null) }
            try {
                withContext(Dispatchers.IO) {
                    val (fullText, bookTitle) = getFullBookTextAndTitle()
                    val pdfBytes = PdfExporter.toPdfBytes(fullText, bookTitle)
                    app.contentResolver.openOutputStream(uri)?.use { outStream ->
                        outStream.write(pdfBytes)
                    } ?: error("Falha ao abrir URI para gravação do PDF")
                }

                _uiState.update {
                    it.copy(
                        isExporting = false,
                        statusMessage = "Livro exportado em PDF com sucesso!",
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        errorMessage = "Falha ao salvar PDF: ${e.message ?: e.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    private suspend fun getFullBookTextAndTitle(): Pair<String, String?> {
        val book = _uiState.value.book ?: error("Livro não carregado")
        val blocks = _uiState.value.blocks
        val fullText = if (blocks.isNotEmpty()) {
            blocks.joinToString("\n\n") { item ->
                (item.translatedText?.takeIf { it.isNotBlank() } ?: item.originalText).trim()
            }
        } else {
            val job = _uiState.value.job ?: error("Tradução não carregada")
            val chunks = dao.chunksForJob(job.id)
            val list = mutableListOf<String>()
            for (chunk in chunks) {
                val sourceBlocks = EpubExporter.decodeChunkBlocks(chunk.sourceText, chunk.blockCount)
                val translatedBlocks = EpubExporter.decodeChunkBlocks(chunk.translatedText, chunk.blockCount)
                val count = maxOf(1, chunk.blockCount)
                for (blockIndex in 0 until count) {
                    val original = sourceBlocks?.getOrNull(blockIndex) ?: chunk.sourceText
                    val translated = translatedBlocks?.getOrNull(blockIndex)
                    val text = translated?.takeIf { it.isNotBlank() } ?: original
                    list.add(text.trim())
                }
            }
            list.joinToString("\n\n")
        }
        return Pair(fullText, book.title.takeIf { it.isNotBlank() })
    }

    fun clearStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    fun clearErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
