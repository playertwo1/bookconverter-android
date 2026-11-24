package io.github.playertwo1.bookconverter.core.metadata

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class MetadataSource(val displayName: String) {
  OPEN_LIBRARY("Open Library"),
  WIKIDATA("Wikidata"),
  HARDCOVER("Hardcover"),
}

enum class MetadataField(val storageKey: String, val labelPtBr: String) {
  TITLE("title", "Título"),
  AUTHOR("author", "Autor"),
  ISBN("isbn", "ISBN"),
  PUBLISHER("publisher", "Editora"),
  PUBLISHED_DATE("publishedDate", "Data de publicação"),
  LANGUAGE("language", "Idioma"),
  SUMMARY("summary", "Resumo breve do livro"),
  SERIES_NAME("seriesName", "Nome da série"),
  SERIES_POSITION("seriesPosition", "Posição na série"),
  SERIES_SUMMARY("seriesSummary", "Resumo breve da série"),
}

data class BookMetadataQuery(
  val isbn: String? = null,
  val title: String? = null,
  val author: String? = null,
) {
  val normalizedIsbn: String? = normalizeIsbn(isbn)
  val rawIsbn: String? = isbn?.trim()?.takeIf { it.isNotEmpty() }
  val cleanTitle: String? = title?.trim()?.takeIf { it.isNotEmpty() }
  val cleanAuthor: String? = author?.trim()?.takeIf { it.isNotEmpty() }
  val isBlank: Boolean get() = normalizedIsbn == null && cleanTitle == null && cleanAuthor == null
}

data class SourceBookCandidate(
  val source: MetadataSource,
  val title: String? = null,
  val author: String? = null,
  val isbn: String? = null,
  val publisher: String? = null,
  val publishedDate: String? = null,
  val language: String? = null,
  val summary: String? = null,
  val seriesName: String? = null,
  val seriesPosition: String? = null,
  val seriesSummary: String? = null,
  val openLibraryKey: String? = null,
  val wikidataQid: String? = null,
  val hardcoverId: String? = null,
  val seriesOpenLibraryKey: String? = null,
  val seriesWikidataQid: String? = null,
  val seriesHardcoverId: String? = null,
  val characters: List<String> = emptyList(),
  val places: List<String> = emptyList(),
)

sealed interface SourceSearchResult {
  val source: MetadataSource

  data class Success(
    override val source: MetadataSource,
    val candidates: List<SourceBookCandidate>,
  ) : SourceSearchResult

  data class Skipped(
    override val source: MetadataSource,
    val reason: String,
  ) : SourceSearchResult

  data class Failed(
    override val source: MetadataSource,
    val message: String,
  ) : SourceSearchResult
}

interface BookMetadataProvider {
  val source: MetadataSource
  suspend fun search(query: BookMetadataQuery): SourceSearchResult
}

object MetadataUserAgent {
  const val REPOSITORY_URL = "https://github.com/playertwo1/bookconverter-android"

  fun forApp(appVersion: String): String {
    val cleanVersion = appVersion.trim().ifEmpty { "1.0" }
    return "Bookconverter-Android/$cleanVersion ($REPOSITORY_URL)"
  }
}

class MetadataRateLimiter(
  private val minIntervalMs: Long,
  private val clockMs: () -> Long = System::currentTimeMillis,
  private val sleepFn: suspend (Long) -> Unit = { delay(it) },
) {
  private val mutex = Mutex()
  private var lastRequestAtMs: Long = Long.MIN_VALUE

  suspend fun acquire() {
    if (minIntervalMs <= 0L) return
    mutex.withLock {
      val now = clockMs()
      if (lastRequestAtMs != Long.MIN_VALUE) {
        val elapsed = now - lastRequestAtMs
        val waitMs = minIntervalMs - elapsed
        if (waitMs > 0L) {
          sleepFn(waitMs)
        }
      }
      lastRequestAtMs = clockMs()
    }
  }
}

internal fun defaultMetadataHttpClient(timeoutSeconds: Long = 15L): OkHttpClient =
  OkHttpClient.Builder()
    .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
    .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
    .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
    .build()

internal suspend fun OkHttpClient.executeCancellable(request: Request): Response =
  suspendCancellableCoroutine { cont ->
    val call = newCall(request)
    cont.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
      override fun onFailure(call: Call, e: IOException) {
        if (cont.isActive) cont.resumeWithException(e)
      }

      override fun onResponse(call: Call, response: Response) {
        if (cont.isActive) {
          cont.resume(response)
        } else {
          response.close()
        }
      }
    })
  }

fun normalizeIsbn(raw: String?): String? {
  val cleaned = raw?.replace(Regex("[^0-9Xx]"), "")?.uppercase(Locale.ROOT).orEmpty()
  return when {
    cleaned.length == 13 && cleaned.all { it.isDigit() } -> cleaned
    cleaned.length == 10 && cleaned.substring(0, 9).all { it.isDigit() } && (cleaned[9].isDigit() || cleaned[9] == 'X') -> cleaned
    else -> null
  }
}

fun parseRetryAfterMillis(headerValue: String?, nowEpochMs: Long = System.currentTimeMillis()): Long? {
  val trimmed = headerValue?.trim()?.takeIf { it.isNotEmpty() } ?: return null
  trimmed.toLongOrNull()?.let { seconds ->
    return (seconds.coerceAtLeast(0L)) * 1000L
  }
  return runCatching {
    val targetMs = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    (targetMs - nowEpochMs).coerceAtLeast(0L)
  }.getOrNull()
}

private val HTML_TAG_REGEX = Regex("<[^>]+>")
private val WHITESPACE_REGEX = Regex("\\s+")
private val SENTENCE_END_REGEX = Regex("""[.!?](?:\s|$)""")

/**
 * Normalizes and trims a source synopsis into a clean brief summary up to [maxChars] characters
 * without calling any AI provider.
 */
fun trimToBriefSummary(raw: String?, maxChars: Int = 400): String? {
  val cleaned = raw
    ?.replace(HTML_TAG_REGEX, " ")
    ?.replace("\r\n", " ")
    ?.replace('\n', ' ')
    ?.replace(WHITESPACE_REGEX, " ")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: return null

  if (cleaned.length <= maxChars) return cleaned

  val window = cleaned.substring(0, maxChars)
  val sentenceEnds = SENTENCE_END_REGEX.findAll(window).toList()
  val lastSentence = sentenceEnds.lastOrNull { it.range.first >= maxChars / 3 }
    ?: sentenceEnds.lastOrNull()
  if (lastSentence != null) {
    val cut = window.substring(0, lastSentence.range.first + 1).trim()
    if (cut.isNotEmpty()) return cut
  }

  val budget = (maxChars - 3).coerceAtLeast(1)
  val slice = cleaned.substring(0, budget)
  val lastSpace = slice.lastIndexOf(' ')
  val base = if (lastSpace >= budget / 2) slice.substring(0, lastSpace) else slice
  return base.trimEnd(' ', ',', ';', ':') + "..."
}

fun normalizeSeriesPosition(raw: String?): String? {
  val trimmed = raw?.trim()?.removePrefix("#")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
  val number = trimmed.toDoubleOrNull() ?: return trimmed
  return if (number == number.toLong().toDouble()) {
    number.toLong().toString()
  } else {
    number.toString()
  }
}

fun serializeMetadataSources(sources: Map<MetadataField, MetadataSource>): String? {
  if (sources.isEmpty()) return null
  val obj = buildJsonObject {
    sources.entries
      .sortedBy { it.key.storageKey }
      .forEach { (field, source) ->
        put(field.storageKey, JsonPrimitive(source.name))
      }
  }
  return obj.toString()
}

fun parseMetadataSources(jsonText: String?): Map<MetadataField, MetadataSource> {
  if (jsonText.isNullOrBlank()) return emptyMap()
  val obj: JsonObject = runCatching { Json.parseToJsonElement(jsonText).jsonObject }.getOrNull() ?: return emptyMap()
  val fieldsByKey = MetadataField.entries.associateBy { it.storageKey }
  val result = linkedMapOf<MetadataField, MetadataSource>()
  for ((key, value) in obj) {
    val field = fieldsByKey[key] ?: continue
    val sourceName = runCatching { value.jsonPrimitive.contentOrNull }.getOrNull() ?: continue
    val source = MetadataSource.entries.firstOrNull { it.name == sourceName } ?: continue
    result[field] = source
  }
  return result
}
