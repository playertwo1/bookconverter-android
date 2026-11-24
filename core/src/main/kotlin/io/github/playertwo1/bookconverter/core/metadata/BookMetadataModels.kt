package io.github.playertwo1.bookconverter.core.metadata

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.text.Normalizer
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

data class MetadataNamedItem(
  val name: String,
  val description: String? = null,
  val aliases: List<String> = emptyList(),
  val source: MetadataSource,
  val additionalSources: Set<MetadataSource> = emptySet(),
  val fromFreeText: Boolean = (source == MetadataSource.OPEN_LIBRARY),
) {
  val allSources: List<MetadataSource>
    get() = (setOf(source) + additionalSources).sortedBy { it.ordinal }
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
  val alternateTitle: String? = null,
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
  val characters: List<MetadataNamedItem> = emptyList(),
  val places: List<MetadataNamedItem> = emptyList(),
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
private val DIACRITICS_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")
private val NON_ENTITY_ALNUM_REGEX = Regex("[^\\p{L}\\p{N}]+")
private val OL_DOUBLE_DASH_SUFFIX_REGEX = Regex("""\s*--\s*.*$""")
private val OL_PAREN_NOISE_REGEX = Regex(
  """\s*\((?:fictitious|fictional|imaginary)\s+[^)]*\)\s*$""",
  RegexOption.IGNORE_CASE,
)
private val OL_DATES_SUFFIX_REGEX = Regex(
  """,?\s*(?:b\.|d\.|ca\.|fl\.)?\s*\d{3,4}\s*-\s*(?:\d{0,4})?\s*$""",
  RegexOption.IGNORE_CASE,
)
private val OL_GENERIC_NOISE = setOf(
  "fiction",
  "juvenile fiction",
  "science fiction",
  "fantasy fiction",
  "fictitious characters",
  "fictional characters",
  "imaginary places",
  "imaginary societies",
  "stories",
  "novels",
)
private val PERSON_SUFFIX_AFTER_COMMA = setOf(
  "jr", "sr", "ii", "iii", "iv", "v", "duke", "baron", "lord", "lady", "king", "queen", "emperor", "prince", "princess",
)

/**
 * Accent-insensitive and case-insensitive key used to deduplicate characters and places across sources.
 */
fun normalizeEntityComparisonKey(raw: String): String {
  val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFD)
  return DIACRITICS_REGEX.replace(decomposed, "")
    .lowercase(Locale.ROOT)
    .replace(NON_ENTITY_ALNUM_REGEX, " ")
    .trim()
}

/**
 * Cleans noisy Library-of-Congress / Open Library free-text tags (e.g. "Atreides, Paul (Fictitious character)",
 * "Arrakis (Imaginary place) -- Fiction") into a readable display name, or null if noise/empty.
 */
fun normalizeOpenLibraryFreeTextEntity(raw: String, isPerson: Boolean): String? {
  var value = raw
    .replace(WHITESPACE_REGEX, " ")
    .trim()
    .trim('"', '\'', '[', ']', ';', '.')
    .trim()
  if (value.isEmpty()) return null

  value = OL_DOUBLE_DASH_SUFFIX_REGEX.replace(value, "").trim()
  // Strip trailing catalog parentheticals (may appear more than once in messy records)
  repeat(2) {
    value = OL_PAREN_NOISE_REGEX.replace(value, "").trim()
  }
  value = OL_DATES_SUFFIX_REGEX.replace(value, "").trim()
  value = value.trim(',', ';', ':', '.', '"', '\'').replace(WHITESPACE_REGEX, " ").trim()

  if (isPerson && value.contains(',')) {
    val parts = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.size == 2) {
      val firstWordRight = parts[1].substringBefore(' ').trimEnd('.').lowercase(Locale.ROOT)
      if (firstWordRight !in PERSON_SUFFIX_AFTER_COMMA && !parts[0].contains(' ')) {
        value = "${parts[1]} ${parts[0]}".replace(WHITESPACE_REGEX, " ").trim()
      }
    }
  }

  val key = normalizeEntityComparisonKey(value)
  if (key.length < 2 || key.all { it.isDigit() || it == ' ' } || key in OL_GENERIC_NOISE) {
    return null
  }
  return value
}

private val NAMED_ITEM_SOURCE_PRIORITY = listOf(
  MetadataSource.WIKIDATA,
  MetadataSource.HARDCOVER,
  MetadataSource.OPEN_LIBRARY,
)

/**
 * Merges and deduplicates characters or locations across sources (accent-insensitive and case-insensitive).
 * Structured sources (Wikidata, Hardcover) take precedence over free-text Open Library entries while
 * preserving provenance from every contributing source.
 */
fun mergeNamedItems(
  existing: List<MetadataNamedItem>,
  incoming: List<MetadataNamedItem>,
): List<MetadataNamedItem> {
  val merged = linkedMapOf<String, MetadataNamedItem>()

  fun upsert(item: MetadataNamedItem, preserveExistingName: Boolean) {
    val cleanName = item.name.replace(WHITESPACE_REGEX, " ").trim()
    if (cleanName.isEmpty()) return
    val key = normalizeEntityComparisonKey(cleanName)
    if (key.isEmpty()) return
    val cleanDesc = trimToBriefSummary(item.description, maxChars = 160)
    val cleanAliases = item.aliases
      .map { it.replace(WHITESPACE_REGEX, " ").trim() }
      .filter { it.isNotEmpty() && normalizeEntityComparisonKey(it) != key }
      .distinctBy { normalizeEntityComparisonKey(it) }
      .take(4)

    val normalizedItem = item.copy(
      name = cleanName,
      description = cleanDesc,
      aliases = cleanAliases,
    )

    val prev = merged[key]
    if (prev == null) {
      merged[key] = normalizedItem
      return
    }

    val prevRank = NAMED_ITEM_SOURCE_PRIORITY.indexOf(prev.source).let { if (it == -1) Int.MAX_VALUE else it }
    val newRank = NAMED_ITEM_SOURCE_PRIORITY.indexOf(normalizedItem.source).let { if (it == -1) Int.MAX_VALUE else it }
    val incomingWinsSource = newRank < prevRank

    val primarySource = if (incomingWinsSource) normalizedItem.source else prev.source
    val allSources = (setOf(prev.source) + prev.additionalSources + setOf(normalizedItem.source) + normalizedItem.additionalSources) - primarySource
    val chosenName = when {
      preserveExistingName -> prev.name
      incomingWinsSource -> normalizedItem.name
      else -> prev.name
    }
    val chosenDesc = when {
      incomingWinsSource && normalizedItem.description != null -> normalizedItem.description
      prev.description != null -> prev.description
      else -> normalizedItem.description
    }
    val combinedAliases = (prev.aliases + normalizedItem.aliases)
      .filter { normalizeEntityComparisonKey(it) != normalizeEntityComparisonKey(chosenName) }
      .distinctBy { normalizeEntityComparisonKey(it) }
      .take(4)
    val onlyFreeText = (setOf(primarySource) + allSources).all { it == MetadataSource.OPEN_LIBRARY } &&
      (prev.fromFreeText && normalizedItem.fromFreeText)

    merged[key] = MetadataNamedItem(
      name = chosenName,
      description = chosenDesc,
      aliases = combinedAliases,
      source = primarySource,
      additionalSources = allSources,
      fromFreeText = onlyFreeText,
    )
  }

  existing.forEach { upsert(it, preserveExistingName = true) }
  // Sort incoming so structured sources land first in deterministic order
  val sortedIncoming = incoming.sortedBy {
    NAMED_ITEM_SOURCE_PRIORITY.indexOf(it.source).let { idx -> if (idx == -1) Int.MAX_VALUE else idx }
  }
  sortedIncoming.forEach { upsert(it, preserveExistingName = existing.isNotEmpty() && normalizeEntityComparisonKey(it.name) in merged) }
  return merged.values.toList()
}

fun serializeNamedItems(items: List<MetadataNamedItem>): String? {
  if (items.isEmpty()) return null
  val arr = buildJsonArray {
    items.forEach { item ->
      add(
        buildJsonObject {
          put("name", JsonPrimitive(item.name))
          item.description?.takeIf { it.isNotBlank() }?.let { put("description", JsonPrimitive(it)) }
          if (item.aliases.isNotEmpty()) {
            put(
              "aliases",
              buildJsonArray { item.aliases.forEach { add(JsonPrimitive(it)) } },
            )
          }
          put("source", JsonPrimitive(item.source.name))
          if (item.additionalSources.isNotEmpty()) {
            put(
              "additionalSources",
              buildJsonArray {
                item.additionalSources.sortedBy { it.ordinal }.forEach { add(JsonPrimitive(it.name)) }
              },
            )
          }
          put("fromFreeText", JsonPrimitive(item.fromFreeText))
        }
      )
    }
  }
  return arr.toString()
}

fun parseNamedItems(jsonText: String?): List<MetadataNamedItem> {
  if (jsonText.isNullOrBlank()) return emptyList()
  val arr = runCatching { Json.parseToJsonElement(jsonText).jsonArray }.getOrNull() ?: return emptyList()
  return arr.mapNotNull { el ->
    val obj = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
    val name = runCatching { obj["name"]?.jsonPrimitive?.contentOrNull?.trim() }.getOrNull()
      ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
    val description = runCatching { obj["description"]?.jsonPrimitive?.contentOrNull?.trim() }.getOrNull()
      ?.takeIf { it.isNotEmpty() }
    val aliases = obj["aliases"]?.asJsonArrayOrNull()
      ?.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }.getOrNull() }
      ?: emptyList()
    val sourceName = runCatching { obj["source"]?.jsonPrimitive?.contentOrNull }.getOrNull()
    val source = MetadataSource.entries.firstOrNull { it.name == sourceName } ?: return@mapNotNull null
    val additionalSources = obj["additionalSources"]?.asJsonArrayOrNull()
      ?.mapNotNull { elSrc ->
        val n = runCatching { elSrc.jsonPrimitive.contentOrNull }.getOrNull()
        MetadataSource.entries.firstOrNull { it.name == n }
      }
      ?.filter { it != source }
      ?.toSet()
      ?: emptySet()
    val fromFreeText = runCatching { obj["fromFreeText"]?.jsonPrimitive?.booleanOrNull }.getOrNull()
      ?: (source == MetadataSource.OPEN_LIBRARY && additionalSources.isEmpty())
    MetadataNamedItem(
      name = name,
      description = description,
      aliases = aliases,
      source = source,
      additionalSources = additionalSources,
      fromFreeText = fromFreeText,
    )
  }
}

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
