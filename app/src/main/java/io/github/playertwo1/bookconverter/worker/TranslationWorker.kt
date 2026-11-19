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
import io.github.playertwo1.bookconverter.core.provider.Provider
import io.github.playertwo1.bookconverter.core.provider.ProviderClient
import io.github.playertwo1.bookconverter.core.translate.Effort
import io.github.playertwo1.bookconverter.core.translate.JobOutcome
import io.github.playertwo1.bookconverter.core.translate.JobProgress
import io.github.playertwo1.bookconverter.core.translate.JobStatus as CoreJobStatus
import io.github.playertwo1.bookconverter.core.translate.StoredChunk
import io.github.playertwo1.bookconverter.core.translate.TranslationEngine
import io.github.playertwo1.bookconverter.core.translate.TranslationStore
import io.github.playertwo1.bookconverter.data.AppDatabase
import io.github.playertwo1.bookconverter.data.JobStatus
import io.github.playertwo1.bookconverter.data.ProviderSettings
import kotlinx.coroutines.CancellationException

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

        try {
            while (true) {
                if (isStopped) {
                    return Result.retry()
                }

                val job = dao.nextRunnableJob() ?: break
                val book = db.books().byId(job.bookId)
                val bookTitle = book?.title ?: "Livro"

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
                        providerSettings.client(provider)
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

                val freshJob = dao.jobById(job.id) ?: continue
                if (freshJob.status == JobStatus.PAUSED || freshJob.status == JobStatus.CANCELLED) {
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

                val outcome = try {
                    engine.runJob(
                        jobId = job.id,
                        client = client,
                        model = job.modelId,
                        effort = job.effort,
                        tokenBudget = job.tokenBudget,
                        store = store,
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
            notificationManager.cancel(NOTIFICATION_ID)
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
        done: Int,
        total: Int,
        jobId: Long,
    ): Notification {
        val safeTotal = total.coerceAtLeast(0)
        val safeDone = done.coerceIn(0, if (safeTotal > 0) safeTotal else 0)
        val percent = if (safeTotal > 0) (safeDone * 100) / safeTotal else 0
        val contentText = "$safeDone / $safeTotal blocos · $percent%"

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
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setProgress(safeTotal, safeDone, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Pausar",
                pausePendingIntent
            )
            .build()
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

    companion object {
        const val CHANNEL_ID = "translation_jobs"
        const val NOTIFICATION_ID = 4001
        private const val TAG = "TranslationWorker"
    }
}
