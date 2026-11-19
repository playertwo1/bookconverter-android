package io.github.playertwo1.bookconverter.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import io.github.playertwo1.bookconverter.data.AppDatabase
import io.github.playertwo1.bookconverter.data.JobStatus
import java.util.concurrent.TimeUnit

object QueueManager {
    const val UNIQUE_WORK_NAME = "translation_queue"

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<TranslationWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    suspend fun pauseJob(context: Context, jobId: Long) {
        val dao = AppDatabase.get(context).translations()
        val now = System.currentTimeMillis()
        dao.updateJobStatus(jobId, JobStatus.PAUSED, null, now)
    }

    suspend fun resumeJob(context: Context, jobId: Long, clearBudget: Boolean = false) {
        val dao = AppDatabase.get(context).translations()
        val now = System.currentTimeMillis()
        if (clearBudget) {
            dao.updateJobBudget(jobId, null, now)
        }
        dao.updateJobStatus(jobId, JobStatus.QUEUED, null, now)
        enqueue(context)
    }

    suspend fun cancelJob(context: Context, jobId: Long) {
        val dao = AppDatabase.get(context).translations()
        val now = System.currentTimeMillis()
        dao.updateJobStatus(jobId, JobStatus.CANCELLED, null, now)
    }

    suspend fun deleteJob(context: Context, jobId: Long) {
        val dao = AppDatabase.get(context).translations()
        val now = System.currentTimeMillis()
        dao.updateJobStatus(jobId, JobStatus.CANCELLED, null, now)
        dao.deleteJob(jobId)
    }
}
