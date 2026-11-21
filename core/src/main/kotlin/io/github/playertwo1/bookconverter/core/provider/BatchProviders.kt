package io.github.playertwo1.bookconverter.core.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

data class BatchRequestItem(val customId: String, val systemPrompt: String, val userText: String)

enum class BatchJobState { SUBMITTED, IN_PROGRESS, COMPLETED, FAILED, EXPIRED, CANCELLED }

data class BatchStatus(
  val batchId: String,
  val state: BatchJobState,
  val doneCount: Int = 0,
  val totalCount: Int = 0,
  val errorMessage: String? = null
)

data class BatchItemResult(
  val customId: String,
  val text: String?,
  val inputTokens: Long = 0,
  val outputTokens: Long = 0,
  val errorMessage: String? = null
)

interface BatchProviderClient {
  suspend fun submitBatch(model: String, effort: String? = null, items: List<BatchRequestItem>): String
  suspend fun pollBatch(batchId: String): BatchStatus
  suspend fun fetchResults(batchId: String): List<BatchItemResult>
}

val defaultBatchHttp: OkHttpClient by lazy {
  OkHttpClient.Builder()
    .readTimeout(10, TimeUnit.MINUTES)
    .callTimeout(15, TimeUnit.MINUTES)
    .build()
}

private val BATCH_JSON = Json { ignoreUnknownKeys = true }
private val MEDIA_TYPE_JSON = "application/json".toMediaType()
private val MEDIA_TYPE_JSONL = "application/jsonl".toMediaType()

private fun executeHttp(http: OkHttpClient, request: Request): Response {
  return try {
    http.newCall(request).execute()
  } catch (e: IOException) {
    throw ProviderError(null, "Falha de rede: ${e.message}")
  }
}

private fun executeJson(http: OkHttpClient, request: Request): JsonObject {
  val response = executeHttp(http, request)
  response.use {
    val body = it.body?.string().orEmpty()
    if (!it.isSuccessful) throw ProviderError(it.code, body.take(500))
    return if (body.isNotBlank()) BATCH_JSON.parseToJsonElement(body).jsonObject else JsonObject(emptyMap())
  }
}

private fun executeText(http: OkHttpClient, request: Request): String {
  val response = executeHttp(http, request)
  response.use {
    val body = it.body?.string().orEmpty()
    if (!it.isSuccessful) throw ProviderError(it.code, body.take(500))
    return body
  }
}

class OpenAiBatchClient(
  private val apiKey: String,
  baseUrl: String? = null,
  private val http: OkHttpClient = defaultBatchHttp
) : BatchProviderClient {
  private val base = (baseUrl ?: "https://api.openai.com/v1").removeSuffix("/")

  override suspend fun submitBatch(model: String, effort: String?, items: List<BatchRequestItem>): String =
    withContext(Dispatchers.IO) {
      require(items.isNotEmpty()) { "items cannot be empty" }

      val jsonl = items.joinToString("\n") { item ->
        buildJsonObject {
          put("custom_id", item.customId)
          put("method", "POST")
          put("url", "/v1/responses")
          put("body", buildJsonObject {
            put("model", model)
            put("instructions", item.systemPrompt)
            put("input", item.userText)
            if (effort != null) putJsonObject("reasoning") { put("effort", effort) }
          })
        }.toString()
      }

      val multipart = MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart("purpose", "batch")
        .addFormDataPart("file", "batch.jsonl", jsonl.toByteArray(Charsets.UTF_8).toRequestBody(MEDIA_TYPE_JSONL))
        .build()

      val uploadReq = Request.Builder()
        .url("$base/files")
        .header("Authorization", "Bearer $apiKey")
        .post(multipart)
        .build()

      val uploadResp = executeJson(http, uploadReq)
      val fileId = uploadResp["id"]?.jsonPrimitive?.content
        ?: throw ProviderError(null, "OpenAI não retornou id do arquivo")

      val batchBody = buildJsonObject {
        put("input_file_id", fileId)
        put("endpoint", "/v1/responses")
        put("completion_window", "24h")
      }

      val batchReq = Request.Builder()
        .url("$base/batches")
        .header("Authorization", "Bearer $apiKey")
        .post(batchBody.toString().toRequestBody(MEDIA_TYPE_JSON))
        .build()

      val batchResp = executeJson(http, batchReq)
      batchResp["id"]?.jsonPrimitive?.content
        ?: throw ProviderError(null, "OpenAI não retornou id do batch")
    }

  override suspend fun pollBatch(batchId: String): BatchStatus = withContext(Dispatchers.IO) {
    val req = Request.Builder()
      .url("$base/batches/$batchId")
      .header("Authorization", "Bearer $apiKey")
      .get()
      .build()

    val resp = executeJson(http, req)
    val status = resp["status"]?.jsonPrimitive?.content
    val state = when (status) {
      "validating", "in_progress", "finalizing" -> BatchJobState.IN_PROGRESS
      "completed" -> BatchJobState.COMPLETED
      "failed" -> BatchJobState.FAILED
      "expired" -> BatchJobState.EXPIRED
      "cancelling", "cancelled" -> BatchJobState.CANCELLED
      else -> BatchJobState.SUBMITTED
    }

    val requestCounts = resp["request_counts"]?.jsonObject
    val total = requestCounts?.get("total")?.jsonPrimitive?.intOrNull ?: 0
    val completed = requestCounts?.get("completed")?.jsonPrimitive?.intOrNull ?: 0
    val errors = resp["errors"]?.jsonObject
    val errorMsg = errors?.get("data")?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonPrimitive?.content
      ?: errors?.get("message")?.jsonPrimitive?.content

    BatchStatus(
      batchId = batchId,
      state = state,
      doneCount = completed,
      totalCount = total,
      errorMessage = errorMsg
    )
  }

  override suspend fun fetchResults(batchId: String): List<BatchItemResult> = withContext(Dispatchers.IO) {
    val pollReq = Request.Builder()
      .url("$base/batches/$batchId")
      .header("Authorization", "Bearer $apiKey")
      .get()
      .build()

    val batchInfo = executeJson(http, pollReq)
    val outputFileId = batchInfo["output_file_id"]?.jsonPrimitive?.content
    val errorFileId = batchInfo["error_file_id"]?.jsonPrimitive?.content

    val results = mutableListOf<BatchItemResult>()

    if (!outputFileId.isNullOrEmpty()) {
      val contentReq = Request.Builder()
        .url("$base/files/$outputFileId/content")
        .header("Authorization", "Bearer $apiKey")
        .get()
        .build()
      val content = executeText(http, contentReq)
      parseOpenAiJsonl(content, results)
    }

    if (!errorFileId.isNullOrEmpty() && results.isEmpty()) {
      val errReq = Request.Builder()
        .url("$base/files/$errorFileId/content")
        .header("Authorization", "Bearer $apiKey")
        .get()
        .build()
      val errContent = executeText(http, errReq)
      parseOpenAiJsonl(errContent, results)
    }

    results
  }

  private fun parseOpenAiJsonl(content: String, out: MutableList<BatchItemResult>) {
    content.lineSequence().forEach { line ->
      if (line.isBlank()) return@forEach
      val obj = BATCH_JSON.parseToJsonElement(line).jsonObject
      val customId = obj["custom_id"]?.jsonPrimitive?.content.orEmpty()
      val errorObj = obj["error"]?.jsonObject
      val responseObj = obj["response"]?.jsonObject

      if (errorObj != null) {
        val msg = errorObj["message"]?.jsonPrimitive?.content ?: errorObj.toString()
        out.add(BatchItemResult(customId = customId, text = null, errorMessage = msg))
      } else if (responseObj != null) {
        val statusCode = responseObj["status_code"]?.jsonPrimitive?.intOrNull ?: 200
        val body = responseObj["body"]?.jsonObject
        if (statusCode in 200..299 && body != null) {
          val text = body["output"]?.jsonArray?.map { it.jsonObject }
            ?.filter { it["type"]?.jsonPrimitive?.content == "message" }
            ?.flatMap { it["content"]?.jsonArray?.map { c -> c.jsonObject }.orEmpty() }
            ?.filter { it["type"]?.jsonPrimitive?.content == "output_text" }
            ?.joinToString("") { it["text"]?.jsonPrimitive?.content.orEmpty() }
            ?: body["choices"]?.jsonArray?.firstOrNull()?.jsonObject
              ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content

          val usage = body["usage"]?.jsonObject
          val inTokens = usage?.get("input_tokens")?.jsonPrimitive?.longOrNull
            ?: usage?.get("prompt_tokens")?.jsonPrimitive?.longOrNull ?: 0L
          val outTokens = usage?.get("output_tokens")?.jsonPrimitive?.longOrNull
            ?: usage?.get("completion_tokens")?.jsonPrimitive?.longOrNull ?: 0L

          out.add(BatchItemResult(customId = customId, text = text, inputTokens = inTokens, outputTokens = outTokens))
        } else {
          val err = body?.get("error")?.jsonObject?.get("message")?.jsonPrimitive?.content ?: "HTTP $statusCode"
          out.add(BatchItemResult(customId = customId, text = null, errorMessage = err))
        }
      }
    }
  }
}

class AnthropicBatchClient(
  private val apiKey: String,
  baseUrl: String? = null,
  private val http: OkHttpClient = defaultBatchHttp
) : BatchProviderClient {
  private val base = (baseUrl ?: "https://api.anthropic.com").removeSuffix("/v1").removeSuffix("/")

  override suspend fun submitBatch(model: String, effort: String?, items: List<BatchRequestItem>): String =
    withContext(Dispatchers.IO) {
      require(items.isNotEmpty()) { "items cannot be empty" }

      val requestsArray = items.map { item ->
        buildJsonObject {
          put("custom_id", item.customId)
          putJsonObject("params") {
            put("model", model)
            put("max_tokens", 16000L)
            put("system", item.systemPrompt)
            putJsonArray("messages") {
              addJsonObject {
                put("role", "user")
                put("content", item.userText)
              }
            }
            if (effort != null) {
              putJsonObject("output_config") {
                put("effort", effort)
              }
            }
          }
        }
      }

      val body = buildJsonObject {
        putJsonArray("requests") {
          requestsArray.forEach { add(it) }
        }
      }

      val req = Request.Builder()
        .url("$base/v1/messages/batches")
        .header("x-api-key", apiKey)
        .header("anthropic-version", "2023-06-01")
        .header("anthropic-beta", "message-batches-2024-09-24")
        .post(body.toString().toRequestBody(MEDIA_TYPE_JSON))
        .build()

      val resp = executeJson(http, req)
      resp["id"]?.jsonPrimitive?.content
        ?: throw ProviderError(null, "Anthropic não retornou id do batch")
    }

  override suspend fun pollBatch(batchId: String): BatchStatus = withContext(Dispatchers.IO) {
    val req = Request.Builder()
      .url("$base/v1/messages/batches/$batchId")
      .header("x-api-key", apiKey)
      .header("anthropic-version", "2023-06-01")
      .header("anthropic-beta", "message-batches-2024-09-24")
      .get()
      .build()

    val resp = executeJson(http, req)
    val processingStatus = resp["processing_status"]?.jsonPrimitive?.content
    val state = when (processingStatus) {
      "in_progress" -> BatchJobState.IN_PROGRESS
      "ended" -> BatchJobState.COMPLETED
      "canceling" -> BatchJobState.CANCELLED
      else -> BatchJobState.SUBMITTED
    }

    val requestCounts = resp["request_counts"]?.jsonObject
    val processing = requestCounts?.get("processing")?.jsonPrimitive?.intOrNull ?: 0
    val succeeded = requestCounts?.get("succeeded")?.jsonPrimitive?.intOrNull ?: 0
    val errored = requestCounts?.get("errored")?.jsonPrimitive?.intOrNull ?: 0
    val canceled = requestCounts?.get("canceled")?.jsonPrimitive?.intOrNull ?: 0
    val expired = requestCounts?.get("expired")?.jsonPrimitive?.intOrNull ?: 0

    val total = processing + succeeded + errored + canceled + expired
    val done = succeeded + errored + canceled + expired
    val errorMsg = resp["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content

    BatchStatus(
      batchId = batchId,
      state = state,
      doneCount = done,
      totalCount = total,
      errorMessage = errorMsg
    )
  }

  override suspend fun fetchResults(batchId: String): List<BatchItemResult> = withContext(Dispatchers.IO) {
    val req = Request.Builder()
      .url("$base/v1/messages/batches/$batchId/results")
      .header("x-api-key", apiKey)
      .header("anthropic-version", "2023-06-01")
      .header("anthropic-beta", "message-batches-2024-09-24")
      .get()
      .build()

    val content = executeText(http, req)
    val results = mutableListOf<BatchItemResult>()

    content.lineSequence().forEach { line ->
      if (line.isBlank()) return@forEach
      val obj = BATCH_JSON.parseToJsonElement(line).jsonObject
      val customId = obj["custom_id"]?.jsonPrimitive?.content.orEmpty()
      val resultObj = obj["result"]?.jsonObject
      val type = resultObj?.get("type")?.jsonPrimitive?.content

      if (type == "succeeded") {
        val message = resultObj["message"]?.jsonObject
        val text = message?.get("content")?.jsonArray?.map { it.jsonObject }
          ?.filter { it["type"]?.jsonPrimitive?.content == "text" }
          ?.joinToString("") { it["text"]?.jsonPrimitive?.content.orEmpty() }

        val usage = message?.get("usage")?.jsonObject
        val inTokens = usage?.get("input_tokens")?.jsonPrimitive?.longOrNull ?: 0L
        val outTokens = usage?.get("output_tokens")?.jsonPrimitive?.longOrNull ?: 0L

        results.add(BatchItemResult(customId = customId, text = text, inputTokens = inTokens, outputTokens = outTokens))
      } else {
        val errorObj = resultObj?.get("error")?.jsonObject
        val errorMsg = errorObj?.get("message")?.jsonPrimitive?.content ?: "Resultado: $type"
        results.add(BatchItemResult(customId = customId, text = null, errorMessage = errorMsg))
      }
    }

    results
  }
}

class GeminiBatchClient(
  private val apiKey: String,
  baseUrl: String? = null,
  private val http: OkHttpClient = defaultBatchHttp
) : BatchProviderClient {
  private val base = (baseUrl ?: "https://generativelanguage.googleapis.com").removeSuffix("/v1beta").removeSuffix("/")

  private fun batchUrl(batchId: String): String {
    val clean = batchId.trim().removePrefix("/").removePrefix("v1beta/").removePrefix("/")
    return "$base/v1beta/$clean"
  }

  override suspend fun submitBatch(model: String, effort: String?, items: List<BatchRequestItem>): String =
    withContext(Dispatchers.IO) {
      require(items.isNotEmpty()) { "items cannot be empty" }
      val cleanModel = model.removePrefix("models/")

      val requestsArray = items.map { item ->
        buildJsonObject {
          putJsonObject("metadata") {
            put("key", item.customId)
          }
          putJsonObject("request") {
            putJsonObject("systemInstruction") {
              putJsonArray("parts") {
                addJsonObject { put("text", item.systemPrompt) }
              }
            }
            putJsonArray("contents") {
              addJsonObject {
                put("role", "user")
                putJsonArray("parts") {
                  addJsonObject { put("text", item.userText) }
                }
              }
            }
            if (effort != null) {
              putJsonObject("generationConfig") {
                putJsonObject("thinkingConfig") {
                  put("thinkingLevel", effort)
                }
              }
            }
          }
        }
      }

      val body = buildJsonObject {
        putJsonObject("batch") {
          putJsonObject("inputConfig") {
            putJsonObject("requests") {
              putJsonArray("requests") {
                requestsArray.forEach { add(it) }
              }
            }
          }
        }
      }

      val req = Request.Builder()
        .url("$base/v1beta/models/$cleanModel:batchGenerateContent")
        .header("x-goog-api-key", apiKey)
        .post(body.toString().toRequestBody(MEDIA_TYPE_JSON))
        .build()

      val resp = executeJson(http, req)
      val name = resp["name"]?.jsonPrimitive?.content
        ?: resp["batch"]?.jsonObject?.get("name")?.jsonPrimitive?.content
        ?: throw ProviderError(null, "Gemini não retornou o nome do batch")

      name
    }

  override suspend fun pollBatch(batchId: String): BatchStatus = withContext(Dispatchers.IO) {
    val req = Request.Builder()
      .url(batchUrl(batchId))
      .header("x-goog-api-key", apiKey)
      .get()
      .build()

    val resp = executeJson(http, req)
    val rawState = resp["state"]?.jsonPrimitive?.content
      ?: resp["metadata"]?.jsonObject?.get("state")?.jsonPrimitive?.content
    val done = resp["done"]?.jsonPrimitive?.booleanOrNull ?: false

    val state = when {
      rawState != null -> when (rawState.uppercase().removePrefix("BATCH_STATE_").removePrefix("JOB_STATE_").removePrefix("STATE_")) {
        "SUCCEEDED", "COMPLETED" -> BatchJobState.COMPLETED
        "RUNNING", "ACTIVE", "PROCESSING" -> BatchJobState.IN_PROGRESS
        "PENDING", "QUEUED", "SUBMITTED" -> BatchJobState.IN_PROGRESS
        "FAILED" -> BatchJobState.FAILED
        "CANCELLED", "CANCELED" -> BatchJobState.CANCELLED
        "EXPIRED" -> BatchJobState.EXPIRED
        else -> if (done) BatchJobState.COMPLETED else BatchJobState.IN_PROGRESS
      }
      done -> BatchJobState.COMPLETED
      else -> BatchJobState.IN_PROGRESS
    }

    val metadata = resp["metadata"]?.jsonObject
    val total = metadata?.get("totalItemCount")?.jsonPrimitive?.intOrNull
      ?: metadata?.get("totalCount")?.jsonPrimitive?.intOrNull
      ?: metadata?.get("itemCounts")?.jsonObject?.get("total")?.jsonPrimitive?.intOrNull
      ?: 0
    val completed = metadata?.get("completedItemCount")?.jsonPrimitive?.intOrNull
      ?: metadata?.get("completedCount")?.jsonPrimitive?.intOrNull
      ?: metadata?.get("itemCounts")?.jsonObject?.get("completed")?.jsonPrimitive?.intOrNull
      ?: if (state == BatchJobState.COMPLETED && total > 0) total else 0

    val errorMsg = resp["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
      ?: metadata?.get("errorMessage")?.jsonPrimitive?.content

    BatchStatus(
      batchId = batchId,
      state = state,
      doneCount = completed,
      totalCount = total,
      errorMessage = errorMsg
    )
  }

  override suspend fun fetchResults(batchId: String): List<BatchItemResult> = withContext(Dispatchers.IO) {
    val req = Request.Builder()
      .url(batchUrl(batchId))
      .header("x-goog-api-key", apiKey)
      .get()
      .build()

    val resp = executeJson(http, req)
    val inlinedList = resp["response"]?.jsonObject?.get("inlinedResponses")?.jsonObject?.get("inlinedResponses")?.jsonArray
      ?: resp["output"]?.jsonObject?.get("inlinedResponses")?.jsonObject?.get("inlinedResponses")?.jsonArray
      ?: resp["inlinedResponses"]?.jsonObject?.get("inlinedResponses")?.jsonArray
      ?: resp["inlinedResponses"]?.jsonArray
      ?: emptyList()

    inlinedList.map { itemElem ->
      val itemObj = itemElem.jsonObject
      val customId = itemObj["metadata"]?.jsonObject?.get("key")?.jsonPrimitive?.content.orEmpty()
      val itemError = itemObj["error"]?.jsonObject

      if (itemError != null) {
        val errMsg = itemError["message"]?.jsonPrimitive?.content ?: itemError.toString()
        BatchItemResult(customId = customId, text = null, errorMessage = errMsg)
      } else {
        val itemResp = itemObj["response"]?.jsonObject ?: itemObj
        val parts = itemResp["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
          ?.get("content")?.jsonObject?.get("parts")?.jsonArray.orEmpty()

        val text = parts.map { it.jsonObject }
          .filter { it["thought"]?.jsonPrimitive?.content != "true" }
          .joinToString("") { it["text"]?.jsonPrimitive?.content.orEmpty() }

        val usage = itemResp["usageMetadata"]?.jsonObject
        val inTokens = usage?.get("promptTokenCount")?.jsonPrimitive?.longOrNull ?: 0L
        val candidateTokens = usage?.get("candidatesTokenCount")?.jsonPrimitive?.longOrNull ?: 0L
        val thoughtsTokens = usage?.get("thoughtsTokenCount")?.jsonPrimitive?.longOrNull ?: 0L
        val outTokens = candidateTokens + thoughtsTokens

        if (text.isEmpty()) {
          val finishReason = itemResp["candidates"]?.jsonArray?.firstOrNull()?.jsonObject?.get("finishReason")?.jsonPrimitive?.content
            ?: itemResp["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitive?.content
          val errMsg = if (finishReason != null) "Recusado pelo modelo ($finishReason)" else "Sem texto gerado"
          BatchItemResult(customId = customId, text = null, inputTokens = inTokens, outputTokens = outTokens, errorMessage = errMsg)
        } else {
          BatchItemResult(customId = customId, text = text, inputTokens = inTokens, outputTokens = outTokens)
        }
      }
    }
  }
}

fun batchProviderClient(
  provider: Provider,
  apiKey: String,
  baseUrl: String? = null,
  http: OkHttpClient = defaultBatchHttp
): BatchProviderClient = when (provider) {
  Provider.OPENAI -> OpenAiBatchClient(apiKey, baseUrl, http)
  Provider.CHATGPT -> throw ProviderError(null, "Batch API não é suportada no plano ChatGPT", ErrorKind.PLAN_UNAVAILABLE)
  Provider.ANTHROPIC -> AnthropicBatchClient(apiKey, baseUrl, http)
  Provider.GEMINI -> GeminiBatchClient(apiKey, baseUrl, http)
  Provider.OPENROUTER -> throw ProviderError(null, "Batch API não é suportada pelo OpenRouter / Genérico", ErrorKind.PLAN_UNAVAILABLE)
}
