package io.github.playertwo1.bookconverter.core.translate

import io.github.playertwo1.bookconverter.core.provider.ErrorKind
import io.github.playertwo1.bookconverter.core.provider.ProviderClient
import io.github.playertwo1.bookconverter.core.provider.ProviderError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class JobStatus {
  QUEUED,
  RUNNING,
  PAUSED,
  WAITING_NETWORK,
  WAITING_RATE_LIMIT,
  PAUSED_BUDGET,
  COMPLETED,
  FAILED,
  CANCELLED,
}

enum class ChunkStatus {
  PENDING,
  IN_PROGRESS,
  COMPLETED,
  FAILED,
}

data class StoredChunk(
  val id: Long,
  val chunkIndex: Int,
  val blockCount: Int,
  val sourceText: String,
  val attemptCount: Int,
)

data class JobProgress(
  val done: Int,
  val total: Int,
  val tokensUsed: Long,
)

typealias Effort = String

sealed interface JobOutcome {
  data object Completed : JobOutcome
  data object Paused : JobOutcome
  data object Cancelled : JobOutcome
  data class WaitingNetwork(val message: String) : JobOutcome
  data class WaitingRateLimit(val retryAfterMs: Long, val message: String) : JobOutcome
  data class PausedBudget(val tokensUsed: Long, val budget: Long) : JobOutcome
  data class Failed(val message: String) : JobOutcome
}

interface TranslationStore {
  suspend fun reconcileInProgress(jobId: Long)
  suspend fun nextPendingChunk(jobId: Long): StoredChunk?
  suspend fun markChunkInProgress(chunkId: Long)
  suspend fun resetChunkToPending(chunkId: Long)
  suspend fun markChunkCompleted(jobId: Long, chunkId: Long, translatedText: String, inputTokens: Long, outputTokens: Long)
  suspend fun markChunkFailed(chunkId: Long, attemptCount: Int, errorMessage: String)
  suspend fun currentProgress(jobId: Long): JobProgress
  suspend fun stopSignal(jobId: Long): JobStatus?
  suspend fun onEffortDowngraded(jobId: Long, newEffort: Effort?)
}

class TranslationEngine(
  private val delayFn: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {
  suspend fun runJob(
    jobId: Long,
    client: ProviderClient,
    model: String,
    effort: Effort? = null,
    tokenBudget: Long? = null,
    store: TranslationStore,
    systemPrompt: String = Prompt.SYSTEM,
    glossary: List<GlossaryTerm> = emptyList(),
    styleNotes: String? = null,
    characters: List<GlossaryTerm> = emptyList(),
    examplePairs: List<GlossaryTerm> = emptyList(),
    onProgress: suspend (JobProgress) -> Unit = {},
  ): JobOutcome {
    // AC08: Reconcile chunks stranded IN_PROGRESS from previous crashed/killed runs
    store.reconcileInProgress(jobId)

    var currentEffort: Effort? = effort

    while (true) {
      currentCoroutineContext().ensureActive()

      when (store.stopSignal(jobId)) {
        JobStatus.PAUSED -> return JobOutcome.Paused
        JobStatus.CANCELLED -> return JobOutcome.Cancelled
        else -> Unit
      }

      val chunk = store.nextPendingChunk(jobId) ?: return JobOutcome.Completed

      val currentProgress = store.currentProgress(jobId)
      if (tokenBudget != null && currentProgress.tokensUsed >= tokenBudget) {
        return JobOutcome.PausedBudget(tokensUsed = currentProgress.tokensUsed, budget = tokenBudget)
      }

      store.markChunkInProgress(chunk.id)

      var badOutputRetries = 0
      var serverRetries = 0
      var rateLimitRetries = 0
      var attemptsThisRun = 0

      chunkLoop@ while (true) {
        currentCoroutineContext().ensureActive()

        when (store.stopSignal(jobId)) {
          JobStatus.PAUSED -> {
            store.resetChunkToPending(chunk.id)
            return JobOutcome.Paused
          }
          JobStatus.CANCELLED -> {
            store.resetChunkToPending(chunk.id)
            return JobOutcome.Cancelled
          }
          else -> Unit
        }

        attemptsThisRun++

        val matchedGlossary = Glossary.matchTerms(chunk.sourceText, glossary)
        val matchedCharacters = Glossary.matchTerms(chunk.sourceText, characters)
        val rankedPairs = if (examplePairs.isNotEmpty()) {
          SeriesRag.rankRelevantPairs(chunk.sourceText, examplePairs, topK = 5)
        } else {
          emptyList()
        }
        val promptToSend = Prompt.systemPrompt(
          glossary = matchedGlossary,
          styleNotes = styleNotes,
          basePrompt = systemPrompt,
          characters = matchedCharacters,
          examplePairs = rankedPairs,
        )

        val translation = try {
          try {
            client.translate(
              model = model,
              system = promptToSend,
              text = chunk.sourceText,
              effort = currentEffort,
            )
          } catch (e: ProviderError) {
            throw e
          } catch (e: java.io.IOException) {
            throw ProviderError(null, "Falha de rede: ${e.message}", ErrorKind.NETWORK)
          } catch (ce: CancellationException) {
            throw ce
          } catch (e: Exception) {
            throw ProviderError(null, e.message ?: "Erro inesperado", ErrorKind.OTHER)
          }
        } catch (ce: CancellationException) {
          withContext(NonCancellable) {
            store.resetChunkToPending(chunk.id)
          }
          throw ce
        } catch (e: ProviderError) {
          when (e.kind) {
            ErrorKind.EFFORT_REJECTED -> {
              if (currentEffort != null) {
                currentEffort = null
                store.onEffortDowngraded(jobId, null)
                continue@chunkLoop
              } else {
                store.resetChunkToPending(chunk.id)
                return JobOutcome.Failed(e.userMessage)
              }
            }
            ErrorKind.BAD_OUTPUT -> {
              if (badOutputRetries < 2) {
                badOutputRetries++
                continue@chunkLoop
              } else {
                store.markChunkFailed(chunk.id, chunk.attemptCount + attemptsThisRun, e.userMessage)
                return JobOutcome.Failed(e.userMessage)
              }
            }
            ErrorKind.SERVER -> {
              if (serverRetries < 2) { // 1st attempt + 2 retries = 3 attempts total
                serverRetries++
                val backoff = if (serverRetries == 1) 500L else 1000L
                delayFn(backoff)
                continue@chunkLoop
              } else {
                store.markChunkFailed(chunk.id, chunk.attemptCount + attemptsThisRun, e.userMessage)
                return JobOutcome.Failed(e.userMessage)
              }
            }
            ErrorKind.RATE_LIMIT -> {
              val rateLimitDelays = listOf(1000L, 2000L, 4000L)
              if (rateLimitRetries < rateLimitDelays.size) {
                val delayMs = rateLimitDelays[rateLimitRetries]
                rateLimitRetries++
                delayFn(delayMs)
                continue@chunkLoop
              } else {
                store.resetChunkToPending(chunk.id)
                return JobOutcome.WaitingRateLimit(retryAfterMs = 4000L, message = e.userMessage)
              }
            }
            ErrorKind.NETWORK -> {
              store.resetChunkToPending(chunk.id)
              return JobOutcome.WaitingNetwork(e.userMessage)
            }
            ErrorKind.INVALID_KEY,
            ErrorKind.SIGNED_OUT,
            ErrorKind.PLAN_UNAVAILABLE,
            ErrorKind.MODEL_UNAVAILABLE,
            ErrorKind.REFUSED,
            ErrorKind.OTHER -> {
              store.resetChunkToPending(chunk.id)
              return JobOutcome.Failed(e.userMessage)
            }
          }
        }

        // Validate and decode output according to Prompt format
        try {
          Prompt.decode(translation.text, chunk.blockCount)
        } catch (ce: CancellationException) {
          withContext(NonCancellable) {
            store.resetChunkToPending(chunk.id)
          }
          throw ce
        } catch (e: ProviderError) {
          if (e.kind == ErrorKind.BAD_OUTPUT && badOutputRetries < 2) {
            badOutputRetries++
            continue@chunkLoop
          } else {
            store.markChunkFailed(chunk.id, chunk.attemptCount + attemptsThisRun, e.userMessage)
            return JobOutcome.Failed(e.userMessage)
          }
        } catch (e: Exception) {
          val msg = "A resposta da IA veio fora do formato esperado: ${e.message}"
          if (badOutputRetries < 2) {
            badOutputRetries++
            continue@chunkLoop
          } else {
            store.markChunkFailed(chunk.id, chunk.attemptCount + attemptsThisRun, msg)
            return JobOutcome.Failed(msg)
          }
        }

        // Chunk succeeded
        val inTokens = translation.inputTokens ?: 0L
        val outTokens = translation.outputTokens ?: 0L
        store.markChunkCompleted(jobId, chunk.id, translation.text, inTokens, outTokens)
        val progress = store.currentProgress(jobId)
        onProgress(progress)
        break@chunkLoop
      }
    }
  }
}
