package io.github.playertwo1.bookconverter.core.metadata

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class BookMetadataProvidersTest {

  private fun fixture(name: String): String =
    requireNotNull(javaClass.getResource("/metadata/$name")) { "Fixture missing: $name" }.readText()

  private fun fastClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(3, TimeUnit.SECONDS)
    .readTimeout(3, TimeUnit.SECONDS)
    .build()

  @Test
  fun userAgentIncludesAppVersionAndRepoWithoutPersonalEmail() {
    val ua = MetadataUserAgent.forApp("1.0.3")
    assertEquals("Bookconverter-Android/1.0.3 (https://github.com/playertwo1/bookconverter-android)", ua)
    assertFalse("User-Agent must never contain an email address", ua.contains("@"))
  }

  @Test
  fun openLibraryIsbnFetchesEditionWorkAndAuthorRespectingRateLimit() = runBlocking {
    val waits = mutableListOf<Long>()
    var fakeNow = 10_000L
    val limiter = MetadataRateLimiter(
      minIntervalMs = 1000L,
      clockMs = { fakeNow },
      sleepFn = { ms ->
        waits += ms
        fakeNow += ms
      },
    )

    MockWebServer().use { server ->
      server.enqueue(MockResponse().setResponseCode(200).setBody(fixture("openlibrary_isbn.json")))
      server.enqueue(MockResponse().setResponseCode(200).setBody(fixture("openlibrary_work.json")))
      server.enqueue(MockResponse().setResponseCode(200).setBody(fixture("openlibrary_author.json")))

      val provider = OpenLibraryMetadataProvider(
        appVersion = "1.0.3",
        baseUrl = server.url("/").toString(),
        client = fastClient(),
        rateLimiter = limiter,
      )
      val res = provider.search(BookMetadataQuery(isbn = "978-0-441-17271-9"))
      assertTrue(res is SourceSearchResult.Success)
      val c = (res as SourceSearchResult.Success).candidates.single()

      assertEquals("Dune", c.title)
      assertEquals("Frank Herbert", c.author)
      assertEquals("9780441172719", c.isbn)
      assertEquals("Ace Books", c.publisher)
      assertEquals("1990", c.publishedDate)
      assertEquals("en", c.language)
      assertEquals("/works/OL893415W", c.openLibraryKey)
      assertNull("Open Library must not emit unreliable seriesPosition", c.seriesPosition)
      assertTrue((c.summary?.length ?: 0) <= 400)
      assertTrue(c.summary!!.endsWith("."))
      assertEquals(listOf("Paul Atreides", "Lady Jessica"), c.characters)
      assertEquals(listOf("Arrakis"), c.places)

      // 3 sequential calls at the same clock tick trigger two 1000ms waits (1 req/s)
      assertEquals(listOf(1000L, 1000L), waits)

      val req1 = server.takeRequest()
      assertEquals("/isbn/9780441172719.json", req1.path)
      assertEquals(MetadataUserAgent.forApp("1.0.3"), req1.getHeader("User-Agent"))
      assertEquals("/works/OL893415W.json", server.takeRequest().path)
      assertEquals("/authors/OL79034A.json", server.takeRequest().path)
    }
  }

  @Test
  fun openLibraryTitleAuthorSearchParsesCandidates() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setResponseCode(200).setBody(fixture("openlibrary_search.json")))
      val provider = OpenLibraryMetadataProvider(
        appVersion = "1.0.3",
        baseUrl = server.url("/").toString(),
        client = fastClient(),
        rateLimiter = MetadataRateLimiter(0L),
      )
      val res = provider.search(BookMetadataQuery(title = "Dune", author = "Frank Herbert"))
      val c = (res as SourceSearchResult.Success).candidates.single()
      assertEquals("Dune", c.title)
      assertEquals("Frank Herbert", c.author)
      assertEquals("9780441172719", c.isbn)
      assertEquals("Chilton Books", c.publisher)
      assertEquals("1965", c.publishedDate)
      assertEquals("en", c.language)

      val req = server.takeRequest()
      assertTrue(req.path!!.startsWith("/search.json?"))
      assertTrue(req.path!!.contains("title=Dune"))
      assertTrue(req.path!!.contains("author=Frank"))
    }
  }

  @Test
  fun wikidataParsesSeriesPositionAndHandlesHttp429RetryAfter() = runBlocking {
    val retryWaits = mutableListOf<Long>()
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "2"))
      server.enqueue(MockResponse().setResponseCode(200).setBody(fixture("wikidata_search.json")))
      server.enqueue(MockResponse().setResponseCode(200).setBody(fixture("wikidata_sparql.json")))

      val provider = WikidataMetadataProvider(
        appVersion = "1.0.3",
        apiBaseUrl = server.url("/").toString(),
        sparqlBaseUrl = server.url("/").toString(),
        client = fastClient(),
        rateLimiter = MetadataRateLimiter(0L),
        retrySleepFn = { ms -> retryWaits += ms },
      )
      val res = provider.search(BookMetadataQuery(isbn = "9780441172719"))
      assertTrue(res is SourceSearchResult.Success)
      val c = (res as SourceSearchResult.Success).candidates.single()

      assertEquals(listOf(2000L), retryWaits)
      assertEquals("Q190192", c.wikidataQid)
      assertEquals("Q837705", c.seriesWikidataQid)
      assertEquals("Dune", c.seriesName)
      assertEquals("1", c.seriesPosition)
      assertEquals("Série de livros de ficção científica criada por Frank Herbert.", c.seriesSummary)
      assertEquals("9780441172719", c.isbn)
      assertEquals("/works/OL893415W", c.openLibraryKey)
      assertEquals(listOf("Paul Atreides"), c.characters)
      assertEquals(listOf("Arrakis"), c.places)

      val firstReq = server.takeRequest()
      assertEquals(MetadataUserAgent.forApp("1.0.3"), firstReq.getHeader("User-Agent"))
    }
  }

  @Test
  fun wikidataFailsClearlyWhenRetryAfterExceedsCap() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "60"))
      val provider = WikidataMetadataProvider(
        appVersion = "1.0.3",
        apiBaseUrl = server.url("/").toString(),
        sparqlBaseUrl = server.url("/").toString(),
        client = fastClient(),
        rateLimiter = MetadataRateLimiter(0L),
        maxRetryAfterMs = 5000L,
      )
      val res = provider.search(BookMetadataQuery(isbn = "9780441172719"))
      assertTrue(res is SourceSearchResult.Failed)
      assertTrue((res as SourceSearchResult.Failed).message.contains("429"))
      assertEquals(1, server.requestCount)
    }
  }

  @Test
  fun hardcoverSkipsSilentlyWithoutTokenAndSendsZeroRequests() = runBlocking {
    MockWebServer().use { server ->
      val provider = HardcoverMetadataProvider(
        tokenProvider = { null },
        endpointUrl = server.url("/v1/graphql").toString(),
        client = fastClient(),
        rateLimiter = MetadataRateLimiter(0L),
      )
      val res = provider.search(BookMetadataQuery(isbn = "9780441172719"))
      assertTrue(res is SourceSearchResult.Skipped)
      assertEquals(0, server.requestCount)
    }
  }

  @Test
  fun hardcoverQueriesIsbnAndTitleWithBearerHeaderAndParsesSeriesOrder() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setResponseCode(200).setBody(fixture("hardcover_isbn.json")))
      server.enqueue(MockResponse().setResponseCode(200).setBody(fixture("hardcover_books.json")))

      val provider = HardcoverMetadataProvider(
        tokenProvider = { "hc_pat_test_token" },
        appVersion = "1.0.3",
        endpointUrl = server.url("/v1/graphql").toString(),
        client = fastClient(),
        rateLimiter = MetadataRateLimiter(0L),
      )

      val isbnRes = provider.search(BookMetadataQuery(isbn = "9780441172719")) as SourceSearchResult.Success
      val book1 = isbnRes.candidates.single()
      assertEquals("1001", book1.hardcoverId)
      assertEquals("55", book1.seriesHardcoverId)
      assertEquals("Dune Chronicles", book1.seriesName)
      assertEquals("1", book1.seriesPosition)
      assertEquals("Ace", book1.publisher)
      assertEquals("en", book1.language)

      val req1 = server.takeRequest()
      assertEquals("Bearer hc_pat_test_token", req1.getHeader("Authorization"))
      assertEquals(MetadataUserAgent.forApp("1.0.3"), req1.getHeader("User-Agent"))

      val titleRes = provider.search(BookMetadataQuery(title = "Dune Messiah", author = "Frank Herbert")) as SourceSearchResult.Success
      val book2 = titleRes.candidates.single()
      assertEquals("Dune Messiah", book2.title)
      assertEquals("2", book2.seriesPosition)
      assertEquals("9780441172696", book2.isbn)
    }
  }

  @Test
  fun hardcoverHandlesUnauthorizedAndGraphQlErrorsCleanly() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setResponseCode(401))
      server.enqueue(MockResponse().setResponseCode(200).setBody("""{"errors":[{"message":"invalid query"}]}"""))

      val provider = HardcoverMetadataProvider(
        tokenProvider = { "Bearer expired_token" },
        endpointUrl = server.url("/v1/graphql").toString(),
        client = fastClient(),
        rateLimiter = MetadataRateLimiter(0L),
      )
      val r401 = provider.search(BookMetadataQuery(title = "Dune"))
      assertTrue(r401 is SourceSearchResult.Failed)
      assertTrue((r401 as SourceSearchResult.Failed).message.contains("Token da Hardcover"))

      val rGql = provider.search(BookMetadataQuery(title = "Dune"))
      assertTrue(rGql is SourceSearchResult.Failed)
      assertTrue((rGql as SourceSearchResult.Failed).message.contains("invalid query"))

      // Should not duplicate "Bearer " prefix when user pasted it
      assertEquals("Bearer expired_token", server.takeRequest().getHeader("Authorization"))
    }
  }

  @Test
  fun offlineNetworkFailureReturnsFailedResultInsteadOfThrowing() = runBlocking {
    val provider = OpenLibraryMetadataProvider(
      baseUrl = "http://127.0.0.1:1/",
      client = fastClient(),
      rateLimiter = MetadataRateLimiter(0L),
    )
    val res = provider.search(BookMetadataQuery(title = "Dune"))
    assertTrue(res is SourceSearchResult.Failed)
    assertTrue((res as SourceSearchResult.Failed).message.isNotBlank())
  }
}
