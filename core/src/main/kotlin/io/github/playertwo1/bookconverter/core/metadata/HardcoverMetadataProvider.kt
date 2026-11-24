package io.github.playertwo1.bookconverter.core.metadata

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Hardcover GraphQL API client (https://api.hardcover.app/v1/graphql).
 * Requires a personal API token configured by the user in Settings; skipped silently when unset.
 * Respects 60 req/min rate limit (~1100ms spacing) and HTTP 429 Retry-After.
 */
class HardcoverMetadataProvider(
  private val tokenProvider: () -> String?,
  private val appVersion: String = "1.0",
  private val endpointUrl: String = DEFAULT_ENDPOINT_URL,
  private val client: OkHttpClient = defaultMetadataHttpClient(timeoutSeconds = 15L),
  private val rateLimiter: MetadataRateLimiter = MetadataRateLimiter(minIntervalMs = 1100L),
  private val retrySleepFn: suspend (Long) -> Unit = { delay(it) },
  private val maxRetryAfterMs: Long = 5000L,
) : BookMetadataProvider {

  override val source: MetadataSource = MetadataSource.HARDCOVER

  private val userAgent: String = MetadataUserAgent.forApp(appVersion)

  override suspend fun search(query: BookMetadataQuery): SourceSearchResult {
    val rawToken = tokenProvider()?.trim().orEmpty()
    if (rawToken.isEmpty()) {
      return SourceSearchResult.Skipped(source, "Token da Hardcover não configurado.")
    }
    if (query.isBlank) return SourceSearchResult.Success(source, emptyList())

    val bearer = if (rawToken.startsWith("Bearer ", ignoreCase = true)) rawToken else "Bearer $rawToken"

    return try {
      val isbn = query.normalizedIsbn
      val candidates = if (isbn != null) {
        val byIsbn = queryByIsbn(isbn, bearer)
        if (byIsbn.isNotEmpty() || (query.cleanTitle == null && query.cleanAuthor == null)) {
          byIsbn
        } else {
          queryByTitleAuthor(query, bearer)
        }
      } else {
        queryByTitleAuthor(query, bearer)
      }
      SourceSearchResult.Success(source, candidates)
    } catch (e: CancellationException) {
      throw e
    } catch (_: java.net.SocketTimeoutException) {
      SourceSearchResult.Failed(source, "Tempo esgotado ao consultar Hardcover.")
    } catch (_: java.io.IOException) {
      SourceSearchResult.Failed(source, "Sem conexão com Hardcover.")
    } catch (e: HttpStatusException) {
      SourceSearchResult.Failed(source, e.userMessage)
    } catch (_: Exception) {
      SourceSearchResult.Failed(source, "Falha ao ler resposta da Hardcover.")
    }
  }

  private suspend fun queryByIsbn(isbn: String, bearer: String): List<SourceBookCandidate> {
    val gql = """
      query BookByIsbn(${'$'}isbn: String!) {
        editions(
          where: { _or: [{ isbn_13: { _eq: ${'$'}isbn } }, { isbn_10: { _eq: ${'$'}isbn } }] }
          limit: 6
        ) {
          isbn_13
          isbn_10
          release_date
          publisher { name }
          language { language }
          book {
            id
            title
            description
            release_date
            release_year
            contributions { author { name } }
            book_series {
              position
              series { id name description }
            }
          }
        }
      }
    """.trimIndent()
    val vars = buildJsonObject { put("isbn", JsonPrimitive(isbn)) }
    val data = postGraphQl(gql, vars, bearer)
    val editions = data["editions"]?.asJsonArrayOrNull() ?: return emptyList()
    return editions.mapNotNull { el ->
      val ed = el.asJsonObjectOrNull() ?: return@mapNotNull null
      val book = ed["book"]?.asJsonObjectOrNull() ?: return@mapNotNull null
      parseBookObject(book, preferredEdition = ed)
    }
  }

  private suspend fun queryByTitleAuthor(query: BookMetadataQuery, bearer: String): List<SourceBookCandidate> {
    val title = query.cleanTitle
    val author = query.cleanAuthor
    val gql: String
    val vars: JsonObject
    when {
      title != null && author != null -> {
        gql = """
          query SearchBooksByTitleAndAuthor(${'$'}title: String!, ${'$'}author: String!) {
            books(
              where: {
                _and: [
                  { title: { _ilike: ${'$'}title } }
                  { contributions: { author: { name: { _ilike: ${'$'}author } } } }
                ]
              }
              limit: 6
            ) {
              id
              title
              description
              release_date
              release_year
              contributions { author { name } }
              book_series {
                position
                series { id name description }
              }
              editions(limit: 3) {
                isbn_13
                isbn_10
                release_date
                publisher { name }
                language { language }
              }
            }
          }
        """.trimIndent()
        vars = buildJsonObject {
          put("title", JsonPrimitive("%$title%"))
          put("author", JsonPrimitive("%$author%"))
        }
      }
      title != null -> {
        gql = """
          query SearchBooksByTitle(${'$'}title: String!) {
            books(where: { title: { _ilike: ${'$'}title } }, limit: 6) {
              id
              title
              description
              release_date
              release_year
              contributions { author { name } }
              book_series {
                position
                series { id name description }
              }
              editions(limit: 3) {
                isbn_13
                isbn_10
                release_date
                publisher { name }
                language { language }
              }
            }
          }
        """.trimIndent()
        vars = buildJsonObject { put("title", JsonPrimitive("%$title%")) }
      }
      author != null -> {
        gql = """
          query SearchBooksByAuthor(${'$'}author: String!) {
            books(
              where: { contributions: { author: { name: { _ilike: ${'$'}author } } } }
              limit: 6
            ) {
              id
              title
              description
              release_date
              release_year
              contributions { author { name } }
              book_series {
                position
                series { id name description }
              }
              editions(limit: 3) {
                isbn_13
                isbn_10
                release_date
                publisher { name }
                language { language }
              }
            }
          }
        """.trimIndent()
        vars = buildJsonObject { put("author", JsonPrimitive("%$author%")) }
      }
      else -> return emptyList()
    }

    val data = postGraphQl(gql, vars, bearer)
    val books = data["books"]?.asJsonArrayOrNull() ?: return emptyList()
    return books.mapNotNull { el ->
      val book = el.asJsonObjectOrNull() ?: return@mapNotNull null
      val firstEdition = book["editions"]?.asJsonArrayOrNull()?.firstOrNull()?.asJsonObjectOrNull()
      parseBookObject(book, preferredEdition = firstEdition)
    }
  }

  private fun parseBookObject(book: JsonObject, preferredEdition: JsonObject?): SourceBookCandidate? {
    val title = book.stringField("title") ?: return null
    val hardcoverId = book["id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    val author = book["contributions"]
      ?.asJsonArrayOrNull()
      ?.mapNotNull { it.asJsonObjectOrNull()?.get("author")?.asJsonObjectOrNull()?.stringField("name") }
      ?.firstOrNull()

    val isbn = normalizeIsbn(preferredEdition?.stringField("isbn_13"))
      ?: normalizeIsbn(preferredEdition?.stringField("isbn_10"))
    val publisher = preferredEdition?.get("publisher")?.asJsonObjectOrNull()?.stringField("name")
    val publishedDate = preferredEdition?.stringField("release_date")
      ?: book.stringField("release_date")
      ?: book["release_year"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    val language = preferredEdition?.get("language")?.asJsonObjectOrNull()?.stringField("language")
      ?.let(::normalizeHardcoverLanguage)

    val seriesEntry = book["book_series"]?.asJsonArrayOrNull()?.firstOrNull()?.asJsonObjectOrNull()
    val seriesObj = seriesEntry?.get("series")?.asJsonObjectOrNull()
    val seriesName = seriesObj?.stringField("name")
    val seriesId = seriesObj?.get("id")?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    val seriesPosition = normalizeSeriesPosition(seriesEntry?.get("position")?.jsonPrimitive?.contentOrNull)
    val seriesSummary = trimToBriefSummary(seriesObj?.stringField("description"))

    return SourceBookCandidate(
      source = source,
      title = title,
      author = author,
      isbn = isbn,
      publisher = publisher,
      publishedDate = publishedDate,
      language = language,
      summary = trimToBriefSummary(book.stringField("description")),
      seriesName = seriesName,
      seriesPosition = seriesPosition,
      seriesSummary = seriesSummary,
      hardcoverId = hardcoverId,
      seriesHardcoverId = seriesId,
    )
  }

  private suspend fun postGraphQl(query: String, variables: JsonObject, bearer: String): JsonObject {
    val payload = buildJsonObject {
      put("query", JsonPrimitive(query))
      put("variables", variables)
    }.toString()

    var attempt = 0
    while (true) {
      rateLimiter.acquire()
      val request = Request.Builder()
        .url(endpointUrl)
        .header("Authorization", bearer)
        .header("User-Agent", userAgent)
        .header("Accept", "application/json")
        .post(payload.toRequestBody(JSON_MEDIA_TYPE))
        .build()

      client.executeCancellable(request).use { resp ->
        when {
          resp.code == 401 || resp.code == 403 ->
            throw HttpStatusException("Token da Hardcover inválido ou expirado. Atualize em Ajustes.")
          resp.code == 429 -> {
            val retryAfterMs = parseRetryAfterMillis(resp.header("Retry-After"))
            if (attempt == 0 && retryAfterMs != null && retryAfterMs <= maxRetryAfterMs) {
              attempt++
              retrySleepFn(retryAfterMs)
              continue
            }
            throw HttpStatusException("Limite de requisições da Hardcover atingido (60/min). Tente novamente em instantes.")
          }
          !resp.isSuccessful ->
            throw HttpStatusException("Hardcover respondeu com erro HTTP ${resp.code}.")
          else -> {
            val body = resp.body?.string().orEmpty()
            val root = Json.parseToJsonElement(body).jsonObject
            val errors = root["errors"]?.asJsonArrayOrNull()
            if (errors != null && errors.isNotEmpty()) {
              val firstMsg = errors.firstOrNull()?.asJsonObjectOrNull()?.stringField("message")
                ?: "erro na consulta GraphQL"
              throw HttpStatusException("Hardcover retornou erro: $firstMsg.")
            }
            return root["data"]?.asJsonObjectOrNull()
              ?: throw HttpStatusException("Resposta inválida da Hardcover.")
          }
        }
      }
    }
  }

  private class HttpStatusException(val userMessage: String) : Exception(userMessage)

  companion object {
    const val DEFAULT_ENDPOINT_URL = "https://api.hardcover.app/v1/graphql"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
  }
}

private fun normalizeHardcoverLanguage(raw: String): String = when (raw.trim().lowercase()) {
  "english" -> "en"
  "portuguese" -> "pt"
  "spanish" -> "es"
  "french" -> "fr"
  "german" -> "de"
  "italian" -> "it"
  "japanese" -> "ja"
  else -> raw.trim().lowercase()
}
