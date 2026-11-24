package io.github.playertwo1.bookconverter.core.metadata

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookMetadataAggregatorTest {

  private class StubProvider(
    override val source: MetadataSource,
    private val result: SourceSearchResult,
  ) : BookMetadataProvider {
    override suspend fun search(query: BookMetadataQuery): SourceSearchResult = result
  }

  @Test
  fun mergesSourcesPrioritizesWikidataHardcoverForPositionAndDeduplicatesCharactersAndPlaces() = runBlocking {
    val ol = SourceBookCandidate(
      source = MetadataSource.OPEN_LIBRARY,
      title = "Dune",
      author = "Frank Herbert",
      isbn = "9780441172719",
      publisher = "Ace Books",
      publishedDate = "1990",
      language = "en",
      summary = "Set on the desert planet Arrakis, Dune tells the story of Paul Atreides, heir to a noble family tasked with ruling an inhospitable world.",
      seriesPosition = "99", // Even if injected, Open Library must never be trusted for series position
      openLibraryKey = "/works/OL893415W",
      characters = listOf(
        MetadataNamedItem(name = "paul atreides", source = MetadataSource.OPEN_LIBRARY, fromFreeText = true),
        MetadataNamedItem(name = "Lady Jéssica", source = MetadataSource.OPEN_LIBRARY, fromFreeText = true),
        MetadataNamedItem(name = "Duncan Idaho", source = MetadataSource.OPEN_LIBRARY, fromFreeText = true),
      ),
      places = listOf(
        MetadataNamedItem(name = "Árrakis", source = MetadataSource.OPEN_LIBRARY, fromFreeText = true),
        MetadataNamedItem(name = "Giedi Prime", source = MetadataSource.OPEN_LIBRARY, fromFreeText = true),
      ),
    )
    val wd = SourceBookCandidate(
      source = MetadataSource.WIKIDATA,
      title = "Dune",
      author = "Frank Herbert",
      isbn = "9780441172719",
      publisher = "Chilton Books",
      publishedDate = "1965-08-01",
      language = "en",
      summary = "Romance de ficção científica.",
      seriesName = "Dune",
      seriesPosition = "1",
      seriesSummary = "Série de livros de ficção científica criada por Frank Herbert.",
      openLibraryKey = "/works/OL893415W",
      wikidataQid = "Q190192",
      seriesWikidataQid = "Q837705",
      characters = listOf(
        MetadataNamedItem(
          name = "Paul Atreides",
          description = "Protagonista da série Duna.",
          aliases = listOf("Muad'Dib"),
          source = MetadataSource.WIKIDATA,
          fromFreeText = false,
        ),
        MetadataNamedItem(
          name = "Lady Jessica",
          description = "Concubina Bene Gesserit.",
          source = MetadataSource.WIKIDATA,
          fromFreeText = false,
        ),
      ),
      places = listOf(
        MetadataNamedItem(
          name = "Arrakis",
          description = "Planeta desértico fictício.",
          aliases = listOf("Duna"),
          source = MetadataSource.WIKIDATA,
          fromFreeText = false,
        ),
      ),
    )
    val hc = SourceBookCandidate(
      source = MetadataSource.HARDCOVER,
      title = "Dune",
      author = "Frank Herbert",
      isbn = "9780441172719",
      publisher = "Ace",
      publishedDate = "1990",
      language = "en",
      summary = "Paul Atreides leads nomadic tribes in a battle to control the desert planet Arrakis and its vital spice melange.",
      seriesName = "Dune Chronicles",
      seriesPosition = "1",
      seriesSummary = "Epic science fiction saga spanning thousands of years on Arrakis.",
      hardcoverId = "1001",
      seriesHardcoverId = "55",
      characters = listOf(
        MetadataNamedItem(
          name = "PAUL ATREIDES",
          description = "Heir to House Atreides.",
          source = MetadataSource.HARDCOVER,
          fromFreeText = false,
        ),
        MetadataNamedItem(
          name = "Chani",
          description = "Fremen warrior.",
          source = MetadataSource.HARDCOVER,
          fromFreeText = false,
        ),
      ),
    )

    val aggregator = BookMetadataAggregator(
      listOf(
        StubProvider(MetadataSource.OPEN_LIBRARY, SourceSearchResult.Success(MetadataSource.OPEN_LIBRARY, listOf(ol))),
        StubProvider(MetadataSource.WIKIDATA, SourceSearchResult.Success(MetadataSource.WIKIDATA, listOf(wd))),
        StubProvider(MetadataSource.HARDCOVER, SourceSearchResult.Success(MetadataSource.HARDCOVER, listOf(hc))),
      )
    )

    val response = aggregator.search(BookMetadataQuery(isbn = "9780441172719"))
    assertEquals(1, response.candidates.size)
    val merged = response.candidates.single()

    assertEquals(setOf(MetadataSource.OPEN_LIBRARY, MetadataSource.WIKIDATA, MetadataSource.HARDCOVER), merged.sources)
    assertEquals("/works/OL893415W", merged.openLibraryKey)
    assertEquals("Q190192", merged.wikidataQid)
    assertEquals("1001", merged.hardcoverId)
    assertEquals("Q837705", merged.seriesWikidataQid)
    assertEquals("55", merged.seriesHardcoverId)

    // Series position ignores Open Library (99) and agrees on "1" (Wikidata prioritized)
    val posProposal = merged.fields[MetadataField.SERIES_POSITION]!!
    assertEquals("1", posProposal.selectedValue)
    assertEquals(MetadataSource.WIKIDATA, posProposal.selectedSource)
    assertFalse("Equal position 1 from Wikidata & Hardcover is not a conflict", posProposal.hasConflict)

    // Series name conflicts between Wikidata ("Dune") and Hardcover ("Dune Chronicles") -> both exposed!
    val seriesProposal = merged.fields[MetadataField.SERIES_NAME]!!
    assertTrue(seriesProposal.hasConflict)
    assertEquals(listOf("Dune", "Dune Chronicles"), seriesProposal.options.map { it.value })
    assertEquals(listOf(MetadataSource.WIKIDATA, MetadataSource.HARDCOVER), seriesProposal.options.map { it.source })

    // Publisher conflicts across sources -> all distinct options preserved
    val pubProposal = merged.fields[MetadataField.PUBLISHER]!!
    assertTrue(pubProposal.hasConflict)
    assertEquals(3, pubProposal.options.size)

    // Informative synopsis (> 80 chars) beats short 1-line Wikidata description
    val summaryProposal = merged.fields[MetadataField.SUMMARY]!!
    assertEquals(MetadataSource.HARDCOVER, summaryProposal.selectedSource)
    assertTrue(summaryProposal.selectedValue.length <= 400)

    // Characters deduplicated accent- and case-insensitively across all 3 sources
    assertEquals(listOf("Paul Atreides", "Lady Jessica", "Chani", "Duncan Idaho"), merged.characters.map { it.name })
    val paul = merged.characters[0]
    assertEquals(MetadataSource.WIKIDATA, paul.source)
    assertEquals(setOf(MetadataSource.HARDCOVER, MetadataSource.OPEN_LIBRARY), paul.additionalSources)
    assertFalse(paul.fromFreeText)
    assertEquals(listOf("Muad'Dib"), paul.aliases)

    val jessica = merged.characters[1]
    assertEquals(MetadataSource.WIKIDATA, jessica.source)
    assertEquals(setOf(MetadataSource.OPEN_LIBRARY), jessica.additionalSources)
    assertFalse(jessica.fromFreeText)

    val chani = merged.characters[2]
    assertEquals(MetadataSource.HARDCOVER, chani.source)
    assertFalse(chani.fromFreeText)

    val duncan = merged.characters[3]
    assertEquals(MetadataSource.OPEN_LIBRARY, duncan.source)
    assertTrue("Open Library-only item must stay marked as free text", duncan.fromFreeText)

    // Places deduplicated accent-insensitively ("Árrakis" vs "Arrakis")
    assertEquals(listOf("Arrakis", "Giedi Prime"), merged.places.map { it.name })
    assertEquals(MetadataSource.WIKIDATA, merged.places[0].source)
    assertEquals(setOf(MetadataSource.OPEN_LIBRARY), merged.places[0].additionalSources)
    assertFalse(merged.places[0].fromFreeText)
    assertEquals(MetadataSource.OPEN_LIBRARY, merged.places[1].source)
    assertTrue(merged.places[1].fromFreeText)
  }

  @Test
  fun openLibraryFreeTextNormalizerCleansNoisyCatalogEntries() {
    assertEquals("Paul Atreides", normalizeOpenLibraryFreeTextEntity("Atreides, Paul (Fictitious character)", isPerson = true))
    assertEquals("Lady Jessica", normalizeOpenLibraryFreeTextEntity("  Lady Jessica -- Juvenile fiction ", isPerson = true))
    assertEquals("Arrakis", normalizeOpenLibraryFreeTextEntity("Arrakis (Imaginary place) -- Fiction", isPerson = false))
    assertEquals("Frank Herbert", normalizeOpenLibraryFreeTextEntity("Herbert, Frank, 1920-1986", isPerson = true))
    assertNull(normalizeOpenLibraryFreeTextEntity("Fictitious characters", isPerson = true))
    assertNull(normalizeOpenLibraryFreeTextEntity("Imaginary places", isPerson = false))
    assertNull(normalizeOpenLibraryFreeTextEntity("1965", isPerson = false))
  }

  @Test
  fun namedItemsRoundTripSerialization() {
    val items = listOf(
      MetadataNamedItem(
        name = "Paul Atreides",
        description = "Protagonista",
        aliases = listOf("Muad'Dib"),
        source = MetadataSource.WIKIDATA,
        additionalSources = setOf(MetadataSource.HARDCOVER),
        fromFreeText = false,
      ),
      MetadataNamedItem(
        name = "Duncan Idaho",
        source = MetadataSource.OPEN_LIBRARY,
        fromFreeText = true,
      ),
    )
    val json = serializeNamedItems(items)
    assertEquals(items, parseNamedItems(json))
    assertNull(serializeNamedItems(emptyList()))
    assertTrue(parseNamedItems("not-json").isEmpty())
  }

  @Test
  fun matchesPortugueseAndEnglishLabelsViaAlternateTitleAndDoesNotMergeAuthorlessNonBook() {
    val ol = SourceBookCandidate(
      source = MetadataSource.OPEN_LIBRARY,
      title = "Dune",
      author = "Frank Herbert",
      openLibraryKey = "/works/OL893415W",
    )
    val wdBook = SourceBookCandidate(
      source = MetadataSource.WIKIDATA,
      title = "Duna",
      alternateTitle = "Dune",
      author = "Frank Herbert",
      wikidataQid = "Q190192",
    )
    val wdAuthorless = SourceBookCandidate(
      source = MetadataSource.WIKIDATA,
      title = "Dune",
      author = null,
      wikidataQid = "Q999999",
    )
    val merged = BookMetadataAggregator.mergeCandidates(listOf(ol, wdBook, wdAuthorless))
    assertEquals(2, merged.size)
    val bookCand = merged.first { it.wikidataQid == "Q190192" }
    assertEquals(setOf(MetadataSource.OPEN_LIBRARY, MetadataSource.WIKIDATA), bookCand.sources)
  }

  @Test
  fun failedOrSkippedProviderDoesNotBreakOtherSources() = runBlocking {
    val ol = SourceBookCandidate(
      source = MetadataSource.OPEN_LIBRARY,
      title = "The Hobbit",
      author = "J.R.R. Tolkien",
      openLibraryKey = "/works/OL262758W",
    )
    val aggregator = BookMetadataAggregator(
      listOf(
        StubProvider(MetadataSource.OPEN_LIBRARY, SourceSearchResult.Success(MetadataSource.OPEN_LIBRARY, listOf(ol))),
        StubProvider(MetadataSource.WIKIDATA, SourceSearchResult.Failed(MetadataSource.WIKIDATA, "Sem conexão com Wikidata.")),
        StubProvider(MetadataSource.HARDCOVER, SourceSearchResult.Skipped(MetadataSource.HARDCOVER, "Token ausente.")),
      )
    )

    val resp = aggregator.search(BookMetadataQuery(title = "The Hobbit"))
    assertEquals(1, resp.candidates.size)
    assertEquals("The Hobbit", resp.candidates.single().displayTitle)
    assertEquals(1, resp.failedSources.size)
    assertEquals(1, resp.skippedSources.size)
  }

  @Test
  fun trimToBriefSummaryCutsAtCleanSentenceWithin400Chars() {
    val longHtml = "<p>First sentence introduces the world and the hero cleanly in plain prose. " +
      "Second sentence develops the central conflict with plenty of narrative detail for readers to understand the premise. " +
      "Third sentence adds even more context about the kingdom and its factions across the continent. " +
      "Fourth sentence goes well past the four hundred character limit so it must be trimmed cleanly without breaking words or sentences.</p>"

    val trimmed = trimToBriefSummary(longHtml, maxChars = 400)!!
    assertFalse(trimmed.contains("<p>"))
    assertTrue("Expected <= 400 chars, got ${trimmed.length}", trimmed.length <= 400)
    assertTrue("Expected clean sentence ending, got: $trimmed", trimmed.endsWith("."))
  }

  @Test
  fun metadataSourcesRoundTripSerialization() {
    val map = mapOf(
      MetadataField.ISBN to MetadataSource.OPEN_LIBRARY,
      MetadataField.SERIES_POSITION to MetadataSource.WIKIDATA,
      MetadataField.SUMMARY to MetadataSource.HARDCOVER,
    )
    val json = serializeMetadataSources(map)
    assertEquals(map, parseMetadataSources(json))
    assertNull(serializeMetadataSources(emptyMap()))
    assertTrue(parseMetadataSources("not-json").isEmpty())
  }
}
