package io.github.playertwo1.bookconverter.worker

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import io.github.playertwo1.bookconverter.core.epub.ChunkTranslationInput
import io.github.playertwo1.bookconverter.core.epub.EpubExporter
import io.github.playertwo1.bookconverter.core.provider.BatchJobState
import io.github.playertwo1.bookconverter.core.provider.BatchProviderClient
import io.github.playertwo1.bookconverter.core.provider.BatchRequestItem
import io.github.playertwo1.bookconverter.core.provider.ErrorKind
import io.github.playertwo1.bookconverter.core.provider.Provider
import io.github.playertwo1.bookconverter.core.provider.ProviderClient
import io.github.playertwo1.bookconverter.core.provider.ProviderError
import io.github.playertwo1.bookconverter.core.provider.batchProviderClient
import io.github.playertwo1.bookconverter.core.provider.providerClient
import io.github.playertwo1.bookconverter.core.translate.Effort
import io.github.playertwo1.bookconverter.core.translate.Glossary
import io.github.playertwo1.bookconverter.core.translate.GlossaryTerm
import io.github.playertwo1.bookconverter.core.translate.JobOutcome
import io.github.playertwo1.bookconverter.core.translate.JobProgress
import io.github.playertwo1.bookconverter.core.translate.JobStatus as CoreJobStatus
import io.github.playertwo1.bookconverter.core.translate.Prompt
import io.github.playertwo1.bookconverter.core.translate.StoredChunk
import io.github.playertwo1.bookconverter.core.translate.TranslationEngine
import io.github.playertwo1.bookconverter.core.translate.TranslationStore
import io.github.playertwo1.bookconverter.data.AppDatabase
import io.github.playertwo1.bookconverter.data.GoogleDriveSettings
import io.github.playertwo1.bookconverter.data.JobStatus
import io.github.playertwo1.bookconverter.data.MemoryCategory
import io.github.playertwo1.bookconverter.data.ProviderSettings
import io.github.playertwo1.bookconverter.data.TranslationDao
import io.github.playertwo1.bookconverter.data.TranslationJob
import kotlinx.coroutines.CancellationException
import java.io.File

class TranslationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val notificationManager =
        applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result {
        val db = AppDatabase.get(applicationContext)
        val dao = db.translations()

        createNotificationChannel()

        val initialJob = dao.nextRunnableJob() ?: return Result.success()
        val initialBook = db.books().byId(initialJob.bookId)
        val initialBookTitle = initialBook?.title ?: "Livro"

        try {
            setForeground(
                createForegroundInfo(
                    initialBookTitle,
                    initialJob.blockDone,
                    initialJob.blockTotal,
                    initialJob.id
                )
            )
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Log.w(TAG, "Foreground service start not allowed in background; retrying")
            return Result.retry()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start foreground service: ${e.message}")
        }

        val providerSettings = ProviderSettings(applicationContext)
        val engine = TranslationEngine()
        var keepNotificationOnExit = false

        try {
            while (true) {
                if (isStopped) {
                    return Result.retry()
                }

                val job = dao.nextRunnableJob() ?: break
                val book = db.books().byId(job.bookId)
                val bookTitle = book?.title ?: "Livro"

                val freshJob = dao.jobById(job.id) ?: continue
                if (freshJob.status == JobStatus.PAUSED || freshJob.status == JobStatus.CANCELLED) {
                    continue
                }

                if (freshJob.executionMode == "BATCH") {
                    val batchResult = runBatchJob(
                        job = freshJob,
                        settings = providerSettings,
                        dao = dao,
                        bookTitle = bookTitle,
                        db = db,
                    )
                    if (batchResult == BatchRunResult.RETRY) {
                        keepNotificationOnExit = true
                        return Result.retry()
                    }
                    continue
                }

                // Update notification for current job
                val currentNotif = buildNotification(bookTitle, job.blockDone, job.blockTotal, job.id)
                notificationManager.notify(NOTIFICATION_ID, currentNotif)

                val provider = try {
                    Provider.valueOf(job.provider)
                } catch (e: IllegalArgumentException) {
                    null
                }

                val client: ProviderClient? = if (provider != null) {
                    try {
                        when (provider) {
                            Provider.CHATGPT,
                            Provider.OPENAI,
                            Provider.ANTHROPIC,
                            Provider.GEMINI -> providerSettings.client(provider)
                            Provider.OPENROUTER -> {
                                val apiKey = providerSettings.key(provider)
                                val baseUrl = providerSettings.baseUrl(provider)
                                if (apiKey != null) providerClient(Provider.OPENROUTER, apiKey, baseUrl) else null
                            }
                        }
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    null
                }

                if (client == null) {
                    val now = System.currentTimeMillis()
                    val errorMsg = if (provider == null) {
                        "Provedor desconhecido: ${job.provider}"
                    } else {
                        "Credencial ausente ou expirada para ${provider.name}. Configure em Ajustes."
                    }
                    dao.updateJobStatus(job.id, JobStatus.FAILED, errorMsg, now)
                    continue
                }

                val runTime = System.currentTimeMillis()
                dao.updateJobStatus(job.id, JobStatus.RUNNING, null, runTime)

                val store = object : TranslationStore {
                    override suspend fun reconcileInProgress(jobId: Long) {
                        dao.reconcileInProgress(jobId, System.currentTimeMillis())
                    }

                    override suspend fun nextPendingChunk(jobId: Long): StoredChunk? {
                        val chunk = dao.nextPendingChunk(jobId) ?: return null
                        return StoredChunk(
                            id = chunk.id,
                            chunkIndex = chunk.chunkIndex,
                            blockCount = chunk.blockCount,
                            sourceText = chunk.sourceText,
                            attemptCount = chunk.attemptCount
                        )
                    }

                    override suspend fun markChunkInProgress(chunkId: Long) {
                        dao.markChunkInProgress(chunkId, System.currentTimeMillis())
                    }

                    override suspend fun resetChunkToPending(chunkId: Long) {
                        dao.resetChunkToPending(chunkId, System.currentTimeMillis())
                    }

                    override suspend fun markChunkCompleted(
                        jobId: Long,
                        chunkId: Long,
                        translatedText: String,
                        inputTokens: Long,
                        outputTokens: Long,
                    ) {
                        dao.markChunkCompletedAndSyncJob(
                            jobId = jobId,
                            chunkId = chunkId,
                            translatedText = translatedText,
                            inputTokens = inputTokens,
                            outputTokens = outputTokens,
                            updatedAt = System.currentTimeMillis()
                        )
                    }

                    override suspend fun markChunkFailed(
                        chunkId: Long,
                        attemptCount: Int,
                        errorMessage: String,
                    ) {
                        dao.markChunkFailed(
                            chunkId = chunkId,
                            attemptCount = attemptCount,
                            errorMessage = errorMessage,
                            updatedAt = System.currentTimeMillis()
                        )
                    }

                    override suspend fun currentProgress(jobId: Long): JobProgress {
                        val currentJob = dao.jobById(jobId)
                        return if (currentJob != null) {
                            JobProgress(
                                done = currentJob.blockDone,
                                total = currentJob.blockTotal,
                                tokensUsed = currentJob.inputTokens + currentJob.outputTokens
                            )
                        } else {
                            JobProgress(0, 0, 0)
                        }
                    }

                    override suspend fun stopSignal(jobId: Long): CoreJobStatus? {
                        val currentJob = dao.jobById(jobId) ?: return null
                        return when (currentJob.status) {
                            JobStatus.PAUSED -> CoreJobStatus.PAUSED
                            JobStatus.CANCELLED -> CoreJobStatus.CANCELLED
                            else -> null
                        }
                    }

                    override suspend fun onEffortDowngraded(jobId: Long, newEffort: Effort?) {
                        dao.updateJobEffort(jobId, newEffort, System.currentTimeMillis())
                        if (provider != null && job.effort != null) {
                            providerSettings.rejectEffort(provider, job.modelId, job.effort)
                        }
                    }
                }

                val glossary = Glossary.parseSnapshot(job.glossarySnapshot)
                val approvedMemory = if (job.seriesId != null) {
                    db.series().approvedMemoryForSeries(job.seriesId)
                } else {
                    emptyList()
                }
                val characters = approvedMemory
                    .filter { it.category == MemoryCategory.CHARACTER }
                    .map { GlossaryTerm(it.sourceTerm, it.targetTerm, it.notes) }
                val examplePairs = approvedMemory
                    .filter { it.category == MemoryCategory.PAIR }
                    .map { GlossaryTerm(it.sourceTerm, it.targetTerm, it.notes) }
                    .take(5)

                val outcome = try {
                    engine.runJob(
                        jobId = job.id,
                        client = client,
                        model = job.modelId,
                        effort = job.effort,
                        tokenBudget = job.tokenBudget,
                        store = store,
                        glossary = glossary,
                        styleNotes = job.styleNotesSnapshot,
                        characters = characters,
                        examplePairs = examplePairs,
                        onProgress = { progress ->
                            val notif = buildNotification(bookTitle, progress.done, progress.total, job.id)
                            notificationManager.notify(NOTIFICATION_ID, notif)
                        }
                    )
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    JobOutcome.Failed(e.message ?: "Erro inesperado na tradução")
                }

                val outcomeTime = System.currentTimeMillis()
                when (outcome) {
                    is JobOutcome.Completed -> {
                        dao.updateJobStatus(job.id, JobStatus.COMPLETED, null, outcomeTime)
                        maybeAutoUploadToDrive(job.id, db)
                    }
                    is JobOutcome.Paused -> {
                        dao.updateJobStatus(job.id, JobStatus.PAUSED, null, outcomeTime)
                    }
                    is JobOutcome.Cancelled -> {
                        dao.updateJobStatus(job.id, JobStatus.CANCELLED, null, outcomeTime)
                    }
                    is JobOutcome.WaitingNetwork -> {
                        dao.updateJobStatus(job.id, JobStatus.WAITING_NETWORK, outcome.message, outcomeTime)
                        return Result.retry()
                    }
                    is JobOutcome.WaitingRateLimit -> {
                        dao.updateJobStatus(job.id, JobStatus.WAITING_RATE_LIMIT, outcome.message, outcomeTime)
                        return Result.retry()
                    }
                    is JobOutcome.PausedBudget -> {
                        dao.updateJobStatus(
                            job.id,
                            JobStatus.PAUSED_BUDGET,
                            "Teto de tokens atingido (${outcome.tokensUsed} / ${outcome.budget})",
                            outcomeTime
                        )
                    }
                    is JobOutcome.Failed -> {
                        dao.updateJobStatus(job.id, JobStatus.FAILED, outcome.message, outcomeTime)
                    }
                }
            }
            return Result.success()
        } finally {
            if (!keepNotificationOnExit) {
                notificationManager.cancel(NOTIFICATION_ID)
            }
        }
    }

    private suspend fun runBatchJob(
        job: TranslationJob,
        settings: ProviderSettings,
        dao: TranslationDao,
        bookTitle: String = "Livro",
        db: AppDatabase = AppDatabase.get(applicationContext),
    ): BatchRunResult {
        val now = System.currentTimeMillis()
        if (batchCeilingBlocks(job)) {
            // Stop before any provider call; "Continuar sem teto" in Activity clears the ceiling.
            dao.updateJobStatus(job.id, JobStatus.PAUSED_BUDGET, BATCH_CEILING_REASON, now)
            return BatchRunResult.DONE
        }
        val provider = try {
            Provider.valueOf(job.provider)
        } catch (e: IllegalArgumentException) {
            null
        }

        if (provider == null) {
            dao.updateJobStatus(job.id, JobStatus.FAILED, "Provedor desconhecido: ${job.provider}", now)
            return BatchRunResult.DONE
        }

        if (provider == Provider.CHATGPT) {
            dao.updateJobStatus(
                job.id,
                JobStatus.FAILED,
                "O plano ChatGPT não suporta modo Batch. Use OpenAI com API Key.",
                now
            )
            return BatchRunResult.DONE
        }

        if (provider == Provider.OPENROUTER) {
            dao.updateJobStatus(
                job.id,
                JobStatus.FAILED,
                "OpenRouter / Genérico não suporta modo Batch.",
                now
            )
            return BatchRunResult.DONE
        }

        val apiKey = settings.key(provider)
        if (apiKey.isNullOrBlank()) {
            dao.updateJobStatus(
                job.id,
                JobStatus.FAILED,
                "Credencial ausente ou expirada para ${provider.name}. Configure em Ajustes.",
                now
            )
            return BatchRunResult.DONE
        }

        val baseUrl = settings.baseUrl(provider)
        val batchClient: BatchProviderClient = try {
            batchProviderClient(provider, apiKey, baseUrl)
        } catch (pe: ProviderError) {
            return handleBatchProviderError(job.id, dao, pe)
        } catch (e: Exception) {
            dao.updateJobStatus(job.id, JobStatus.FAILED, e.message ?: "Erro ao inicializar cliente Batch", now)
            return BatchRunResult.DONE
        }

        val glossary = Glossary.parseSnapshot(job.glossarySnapshot)
        val approvedMemory = if (job.seriesId != null) {
            db.series().approvedMemoryForSeries(job.seriesId)
        } else {
            emptyList()
        }
        val characters = approvedMemory
            .filter { it.category == MemoryCategory.CHARACTER }
            .map { GlossaryTerm(it.sourceTerm, it.targetTerm, it.notes) }
        val examplePairs = approvedMemory
            .filter { it.category == MemoryCategory.PAIR }
            .map { GlossaryTerm(it.sourceTerm, it.targetTerm, it.notes) }
            .take(5)
        val styleNotes = job.styleNotesSnapshot

        if (job.remoteBatchId == null) {
            val pending = dao.pendingChunksForJob(job.id)
            if (pending.isEmpty()) {
                dao.updateJobStatus(job.id, JobStatus.COMPLETED, null, now)
                maybeAutoUploadToDrive(job.id, db)
                return BatchRunResult.DONE
            }

            val items = pending.map { chunk ->
                val matchedGlossary = Glossary.matchTerms(chunk.sourceText, glossary)
                val matchedChars = Glossary.matchTerms(chunk.sourceText, characters)
                val systemPrompt = Prompt.systemPrompt(
                    glossary = matchedGlossary,
                    styleNotes = styleNotes,
                    characters = matchedChars,
                    examplePairs = examplePairs,
                )
                BatchRequestItem(
                    customId = chunk.id.toString(),
                    systemPrompt = systemPrompt,
                    userText = chunk.sourceText,
                )
            }

            val batchId = try {
                batchClient.submitBatch(job.modelId, job.effort, items)
            } catch (ce: CancellationException) {
                throw ce
            } catch (pe: ProviderError) {
                return handleBatchProviderError(job.id, dao, pe)
            } catch (e: Exception) {
                dao.updateJobStatus(job.id, JobStatus.FAILED, e.message ?: "Erro ao submeter lote Batch", System.currentTimeMillis())
                return BatchRunResult.DONE
            }

            val submitTime = System.currentTimeMillis()
            dao.updateJobBatchId(job.id, batchId, JobStatus.WAITING_BATCH, submitTime)
            val notif = buildNotification(bookTitle, "Lote Batch enviado (${items.size} blocos)", 0, items.size, job.id, ongoing = false)
            notificationManager.notify(NOTIFICATION_ID, notif)
            return BatchRunResult.RETRY
        } else {
            val batchId = job.remoteBatchId
            val status = try {
                batchClient.pollBatch(batchId)
            } catch (ce: CancellationException) {
                throw ce
            } catch (pe: ProviderError) {
                return handleBatchProviderError(job.id, dao, pe)
            } catch (e: Exception) {
                dao.updateJobStatus(job.id, JobStatus.FAILED, e.message ?: "Erro ao consultar status do lote Batch", System.currentTimeMillis())
                return BatchRunResult.DONE
            }

            when (status.state) {
                BatchJobState.SUBMITTED,
                BatchJobState.IN_PROGRESS -> {
                    val notifText = "Lote Batch em processamento (${status.doneCount}/${status.totalCount})"
                    val notif = buildNotification(bookTitle, notifText, status.doneCount, status.totalCount, job.id, ongoing = false)
                    notificationManager.notify(NOTIFICATION_ID, notif)
                    return BatchRunResult.RETRY
                }

                BatchJobState.COMPLETED -> {
                    val results = try {
                        batchClient.fetchResults(batchId)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (pe: ProviderError) {
                        return handleBatchProviderError(job.id, dao, pe)
                    } catch (e: Exception) {
                        dao.updateJobStatus(job.id, JobStatus.FAILED, e.message ?: "Erro ao obter resultados do lote Batch", System.currentTimeMillis())
                        return BatchRunResult.DONE
                    }

                    val allChunks = dao.chunksForJob(job.id)
                    val chunksById = allChunks.associateBy { it.id }

                    for (item in results) {
                        val chunkId = item.customId.toLongOrNull() ?: continue
                        val chunk = chunksById[chunkId] ?: continue
                        if (chunk.status == "COMPLETED") continue

                        val itemTime = System.currentTimeMillis()
                        val text = item.text
                        if (item.errorMessage != null || text.isNullOrBlank()) {
                            val err = item.errorMessage ?: "Resposta vazia do lote Batch"
                            dao.markChunkFailed(chunk.id, chunk.attemptCount + 1, err, itemTime)
                        } else {
                            try {
                                Prompt.decode(text, chunk.blockCount)
                                dao.markBatchChunkCompleted(
                                    jobId = job.id,
                                    chunkId = chunk.id,
                                    translatedText = text,
                                    inputTokens = item.inputTokens,
                                    outputTokens = item.outputTokens,
                                    updatedAt = itemTime,
                                )
                            } catch (e: Exception) {
                                val err = e.message ?: "Resposta fora do formato esperado"
                                dao.markChunkFailed(chunk.id, chunk.attemptCount + 1, err, itemTime)
                            }
                        }
                    }

                    val updatedChunks = dao.chunksForJob(job.id)
                    val remainingPending = updatedChunks.filter { it.status == "PENDING" }
                    val failedChunks = updatedChunks.filter { it.status == "FAILED" }
                    val finishTime = System.currentTimeMillis()

                    if (remainingPending.isEmpty() && failedChunks.isEmpty()) {
                        dao.updateJobStatus(job.id, JobStatus.COMPLETED, null, finishTime)
                        val doneNotif = buildNotification(bookTitle, updatedChunks.size, updatedChunks.size, job.id, ongoing = false)
                        notificationManager.notify(NOTIFICATION_ID, doneNotif)
                        maybeAutoUploadToDrive(job.id, db)
                    } else {
                        val failedCount = failedChunks.size + remainingPending.size
                        val reason = "Lote Batch finalizado com falhas: $failedCount de ${updatedChunks.size} blocos não traduzidos."
                        dao.updateJobStatus(job.id, JobStatus.FAILED, reason, finishTime)
                    }
                    return BatchRunResult.DONE
                }

                BatchJobState.FAILED,
                BatchJobState.EXPIRED,
                BatchJobState.CANCELLED -> {
                    val finishTime = System.currentTimeMillis()
                    val errorMsg = status.errorMessage
                        ?: when (status.state) {
                            BatchJobState.EXPIRED -> "Lote Batch expirado pelo provedor (janela de 24h excedida)."
                            BatchJobState.CANCELLED -> "Lote Batch cancelado."
                            else -> "Lote Batch falhou no provedor."
                        }
                    dao.updateJobStatus(job.id, JobStatus.FAILED, errorMsg, finishTime)
                    return BatchRunResult.DONE
                }
            }
        }
    }

    private suspend fun handleBatchProviderError(
        jobId: Long,
        dao: TranslationDao,
        error: ProviderError,
        now: Long = System.currentTimeMillis(),
    ): BatchRunResult {
        return when (error.kind) {
            ErrorKind.NETWORK -> {
                dao.updateJobStatus(jobId, JobStatus.WAITING_NETWORK, error.userMessage, now)
                BatchRunResult.RETRY
            }
            ErrorKind.RATE_LIMIT -> {
                dao.updateJobStatus(jobId, JobStatus.WAITING_RATE_LIMIT, error.userMessage, now)
                BatchRunResult.RETRY
            }
            ErrorKind.INVALID_KEY -> {
                dao.updateJobStatus(jobId, JobStatus.FAILED, error.userMessage, now)
                BatchRunResult.DONE
            }
            else -> {
                dao.updateJobStatus(jobId, JobStatus.FAILED, error.userMessage, now)
                BatchRunResult.DONE
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Tradução de Livros",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Progresso de tradução de livros em segundo plano"
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(
        bookTitle: String,
        statusText: String,
        done: Int,
        total: Int,
        jobId: Long,
        ongoing: Boolean = true,
    ): Notification {
        val safeTotal = total.coerceAtLeast(0)
        val safeDone = done.coerceIn(0, if (safeTotal > 0) safeTotal else 0)
        val indeterminate = safeTotal == 0

        val pauseIntent = Intent(applicationContext, PauseActionReceiver::class.java).apply {
            putExtra(PauseActionReceiver.EXTRA_JOB_ID, jobId)
        }
        val pausePendingIntent = PendingIntent.getBroadcast(
            applicationContext,
            jobId.toInt(),
            pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(bookTitle)
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setProgress(safeTotal, safeDone, indeterminate)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Pausar",
                pausePendingIntent
            )
            .build()
    }

    private fun buildNotification(
        bookTitle: String,
        done: Int,
        total: Int,
        jobId: Long,
        ongoing: Boolean = true,
    ): Notification {
        val safeTotal = total.coerceAtLeast(0)
        val safeDone = done.coerceIn(0, if (safeTotal > 0) safeTotal else 0)
        val percent = if (safeTotal > 0) (safeDone * 100) / safeTotal else 0
        val contentText = "$safeDone / $safeTotal blocos · $percent%"
        return buildNotification(bookTitle, contentText, safeDone, safeTotal, jobId, ongoing)
    }

    private fun createForegroundInfo(
        bookTitle: String,
        done: Int,
        total: Int,
        jobId: Long,
    ): ForegroundInfo {
        createNotificationChannel()
        val notification = buildNotification(bookTitle, done, total, jobId)
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private enum class BatchRunResult {
        DONE,
        RETRY,
    }

    private suspend fun maybeAutoUploadToDrive(jobId: Long, db: AppDatabase) {
        try {
            val driveSettings = GoogleDriveSettings(applicationContext)
            if (!driveSettings.autoUploadOnComplete || !driveSettings.isConnected) {
                return
            }
            val job = db.translations().jobById(jobId) ?: return
            val book = db.books().byId(job.bookId) ?: return
            val originalFile = File(book.privatePath)
            if (!originalFile.exists()) {
                Log.w(TAG, "Auto-upload Drive: arquivo original não encontrado")
                return
            }

            val chunks = db.translations().chunksForJob(job.id)
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
            val exportsDir = File(applicationContext.cacheDir, "exports").apply { mkdirs() }
            val tempOut = File(exportsDir, "tmp_drive_${job.id}_${System.currentTimeMillis()}.epub")
            try {
                EpubExporter.exportPtBr(originalFile, tempOut, blockMap)
                val bytes = tempOut.readBytes()
                val safeTitle = book.title
                    .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    .trim('_')
                    .take(50)
                    .ifBlank { "livro" }
                val fileName = "$safeTitle-pt-BR.epub"
                driveSettings.uploadToConfiguredFolder(fileName, "application/epub+zip", bytes)
                Log.i(TAG, "Auto-upload para o Google Drive concluído: $fileName")
            } finally {
                tempOut.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Falha no auto-upload para o Google Drive: ${e.message}")
        }
    }

    companion object {
        const val CHANNEL_ID = "translation_jobs"
        const val NOTIFICATION_ID = 4001
        private const val TAG = "TranslationWorker"
        const val BATCH_CEILING_REASON =
            "O modo econômico (Batch) não aplica teto de tokens. Continue sem teto ou cancele e crie de novo no modo normal para manter o teto."

        /** A new Batch (no remote id yet) with a positive ceiling would send the whole book unchecked. */
        fun batchCeilingBlocks(job: TranslationJob): Boolean =
            job.remoteBatchId == null && (job.tokenBudget ?: 0L) > 0L
    }
}
