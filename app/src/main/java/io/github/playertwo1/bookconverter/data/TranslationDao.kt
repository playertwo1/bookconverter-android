package io.github.playertwo1.bookconverter.data

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

data class JobWithBook(
    @Embedded val job: TranslationJob,
    val bookTitle: String,
    val bookAuthor: String?,
) {
    val id: Long get() = job.id
    val bookId: Long get() = job.bookId
    val status: String get() = job.status
    val provider: String get() = job.provider
    val modelId: String get() = job.modelId
    val effort: String? get() = job.effort
    val tokenBudget: Long? get() = job.tokenBudget
    val blockTotal: Int get() = job.blockTotal
    val blockDone: Int get() = job.blockDone
    val inputTokens: Long get() = job.inputTokens
    val outputTokens: Long get() = job.outputTokens
    val failureReason: String? get() = job.failureReason
    val createdAt: Long get() = job.createdAt
    val updatedAt: Long get() = job.updatedAt
}

@Dao
interface TranslationDao {

    @Insert
    suspend fun insertJob(job: TranslationJob): Long

    @Insert
    suspend fun insertChunks(chunks: List<TranslationChunk>)

    @Transaction
    suspend fun createJobWithChunks(job: TranslationJob, chunks: List<TranslationChunk>): Long {
        val jobId = insertJob(job)
        val chunksWithJobId = chunks.map { it.copy(jobId = jobId) }
        insertChunks(chunksWithJobId)
        return jobId
    }

    @Query("""
        SELECT TranslationJob.*, Book.title AS bookTitle, Book.author AS bookAuthor 
        FROM TranslationJob 
        INNER JOIN Book ON TranslationJob.bookId = Book.id 
        ORDER BY 
            CASE TranslationJob.status
                WHEN 'RUNNING' THEN 1
                WHEN 'WAITING_NETWORK' THEN 2
                WHEN 'WAITING_RATE_LIMIT' THEN 3
                WHEN 'PAUSED_BUDGET' THEN 4
                WHEN 'QUEUED' THEN 5
                WHEN 'PAUSED' THEN 6
                WHEN 'FAILED' THEN 7
                WHEN 'CANCELLED' THEN 8
                WHEN 'COMPLETED' THEN 9
                ELSE 10
            END,
            TranslationJob.createdAt ASC
    """)
    fun observeAllJobsWithBook(): Flow<List<JobWithBook>>

    @Query("SELECT * FROM TranslationJob WHERE id = :jobId")
    suspend fun jobById(jobId: Long): TranslationJob?

    @Query("SELECT * FROM TranslationJob WHERE id = :jobId")
    fun observeJobById(jobId: Long): Flow<TranslationJob?>

    @Query("""
        SELECT * FROM TranslationJob 
        WHERE status IN ('RUNNING', 'WAITING_NETWORK', 'WAITING_RATE_LIMIT', 'QUEUED')
        ORDER BY 
            CASE status
                WHEN 'RUNNING' THEN 1
                WHEN 'WAITING_NETWORK' THEN 2
                WHEN 'WAITING_RATE_LIMIT' THEN 3
                WHEN 'QUEUED' THEN 4
                ELSE 5
            END,
            createdAt ASC
        LIMIT 1
    """)
    suspend fun nextRunnableJob(): TranslationJob?

    @Query("UPDATE TranslationJob SET status = :status, failureReason = :failureReason, updatedAt = :updatedAt WHERE id = :jobId")
    suspend fun updateJobStatus(jobId: Long, status: String, failureReason: String?, updatedAt: Long)

    @Query("UPDATE TranslationJob SET effort = :effort, updatedAt = :updatedAt WHERE id = :jobId")
    suspend fun updateJobEffort(jobId: Long, effort: String?, updatedAt: Long)

    @Query("UPDATE TranslationJob SET tokenBudget = :tokenBudget, updatedAt = :updatedAt WHERE id = :jobId")
    suspend fun updateJobBudget(jobId: Long, tokenBudget: Long?, updatedAt: Long)

    @Query("DELETE FROM TranslationJob WHERE id = :jobId")
    suspend fun deleteJob(jobId: Long)

    @Query("UPDATE TranslationChunk SET status = 'PENDING', updatedAt = :updatedAt WHERE jobId = :jobId AND status = 'IN_PROGRESS'")
    suspend fun reconcileInProgress(jobId: Long, updatedAt: Long)

    @Query("UPDATE TranslationChunk SET status = 'PENDING', updatedAt = :updatedAt WHERE status = 'IN_PROGRESS'")
    suspend fun reconcileAllInProgress(updatedAt: Long)

    @Query("SELECT * FROM TranslationChunk WHERE jobId = :jobId AND status = 'PENDING' ORDER BY chunkIndex ASC LIMIT 1")
    suspend fun nextPendingChunk(jobId: Long): TranslationChunk?

    @Query("UPDATE TranslationChunk SET status = 'IN_PROGRESS', updatedAt = :updatedAt WHERE id = :chunkId")
    suspend fun markChunkInProgress(chunkId: Long, updatedAt: Long)

    @Query("UPDATE TranslationChunk SET status = 'PENDING', updatedAt = :updatedAt WHERE id = :chunkId")
    suspend fun resetChunkToPending(chunkId: Long, updatedAt: Long)

    @Query("UPDATE TranslationChunk SET status = 'FAILED', attemptCount = :attemptCount, errorMessage = :errorMessage, updatedAt = :updatedAt WHERE id = :chunkId")
    suspend fun markChunkFailed(chunkId: Long, attemptCount: Int, errorMessage: String?, updatedAt: Long)

    @Query("""
        UPDATE TranslationChunk 
        SET status = 'COMPLETED', 
            translatedText = :translatedText, 
            inputTokens = :inputTokens, 
            outputTokens = :outputTokens, 
            updatedAt = :updatedAt 
        WHERE id = :chunkId
    """)
    suspend fun updateChunkCompleted(
        chunkId: Long,
        translatedText: String,
        inputTokens: Long,
        outputTokens: Long,
        updatedAt: Long,
    )

    @Query("""
        UPDATE TranslationJob 
        SET blockDone = (SELECT COUNT(*) FROM TranslationChunk WHERE jobId = :jobId AND status = 'COMPLETED'),
            inputTokens = (SELECT COALESCE(SUM(inputTokens), 0) FROM TranslationChunk WHERE jobId = :jobId),
            outputTokens = (SELECT COALESCE(SUM(outputTokens), 0) FROM TranslationChunk WHERE jobId = :jobId),
            updatedAt = :updatedAt 
        WHERE id = :jobId
    """)
    suspend fun syncJobProgress(jobId: Long, updatedAt: Long)

    @Transaction
    suspend fun markChunkCompletedAndSyncJob(
        jobId: Long,
        chunkId: Long,
        translatedText: String,
        inputTokens: Long,
        outputTokens: Long,
        updatedAt: Long,
    ) {
        updateChunkCompleted(chunkId, translatedText, inputTokens, outputTokens, updatedAt)
        syncJobProgress(jobId, updatedAt)
    }

    @Query("""
        UPDATE TranslationChunk 
        SET status = 'COMPLETED', 
            translatedText = :translatedText, 
            errorMessage = NULL, 
            updatedAt = :updatedAt 
        WHERE id = :chunkId
    """)
    suspend fun updateChunkEdited(
        chunkId: Long,
        translatedText: String,
        updatedAt: Long,
    )

    @Transaction
    suspend fun saveEditedChunkAndSyncJob(
        jobId: Long,
        chunkId: Long,
        translatedText: String,
        updatedAt: Long,
    ) {
        updateChunkEdited(chunkId, translatedText, updatedAt)
        syncJobProgress(jobId, updatedAt)
    }

    @Query("SELECT COUNT(*) FROM TranslationChunk WHERE jobId = :jobId AND status = 'COMPLETED'")
    suspend fun countCompletedChunks(jobId: Long): Int

    @Query("SELECT * FROM TranslationChunk WHERE jobId = :jobId ORDER BY chunkIndex ASC")
    suspend fun chunksForJob(jobId: Long): List<TranslationChunk>

    @Query("SELECT * FROM TranslationChunk WHERE jobId = :jobId ORDER BY chunkIndex ASC")
    fun observeChunksForJob(jobId: Long): Flow<List<TranslationChunk>>

    @Query("SELECT * FROM TranslationChunk WHERE id = :chunkId")
    suspend fun chunkById(chunkId: Long): TranslationChunk?
}
