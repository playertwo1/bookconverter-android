package io.github.playertwo1.bookconverter.core

import io.github.playertwo1.bookconverter.core.provider.ErrorKind
import io.github.playertwo1.bookconverter.core.provider.Provider
import io.github.playertwo1.bookconverter.core.provider.ProviderError
import io.github.playertwo1.bookconverter.core.provider.providerClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Offline: each adapter against a local server answering in that provider's wire shape (bodies from the public API docs). */
class ProviderAdapterTest {
  private val server = MockWebServer().apply { start() }
  private val base = server.url("/").toString().removeSuffix("/")

  @After fun stop() = server.shutdown()

  private fun reply(status: Int, body: String) = server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body))

  private fun kindOf(block: () -> Unit): ErrorKind = try { block(); fail("esperava ProviderError"); error("") } catch (e: ProviderError) { e.kind }

  @Test fun openAiSendsEffortAndReadsTextAndUsage() {
    reply(200, """{"model":"gpt-x-2026","output":[{"type":"reasoning","summary":[]},{"type":"message","content":[{"type":"output_text","text":"⟦1⟧ Olá"}]}],"usage":{"input_tokens":120,"output_tokens":45}}""")
    val t = providerClient(Provider.OPENAI, "k", base).translate("gpt-x", "sys", "⟦1⟧ Hi", "low")
    assertEquals("⟦1⟧ Olá", t.text)
    assertEquals(120L, t.inputTokens)
    assertEquals(45L, t.outputTokens)
    val sent = server.takeRequest()
    assertEquals("/responses", sent.path)
    assertEquals("Bearer k", sent.getHeader("Authorization"))
    assertTrue(sent.body.readUtf8().contains(""""reasoning":{"effort":"low"}"""))
  }

  @Test fun openAiErrorsAreClassified() {
    val c = providerClient(Provider.OPENAI, "k", base)
    reply(400, """{"error":{"message":"Unsupported parameter: 'reasoning.effort' is not supported with this model.","type":"invalid_request_error","param":"reasoning.effort","code":"unsupported_parameter"}}""")
    assertEquals(ErrorKind.EFFORT_REJECTED, kindOf { c.translate("m", "s", "t", "high") })
    reply(401, """{"error":{"message":"Incorrect API key provided.","type":"invalid_request_error","code":"invalid_api_key"}}""")
    assertEquals(ErrorKind.INVALID_KEY, kindOf { c.listModels() })
    reply(404, """{"error":{"message":"The model `nope` does not exist or you do not have access to it.","code":"model_not_found"}}""")
    assertEquals(ErrorKind.MODEL_UNAVAILABLE, kindOf { c.translate("nope", "s", "t", null) })
    reply(429, """{"error":{"message":"You exceeded your current quota","code":"insufficient_quota"}}""")
    assertEquals(ErrorKind.RATE_LIMIT, kindOf { c.translate("m", "s", "t", null) })
    // A 400 that only mentions reasoning in passing is not an answer about effort.
    reply(400, """{"error":{"message":"max_output_tokens is too small to leave room for reasoning.","param":"max_output_tokens"}}""")
    assertEquals(ErrorKind.OTHER, kindOf { c.translate("m", "s", "t", "high") })
  }

  @Test fun geminiCountsThinkingAsOutputAndSkipsThoughtParts() {
    reply(200, """{"candidates":[{"content":{"parts":[{"text":"pensando…","thought":true},{"text":"⟦1⟧ Olá"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":90,"candidatesTokenCount":30,"thoughtsTokenCount":200},"modelVersion":"gemini-x-001"}""")
    val t = providerClient(Provider.GEMINI, "k", base).translate("gemini-x", "sys", "⟦1⟧ Hi", "low")
    assertEquals("⟦1⟧ Olá", t.text)
    assertEquals(230L, t.outputTokens)
    val sent = server.takeRequest()
    assertEquals("/models/gemini-x:generateContent", sent.path)
    assertEquals("k", sent.getHeader("x-goog-api-key"))
    assertTrue(sent.body.readUtf8().contains(""""thinkingLevel":"low""""))
  }

  @Test fun geminiErrorsAreClassified() {
    val c = providerClient(Provider.GEMINI, "k", base)
    reply(400, """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}""")
    assertEquals(ErrorKind.INVALID_KEY, kindOf { c.listModels() })
    reply(400, """{"error":{"code":400,"message":"Thinking level is not supported for this model.","status":"INVALID_ARGUMENT"}}""")
    assertEquals(ErrorKind.EFFORT_REJECTED, kindOf { c.translate("m", "s", "t", "medium") })
    reply(404, """{"error":{"code":404,"message":"models/nope is not found for API version v1beta","status":"NOT_FOUND"}}""")
    assertEquals(ErrorKind.MODEL_UNAVAILABLE, kindOf { c.translate("nope", "s", "t", null) })
    reply(200, """{"candidates":[{"finishReason":"SAFETY"}]}""")
    assertEquals(ErrorKind.REFUSED, kindOf { c.translate("m", "s", "t", null) })
  }

  @Test fun anthropicThroughTheSdk() {
    reply(200, """{"id":"msg_1","type":"message","role":"assistant","model":"claude-x-2026","content":[{"type":"text","text":"⟦1⟧ Olá"}],"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":100,"output_tokens":40}}""")
    val c = providerClient(Provider.ANTHROPIC, "k", base)
    val t = c.translate("claude-x", "sys", "⟦1⟧ Hi", "low")
    assertEquals("⟦1⟧ Olá", t.text)
    assertEquals(100L, t.inputTokens)
    val sent = server.takeRequest()
    assertEquals("/v1/messages", sent.path)
    // SDK 3.x sends the API key as a bearer token (StaticAuthHeaders), not x-api-key.
    assertEquals("Bearer k", sent.getHeader("Authorization"))
    assertTrue(sent.body.readUtf8().contains(""""effort":"low""""))

    reply(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")
    assertEquals(ErrorKind.INVALID_KEY, kindOf { c.listModels() })
    reply(400, """{"type":"error","error":{"type":"invalid_request_error","message":"This model does not support the effort parameter."}}""")
    assertEquals(ErrorKind.EFFORT_REJECTED, kindOf { c.translate("claude-old", "s", "t", "high") })
    reply(404, """{"type":"error","error":{"type":"not_found_error","message":"model: claude-nope"}}""")
    assertEquals(ErrorKind.MODEL_UNAVAILABLE, kindOf { c.translate("claude-nope", "s", "t", null) })
  }

  @Test fun modelListHidesNonTextFamilies() {
    val ids = listOf("gpt-5.1", "text-embedding-3-large", "tts-1", "whisper-1", "gpt-4o-transcribe", "dall-e-3", "gpt-image-1",
      "gpt-realtime", "omni-moderation-latest", "gpt-4o-search-preview", "gemini-3-pro", "gemini-embedding-001",
      "imagen-4.0-generate", "gemini-2.5-flash-preview-tts", "gemini-live-2.5-flash", "claude-sonnet-5-5")
    assertEquals(listOf("gpt-5.1", "gemini-3-pro", "claude-sonnet-5-5"), io.github.playertwo1.bookconverter.core.provider.translationModels(ids))
  }

  @Test fun unreachableHostIsNetwork() {
    server.shutdown()
    assertEquals(ErrorKind.NETWORK, kindOf { providerClient(Provider.OPENAI, "k", base).listModels() })
  }
}
