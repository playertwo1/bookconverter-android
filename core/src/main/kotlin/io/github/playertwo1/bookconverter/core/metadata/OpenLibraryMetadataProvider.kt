package io.github.playertwo1.bookconverter.core.metadata

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Open Library metadata client (public API, no key required).
 * Enforces 1 req/s rate limiting and sends a descriptive app User-Agent without personal data.
 */
class OpenLibraryMetadataProvider(
  private val appVersion: String = "1.0",
  private val baseUrl: String = DEFAULT_BASE_URL,
  private val client: OkHttpClient = defaultMetadataHttpClient(),
  private val rateLimiter: MetadataRateLimiter = MetadataRateLimiter(minIntervalMs = 1000L),
) : BookMetadataProvider {

  override val source: MetadataSource = MetadataSource.OPEN_LIBRARY

  private val userAgent: String = MetadataUserAgent.forApp(appVersion)

  override suspend fun search(query: BookMetadataQuery): SourceSearchResult {
    if (query.isBlank) return SourceSearchResult.Success(source, emptyList())
    return try {
      val isbn = query.normalizedIsbn
      if (isbn != null) {
        val isbnCandidate = lookupByIsbn(isbn)
        if (isbnCandidate != null) {
          return SourceSearchResult.Success(source, listOf(isbnCandidate))
        }
        if (query.cleanTitle == null && query.cleanAuthor == null) {
          return SourceSearchResult.Success(source, emptyList())
        }
      }
      val candidates = searchCatalog(query)
      SourceSearchResult.Success(source, candidates)
    } catch (e: CancellationException) {
      throw e
    } catch (_: SocketTimeoutException) {
      SourceSearchResult.Failed(source, "Tempo esgotado ao consultar Open Library.")
    } catch (_: IOException) {
      SourceSearchResult.Failed(source, "Sem conexão com Open Library.")
    } catch (e: HttpStatusException) {
      SourceSearchResult.Failed(source, e.userMessage)
    } catch (_: Exception) {
      SourceSearchResult.Failed(source, "Falha ao ler resposta da Open Library.")
    }
  }

  private suspend fun lookupByIsbn(isbn: String): SourceBookCandidate? {
    val url = "${baseUrl.trimEnd('/')}/isbn/$isbn.json"
    val editionObj = getJsonObject(url, allowNotFound = true) ?: return null

    val title = editionObj.stringField("title")
    val publisher = editionObj.firstArrayString("publishers")
    val publishedDate = editionObj.stringField("publish_date")
    val resolvedIsbn = editionObj.firstArrayString("isbn_13")
      ?: editionObj.firstArrayString("isbn_10")
      ?: isbn
    val language = editionObj["languages"]
      ?.asJsonArrayOrNull()
      ?.firstOrNull()
      ?.asJsonObjectOrNull()
      ?.stringField("key")
      ?.substringAfterLast('/')
      ?.let(::normalizeOpenLibraryLanguage)

    val workKey = editionObj["works"]
      ?.asJsonArrayOrNull()
      ?.firstOrNull()
      ?.asJsonObjectOrNull()
      ?.stringField("key")

    var summary = extractOpenLibraryText(editionObj["description"])
    var characters = emptyList<MetadataNamedItem>()
    var places = emptyList<MetadataNamedItem>()
    var authorKey = editionObj["authors"]
      ?.asJsonArrayOrNull()
      ?.firstOrNull()
      ?.asJsonObjectOrNull()
      ?.stringField("key")

    if (workKey != null) {
      val workUrl = "${baseUrl.trimEnd('/')}$workKey.json"
      val workObj = runCatching { getJsonObject(workUrl, allowNotFound = true) }.getOrNull()
      if (workObj != null) {
        if (summary == null) {
          summary = extractOpenLibraryText(workObj["description"])
        }
        characters = extractOpenLibraryNamedItems(workObj.stringList("subject_people"), isPerson = true)
        places = extractOpenLibraryNamedItems(workObj.stringList("subject_places"), isPerson = false)
        if (authorKey == null) {
          authorKey = workObj["authors"]
            ?.asJsonArrayOrNull()
            ?.firstOrNull()
            ?.asJsonObjectOrNull()
            ?.get("author")
            ?.asJsonObjectOrNull()
            ?.stringField("key")
        }
      }
    }

    val authorName = if (authorKey != null) {
      val authorUrl = "${baseUrl.trimEnd('/')}$authorKey.json"
      runCatching { getJsonObject(authorUrl, allowNotFound = true) }
        .getOrNull()
        ?.stringField("name")
    } else {
      editionObj.stringField("by_statement")
    }

    return SourceBookCandidate(
      source = source,
      title = title,
      author = authorName,
      isbn = normalizeIsbn(resolvedIsbn) ?: resolvedIsbn,
      publisher = publisher,
      publishedDate = publishedDate,
      language = language,
      summary = trimToBriefSummary(summary),
      seriesName = null,
      seriesPosition = null, // Open Library is unreliable for series ordering
      openLibraryKey = workKey ?: editionObj.stringField("key"),
      characters = characters,
      places = places,
    )
  }

  private suspend fun searchCatalog(query: BookMetadataQuery): List<SourceBookCandidate> {
    val urlBuilder = "${baseUrl.trimEnd('/')}/search.json".toHttpUrl().newBuilder()
    query.normalizedIsbn?.let { urlBuilder.addQueryParameter("isbn", it) }
    query.cleanTitle?.let { urlBuilder.addQueryParameter("title", it) }
    query.cleanAuthor?.let { urlBuilder.addQueryParameter("author", it) }
    urlBuilder.addQueryParameter("limit", MAX_RESULTS.toString())
    urlBuilder.addQueryParameter(
      "fields",
      "key,title,author_name,isbn,publisher,first_publish_year,language,subject_people,subject_places,first_sentence",
    )

    val root = getJsonObject(urlBuilder.build().toString(), allowNotFound = false) ?: return emptyList()
    val docs = root["docs"]?.asJsonArrayOrNull() ?: return emptyList()
    return docs.mapNotNull { element ->
      val doc = element.asJsonObjectOrNull() ?: return@mapNotNull null
      val title = doc.stringField("title") ?: return@mapNotNull null
      val author = doc.firstArrayString("author_name")
      val isbns = doc.stringList("isbn").mapNotNull(::normalizeIsbn)
      val bestIsbn = isbns.firstOrNull { it.length == 13 } ?: isbns.firstOrNull()
      val publisher = doc.firstArrayString("publisher")
      val year = doc["first_publish_year"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
      val language = doc.firstArrayString("language")?.let(::normalizeOpenLibraryLanguage)
      val summary = doc.firstArrayString("first_sentence")
      SourceBookCandidate(
        source = source,
        title = title,
        author = author,
        isbn = bestIsbn,
        publisher = publisher,
        publishedDate = year,
        language = language,
        summary = trimToBriefSummary(summary),
        seriesName = null,
        seriesPosition = null,
        openLibraryKey = doc.stringField("key"),
        characters = extractOpenLibraryNamedItems(doc.stringList("subject_people"), isPerson = true),
        places = extractOpenLibraryNamedItems(doc.stringList("subject_places"), isPerson = false),
      )
    }
  }

  private suspend fun getJsonObject(url: String, allowNotFound: Boolean): JsonObject? {
    rateLimiter.acquire()
    val request = Request.Builder()
      .url(url)
      .header("User-Agent", userAgent)
      .header("Accept", "application/json")
      .get()
      .build()
    client.executeCancellable(request).use { resp ->
      if (allowNotFound && resp.code == 404) return null
      if (resp.code == 429) {
        throw HttpStatusException("Limite de requisições da Open Library atingido. Tente novamente em instantes.")
      }
      if (!resp.isSuccessful) {
        throw HttpStatusException("Open Library respondeu com erro HTTP ${resp.code}.")
      }
      val body = resp.body?.string().orEmpty()
      return Json.parseToJsonElement(body).jsonObject
    }
  }

  private class HttpStatusException(val userMessage: String) : Exception(userMessage)

  companion object {
    const val DEFAULT_BASE_URL = "https://openlibrary.org"
    private const val MAX_RESULTS = 6
  }
}

private fun extractOpenLibraryNamedItems(rawTags: List<String>, isPerson: Boolean): List<MetadataNamedItem> {
  val items = rawTags.mapNotNull { raw ->
    val cleaned = normalizeOpenLibraryFreeTextEntity(raw, isPerson = isPerson) ?: return@mapNotNull null
    MetadataNamedItem(
      name = cleaned,
      source = MetadataSource.OPEN_LIBRARY,
      fromFreeText = true,
    )
  }
  return mergeNamedItems(emptyList(), items).take(MAX_NAMED_ITEMS)
}

private const val MAX_NAMED_ITEMS = 25

private fun extractOpenLibraryText(element: JsonElement?): String? {
  if (element == null) return null
  runCatching { element.jsonPrimitive.contentOrNull }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
  return element.asJsonObjectOrNull()?.stringField("value")
}

private fun normalizeOpenLibraryLanguage(code: String): String = when (code.trim().lowercase()) {
  "eng" -> "en"
  "por" -> "pt"
  "spa" -> "es"
  "fre", "fra" -> "fr"
  "ger", "deu" -> "de"
  "ita" -> "it"
  "jpn" -> "ja"
  else -> code.trim().lowercase()
}

internal fun JsonElement.asJsonObjectOrNull(): JsonObject? = runCatching { this.jsonObject }.getOrNull()
internal fun JsonElement.asJsonArrayOrNull(): JsonArray? = runCatching { this.jsonArray }.getOrNull()
internal fun JsonObject.stringField(key: String): String? =
  runCatching { this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } }.getOrNull()

internal fun JsonObject.stringList(key: String): List<String> =
  this[key]?.asJsonArrayOrNull()
    ?.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }.getOrNull() }
    ?: emptyList()

internal fun JsonObject.firstArrayString(key: String): String? = stringList(key).firstOrNull()
