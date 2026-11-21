package io.github.playertwo1.bookconverter.core

import io.github.playertwo1.bookconverter.core.provider.AnthropicBatchClient
import io.github.playertwo1.bookconverter.core.provider.BatchJobState
import io.github.playertwo1.bookconverter.core.provider.BatchRequestItem
import io.github.playertwo1.bookconverter.core.provider.ErrorKind
import io.github.playertwo1.bookconverter.core.provider.GeminiBatchClient
import io.github.playertwo1.bookconverter.core.provider.OpenAiBatchClient
import io.github.playertwo1.bookconverter.core.provider.Provider
import io.github.playertwo1.bookconverter.core.provider.ProviderError
import io.github.playertwo1.bookconverter.core.provider.batchProviderClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.TimeUnit

class BatchProvidersTest {
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

  private fun reply(status: Int, body: String, contentType: String = "application/json") {
    server.enqueue(
      MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", contentType)
        .setBody(body)
    )
  }

  // ==================== OpenAI Tests ====================

  @Test
  fun openAiSubmitBatchUploadsFileAndCreatesBatch() = runBlocking {
    val client = OpenAiBatchClient(apiKey = "sk-test", baseUrl = "$base/v1", http = testHttp)

    // 1. POST /v1/files
    reply(200, """{"id":"file-999","purpose":"batch"}""")
    // 2. POST /v1/batches
    reply(200, """{"id":"batch-abc-123","status":"validating"}""")

    val items = listOf(
      BatchRequestItem(customId = "req-1", systemPrompt = "Translate sys", userText = "Chapter 1"),
      BatchRequestItem(customId = "req-2", systemPrompt = "Translate sys", userText = "Chapter 2")
    )

    val batchId = client.submitBatch(model = "gpt-5-mini", effort = "low", items = items)
    assertEquals("batch-abc-123", batchId)

    // Verify file upload request
    val fileReq = server.takeRequest()
    assertEquals("/v1/files", fileReq.path)
    assertEquals("Bearer sk-test", fileReq.getHeader("Authorization"))
    val fileBody = fileReq.body.readUtf8()
    assertTrue(fileBody.contains(""""custom_id":"req-1""""))
    assertTrue(fileBody.contains(""""instructions":"Translate sys""""))
    assertTrue(fileBody.contains(""""reasoning":{"effort":"low"}"""))
    assertTrue(fileBody.contains(""""url":"/v1/responses""""))

    // Verify batch create request
    val batchReq = server.takeRequest()
    assertEquals("/v1/batches", batchReq.path)
    assertEquals("Bearer sk-test", batchReq.getHeader("Authorization"))
    val batchBody = batchReq.body.readUtf8()
    assertTrue(batchBody.contains(""""input_file_id":"file-999""""))
    assertTrue(batchBody.contains(""""endpoint":"/v1/responses""""))
    assertTrue(batchBody.contains(""""completion_window":"24h""""))
  }

  @Test
  fun openAiPollBatchMapsStatusesCorrectly() = runBlocking {
    val client = OpenAiBatchClient(apiKey = "sk-test", baseUrl = base, http = testHttp)

    reply(
      200,
      """{"id":"batch-1","status":"in_progress","request_counts":{"total":10,"completed":4,"failed":0}}"""
    )
    val status1 = client.pollBatch("batch-1")
    assertEquals(BatchJobState.IN_PROGRESS, status1.state)
    assertEquals(4, status1.doneCount)
    assertEquals(10, status1.totalCount)

    reply(
      200,
      """{"id":"batch-1","status":"completed","request_counts":{"total":10,"completed":10,"failed":0}}"""
    )
    val status2 = client.pollBatch("batch-1")
    assertEquals(BatchJobState.COMPLETED, status2.state)
    assertEquals(10, status2.doneCount)

    reply(
      200,
      """{"id":"batch-1","status":"failed","errors":{"message":"Batch cancelled or expired"}}"""
    )
    val status3 = client.pollBatch("batch-1")
    assertEquals(BatchJobState.FAILED, status3.state)
    assertEquals("Batch cancelled or expired", status3.errorMessage)
  }

  @Test
  fun openAiFetchResultsParsesOutputJsonl() = runBlocking {
    val client = OpenAiBatchClient(apiKey = "sk-test", baseUrl = base, http = testHttp)

    // 1. GET /batches/batch-1
    reply(200, """{"id":"batch-1","output_file_id":"file-out-777"}""")

    // 2. GET /files/file-out-777/content (JSONL lines)
    val jsonl = """
      {"id":"b1","custom_id":"c1","response":{"status_code":200,"body":{"model":"gpt-5-mini","output":[{"type":"message","content":[{"type":"output_text","text":"Capítulo 1 traduzido"}]}],"usage":{"input_tokens":120,"output_tokens":45}}}}
      {"id":"b2","custom_id":"c2","response":{"status_code":400,"body":{"error":{"message":"Trecho recusado"}}}}
    """.trimIndent()
    reply(200, jsonl, contentType = "application/jsonl")

    val results = client.fetchResults("batch-1")
    assertEquals(2, results.size)

    val r1 = results[0]
    assertEquals("c1", r1.customId)
    assertEquals("Capítulo 1 traduzido", r1.text)
    assertEquals(120L, r1.inputTokens)
    assertEquals(45L, r1.outputTokens)
    assertNull(r1.errorMessage)

    val r2 = results[1]
    assertEquals("c2", r2.customId)
    assertNull(r2.text)
    assertEquals("Trecho recusado", r2.errorMessage)
  }

  // ==================== Anthropic Tests ====================

  @Test
  fun anthropicSubmitBatchSendsMessageBatchRequest() = runBlocking {
    val client = AnthropicBatchClient(apiKey = "ant-key", baseUrl = base, http = testHttp)

    reply(200, """{"id":"msgbatch_001","processing_status":"in_progress"}""")

    val items = listOf(
      BatchRequestItem(customId = "c1", systemPrompt = "System instruction", userText = "Hello world")
    )

    val batchId = client.submitBatch(model = "claude-sonnet-5-5", effort = "medium", items = items)
    assertEquals("msgbatch_001", batchId)

    val sent = server.takeRequest()
    assertEquals("/v1/messages/batches", sent.path)
    assertEquals("ant-key", sent.getHeader("x-api-key"))
    assertEquals("2023-06-01", sent.getHeader("anthropic-version"))
    assertEquals("message-batches-2024-09-24", sent.getHeader("anthropic-beta"))

    val body = sent.body.readUtf8()
    assertTrue(body.contains(""""custom_id":"c1""""))
    assertTrue(body.contains(""""model":"claude-sonnet-5-5""""))
    assertTrue(body.contains(""""max_tokens":16000"""))
    assertTrue(body.contains(""""system":"System instruction""""))
    assertTrue(body.contains(""""effort":"medium""""))
  }

  @Test
  fun anthropicPollBatchTracksProcessingCounts() = runBlocking {
    val client = AnthropicBatchClient(apiKey = "ant-key", baseUrl = base, http = testHttp)

    reply(
      200,
      """{"id":"msgbatch_001","processing_status":"in_progress","request_counts":{"processing":3,"succeeded":7,"errored":0,"canceled":0,"expired":0}}"""
    )
    val s1 = client.pollBatch("msgbatch_001")
    assertEquals(BatchJobState.IN_PROGRESS, s1.state)
    assertEquals(7, s1.doneCount)
    assertEquals(10, s1.totalCount)

    reply(
      200,
      """{"id":"msgbatch_001","processing_status":"ended","request_counts":{"processing":0,"succeeded":10,"errored":0,"canceled":0,"expired":0}}"""
    )
    val s2 = client.pollBatch("msgbatch_001")
    assertEquals(BatchJobState.COMPLETED, s2.state)
    assertEquals(10, s2.doneCount)
    assertEquals(10, s2.totalCount)
  }

  @Test
  fun anthropicFetchResultsParsesJsonl() = runBlocking {
    val client = AnthropicBatchClient(apiKey = "ant-key", baseUrl = base, http = testHttp)

    val jsonl = """
      {"custom_id":"c1","result":{"type":"succeeded","message":{"id":"msg_1","content":[{"type":"text","text":"Olá mundo"}],"usage":{"input_tokens":50,"output_tokens":25}}}}
      {"custom_id":"c2","result":{"type":"errored","error":{"type":"rate_limit_error","message":"Cota excedida"}}}
    """.trimIndent()
    reply(200, jsonl)

    val results = client.fetchResults("msgbatch_001")
    assertEquals(2, results.size)

    assertEquals("c1", results[0].customId)
    assertEquals("Olá mundo", results[0].text)
    assertEquals(50L, results[0].inputTokens)
    assertEquals(25L, results[0].outputTokens)
    assertNull(results[0].errorMessage)

    assertEquals("c2", results[1].customId)
    assertNull(results[1].text)
    assertEquals("Cota excedida", results[1].errorMessage)
  }

  // ==================== Gemini Tests ====================

  @Test
  fun geminiSubmitBatchSendsInlinedRequests() = runBlocking {
    val client = GeminiBatchClient(apiKey = "gem-key", baseUrl = base, http = testHttp)

    reply(200, """{"name":"batches/gemini-job-888"}""")

    val items = listOf(
      BatchRequestItem(customId = "g1", systemPrompt = "Translate to pt-BR", userText = "The quick brown fox")
    )

    val batchId = client.submitBatch(model = "gemini-2.5-flash", effort = "high", items = items)
    assertEquals("batches/gemini-job-888", batchId)

    val sent = server.takeRequest()
    assertEquals("/v1beta/models/gemini-2.5-flash:batchGenerateContent", sent.path)
    assertEquals("gem-key", sent.getHeader("x-goog-api-key"))

    val body = sent.body.readUtf8()
    assertTrue(body.contains(""""key":"g1""""))
    assertTrue(body.contains(""""text":"Translate to pt-BR""""))
    assertTrue(body.contains(""""text":"The quick brown fox""""))
    assertTrue(body.contains(""""thinkingLevel":"high""""))
  }

  @Test
  fun geminiPollBatchMapsStates() = runBlocking {
    val client = GeminiBatchClient(apiKey = "gem-key", baseUrl = base, http = testHttp)

    reply(
      200,
      """{"name":"batches/gemini-job-888","state":"BATCH_STATE_RUNNING","metadata":{"totalItemCount":20,"completedItemCount":5}}"""
    )
    val s1 = client.pollBatch("batches/gemini-job-888")
    assertEquals(BatchJobState.IN_PROGRESS, s1.state)
    assertEquals(5, s1.doneCount)
    assertEquals(20, s1.totalCount)

    reply(
      200,
      """{"name":"batches/gemini-job-888","state":"BATCH_STATE_SUCCEEDED","done":true,"metadata":{"totalItemCount":20,"completedItemCount":20}}"""
    )
    val s2 = client.pollBatch("batches/gemini-job-888")
    assertEquals(BatchJobState.COMPLETED, s2.state)
    assertEquals(20, s2.doneCount)
  }

  @Test
  fun geminiFetchResultsExtractsInlinedResponsesAndUsage() = runBlocking {
    val client = GeminiBatchClient(apiKey = "gem-key", baseUrl = base, http = testHttp)

    val json = """
      {
        "name": "batches/gemini-job-888",
        "response": {
          "inlinedResponses": {
            "inlinedResponses": [
              {
                "metadata": { "key": "g1" },
                "response": {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          { "text": "Pensamento interno...", "thought": "true" },
                          { "text": "A rápida raposa marrom" }
                        ]
                      },
                      "finishReason": "STOP"
                    }
                  ],
                  "usageMetadata": {
                    "promptTokenCount": 60,
                    "candidatesTokenCount": 20,
                    "thoughtsTokenCount": 15
                  }
                }
              },
              {
                "metadata": { "key": "g2" },
                "error": {
                  "code": 400,
                  "message": "Filtro de segurança ativado"
                }
              }
            ]
          }
        }
      }
    """.trimIndent()
    reply(200, json)

    val results = client.fetchResults("batches/gemini-job-888")
    assertEquals(2, results.size)

    val r1 = results[0]
    assertEquals("g1", r1.customId)
    assertEquals("A rápida raposa marrom", r1.text)
    assertEquals(60L, r1.inputTokens)
    // candidatesTokenCount (20) + thoughtsTokenCount (15) = 35
    assertEquals(35L, r1.outputTokens)
    assertNull(r1.errorMessage)

    val r2 = results[1]
    assertEquals("g2", r2.customId)
    assertNull(r2.text)
    assertEquals("Filtro de segurança ativado", r2.errorMessage)
  }

  // ==================== Factory & Error Classification ====================

  @Test
  fun factoryCreatesCorrectClients() {
    val openAi = batchProviderClient(Provider.OPENAI, "k1", base, testHttp)
    assertTrue(openAi is OpenAiBatchClient)

    val anthropic = batchProviderClient(Provider.ANTHROPIC, "k2", base, testHttp)
    assertTrue(anthropic is AnthropicBatchClient)

    val gemini = batchProviderClient(Provider.GEMINI, "k3", base, testHttp)
    assertTrue(gemini is GeminiBatchClient)

    try {
      batchProviderClient(Provider.CHATGPT, "token", base, testHttp)
      fail("Deveria falhar para CHATGPT")
    } catch (e: ProviderError) {
      assertEquals(ErrorKind.PLAN_UNAVAILABLE, e.kind)
    }
  }

  @Test
  fun errorClassificationOnHttpFailures() = runBlocking {
    val client = OpenAiBatchClient(apiKey = "bad-key", baseUrl = base, http = testHttp)

    // 401 Invalid key
    reply(401, """{"error":{"message":"Invalid API key provided"}}""")
    try {
      client.pollBatch("b-1")
      fail("Esperava ProviderError")
    } catch (e: ProviderError) {
      assertEquals(ErrorKind.INVALID_KEY, e.kind)
    }

    // 429 Rate limit
    reply(429, """{"error":{"message":"Rate limit reached"}}""")
    try {
      client.pollBatch("b-2")
      fail("Esperava ProviderError")
    } catch (e: ProviderError) {
      assertEquals(ErrorKind.RATE_LIMIT, e.kind)
    }
  }

  @Test
  fun unreachableServerThrowsNetworkErrorKind() = runBlocking {
    val client = OpenAiBatchClient(apiKey = "k", baseUrl = "http://127.0.0.1:1", http = testHttp)
    try {
      client.pollBatch("b-1")
      fail("Esperava ProviderError de rede")
    } catch (e: ProviderError) {
      assertEquals(ErrorKind.NETWORK, e.kind)
    }
  }
}
