package io.github.playertwo1.bookconverter.core

import io.github.playertwo1.bookconverter.core.provider.ErrorKind
import io.github.playertwo1.bookconverter.core.provider.OpenRouterClient
import io.github.playertwo1.bookconverter.core.provider.Provider
import io.github.playertwo1.bookconverter.core.provider.ProviderError
import io.github.playertwo1.bookconverter.core.provider.fetchModels
import io.github.playertwo1.bookconverter.core.provider.providerClient
import io.github.playertwo1.bookconverter.core.provider.Translation
import io.github.playertwo1.bookconverter.core.translate.ChunkStatus
import io.github.playertwo1.bookconverter.core.translate.Effort
import io.github.playertwo1.bookconverter.core.translate.GlossaryTerm
import io.github.playertwo1.bookconverter.core.translate.JobOutcome
import io.github.playertwo1.bookconverter.core.translate.JobProgress
import io.github.playertwo1.bookconverter.core.translate.JobStatus
import io.github.playertwo1.bookconverter.core.translate.SeriesRag
import io.github.playertwo1.bookconverter.core.translate.StoredChunk
import io.github.playertwo1.bookconverter.core.translate.TranslationEngine
import io.github.playertwo1.bookconverter.core.translate.TranslationStore
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.TimeUnit

class D5OpenRouterAndRagTest {

  private val server = MockWebServer().apply { start() }
  private val base = server.url("/").toString().removeSuffix("/")
  private val testHttp = OkHttpClient.Builder()
    .readTimeout(5, TimeUnit.SECONDS)
    .callTimeout(5, TimeUnit.SECONDS)
    .build()

  @After
  fun tearDown() {
    server.shutdown()
  }

  private fun reply(status: Int, body: String) {
    server.enqueue(
      MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/json")
        .setBody(body)
    )
  }

  private fun kindOf(block: () -> Unit): ErrorKind = try {
    block()
    fail("Esperava ProviderError")
    error("unreachable")
  } catch (e: ProviderError) {
    e.kind
  }

  @Test
  fun openRouterTranslateSuccessWithCompletionsPath() {
    reply(
      200,
      """
      {
        "id": "gen-123",
        "model": "deepseek/deepseek-chat",
        "choices": [
          {
            "message": {
              "role": "assistant",
              "content": "⟦1⟧\nO mago ergueu sua varinha."
            },
            "finish_reason": "stop"
          }
        ],
        "usage": {
          "prompt_tokens": 150,
          "completion_tokens": 35,
          "total_tokens": 185
        }
      }
      """.trimIndent()
    )

    val client = OpenRouterClient(apiKey = "sk-or-test", baseUrl = base, clientHttp = testHttp)
    val translation = client.translate(
      model = "deepseek/deepseek-chat",
      system = "Você é um tradutor literário.",
      text = "⟦1⟧\nThe wizard raised his wand.",
      effort = "high"
    )

    assertEquals("⟦1⟧\nO mago ergueu sua varinha.", translation.text)
    assertEquals("deepseek/deepseek-chat", translation.model)
    assertEquals(150L, translation.inputTokens)
    assertEquals(35L, translation.outputTokens)

    val request = server.takeRequest()
    assertEquals("/v1/chat/completions", request.path)
    assertEquals("POST", request.method)
    assertEquals("Bearer sk-or-test", request.getHeader("Authorization"))
    assertEquals("https://github.com/playertwo1/bookconverter-android", request.getHeader("HTTP-Referer"))

    val requestBody = request.body.readUtf8()
    assertTrue(requestBody.contains(""""model":"deepseek/deepseek-chat""""))
    assertTrue(requestBody.contains(""""reasoning_effort":"high""""))
    assertTrue(requestBody.contains(""""role":"system""""))
    assertTrue(requestBody.contains(""""role":"user""""))
  }

  @Test
  fun openRouterFetchModelsDynamically() {
    reply(
      200,
      """
      {
        "data": [
          {"id": "deepseek/deepseek-chat"},
          {"id": "meta-llama/llama-3.3-70b-instruct"},
          {"id": "openai/whisper-large-v3"},
          {"id": "google/gemini-2.0-flash-001"}
        ]
      }
      """.trimIndent()
    )

    val client = OpenRouterClient(apiKey = "sk-test", baseUrl = base, clientHttp = testHttp)
    val models = client.fetchModels()

    assertEquals(listOf("deepseek/deepseek-chat", "google/gemini-2.0-flash-001", "meta-llama/llama-3.3-70b-instruct", "openai/whisper-large-v3"), models)

    val request = server.takeRequest()
    assertEquals("/v1/models", request.path)
    assertEquals("GET", request.method)
  }

  @Test
  fun catalogFetchModelsConnectsOpenRouterAndFiltersNonText() {
    reply(
      200,
      """
      {
        "data": [
          {"id": "qwen/qwen-2.5-72b-instruct"},
          {"id": "openai/whisper-large"},
          {"id": "stability/stable-diffusion-xl"},
          {"id": "anthropic/claude-3.5-sonnet"}
        ]
      }
      """.trimIndent()
    )

    val filtered = fetchModels(Provider.OPENROUTER, apiKey = "test-key", baseUrl = base)
    assertEquals(listOf("anthropic/claude-3.5-sonnet", "qwen/qwen-2.5-72b-instruct"), filtered)
  }

  @Test
  fun openRouterCustomBaseUrlWithV1Suffix() {
    reply(
      200,
      """
      {
        "choices": [
          {"message": {"content": "⟦1⟧\nTexto traduzido"}}
        ],
        "usage": {"prompt_tokens": 10, "completion_tokens": 5}
      }
      """.trimIndent()
    )

    // When base URL already includes "/v1", client appends "/chat/completions" without duplicating "/v1"
    val customV1Base = "$base/v1"
    val client = OpenRouterClient(apiKey = "key-v1", baseUrl = customV1Base, clientHttp = testHttp)
    val translation = client.translate("m", "s", "⟦1⟧\nt", null)

    assertEquals("⟦1⟧\nTexto traduzido", translation.text)
    val req = server.takeRequest()
    assertEquals("/v1/chat/completions", req.path)
  }

  @Test
  fun openRouterErrorClassification401And429And500() {
    val client = OpenRouterClient(apiKey = "bad-key", baseUrl = base, clientHttp = testHttp)

    // 401 Unauthorized -> INVALID_KEY
    reply(401, """{"error": {"message": "Invalid API key provided", "code": 401}}""")
    assertEquals(ErrorKind.INVALID_KEY, kindOf { client.translate("m", "s", "t", null) })

    // 429 Rate limit / Insufficient credits -> RATE_LIMIT
    reply(429, """{"error": {"message": "Credits exceeded or rate limited", "code": 429}}""")
    assertEquals(ErrorKind.RATE_LIMIT, kindOf { client.translate("m", "s", "t", null) })

    // 502 Bad Gateway -> SERVER
    reply(502, """{"error": {"message": "Provider upstream gateway error", "code": 502}}""")
    assertEquals(ErrorKind.SERVER, kindOf { client.translate("m", "s", "t", null) })
  }

  @Test
  fun openRouterRefusalOrEmptyOutputHandled() {
    val client = OpenRouterClient(apiKey = "key", baseUrl = base, clientHttp = testHttp)

    // Content filter refusal
    reply(
      200,
      """
      {
        "choices": [
          {
            "message": {"role": "assistant", "content": ""},
            "finish_reason": "content_filter"
          }
        ]
      }
      """.trimIndent()
    )
    assertEquals(ErrorKind.REFUSED, kindOf { client.translate("m", "s", "t", null) })

    // Empty message content
    reply(
      200,
      """
      {
        "choices": [
          {
            "message": {"role": "assistant", "content": "   "},
            "finish_reason": "stop"
          }
        ]
      }
      """.trimIndent()
    )
    assertEquals(ErrorKind.BAD_OUTPUT, kindOf { client.translate("m", "s", "t", null) })
  }

  @Test
  fun providerClientFactoryReturnsOpenRouter() {
    val client = providerClient(Provider.OPENROUTER, "my-key", "https://openrouter.ai/api")
    assertTrue(client is OpenRouterClient)
  }

  @Test
  fun seriesRagSelectsSemanticallyClosestPairsOverFirstInList() {
    val candidates = listOf(
      GlossaryTerm(source = "spaceship hyperdrive engine", target = "motor de dobra hiperespacial", notes = "sci-fi tech"),
      GlossaryTerm(source = "healing potion herb brew", target = "poção de ervas curativas", notes = "consumable medicine"),
      GlossaryTerm(source = "cyberpunk neon alleyway", target = "beco escuro neon", notes = "city setting"),
      GlossaryTerm(source = "ancient wizard wand spell", target = "feitiço de varinha do mago ancestral", notes = "high fantasy arcana"),
      GlossaryTerm(source = "plasma assault rifle magazine", target = "pente de rifle de plasma", notes = "military weapon"),
      GlossaryTerm(source = "enchanted magical talisman", target = "talismã mágico encantado", notes = "arcane jewelry"),
      GlossaryTerm(source = "sub-orbital shuttle cockpit", target = "cabine da nave suborbital", notes = "cockpit control")
    )

    val fantasyChapter = """
      The old wizard walked through the ruins. In his trembling hands, he raised the
      ancient wand to cast a powerful spell against the shadows. An enchanted magical talisman
      hung around his neck, glowing with azure light.
    """.trimIndent()

    val ranked = SeriesRag.rankRelevantPairs(
      queryText = fantasyChapter,
      candidates = candidates,
      topK = 3
    )

    assertEquals(3, ranked.size)
    // The top two MUST be the fantasy magic items, not the sci-fi spaceship or healing potion from the top of the list!
    val topSources = ranked.map { it.source }
    assertTrue(topSources.contains("ancient wizard wand spell"))
    assertTrue(topSources.contains("enchanted magical talisman"))
    assertTrue(topSources.take(2).containsAll(listOf("ancient wizard wand spell", "enchanted magical talisman")))
  }

  @Test
  fun seriesRagMorphologicalTrigramMatchHandlesWordVariations() {
    val candidates = listOf(
      GlossaryTerm(source = "wandering traveler", target = "viajante errante"),
      GlossaryTerm(source = "gripped swords", target = "empunhou espadas"),
      GlossaryTerm(source = "brewing potion", target = "preparando poção")
    )

    // Morphological variation: "gripping" vs "gripped", "sword" vs "swords"
    val query = "He stood in silence, firmly gripping his ancient sword."

    val ranked = SeriesRag.rankRelevantPairs(queryText = query, candidates = candidates, topK = 1)
    assertEquals(1, ranked.size)
    assertEquals("gripped swords", ranked.first().source)
  }

  @Test
  fun seriesRagTieBreakerPreservesOriginalOrder() {
    val candidates = listOf(
      GlossaryTerm(source = "Alpha", target = "Alfa"),
      GlossaryTerm(source = "Beta", target = "Beta"),
      GlossaryTerm(source = "Gamma", target = "Gama")
    )

    // Query text has nothing to do with any candidate -> all score 0.0
    val query = "Completely unrelated text with zero overlap."

    val ranked = SeriesRag.rankRelevantPairs(queryText = query, candidates = candidates, topK = 2)
    assertEquals(listOf("Alpha", "Beta"), ranked.map { it.source })
  }

  @Test
  fun seriesRagHandlesEmptyAndBlankInputs() {
    val candidates = listOf(
      GlossaryTerm(source = "term1", target = "trad1"),
      GlossaryTerm(source = "term2", target = "trad2")
    )

    assertEquals(emptyList<GlossaryTerm>(), SeriesRag.rankRelevantPairs("", emptyList(), topK = 5))
    assertEquals(listOf(candidates[0]), SeriesRag.rankRelevantPairs("", candidates, topK = 1))
    assertEquals(candidates, SeriesRag.rankRelevantPairs("   ", candidates, topK = 5))
    assertEquals(emptyList<GlossaryTerm>(), SeriesRag.rankRelevantPairs("text", candidates, topK = 0))
  }

  @Test
  fun translationEngineUsesSeriesRagForExamplePairs() = runBlocking {
    val allExamplePairs = listOf(
      GlossaryTerm(source = "quantum flux capacitor", target = "capacitor de fluxo quântico"),
      GlossaryTerm(source = "cyber deck interface", target = "interface de deck cibernético"),
      GlossaryTerm(source = "ancient glowing staff", target = "cajado brilhante ancestral", notes = "magic weapon"),
      GlossaryTerm(source = "dragon fire breath", target = "sopro de fogo do dragão")
    )

    var capturedSystemPrompt: String? = null

    val fakeClient = object : io.github.playertwo1.bookconverter.core.provider.ProviderClient {
      override fun listModels(): List<String> = listOf("test-model")
      override fun translate(model: String, system: String, text: String, effort: String?): Translation {
        capturedSystemPrompt = system
        return Translation("⟦1⟧\nTradução teste", model, 10L, 10L)
      }
    }

    val store = object : TranslationStore {
      var completed = false
      override suspend fun reconcileInProgress(jobId: Long) {}
      override suspend fun nextPendingChunk(jobId: Long): StoredChunk? {
        if (completed) return null
        return StoredChunk(
          id = 1L,
          chunkIndex = 0,
          blockCount = 1,
          sourceText = "The dragon breathed ancient glowing fire across the battlefield.",
          attemptCount = 0
        )
      }
      override suspend fun markChunkInProgress(chunkId: Long) {}
      override suspend fun resetChunkToPending(chunkId: Long) {}
      override suspend fun markChunkCompleted(jobId: Long, chunkId: Long, translatedText: String, inputTokens: Long, outputTokens: Long) {
        completed = true
      }
      override suspend fun markChunkFailed(chunkId: Long, attemptCount: Int, errorMessage: String) {}
      override suspend fun currentProgress(jobId: Long): JobProgress = JobProgress(if (completed) 1 else 0, 1, 0L)
      override suspend fun stopSignal(jobId: Long): JobStatus? = null
      override suspend fun onEffortDowngraded(jobId: Long, newEffort: Effort?) {}
    }

    val engine = TranslationEngine()
    val outcome = engine.runJob(
      jobId = 100L,
      client = fakeClient,
      model = "test-model",
      store = store,
      examplePairs = allExamplePairs
    )

    assertEquals(JobOutcome.Completed, outcome)
    assertTrue("O prompt deve conter os pares RAG mais relevantes", capturedSystemPrompt != null)
    // "dragon fire breath" and "ancient glowing staff" should be prioritized over "quantum flux capacitor"
    val prompt = capturedSystemPrompt!!
    assertTrue(prompt.contains("dragon fire breath"))
    assertTrue(prompt.contains("ancient glowing staff"))
  }
}
