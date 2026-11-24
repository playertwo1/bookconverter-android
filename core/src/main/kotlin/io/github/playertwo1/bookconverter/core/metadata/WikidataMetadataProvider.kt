package io.github.playertwo1.bookconverter.core.metadata

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Locale

/**
 * Wikidata metadata client using MediaWiki API search for fast QID lookup followed by a lightweight
 * VALUES-scoped SPARQL query (avoids full-graph text-search timeouts).
 * Respects HTTP 429 with Retry-After and sends a descriptive app User-Agent without personal data.
 */
class WikidataMetadataProvider(
  private val appVersion: String = "1.0",
  private val apiBaseUrl: String = DEFAULT_API_BASE_URL,
  private val sparqlBaseUrl: String = DEFAULT_SPARQL_BASE_URL,
  private val client: OkHttpClient = defaultMetadataHttpClient(timeoutSeconds = 15L),
  private val rateLimiter: MetadataRateLimiter = MetadataRateLimiter(minIntervalMs = 1000L),
  private val retrySleepFn: suspend (Long) -> Unit = { delay(it) },
  private val maxRetryAfterMs: Long = 5000L,
) : BookMetadataProvider {

  override val source: MetadataSource = MetadataSource.WIKIDATA

  private val userAgent: String = MetadataUserAgent.forApp(appVersion)

  override suspend fun search(query: BookMetadataQuery): SourceSearchResult {
    if (query.isBlank) return SourceSearchResult.Success(source, emptyList())
    return try {
      val qids = resolveCandidateQids(query)
      if (qids.isEmpty()) return SourceSearchResult.Success(source, emptyList())
      val candidates = fetchByQids(qids, query)
      SourceSearchResult.Success(source, candidates)
    } catch (e: CancellationException) {
      throw e
    } catch (_: SocketTimeoutException) {
      SourceSearchResult.Failed(source, "Tempo esgotado ao consultar Wikidata.")
    } catch (_: IOException) {
      SourceSearchResult.Failed(source, "Sem conexão com Wikidata.")
    } catch (e: HttpStatusException) {
      SourceSearchResult.Failed(source, e.userMessage)
    } catch (_: Exception) {
      SourceSearchResult.Failed(source, "Falha ao ler resposta da Wikidata.")
    }
  }

  private suspend fun resolveCandidateQids(query: BookMetadataQuery): List<String> {
    val qids = linkedSetOf<String>()
    val isbn = query.normalizedIsbn
    if (isbn != null) {
      val isbnQuery = query.rawIsbn ?: isbn
      val url = "${apiBaseUrl.trimEnd('/')}/w/api.php".toHttpUrl().newBuilder()
        .addQueryParameter("action", "query")
        .addQueryParameter("list", "search")
        .addQueryParameter("srsearch", isbnQuery)
        .addQueryParameter("srnamespace", "0")
        .addQueryParameter("srlimit", MAX_QIDS.toString())
        .addQueryParameter("format", "json")
        .build()
        .toString()
      val root = getJsonWithRetry(url)
      root["query"]
        ?.asJsonObjectOrNull()
        ?.get("search")
        ?.asJsonArrayOrNull()
        ?.forEach { item ->
          val title = item.asJsonObjectOrNull()?.stringField("title")
          if (title != null && QID_REGEX.matches(title)) qids += title
        }
    }

    if (qids.isEmpty() && (query.cleanTitle != null || query.cleanAuthor != null)) {
      val combined = listOfNotNull(query.cleanTitle, query.cleanAuthor).joinToString(" ")
      qids += searchEntities(combined)
      if (qids.isEmpty() && query.cleanTitle != null && query.cleanAuthor != null) {
        qids += searchEntities(query.cleanTitle)
      }
    }
    return qids.take(MAX_QIDS)
  }

  private suspend fun searchEntities(term: String): List<String> {
    val url = "${apiBaseUrl.trimEnd('/')}/w/api.php".toHttpUrl().newBuilder()
      .addQueryParameter("action", "wbsearchentities")
      .addQueryParameter("search", term)
      .addQueryParameter("language", "en")
      .addQueryParameter("uselang", "pt")
      .addQueryParameter("type", "item")
      .addQueryParameter("limit", MAX_QIDS.toString())
      .addQueryParameter("format", "json")
      .build()
      .toString()
    val root = getJsonWithRetry(url)
    return root["search"]
      ?.asJsonArrayOrNull()
      ?.mapNotNull { it.asJsonObjectOrNull()?.stringField("id")?.takeIf(QID_REGEX::matches) }
      ?: emptyList()
  }

  private suspend fun fetchByQids(qids: List<String>, query: BookMetadataQuery): List<SourceBookCandidate> {
    val valuesClause = qids.joinToString(" ") { "wd:$it" }
    // Keep SPARQL lightweight (no P674/P840 Cartesian product) so Wikidata responds well within timeout.
    val sparql = """
      SELECT DISTINCT ?item ?work ?workLabel ?workDescPt ?workDescEn ?authorLabel ?publisherLabel ?pubDate ?langCode ?isbn13 ?isbn10 ?series ?seriesLabel ?seriesDescPt ?seriesDescEn ?seriesOrdinal ?olId WHERE {
        VALUES ?item { $valuesClause }
        OPTIONAL { ?item wdt:P629 ?linkedWork . }
        BIND(COALESCE(?linkedWork, ?item) AS ?work)
        OPTIONAL { ?work wdt:P50 ?author . }
        OPTIONAL { ?item wdt:P123 ?edPublisher . }
        OPTIONAL { ?work wdt:P123 ?workPublisher . }
        BIND(COALESCE(?edPublisher, ?workPublisher) AS ?publisher)
        OPTIONAL { ?item wdt:P577 ?edDate . }
        OPTIONAL { ?work wdt:P577 ?workDate . }
        BIND(COALESCE(?edDate, ?workDate) AS ?pubDate)
        OPTIONAL { ?work wdt:P407 ?lang . OPTIONAL { ?lang wdt:P218 ?langCode . } }
        OPTIONAL { ?item wdt:P212 ?isbn13 . }
        OPTIONAL { ?item wdt:P957 ?isbn10 . }
        OPTIONAL {
          ?work p:P179 ?seriesStmt .
          ?seriesStmt ps:P179 ?series .
          OPTIONAL { ?seriesStmt pq:P1545 ?seriesOrdinal . }
          OPTIONAL { ?series schema:description ?seriesDescPt . FILTER(LANG(?seriesDescPt) = "pt") }
          OPTIONAL { ?series schema:description ?seriesDescEn . FILTER(LANG(?seriesDescEn) = "en") }
        }
        OPTIONAL { ?work wdt:P648 ?olId . }
        OPTIONAL { ?work schema:description ?workDescPt . FILTER(LANG(?workDescPt) = "pt") }
        OPTIONAL { ?work schema:description ?workDescEn . FILTER(LANG(?workDescEn) = "en") }
        SERVICE wikibase:label { bd:serviceParam wikibase:language "pt,en". }
      }
      LIMIT 30
    """.trimIndent()

    val url = "${sparqlBaseUrl.trimEnd('/')}/sparql".toHttpUrl().newBuilder()
      .addQueryParameter("format", "json")
      .addQueryParameter("query", sparql)
      .build()
      .toString()

    val root = getJsonWithRetry(url, acceptHeader = "application/sparql-results+json")
    val bindings = root["results"]
      ?.asJsonObjectOrNull()
      ?.get("bindings")
      ?.asJsonArrayOrNull()
      ?: return emptyList()

    data class Accumulator(
      val qid: String,
      var title: String? = null,
      val authors: LinkedHashSet<String> = linkedSetOf(),
      var isbn13: String? = null,
      var isbn10: String? = null,
      var publisher: String? = null,
      var publishedDate: String? = null,
      var language: String? = null,
      var descPt: String? = null,
      var descEn: String? = null,
      var seriesQid: String? = null,
      var seriesName: String? = null,
      var seriesPosition: String? = null,
      var seriesDescPt: String? = null,
      var seriesDescEn: String? = null,
      var olId: String? = null,
      val characters: LinkedHashSet<String> = linkedSetOf(),
      val places: LinkedHashSet<String> = linkedSetOf(),
    )

    val byWorkQid = linkedMapOf<String, Accumulator>()
    for (el in bindings) {
      val row = el.asJsonObjectOrNull() ?: continue
      val workUri = row.bindingValue("work") ?: row.bindingValue("item") ?: continue
      val qid = extractQid(workUri) ?: continue
      val acc = byWorkQid.getOrPut(qid) { Accumulator(qid) }

      val label = row.bindingValue("workLabel")?.takeUnless { QID_REGEX.matches(it) }
      if (acc.title == null && label != null) acc.title = label
      row.bindingValue("authorLabel")?.takeUnless { QID_REGEX.matches(it) }?.let { acc.authors += it }
      if (acc.isbn13 == null) acc.isbn13 = normalizeIsbn(row.bindingValue("isbn13"))
      if (acc.isbn10 == null) acc.isbn10 = normalizeIsbn(row.bindingValue("isbn10"))
      if (acc.publisher == null) acc.publisher = row.bindingValue("publisherLabel")?.takeUnless { QID_REGEX.matches(it) }
      if (acc.publishedDate == null) acc.publishedDate = parseWikidataYearOrDate(row.bindingValue("pubDate"))
      if (acc.language == null) acc.language = row.bindingValue("langCode")?.lowercase(Locale.ROOT)
      if (acc.descPt == null) acc.descPt = row.bindingValue("workDescPt")
      if (acc.descEn == null) acc.descEn = row.bindingValue("workDescEn")
      if (acc.seriesQid == null) acc.seriesQid = extractQid(row.bindingValue("series"))
      if (acc.seriesName == null) acc.seriesName = row.bindingValue("seriesLabel")?.takeUnless { QID_REGEX.matches(it) }
      if (acc.seriesPosition == null) acc.seriesPosition = normalizeSeriesPosition(row.bindingValue("seriesOrdinal"))
      if (acc.seriesDescPt == null) acc.seriesDescPt = row.bindingValue("seriesDescPt")
      if (acc.seriesDescEn == null) acc.seriesDescEn = row.bindingValue("seriesDescEn")
      if (acc.olId == null) acc.olId = normalizeOpenLibraryIdFromWikidata(row.bindingValue("olId"))
      row.bindingValue("characterLabel")?.takeUnless { QID_REGEX.matches(it) }?.let { acc.characters += it }
      row.bindingValue("placeLabel")?.takeUnless { QID_REGEX.matches(it) }?.let { acc.places += it }
    }

    val authorFilter = query.cleanAuthor?.lowercase(Locale.ROOT)
    val built = byWorkQid.values.mapNotNull { acc ->
      // Keep only literary-like entities that carry a title plus at least one bibliographic signal
      val title = acc.title ?: return@mapNotNull null
      val hasBookSignal = acc.authors.isNotEmpty() || acc.isbn13 != null || acc.isbn10 != null ||
        acc.seriesName != null || acc.olId != null || acc.publisher != null
      if (!hasBookSignal) return@mapNotNull null
      SourceBookCandidate(
        source = source,
        title = title,
        author = acc.authors.firstOrNull(),
        isbn = acc.isbn13 ?: acc.isbn10,
        publisher = acc.publisher,
        publishedDate = acc.publishedDate,
        language = acc.language,
        summary = trimToBriefSummary(acc.descPt ?: acc.descEn),
        seriesName = acc.seriesName,
        seriesPosition = acc.seriesPosition,
        seriesSummary = trimToBriefSummary(acc.seriesDescPt ?: acc.seriesDescEn),
        openLibraryKey = acc.olId,
        wikidataQid = acc.qid,
        seriesWikidataQid = acc.seriesQid,
        characters = acc.characters.toList(),
        places = acc.places.toList(),
      )
    }

    return if (authorFilter != null) {
      built.sortedByDescending { c -> c.author?.lowercase(Locale.ROOT)?.contains(authorFilter) == true }
    } else {
      built
    }
  }

  private suspend fun getJsonWithRetry(url: String, acceptHeader: String = "application/json"): JsonObject {
    var attempt = 0
    while (true) {
      rateLimiter.acquire()
      val request = Request.Builder()
        .url(url)
        .header("User-Agent", userAgent)
        .header("Accept", acceptHeader)
        .get()
        .build()
      client.executeCancellable(request).use { resp ->
        if (resp.code == 429) {
          val retryAfterMs = parseRetryAfterMillis(resp.header("Retry-After"))
          if (attempt == 0 && retryAfterMs != null && retryAfterMs <= maxRetryAfterMs) {
            attempt++
            retrySleepFn(retryAfterMs)
            continue
          }
          val waitHint = retryAfterMs?.let { " Aguarde ${(it / 1000L).coerceAtLeast(1L)}s e tente de novo." }.orEmpty()
          throw HttpStatusException("Limite de requisições da Wikidata atingido (HTTP 429).$waitHint")
        }
        if (!resp.isSuccessful) {
          throw HttpStatusException("Wikidata respondeu com erro HTTP ${resp.code}.")
        }
        val body = resp.body?.string().orEmpty()
        return Json.parseToJsonElement(body).jsonObject
      }
    }
  }

  private class HttpStatusException(val userMessage: String) : Exception(userMessage)

  companion object {
    const val DEFAULT_API_BASE_URL = "https://www.wikidata.org"
    const val DEFAULT_SPARQL_BASE_URL = "https://query.wikidata.org"
    private const val MAX_QIDS = 4
    private val QID_REGEX = Regex("^Q\\d+$")
  }
}

private fun JsonObject.bindingValue(key: String): String? =
  this[key]?.asJsonObjectOrNull()?.stringField("value")

private fun extractQid(uriOrId: String?): String? {
  val raw = uriOrId?.trim()?.substringAfterLast('/') ?: return null
  return raw.takeIf { it.startsWith("Q") && it.length > 1 && it.drop(1).all(Char::isDigit) }
}

private fun parseWikidataYearOrDate(raw: String?): String? {
  val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
  val datePart = trimmed.substringBefore('T')
  val year = datePart.take(4)
  return if (year.length == 4 && year.all(Char::isDigit)) {
    if (datePart.endsWith("-01-01")) year else datePart
  } else {
    trimmed
  }
}

private fun normalizeOpenLibraryIdFromWikidata(raw: String?): String? {
  val clean = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
  return when {
    clean.startsWith("/works/") || clean.startsWith("/books/") -> clean
    clean.endsWith("W") -> "/works/$clean"
    clean.endsWith("M") -> "/books/$clean"
    else -> clean
  }
}
