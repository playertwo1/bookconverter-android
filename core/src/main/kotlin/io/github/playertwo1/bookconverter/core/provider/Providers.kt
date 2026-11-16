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

/** CHATGPT = OpenAI's Responses API paid by the user's ChatGPT plan through "Sign in with ChatGPT" (no API key). */
enum class Provider { OPENAI, CHATGPT, ANTHROPIC, GEMINI }

data class Translation(val text: String, val model: String, val inputTokens: Long?, val outputTokens: Long?)

enum class ErrorKind { INVALID_KEY, SIGNED_OUT, PLAN_UNAVAILABLE, MODEL_UNAVAILABLE, EFFORT_REJECTED, RATE_LIMIT, SERVER, NETWORK, REFUSED, BAD_OUTPUT, OTHER }

/** [status] is the HTTP status when the provider answered; null for network failures. [message] is the provider's own text. */
class ProviderError(val status: Int?, message: String, val kind: ErrorKind = classify(status, message)) : Exception(message) {
  /** pt-BR, for the screen. Never contains the key: providers do not echo it back. */
  val userMessage: String
    get() = when (kind) {
      ErrorKind.INVALID_KEY -> "Chave de API inválida ou sem permissão. Confira a chave em Ajustes."
      ErrorKind.SIGNED_OUT -> "A sessão do ChatGPT terminou ou o login falhou. Entre de novo em Ajustes. (${message?.take(120)})"
      ErrorKind.PLAN_UNAVAILABLE -> "O seu plano do ChatGPT não permite uso neste app (ou a região/workspace bloqueia)."
      ErrorKind.MODEL_UNAVAILABLE -> "Este modelo não está disponível para a sua chave. Escolha outro."
      ErrorKind.EFFORT_REJECTED -> "Este modelo não aceita esse nível de esforço."
      ErrorKind.RATE_LIMIT -> "Limite de uso ou cota do provedor atingido. Tente mais tarde ou confira o saldo."
      ErrorKind.SERVER -> "O provedor está instável agora (erro ${status ?: "?"}). Tente de novo em instantes."
      ErrorKind.NETWORK -> "Sem conexão com o provedor. Confira a internet e a URL base. (${message?.take(120)})"
      ErrorKind.REFUSED -> "O modelo se recusou a traduzir este trecho."
      ErrorKind.BAD_OUTPUT -> "A resposta da IA veio fora do formato esperado: ${message?.take(160)}"
      ErrorKind.OTHER -> "Erro do provedor (${status ?: "?"}): ${message?.take(160)}"
    }
}

// Both a field the effort control writes and rejection wording must appear: a 400 that merely mentions
// "thinking" (e.g. a max_tokens complaint) is not an answer about effort. Same rule as the desktop's rejected_parameter.
private val EFFORT_FIELD = Regex("""reasoning(\.effort)?|reasoning_effort|thinking[ _]?level|thinkingLevel|thinkingConfig|output_config(\.effort)?|\beffort\b""", RegexOption.IGNORE_CASE)
private val REJECTION = Regex("""unsupported|not supported|unknown|unrecognized|invalid|not allowed|does not support|isn't supported""", RegexOption.IGNORE_CASE)
private val MODEL_MISSING = Regex("""model.{0,80}(not found|does not exist|not supported|is not available|no access|not have access)|model_not_found|not_found_error""", RegexOption.IGNORE_CASE)

internal fun classify(status: Int?, body: String?): ErrorKind {
  val text = body.orEmpty()
  return when {
    status == null -> ErrorKind.NETWORK
    // ChatGPT plan route (developers.openai.com/siwc errors-and-recovery).
    text.contains("subscription_sharing_user_not_eligible") || text.contains("chatpass_v2_") -> ErrorKind.PLAN_UNAVAILABLE
    text.contains("subscription_sharing_invalid_user") -> ErrorKind.SIGNED_OUT
    text.contains("subscription_sharing_usage_limit_exceeded") -> ErrorKind.RATE_LIMIT
    status == 401 || status == 403 -> ErrorKind.INVALID_KEY
    status == 429 -> ErrorKind.RATE_LIMIT
    status >= 500 -> ErrorKind.SERVER
    status == 404 -> ErrorKind.MODEL_UNAVAILABLE
    status == 400 && Regex("API key not valid|API_KEY_INVALID", RegexOption.IGNORE_CASE).containsMatchIn(text) -> ErrorKind.INVALID_KEY
    (status == 400 || status == 422) && EFFORT_FIELD.containsMatchIn(text) && REJECTION.containsMatchIn(text) -> ErrorKind.EFFORT_REJECTED
    (status == 400 || status == 422) && MODEL_MISSING.containsMatchIn(text) -> ErrorKind.MODEL_UNAVAILABLE
    else -> ErrorKind.OTHER
  }
}

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
  // apiKey is the OAuth access token here; long runs should pass a refreshing supplier to ChatGptClient instead.
  Provider.CHATGPT -> ChatGptClient({ apiKey }, baseUrl ?: ChatGptAuth.RESOURCE)
  Provider.GEMINI -> GeminiClient(apiKey, baseUrl ?: "https://generativelanguage.googleapis.com/v1beta")
  Provider.ANTHROPIC -> AnthropicClient(apiKey, baseUrl)
}

internal val http = OkHttpClient.Builder().readTimeout(10, TimeUnit.MINUTES).callTimeout(15, TimeUnit.MINUTES).build()
internal val json = Json { ignoreUnknownKeys = true }
private val JSON_TYPE = "application/json".toMediaType()

internal fun call(request: Request, expectJson: Boolean = true): JsonObject {
  val response = try { http.newCall(request).execute() } catch (e: java.io.IOException) { throw ProviderError(null, "Falha de rede: ${e.message}") }
  response.use {
    val body = it.body?.string().orEmpty()
    if (!it.isSuccessful) throw ProviderError(it.code, body.take(500))
    return if (expectJson) json.parseToJsonElement(body).jsonObject else JsonObject(emptyMap())
  }
}

/**
 * Responses API paid by the ChatGPT plan. Plan route rules (siwc preview limitations): store=false, stream=true,
 * no system items (instructions is fine), no max_output_tokens/temperature. [token] is called per request so a
 * refreshing supplier keeps long runs alive.
 */
class ChatGptClient(private val token: () -> String, private val base: String = ChatGptAuth.RESOURCE) : ProviderClient {
  private fun req(path: String) = Request.Builder().url("$base$path").header("Authorization", "Bearer ${token()}")

  /** No API key on this route: a rejected credential means the ChatGPT session is gone. */
  private fun <T> signedIn(block: () -> T): T = try { block() } catch (e: ProviderError) {
    if (e.kind == ErrorKind.INVALID_KEY) throw ProviderError(e.status, e.message ?: "", ErrorKind.SIGNED_OUT) else throw e
  }

  override fun listModels(): List<String> = signedIn { models() }

  override fun translate(model: String, system: String, text: String, effort: String?): Translation = signedIn { stream(model, system, text, effort) }

  private fun models(): List<String> {
    val r = call(req("/models").get().build())
    // The plan route answers {"models":[{slug, visibility}]}; fall back to the public {"data":[{id}]} shape.
    r["models"]?.jsonArray?.let { models ->
      return models.map { it.jsonObject }.filter { it["visibility"]?.jsonPrimitive?.content == "list" }.map { it["slug"]!!.jsonPrimitive.content }
    }
    return r["data"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }.sorted()
  }

  private fun stream(model: String, system: String, text: String, effort: String?): Translation {
    val body = buildJsonObject {
      put("model", model)
      put("instructions", system)
      putJsonArray("input") { addJsonObject { put("role", "user"); put("content", text) } }
      put("store", false)
      put("stream", true)
      if (effort != null) putJsonObject("reasoning") { put("effort", effort) }
    }
    val request = req("/responses").header("Accept", "text/event-stream").post(body.toString().toRequestBody(JSON_TYPE)).build()
    val response = try { http.newCall(request).execute() } catch (e: java.io.IOException) { throw ProviderError(null, "Falha de rede: ${e.message}") }
    response.use {
      if (!it.isSuccessful) throw ProviderError(it.code, it.body?.string().orEmpty().take(500))
      val out = StringBuilder()
      val source = it.body!!.source()
      while (true) {
        val line = try { source.readUtf8Line() } catch (e: java.io.IOException) { throw ProviderError(null, "Conexão caiu no meio da resposta: ${e.message}") } ?: break
        if (!line.startsWith("data:")) continue
        val data = line.removePrefix("data:").trim()
        if (data.isEmpty() || data == "[DONE]") continue
        val ev = json.parseToJsonElement(data).jsonObject
        when (ev["type"]?.jsonPrimitive?.content) {
          "response.output_text.delta" -> out.append(ev["delta"]?.jsonPrimitive?.content.orEmpty())
          "response.completed" -> {
            val r = ev["response"]?.jsonObject
            val usage = r?.get("usage")?.jsonObject
            return Translation(out.toString(), r?.get("model")?.jsonPrimitive?.content ?: model,
              usage?.get("input_tokens")?.jsonPrimitive?.longOrNull, usage?.get("output_tokens")?.jsonPrimitive?.longOrNull)
          }
          "response.failed", "error" -> {
            val err = ev["response"]?.jsonObject?.get("error") ?: ev["error"] ?: ev
            val code = (err as? JsonObject)?.get("code")?.jsonPrimitive?.content
            throw ProviderError(statusFor(code), err.toString().take(500))
          }
          "response.incomplete" -> throw ProviderError(200, "Resposta incompleta: ${ev["response"]?.jsonObject?.get("incomplete_details")}", ErrorKind.BAD_OUTPUT)
        }
      }
      throw ProviderError(null, "O stream terminou sem response.completed")
    }
  }

  /** Errors inside the stream carry a code but no HTTP status; use the status the docs give for each code. */
  private fun statusFor(code: String?) = when (code) {
    "subscription_sharing_usage_limit_exceeded" -> 429
    "subscription_sharing_user_not_eligible", "subscription_sharing_route_not_supported" -> 403
    "subscription_sharing_invalid_user" -> 401
    "subscription_sharing_unsupported_capability" -> 400
    "subscription_sharing_usage_unavailable", "subscription_sharing_user_unavailable" -> 503
    else -> 500
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
    if (out.isEmpty()) {
      val reason = r["candidates"]?.jsonArray?.firstOrNull()?.jsonObject?.get("finishReason")?.jsonPrimitive?.content
        ?: r["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitive?.content
      throw ProviderError(200, "Resposta sem texto (${reason ?: "sem motivo"})", if (reason in setOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "RECITATION", "OTHER")) ErrorKind.REFUSED else ErrorKind.BAD_OUTPUT)
    }
    val usage = r["usageMetadata"]?.jsonObject
    fun count(name: String) = usage?.get(name)?.jsonPrimitive?.longOrNull
    // Thinking tokens are billed as output but reported apart from the answer.
    val output = count("candidatesTokenCount")?.let { it + (count("thoughtsTokenCount") ?: 0) }
    return Translation(out, r["modelVersion"]?.jsonPrimitive?.content ?: model, count("promptTokenCount"), output)
  }
}

private class AnthropicClient(key: String, base: String?) : ProviderClient {
  private val client = AnthropicOkHttpClient.builder().apiKey(key).apply { if (base != null) baseUrl(base) }.build()

  override fun listModels() = sdk { client.models().list().autoPager().map { it.id() }.sorted() }

  override fun translate(model: String, system: String, text: String, effort: String?): Translation {
    val params = MessageCreateParams.builder()
      .model(model)
      .maxTokens(16000L)
      .system(system)
      .addUserMessage(text)
      .apply { if (effort != null) outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(effort)).build()) }
      .build()
    val msg = sdk { client.messages().create(params) }
    if (msg.stopReason().map { it.toString() }.orElse("") == "refusal") throw ProviderError(200, "Modelo recusou o trecho (refusal)", ErrorKind.REFUSED)
    val out = msg.content().flatMap { b -> b.text().map { listOf(it.text()) }.orElse(emptyList()) }.joinToString("")
    return Translation(out, msg.model().toString(), msg.usage().inputTokens(), msg.usage().outputTokens())
  }

  /** The SDK throws its own types; turn them into ProviderError so callers handle one kind of failure. */
  private fun <T> sdk(block: () -> T): T = try { block() } catch (e: com.anthropic.errors.AnthropicServiceException) {
    throw ProviderError(e.statusCode(), e.message ?: "erro da API")
  } catch (e: com.anthropic.errors.AnthropicIoException) {
    throw ProviderError(null, "Falha de rede: ${e.message}")
  }
}
