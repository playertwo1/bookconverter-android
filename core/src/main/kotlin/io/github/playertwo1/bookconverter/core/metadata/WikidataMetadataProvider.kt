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
 * Wikidata metadata client using MediaWiki API search + wbgetentities (no key required).
 * Fetches structured book/series fields, series position (P179 + qualifier P1545),
 * characters (P674) and narrative locations (P840) with labels, short descriptions and aliases
 * without triggering SPARQL Cartesian-product timeouts.
 * Respects HTTP 429 with Retry-After and sends a descriptive app User-Agent without personal data.
 */
class WikidataMetadataProvider(
  private val appVersion: String = "1.0",
  private val apiBaseUrl: String = DEFAULT_API_BASE_URL,
  @Suppress("unused") private val sparqlBaseUrl: String = DEFAULT_SPARQL_BASE_URL,
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

  private suspend fun fetchEntities(ids: List<String>, includeClaims: Boolean): Map<String, JsonObject> {
    val distinct = ids.distinct().filter(QID_REGEX::matches)
    if (distinct.isEmpty()) return emptyMap()
    val out = linkedMapOf<String, JsonObject>()
    val props = if (includeClaims) "labels|descriptions|aliases|claims" else "labels|descriptions|aliases"
    for (chunk in distinct.chunked(MAX_BATCH_IDS)) {
      val url = "${apiBaseUrl.trimEnd('/')}/w/api.php".toHttpUrl().newBuilder()
        .addQueryParameter("action", "wbgetentities")
        .addQueryParameter("ids", chunk.joinToString("|"))
        .addQueryParameter("props", props)
        .addQueryParameter("languages", "pt|en")
        .addQueryParameter("format", "json")
        .build()
        .toString()
      val root = getJsonWithRetry(url)
      val entities = root["entities"]?.asJsonObjectOrNull() ?: continue
      for ((key, value) in entities) {
        val obj = value.asJsonObjectOrNull() ?: continue
        if (obj.containsKey("missing")) continue
        out[key] = obj
      }
    }
    return out
  }

  private suspend fun fetchByQids(qids: List<String>, query: BookMetadataQuery): List<SourceBookCandidate> {
    val primaryEntities = fetchEntities(qids, includeClaims = true).toMutableMap()
    if (primaryEntities.isEmpty()) return emptyList()

    // If a matched QID is a specific edition with P629 (edition or translation of), also load its work entity
    val linkedWorkQids = primaryEntities.values
      .mapNotNull { it.claimEntityIds("P629").firstOrNull() }
      .filter { it !in primaryEntities }
      .distinct()
    if (linkedWorkQids.isNotEmpty()) {
      primaryEntities.putAll(fetchEntities(linkedWorkQids, includeClaims = true))
    }

    // Collect referenced QIDs (authors, publishers, series, characters P674, narrative locations P840)
    val refQids = linkedSetOf<String>()
    val workPairs = linkedMapOf<String, Pair<JsonObject, JsonObject>>() // workQid -> (editionOrItem, work)
    for (qid in qids) {
      val itemEntity = primaryEntities[qid] ?: continue
      val workQid = itemEntity.claimEntityIds("P629").firstOrNull() ?: qid
      val workEntity = primaryEntities[workQid] ?: itemEntity
      val instanceOf = workEntity.claimEntityIds("P31") + itemEntity.claimEntityIds("P31")
      if (instanceOf.any { it in EXCLUDED_NON_BOOK_P31 }) continue
      workPairs.putIfAbsent(workQid, itemEntity to workEntity)

      workEntity.claimEntityIds("P50").firstOrNull()?.let { refQids += it }
      (itemEntity.claimEntityIds("P123").firstOrNull() ?: workEntity.claimEntityIds("P123").firstOrNull())?.let { refQids += it }
      workEntity.claimSeriesWithOrdinal()?.first?.let { refQids += it }
      workEntity.claimEntityIds("P674").take(MAX_NAMED_ITEMS_PER_KIND).forEach { refQids += it }
      workEntity.claimEntityIds("P840").take(MAX_NAMED_ITEMS_PER_KIND).forEach { refQids += it }
    }

    val missingRefs = refQids.filter { it !in primaryEntities }
    val resolvedRefs = if (missingRefs.isNotEmpty()) {
      fetchEntities(missingRefs, includeClaims = false)
    } else {
      emptyMap()
    }
    val allEntities = primaryEntities + resolvedRefs

    val authorFilter = query.cleanAuthor?.lowercase(Locale.ROOT)
    val built = workPairs.entries.mapNotNull { (workQid, pair) ->
      val (itemEntity, workEntity) = pair
      val instanceOf = (workEntity.claimEntityIds("P31") + itemEntity.claimEntityIds("P31")).toSet()

      val labels = workEntity["labels"]?.asJsonObjectOrNull() ?: itemEntity["labels"]?.asJsonObjectOrNull()
      val ptTitle = labels?.get("pt")?.asJsonObjectOrNull()?.stringField("value")
      val enTitle = labels?.get("en")?.asJsonObjectOrNull()?.stringField("value")
      val title = ptTitle ?: enTitle ?: return@mapNotNull null
      val alternateTitle = enTitle?.takeIf { !it.equals(title, ignoreCase = true) }

      val authorQid = workEntity.claimEntityIds("P50").firstOrNull()
      val author = authorQid?.let { allEntities[it]?.preferredLabel() }

      val publisherQid = itemEntity.claimEntityIds("P123").firstOrNull() ?: workEntity.claimEntityIds("P123").firstOrNull()
      val publisher = publisherQid?.let { allEntities[it]?.preferredLabel() }

      val publishedDate = parseWikidataYearOrDate(
        itemEntity.claimTimeStrings("P577").firstOrNull()
          ?: workEntity.claimTimeStrings("P577").firstOrNull()
      )

      val langQid = workEntity.claimEntityIds("P407").firstOrNull()
        ?: itemEntity.claimEntityIds("P407").firstOrNull()
      val language = langQid?.let(::mapWikidataLanguageQid)

      val isbn13 = normalizeIsbn(itemEntity.claimStrings("P212").firstOrNull() ?: workEntity.claimStrings("P212").firstOrNull())
      val isbn10 = normalizeIsbn(itemEntity.claimStrings("P957").firstOrNull() ?: workEntity.claimStrings("P957").firstOrNull())
      val isbn = isbn13 ?: isbn10

      val olId = normalizeOpenLibraryIdFromWikidata(
        workEntity.claimStrings("P648").firstOrNull() ?: itemEntity.claimStrings("P648").firstOrNull()
      )

      val seriesPair = workEntity.claimSeriesWithOrdinal()
      val seriesQid = seriesPair?.first
      val seriesEntity = seriesQid?.let { allEntities[it] }
      val seriesName = seriesEntity?.preferredLabel()
      val seriesPosition = normalizeSeriesPosition(seriesPair?.second)
      val seriesSummary = trimToBriefSummary(
        seriesEntity?.preferredDescription()?.takeUnless { it.contains("Wikimedia", ignoreCase = true) }
      )

      val characters = workEntity.claimEntityIds("P674")
        .take(MAX_NAMED_ITEMS_PER_KIND)
        .mapNotNull { qid -> allEntities[qid]?.toWikidataNamedItem() }
      val places = workEntity.claimEntityIds("P840")
        .take(MAX_NAMED_ITEMS_PER_KIND)
        .mapNotNull { qid -> allEntities[qid]?.toWikidataNamedItem() }

      // Keep only literary works/books carrying an author, ISBN, Open Library ID, or literary P31
      val hasLiterarySignal = author != null || isbn != null || olId != null ||
        instanceOf.any { it in LITERARY_P31 }
      if (!hasLiterarySignal) return@mapNotNull null

      SourceBookCandidate(
        source = source,
        title = title,
        alternateTitle = alternateTitle,
        author = author,
        isbn = isbn,
        publisher = publisher,
        publishedDate = publishedDate,
        language = language,
        summary = trimToBriefSummary(
          (workEntity.preferredDescription() ?: itemEntity.preferredDescription())
            ?.takeUnless { it.contains("Wikimedia", ignoreCase = true) }
        ),
        seriesName = seriesName,
        seriesPosition = seriesPosition,
        seriesSummary = seriesSummary,
        openLibraryKey = olId,
        wikidataQid = workQid,
        seriesWikidataQid = seriesQid,
        characters = mergeNamedItems(emptyList(), characters),
        places = mergeNamedItems(emptyList(), places),
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
    private const val MAX_BATCH_IDS = 45
    private const val MAX_NAMED_ITEMS_PER_KIND = 20
    private val QID_REGEX = Regex("^Q\\d+$")
    private val EXCLUDED_NON_BOOK_P31 = setOf(
      "Q7889",     // video game
      "Q11424",    // film
      "Q5398426",  // television series
      "Q13406463", // Wikimedia list article
      "Q4167410",  // Wikimedia disambiguation page
      "Q482994",   // album
      "Q7366",     // song
    )
    private val LITERARY_P31 = setOf(
      "Q7725634",  // literary work
      "Q571",      // book
      "Q8261",     // novel
      "Q47461344", // written work
      "Q3331189",  // version, edition, or translation
      "Q49084",    // short story
      "Q1667921",  // novel series
      "Q277759",   // book series
    )
  }
}

private fun JsonObject.preferredLabel(): String? {
  val labels = this["labels"]?.asJsonObjectOrNull() ?: return null
  return labels["pt"]?.asJsonObjectOrNull()?.stringField("value")
    ?: labels["en"]?.asJsonObjectOrNull()?.stringField("value")
}

private fun JsonObject.preferredDescription(): String? {
  val descriptions = this["descriptions"]?.asJsonObjectOrNull() ?: return null
  return descriptions["pt"]?.asJsonObjectOrNull()?.stringField("value")
    ?: descriptions["en"]?.asJsonObjectOrNull()?.stringField("value")
}

private fun JsonObject.toWikidataNamedItem(): MetadataNamedItem? {
  val labels = this["labels"]?.asJsonObjectOrNull() ?: return null
  val ptLabel = labels["pt"]?.asJsonObjectOrNull()?.stringField("value")
  val enLabel = labels["en"]?.asJsonObjectOrNull()?.stringField("value")
  val primaryName = ptLabel ?: enLabel ?: return null
  val primaryKey = normalizeEntityComparisonKey(primaryName)

  val aliasPool = mutableListOf<String>()
  // Preserve English canonical name as alias when Portuguese label differs
  if (ptLabel != null && enLabel != null && normalizeEntityComparisonKey(enLabel) != primaryKey) {
    aliasPool += enLabel
  }
  val aliasesObj = this["aliases"]?.asJsonObjectOrNull()
  listOf("pt", "en").forEach { lang ->
    aliasesObj?.get(lang)?.asJsonArrayOrNull()?.forEach { entry ->
      entry.asJsonObjectOrNull()?.stringField("value")?.let { alias ->
        if (normalizeEntityComparisonKey(alias) != primaryKey) {
          aliasPool += alias
        }
      }
    }
  }
  val distinctAliases = aliasPool.distinctBy(::normalizeEntityComparisonKey).take(3)

  return MetadataNamedItem(
    name = primaryName,
    description = trimToBriefSummary(preferredDescription(), maxChars = 160),
    aliases = distinctAliases,
    source = MetadataSource.WIKIDATA,
    fromFreeText = false,
  )
}

private fun JsonObject.claimStatements(propertyId: String): List<JsonObject> =
  this["claims"]
    ?.asJsonObjectOrNull()
    ?.get(propertyId)
    ?.asJsonArrayOrNull()
    ?.mapNotNull { it.asJsonObjectOrNull() }
    ?.filter { it.stringField("rank") != "deprecated" }
    ?: emptyList()

private fun JsonObject.claimEntityIds(propertyId: String): List<String> =
  claimStatements(propertyId).mapNotNull { stmt ->
    stmt["mainsnak"]
      ?.asJsonObjectOrNull()
      ?.get("datavalue")
      ?.asJsonObjectOrNull()
      ?.get("value")
      ?.asJsonObjectOrNull()
      ?.stringField("id")
  }.distinct()

private fun JsonObject.claimStrings(propertyId: String): List<String> =
  claimStatements(propertyId).mapNotNull { stmt ->
    stmt["mainsnak"]
      ?.asJsonObjectOrNull()
      ?.get("datavalue")
      ?.asJsonObjectOrNull()
      ?.stringField("value")
  }.distinct()

private fun JsonObject.claimTimeStrings(propertyId: String): List<String> =
  claimStatements(propertyId).mapNotNull { stmt ->
    stmt["mainsnak"]
      ?.asJsonObjectOrNull()
      ?.get("datavalue")
      ?.asJsonObjectOrNull()
      ?.get("value")
      ?.asJsonObjectOrNull()
      ?.stringField("time")
  }.distinct()

private fun JsonObject.claimSeriesWithOrdinal(): Pair<String, String?>? {
  for (stmt in claimStatements("P179")) {
    val seriesQid = stmt["mainsnak"]
      ?.asJsonObjectOrNull()
      ?.get("datavalue")
      ?.asJsonObjectOrNull()
      ?.get("value")
      ?.asJsonObjectOrNull()
      ?.stringField("id")
      ?: continue
    val ordinal = stmt["qualifiers"]
      ?.asJsonObjectOrNull()
      ?.get("P1545")
      ?.asJsonArrayOrNull()
      ?.firstOrNull()
      ?.asJsonObjectOrNull()
      ?.get("datavalue")
      ?.asJsonObjectOrNull()
      ?.stringField("value")
    return seriesQid to ordinal
  }
  return null
}

private fun mapWikidataLanguageQid(qid: String): String? = when (qid) {
  "Q1860" -> "en"
  "Q5146" -> "pt"
  "Q1321" -> "es"
  "Q150" -> "fr"
  "Q188" -> "de"
  "Q652" -> "it"
  "Q5287" -> "ja"
  "Q7737" -> "ru"
  else -> null
}

private fun parseWikidataYearOrDate(raw: String?): String? {
  val trimmed = raw?.trim()?.removePrefix("+")?.takeIf { it.isNotEmpty() } ?: return null
  val datePart = trimmed.substringBefore('T')
  val year = datePart.take(4)
  return if (year.length == 4 && year.all(Char::isDigit)) {
    if (datePart.endsWith("-01-01") || datePart.endsWith("-00-00")) year else datePart
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
