package io.github.playertwo1.bookconverter.core

import io.github.playertwo1.bookconverter.core.provider.ErrorKind
import io.github.playertwo1.bookconverter.core.provider.ProviderClient
import io.github.playertwo1.bookconverter.core.provider.ProviderError
import io.github.playertwo1.bookconverter.core.provider.Translation
import io.github.playertwo1.bookconverter.core.translate.ChunkStatus
import io.github.playertwo1.bookconverter.core.translate.Effort
import io.github.playertwo1.bookconverter.core.translate.JobOutcome
import io.github.playertwo1.bookconverter.core.translate.JobProgress
import io.github.playertwo1.bookconverter.core.translate.JobStatus
import io.github.playertwo1.bookconverter.core.translate.StoredChunk
import io.github.playertwo1.bookconverter.core.translate.TranslationEngine
import io.github.playertwo1.bookconverter.core.translate.TranslationStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class TranslationEngineTest {

  private class FakeTranslationStore : TranslationStore {
    data class ChunkRecord(
      val id: Long,
      val chunkIndex: Int,
      val blockCount: Int,
      val sourceText: String,
      var status: ChunkStatus = ChunkStatus.PENDING,
      var attemptCount: Int = 0,
      var translatedText: String? = null,
      var inputTokens: Long = 0,
      var outputTokens: Long = 0,
      var errorMessage: String? = null,
    )

    val chunks = mutableListOf<ChunkRecord>()
    var stopSignal: JobStatus? = null
    var downgradedEffort: Effort? = "UNTOUCHED"
    val completionHistory = mutableListOf<Long>()

    fun addChunk(id: Long, chunkIndex: Int, blockCount: Int = 1, sourceText: String = "⟦1⟧\nSource") {
      chunks.add(ChunkRecord(id, chunkIndex, blockCount, sourceText))
    }

    override suspend fun reconcileInProgress(jobId: Long) {
      for (c in chunks) {
        if (c.status == ChunkStatus.IN_PROGRESS) {
          c.status = ChunkStatus.PENDING
        }
      }
    }

    override suspend fun nextPendingChunk(jobId: Long): StoredChunk? {
      val next = chunks.filter { it.status == ChunkStatus.PENDING }.minByOrNull { it.chunkIndex } ?: return null
      return StoredChunk(next.id, next.chunkIndex, next.blockCount, next.sourceText, next.attemptCount)
    }

    override suspend fun markChunkInProgress(chunkId: Long) {
      val c = chunks.first { it.id == chunkId }
      c.status = ChunkStatus.IN_PROGRESS
    }

    override suspend fun resetChunkToPending(chunkId: Long) {
      val c = chunks.first { it.id == chunkId }
      c.status = ChunkStatus.PENDING
    }

    override suspend fun markChunkCompleted(
      jobId: Long,
      chunkId: Long,
      translatedText: String,
      inputTokens: Long,
      outputTokens: Long,
    ) {
      val c = chunks.first { it.id == chunkId }
      c.status = ChunkStatus.COMPLETED
      c.translatedText = translatedText
      c.inputTokens = inputTokens
      c.outputTokens = outputTokens
      completionHistory.add(chunkId)
    }

    override suspend fun markChunkFailed(chunkId: Long, attemptCount: Int, errorMessage: String) {
      val c = chunks.first { it.id == chunkId }
      c.status = ChunkStatus.FAILED
      c.attemptCount = attemptCount
      c.errorMessage = errorMessage
    }

    override suspend fun currentProgress(jobId: Long): JobProgress {
      val done = chunks.count { it.status == ChunkStatus.COMPLETED }
      val total = chunks.size
      val tokens = chunks.filter { it.status == ChunkStatus.COMPLETED }.sumOf { it.inputTokens + it.outputTokens }
      return JobProgress(done, total, tokens)
    }

    override suspend fun stopSignal(jobId: Long): JobStatus? = stopSignal

    override suspend fun onEffortDowngraded(jobId: Long, newEffort: Effort?) {
      downgradedEffort = newEffort
    }
  }

  private fun validReply(blockCount: Int): String {
    return (1..blockCount).joinToString("\n\n") { "⟦$it⟧\nTraduzido $it" }
  }

  private class FakeClient(
    private val handler: (model: String, system: String, text: String, effort: String?) -> Translation = { m, _, _, _ ->
      Translation("⟦1⟧\nTraduzido 1", m, 10L, 20L)
    },
  ) : ProviderClient {
    val calls = mutableListOf<String>()
    override fun listModels(): List<String> = listOf("model-test")
    override fun translate(model: String, system: String, text: String, effort: String?): Translation {
      calls.add(text)
      return handler(model, system, text, effort)
    }
  }

  @Test
  fun fullFlowMaintainsDoneInvariant() = runBlocking {
    val store = FakeTranslationStore()
    for (i in 0 until 5) {
      store.addChunk(id = i.toLong(), chunkIndex = i, blockCount = 2, sourceText = "⟦1⟧\nA\n\n⟦2⟧\nB")
    }

    val client = FakeClient { m, _, _, _ ->
      Translation(validReply(2), m, 100L, 50L)
    }

    val recordedDelays = mutableListOf<Long>()
    val engine = TranslationEngine(delayFn = { recordedDelays.add(it) })

    val progressUpdates = mutableListOf<JobProgress>()
    val outcome = engine.runJob(
      jobId = 1L,
      client = client,
      model = "model-test",
      store = store,
      onProgress = { progressUpdates.add(it) },
    )

    assertEquals(JobOutcome.Completed, outcome)
    assertEquals(5, store.chunks.count { it.status == ChunkStatus.COMPLETED })
    assertEquals(5, progressUpdates.size)
    assertEquals(JobProgress(done = 5, total = 5, tokensUsed = 750L), store.currentProgress(1L))
    // Invariant: done == count of completed chunks
    assertEquals(store.currentProgress(1L).done, store.chunks.count { it.status == ChunkStatus.COMPLETED })
  }

  @Test
  fun ac07PauseAt73AndResumeTo200WithoutDuplication() = runBlocking {
    val store = FakeTranslationStore()
    val totalChunks = 200
    for (i in 0 until totalChunks) {
      store.addChunk(id = i.toLong(), chunkIndex = i, blockCount = 1, sourceText = "⟦1⟧\nParágrafo $i")
    }

    val client = FakeClient { m, _, _, _ ->
      Translation("⟦1⟧\nTraduzido", m, 10L, 10L)
    }

    val engine = TranslationEngine(delayFn = {})

    // Pause after chunk 72 finishes (i.e. 73 chunks completed: 0..72)
    var outcome1: JobOutcome? = null
    outcome1 = engine.runJob(
      jobId = 1L,
      client = client,
      model = "model-test",
      store = store,
      onProgress = { p ->
        if (p.done == 73) {
          store.stopSignal = JobStatus.PAUSED
        }
      },
    )

    assertEquals(JobOutcome.Paused, outcome1)
    val progress1 = store.currentProgress(1L)
    assertEquals(73, progress1.done)
    assertEquals(73, store.chunks.count { it.status == ChunkStatus.COMPLETED })
    assertEquals(127, store.chunks.count { it.status == ChunkStatus.PENDING })
    assertEquals(0, store.chunks.count { it.status == ChunkStatus.IN_PROGRESS })

    // Resume: clear stop signal and run again
    store.stopSignal = null
    val outcome2 = engine.runJob(
      jobId = 1L,
      client = client,
      model = "model-test",
      store = store,
    )

    assertEquals(JobOutcome.Completed, outcome2)
    val progress2 = store.currentProgress(1L)
    assertEquals(200, progress2.done)
    assertEquals(200, store.chunks.count { it.status == ChunkStatus.COMPLETED })
    // Verify 0 duplications: exactly 200 items in completionHistory, all distinct
    assertEquals(200, store.completionHistory.size)
    assertEquals(200, store.completionHistory.toSet().size)
  }

  @Test
  fun ac08SimulatedCrashReconcilesInProgressToPending() = runBlocking {
    val store = FakeTranslationStore()
    for (i in 0 until 10) {
      store.addChunk(id = i.toLong(), chunkIndex = i, blockCount = 1)
    }

    // Simulate chunks 0, 1, 2 completed, chunk 3 crashed in IN_PROGRESS, chunks 4..9 pending
    for (i in 0..2) {
      store.chunks[i].status = ChunkStatus.COMPLETED
      store.completionHistory.add(i.toLong())
    }
    store.chunks[3].status = ChunkStatus.IN_PROGRESS

    val client = FakeClient { m, _, _, _ ->
      Translation("⟦1⟧\nTraduzido", m, 10L, 10L)
    }

    val engine = TranslationEngine(delayFn = {})
    val outcome = engine.runJob(
      jobId = 1L,
      client = client,
      model = "model-test",
      store = store,
    )

    assertEquals(JobOutcome.Completed, outcome)
    assertEquals(10, store.currentProgress(1L).done)
    // Chunks 0, 1, 2 were already completed; chunk 3 reconciled and completed; 4..9 completed
    assertEquals(10, store.completionHistory.size)
    assertEquals(10, store.completionHistory.toSet().size)
  }

  @Test
  fun ac10NetworkErrorYieldsWaitingNetworkAndPreservesCheckpoint() = runBlocking {
    val store = FakeTranslationStore()
    for (i in 0 until 5) {
      store.addChunk(id = i.toLong(), chunkIndex = i, blockCount = 1)
    }

    var networkFail = false
    val client = FakeClient { m, _, _, _ ->
      if (networkFail) throw ProviderError(null, "Conexão perdida", ErrorKind.NETWORK)
      Translation("⟦1⟧\nTraduzido", m, 10L, 10L)
    }

    val engine = TranslationEngine(delayFn = {})

    // Process chunk 0 and 1, then fail with NETWORK on chunk 2
    var processed = 0
    val outcome1 = engine.runJob(
      jobId = 1L,
      client = FakeClient { m, _, text, _ ->
        processed++
        if (processed > 2) throw ProviderError(null, "Sem internet", ErrorKind.NETWORK)
        Translation("⟦1⟧\nTraduzido", m, 10L, 10L)
      },
      model = "model-test",
      store = store,
    )

    assertTrue(outcome1 is JobOutcome.WaitingNetwork)
    // Checkpoint preserved: chunks 0 and 1 completed, chunk 2 reset to PENDING, chunks 3 and 4 PENDING
    assertEquals(2, store.currentProgress(1L).done)
    assertEquals(ChunkStatus.PENDING, store.chunks[2].status)
    assertEquals(0, store.chunks.count { it.status == ChunkStatus.IN_PROGRESS })

    // Resume when network restored
    val outcome2 = engine.runJob(
      jobId = 1L,
      client = FakeClient { m, _, _, _ -> Translation("⟦1⟧\nTraduzido", m, 10L, 10L) },
      model = "model-test",
      store = store,
    )

    assertEquals(JobOutcome.Completed, outcome2)
    assertEquals(5, store.currentProgress(1L).done)
    assertEquals(5, store.completionHistory.size)
  }

  @Test
  fun ac11RateLimitBackoffAndWaitingRateLimit() = runBlocking {
    val store = FakeTranslationStore()
    store.addChunk(id = 1L, chunkIndex = 0, blockCount = 1)

    val recordedDelays = mutableListOf<Long>()
    val client = FakeClient { _, _, _, _ ->
      throw ProviderError(429, "Rate limit exceeded", ErrorKind.RATE_LIMIT)
    }

    val engine = TranslationEngine(delayFn = { recordedDelays.add(it) })
    val outcome = engine.runJob(
      jobId = 1L,
      client = client,
      model = "model-test",
      store = store,
    )

    assertTrue(outcome is JobOutcome.WaitingRateLimit)
    assertEquals(4000L, (outcome as JobOutcome.WaitingRateLimit).retryAfterMs)
    assertEquals(listOf(1000L, 2000L, 4000L), recordedDelays)
    assertEquals(ChunkStatus.PENDING, store.chunks[0].status)
  }

  @Test
  fun ac11TokenBudgetStopsAtOrAboveThreshold() = runBlocking {
    val store = FakeTranslationStore()
    for (i in 0 until 5) {
      store.addChunk(id = i.toLong(), chunkIndex = i, blockCount = 1)
    }

    // Each chunk consumes 150 input + 150 output = 300 tokens
    val client = FakeClient { m, _, _, _ ->
      Translation("⟦1⟧\nTraduzido", m, 150L, 150L)
    }

    val engine = TranslationEngine(delayFn = {})
    // Budget 500: chunk 0 -> 300 tokens, chunk 1 -> 600 tokens (exceeds 500)
    val outcome = engine.runJob(
      jobId = 1L,
      client = client,
      model = "model-test",
      effort = null,
      tokenBudget = 500L,
      store = store,
    )

    assertTrue(outcome is JobOutcome.PausedBudget)
    val paused = outcome as JobOutcome.PausedBudget
    assertEquals(600L, paused.tokensUsed)
    assertEquals(500L, paused.budget)
    assertEquals(2, store.currentProgress(1L).done)
    assertEquals(ChunkStatus.PENDING, store.chunks[2].status)
  }

  @Test
  fun ac06EffortRejectedTriggersDowngradeAndCompletes() = runBlocking {
    val store = FakeTranslationStore()
    store.addChunk(id = 1L, chunkIndex = 0, blockCount = 1)
    store.addChunk(id = 2L, chunkIndex = 1, blockCount = 1)

    val effortsReceived = mutableListOf<String?>()
    val client = FakeClient { m, _, _, effort ->
      effortsReceived.add(effort)
      if (effort != null) {
        throw ProviderError(400, "Unsupported parameter: reasoning.effort", ErrorKind.EFFORT_REJECTED)
      }
      Translation("⟦1⟧\nTraduzido", m, 10L, 10L)
    }

    val engine = TranslationEngine(delayFn = {})
    val outcome = engine.runJob(
      jobId = 1L,
      client = client,
      model = "model-test",
      effort = "high",
      store = store,
    )

    assertEquals(JobOutcome.Completed, outcome)
    assertNull(store.downgradedEffort)
    // Chunk 0 was attempted with "high", failed with EFFORT_REJECTED, then retried with null.
    // Chunk 1 was called directly with null.
    assertEquals(listOf("high", null, null), effortsReceived)
    assertEquals(2, store.currentProgress(1L).done)
  }

  @Test
  fun badOutputRetriesUpTo2TimesAndSucceedsOnThird() = runBlocking {
    val store = FakeTranslationStore()
    store.addChunk(id = 1L, chunkIndex = 0, blockCount = 2)

    var callCount = 0
    val client = FakeClient { m, _, _, _ ->
      callCount++
      if (callCount < 3) {
        // Missing marker ⟦2⟧ -> bad output
        Translation("⟦1⟧\nApenas um", m, 10L, 10L)
      } else {
        Translation(validReply(2), m, 10L, 10L)
      }
    }

    val engine = TranslationEngine(delayFn = {})
    val outcome = engine.runJob(
      jobId = 1L,
      client = client,
      model = "model-test",
      store = store,
    )

    assertEquals(JobOutcome.Completed, outcome)
    assertEquals(3, callCount)
    assertEquals(1, store.currentProgress(1L).done)
  }

  @Test
  fun badOutputExhausts2RetriesAndFails() = runBlocking {
    val store = FakeTranslationStore()
    store.addChunk(id = 1L, chunkIndex = 0, blockCount = 2)

    var callCount = 0
    val client = FakeClient { m, _, _, _ ->
      callCount++
      Translation("sem marcadores", m, 10L, 10L)
    }

    val engine = TranslationEngine(delayFn = {})
    val outcome = engine.runJob(
      jobId = 1L,
      client = client,
      model = "model-test",
      store = store,
    )

    assertTrue(outcome is JobOutcome.Failed)
    assertEquals(3, callCount) // initial attempt + 2 retries = 3 attempts
    assertEquals(ChunkStatus.FAILED, store.chunks[0].status)
    assertEquals(3, store.chunks[0].attemptCount)
  }

  @Test
  fun server5xxRetriesWithBackoffAndFailsAfter3Attempts() = runBlocking {
    val store = FakeTranslationStore()
    store.addChunk(id = 1L, chunkIndex = 0, blockCount = 1)

    val recordedDelays = mutableListOf<Long>()
    var attempts = 0
    val client = FakeClient { _, _, _, _ ->
      attempts++
      throw ProviderError(500, "Internal Server Error", ErrorKind.SERVER)
    }

    val engine = TranslationEngine(delayFn = { recordedDelays.add(it) })
    val outcome = engine.runJob(
      jobId = 1L,
      client = client,
      model = "model-test",
      store = store,
    )

    assertTrue(outcome is JobOutcome.Failed)
    assertEquals(3, attempts)
    assertEquals(listOf(500L, 1000L), recordedDelays)
    assertEquals(ChunkStatus.FAILED, store.chunks[0].status)
  }

  @Test
  fun cancellationResetsChunkToPending() = runBlocking {
    val store = FakeTranslationStore()
    store.addChunk(id = 1L, chunkIndex = 0, blockCount = 1)

    val engine = TranslationEngine(delayFn = {})

    val job = launch {
      engine.runJob(
        jobId = 1L,
        client = FakeClient { _, _, _, _ ->
          // Coroutine cancelled during translation
          throw CancellationException("Worker cancelled")
        },
        model = "model-test",
        store = store,
      )
    }
    job.join()

    assertEquals(ChunkStatus.PENDING, store.chunks[0].status)
  }

  @Test
  fun jvmChaosTest50Pauses20CrashesNetworkAndRateLimitsOn200Chunks() = runBlocking {
    val totalChunks = 200
    val store = FakeTranslationStore()
    for (i in 0 until totalChunks) {
      store.addChunk(id = i.toLong(), chunkIndex = i, blockCount = 1, sourceText = "⟦1⟧\nChaos chunk $i")
    }

    val random = Random(42)
    val crashCounter = AtomicInteger(20)
    val pauseCounter = AtomicInteger(50)

    val delays = mutableListOf<Long>()
    val engine = TranslationEngine(delayFn = { delays.add(it) })

    var iterations = 0
    while (store.chunks.any { it.status != ChunkStatus.COMPLETED }) {
      iterations++
      if (iterations > 5000) fail("Loop de caos não convergiu após $iterations iterações")

      // Randomly simulate crash before running
      if (crashCounter.get() > 0 && random.nextDouble() < 0.3) {
        val pending = store.chunks.filter { it.status == ChunkStatus.PENDING }
        if (pending.isNotEmpty()) {
          val victim = pending.random(random)
          victim.status = ChunkStatus.IN_PROGRESS // Simulates crash while chunk was leasing
          crashCounter.decrementAndGet()
        }
      }

      // Configure chaos client
      val client = FakeClient { m, _, _, _ ->
        val roll = random.nextDouble()
        when {
          roll < 0.05 -> throw ProviderError(null, "Instabilidade de rede temporária", ErrorKind.NETWORK)
          roll < 0.10 -> throw ProviderError(429, "Too many requests", ErrorKind.RATE_LIMIT)
          else -> Translation("⟦1⟧\nTraduzido", m, 10L, 10L)
        }
      }

      val outcome = engine.runJob(
        jobId = 1L,
        client = client,
        model = "model-test",
        store = store,
        onProgress = { p ->
          // Randomly trigger pause
          if (pauseCounter.get() > 0 && random.nextDouble() < 0.05) {
            store.stopSignal = JobStatus.PAUSED
            pauseCounter.decrementAndGet()
          }
        },
      )

      if (store.stopSignal == JobStatus.PAUSED) {
        store.stopSignal = null // unpause for next loop
      }
    }

    // Verification of invariant and zero duplication
    assertEquals(200, store.chunks.count { it.status == ChunkStatus.COMPLETED })
    assertEquals(0, store.chunks.count { it.status != ChunkStatus.COMPLETED })
    assertEquals(200, store.currentProgress(1L).done)
    assertEquals(200, store.completionHistory.size)
    assertEquals(200, store.completionHistory.toSet().size)
  }
}
