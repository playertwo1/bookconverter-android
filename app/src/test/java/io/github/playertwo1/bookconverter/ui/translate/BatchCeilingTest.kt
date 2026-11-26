package io.github.playertwo1.bookconverter.ui.translate

import io.github.playertwo1.bookconverter.core.provider.Provider
import io.github.playertwo1.bookconverter.data.JobStatus
import io.github.playertwo1.bookconverter.data.TranslationJob
import io.github.playertwo1.bookconverter.worker.TranslationWorker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchCeilingTest {

    private val batchWithCeiling = NewTranslationState(provider = Provider.OPENAI, useBatchMode = true, budget = "5000")

    @Test
    fun batchWithTypedOrSuggestedCeilingNeedsAChoice() {
        // A suggested value from the preview lands in the same field as a typed one.
        assertTrue(batchWithCeiling.batchCeilingConflict)
        assertFalse(batchWithCeiling.copy(budget = "").batchCeilingConflict)
        assertFalse(batchWithCeiling.copy(budget = "0").batchCeilingConflict)
    }

    @Test
    fun confirmingBatchWithoutCeilingIsTiedToTheValueSeen() {
        val confirmed = batchWithCeiling.copy(batchWithoutCeilingFor = "5000")
        assertFalse(confirmed.batchCeilingConflict)
        assertTrue(confirmed.copy(budget = "9000").batchCeilingConflict)
    }

    @Test
    fun normalModeAndChatGptKeepTheCeilingWithoutConflict() {
        assertFalse(batchWithCeiling.copy(useBatchMode = false).batchCeilingConflict)
        val chatGpt = batchWithCeiling.copy(provider = Provider.CHATGPT)
        assertFalse(chatGpt.supportsBatch)
        assertFalse(chatGpt.batchCeilingConflict)
    }

    @Test
    fun workerBlocksOnlyNewBatchesWithCeiling() {
        val job = TranslationJob(
            bookId = 1, status = JobStatus.QUEUED, provider = "OPENAI", modelId = "m", effort = null,
            tokenBudget = 5000L, blockTotal = 3, blockDone = 0, inputTokens = 0, outputTokens = 0,
            failureReason = null, createdAt = 0, updatedAt = 0, executionMode = "BATCH",
        )
        assertTrue(TranslationWorker.batchCeilingBlocks(job))
        assertFalse(TranslationWorker.batchCeilingBlocks(job.copy(tokenBudget = null)))
        assertFalse(TranslationWorker.batchCeilingBlocks(job.copy(remoteBatchId = "batch_123")))
    }
}
