package io.github.playertwo1.bookconverter.core.provider

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

enum class Provider { OPENAI, ANTHROPIC, GEMINI }

data class Translation(val text: String, val model: String, val inputTokens: Long?, val outputTokens: Long?)

/** [status] is the HTTP status when the provider answered; null for network failures. */
class ProviderError(val status: Int?, message: String) : Exception(message)

interface ProviderClient {
  fun listModels(): List<String>
  /** [effort] is passed through as-is ("low", "medium", "high"...); null leaves the provider default. */
  fun translate(model: String, system: String, text: String, effort: String?): Translation
}

/**
 * Base URLs are optional, for gateways such as Cloudflare AI Gateway.
 * OpenAI and Gemini bases include the API version (".../v1", ".../v1beta");
 * the Anthropic base does not, because the SDK appends "/v1/...".
 */
fun providerClient(provider: Provider, apiKey: String, baseUrl: String? = null): ProviderClient = when (provider) {
  Provider.OPENAI -> OpenAiClient(apiKey, baseUrl ?: "https://api.openai.com/v1")
  Provider.GEMINI -> GeminiClient(apiKey, baseUrl ?: "https://generativelanguage.googleapis.com/v1beta")
  Provider.ANTHROPIC -> AnthropicClient(apiKey, baseUrl)
}

private val http = OkHttpClient.Builder().readTimeout(10, TimeUnit.MINUTES).callTimeout(15, TimeUnit.MINUTES).build()
private val json = Json { ignoreUnknownKeys = true }
private val JSON_TYPE = "application/json".toMediaType()

private fun call(request: Request): JsonObject {
  val response = try { http.newCall(request).execute() } catch (e: java.io.IOException) { throw ProviderError(null, "Falha de rede: ${e.message}") }
  response.use {
    val body = it.body?.string().orEmpty()
    if (!it.isSuccessful) throw ProviderError(it.code, body.take(500))
    return json.parseToJsonElement(body).jsonObject
  }
}

private class OpenAiClient(private val key: String, private val base: String) : ProviderClient {
  private fun req(path: String) = Request.Builder().url("$base$path").header("Authorization", "Bearer $key")

  override fun listModels() = call(req("/models").get().build())["data"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }.sorted()

  override fun translate(model: String, system: String, text: String, effort: String?): Translation {
    val body = buildJsonObject {
      put("model", model)
      put("instructions", system)
      put("input", text)
      if (effort != null) putJsonObject("reasoning") { put("effort", effort) }
    }
    val r = call(req("/responses").post(body.toString().toRequestBody(JSON_TYPE)).build())
    val out = r["output"]!!.jsonArray.map { it.jsonObject }
      .filter { it["type"]?.jsonPrimitive?.content == "message" }
      .flatMap { it["content"]!!.jsonArray.map { c -> c.jsonObject } }
      .filter { it["type"]?.jsonPrimitive?.content == "output_text" }
      .joinToString("") { it["text"]!!.jsonPrimitive.content }
    val usage = r["usage"]?.jsonObject
    return Translation(out, r["model"]?.jsonPrimitive?.content ?: model,
      usage?.get("input_tokens")?.jsonPrimitive?.longOrNull, usage?.get("output_tokens")?.jsonPrimitive?.longOrNull)
  }
}

private class GeminiClient(private val key: String, private val base: String) : ProviderClient {
  private fun req(path: String) = Request.Builder().url("$base$path").header("x-goog-api-key", key)

  override fun listModels(): List<String> {
    val models = ArrayList<String>()
    var page: String? = null
    do {
      val r = call(req("/models?pageSize=1000" + (page?.let { "&pageToken=$it" } ?: "")).get().build())
      r["models"]?.jsonArray?.map { it.jsonObject }
        ?.filter { m -> m["supportedGenerationMethods"]?.jsonArray?.any { it.jsonPrimitive.content == "generateContent" } == true }
        ?.forEach { models += it["name"]!!.jsonPrimitive.content.removePrefix("models/") }
      page = r["nextPageToken"]?.jsonPrimitive?.content
    } while (page != null)
    return models.sorted()
  }

  override fun translate(model: String, system: String, text: String, effort: String?): Translation {
    val body = buildJsonObject {
      putJsonObject("systemInstruction") { putJsonArray("parts") { addJsonObject { put("text", system) } } }
      putJsonArray("contents") { addJsonObject { put("role", "user"); putJsonArray("parts") { addJsonObject { put("text", text) } } } }
      if (effort != null) putJsonObject("generationConfig") { putJsonObject("thinkingConfig") { put("thinkingLevel", effort) } }
    }
    val r = call(req("/models/$model:generateContent").post(body.toString().toRequestBody(JSON_TYPE)).build())
    val parts = r["candidates"]?.jsonArray?.firstOrNull()?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray.orEmpty()
    val out = parts.map { it.jsonObject }
      .filter { it["thought"]?.jsonPrimitive?.content != "true" }
      .joinToString("") { it["text"]?.jsonPrimitive?.content.orEmpty() }
    if (out.isEmpty()) throw ProviderError(200, "Resposta sem texto: ${r.toString().take(300)}")
    val usage = r["usageMetadata"]?.jsonObject
    return Translation(out, r["modelVersion"]?.jsonPrimitive?.content ?: model,
      usage?.get("promptTokenCount")?.jsonPrimitive?.longOrNull, usage?.get("candidatesTokenCount")?.jsonPrimitive?.longOrNull)
  }
}

private class AnthropicClient(key: String, base: String?) : ProviderClient {
  private val client = AnthropicOkHttpClient.builder().apiKey(key).apply { if (base != null) baseUrl(base) }.build()

  override fun listModels() = client.models().list().autoPager().map { it.id() }.sorted()

  override fun translate(model: String, system: String, text: String, effort: String?): Translation {
    val params = MessageCreateParams.builder()
      .model(model)
      .maxTokens(16000L)
      .system(system)
      .addUserMessage(text)
      .apply { if (effort != null) outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(effort)).build()) }
      .build()
    val msg = try { client.messages().create(params) } catch (e: com.anthropic.errors.AnthropicServiceException) {
      throw ProviderError(e.statusCode(), e.message ?: "erro da API")
    }
    if (msg.stopReason().map { it.toString() }.orElse("") == "refusal") throw ProviderError(200, "Modelo recusou o trecho (refusal)")
    val out = msg.content().flatMap { b -> b.text().map { listOf(it.text()) }.orElse(emptyList()) }.joinToString("")
    return Translation(out, msg.model().toString(), msg.usage().inputTokens(), msg.usage().outputTokens())
  }
}
