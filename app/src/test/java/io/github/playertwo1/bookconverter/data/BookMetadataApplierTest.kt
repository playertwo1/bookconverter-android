package io.github.playertwo1.bookconverter.data

import io.github.playertwo1.bookconverter.core.metadata.AggregatedBookCandidate
import io.github.playertwo1.bookconverter.core.metadata.FieldOption
import io.github.playertwo1.bookconverter.core.metadata.FieldProposal
import io.github.playertwo1.bookconverter.core.metadata.MetadataField
import io.github.playertwo1.bookconverter.core.metadata.MetadataNamedItem
import io.github.playertwo1.bookconverter.core.metadata.MetadataSource
import io.github.playertwo1.bookconverter.core.metadata.parseMetadataSources
import io.github.playertwo1.bookconverter.core.metadata.parseNamedItems
import io.github.playertwo1.bookconverter.core.metadata.serializeNamedItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookMetadataApplierTest {

    private fun sampleBook(
        title: String = "Dune",
        author: String? = "Frank Herbert",
        isbn: String? = null,
        publisher: String? = "Editora Local",
        charactersJson: String? = null,
        locationsJson: String? = null,
    ) = Book(
        id = 10L,
        title = title,
        author = author,
        language = "en",
        format = "EPUB",
        sha256 = "sha_sample",
        privatePath = "/tmp/sample.epub",
        sourceName = "sample.epub",
        sizeBytes = 1024L,
        importedAt = 100L,
        seriesId = 1L,
        isbn = isbn,
        publisher = publisher,
        charactersJson = charactersJson,
        locationsJson = locationsJson,
    )

    private fun sampleSeries(
        name: String = "Duna",
        author: String? = "Frank Herbert",
        summary: String? = null,
        charactersJson: String? = null,
        locationsJson: String? = null,
    ) = Series(
        id = 1L,
        name = name,
        author = author,
        styleNotes = "Tom solene",
        glossaryVersion = 2,
        createdAt = 100L,
        updatedAt = 100L,
        summary = summary,
        charactersJson = charactersJson,
        locationsJson = locationsJson,
    )

    private fun sampleCandidate() = AggregatedBookCandidate(
        id = "cand-0",
        sources = setOf(MetadataSource.OPEN_LIBRARY, MetadataSource.WIKIDATA),
        fields = mapOf(
            MetadataField.TITLE to FieldProposal(MetadataField.TITLE, "Dune", MetadataSource.OPEN_LIBRARY, listOf(FieldOption("Dune", MetadataSource.OPEN_LIBRARY))),
            MetadataField.AUTHOR to FieldProposal(MetadataField.AUTHOR, "Frank Herbert", MetadataSource.OPEN_LIBRARY, listOf(FieldOption("Frank Herbert", MetadataSource.OPEN_LIBRARY))),
            MetadataField.ISBN to FieldProposal(MetadataField.ISBN, "9780441172719", MetadataSource.OPEN_LIBRARY, listOf(FieldOption("9780441172719", MetadataSource.OPEN_LIBRARY))),
            MetadataField.PUBLISHER to FieldProposal(MetadataField.PUBLISHER, "Ace Books", MetadataSource.OPEN_LIBRARY, listOf(FieldOption("Ace Books", MetadataSource.OPEN_LIBRARY))),
            MetadataField.SERIES_NAME to FieldProposal(MetadataField.SERIES_NAME, "Dune", MetadataSource.WIKIDATA, listOf(FieldOption("Dune", MetadataSource.WIKIDATA))),
            MetadataField.SERIES_POSITION to FieldProposal(MetadataField.SERIES_POSITION, "1", MetadataSource.WIKIDATA, listOf(FieldOption("1", MetadataSource.WIKIDATA))),
            MetadataField.SERIES_SUMMARY to FieldProposal(MetadataField.SERIES_SUMMARY, "Saga de ficção científica.", MetadataSource.WIKIDATA, listOf(FieldOption("Saga de ficção científica.", MetadataSource.WIKIDATA))),
        ),
        openLibraryKey = "/works/OL893415W",
        wikidataQid = "Q190192",
        seriesWikidataQid = "Q837705",
    )

    @Test
    fun defaultAppliedFieldsLeavesConflictingExistingValuesUnchecked() {
        val book = sampleBook(publisher = "Editora Local", isbn = null)
        val series = sampleSeries(name = "Duna", summary = null)
        val defaults = defaultAppliedFields(sampleCandidate(), existingBook = book, existingSeries = series)

        // Blank fields auto-checked
        assertTrue(MetadataField.ISBN in defaults)
        assertTrue(MetadataField.SERIES_POSITION in defaults)
        assertTrue(MetadataField.SERIES_SUMMARY in defaults)

        // Existing different values stay unchecked until user confirms
        assertFalse(MetadataField.PUBLISHER in defaults)
        assertFalse(MetadataField.SERIES_NAME in defaults)
    }

    @Test
    fun applyMetadataPreservesUncheckedExistingFieldsAndRecordsPerFieldSources() {
        val book = sampleBook(publisher = "Editora Local", isbn = null)
        val series = sampleSeries(name = "Duna", summary = null)
        val selection = BookMetadataReviewSelection(
            values = mapOf(
                MetadataField.ISBN to "978-0-441-17271-9",
                MetadataField.PUBLISHER to "Ace Books",
                MetadataField.SERIES_POSITION to "1.0",
                MetadataField.SERIES_NAME to "Dune",
                MetadataField.SERIES_SUMMARY to "Saga de ficção científica.",
            ),
            sources = mapOf(
                MetadataField.ISBN to MetadataSource.OPEN_LIBRARY,
                MetadataField.PUBLISHER to MetadataSource.OPEN_LIBRARY,
                MetadataField.SERIES_POSITION to MetadataSource.WIKIDATA,
                MetadataField.SERIES_NAME to MetadataSource.WIKIDATA,
                MetadataField.SERIES_SUMMARY to MetadataSource.WIKIDATA,
            ),
            // User deliberately unchecked PUBLISHER and SERIES_NAME to keep local values
            appliedFields = setOf(
                MetadataField.ISBN,
                MetadataField.SERIES_POSITION,
                MetadataField.SERIES_SUMMARY,
            ),
            openLibraryKey = "/works/OL893415W",
            wikidataQid = "Q190192",
            seriesWikidataQid = "Q837705",
        )

        val updatedBook = applyMetadataToBook(book, selection, targetSeriesId = 1L)
        assertEquals("9780441172719", updatedBook.isbn)
        assertEquals("1", updatedBook.seriesPosition)
        assertEquals("Editora Local", updatedBook.publisher)
        assertEquals("/works/OL893415W", updatedBook.openLibraryKey)
        assertEquals("Q190192", updatedBook.wikidataQid)
        val bookSources = parseMetadataSources(updatedBook.metadataSourcesJson)
        assertEquals(MetadataSource.OPEN_LIBRARY, bookSources[MetadataField.ISBN])
        assertEquals(MetadataSource.WIKIDATA, bookSources[MetadataField.SERIES_POSITION])
        assertEquals("Fontes: Open Library, Wikidata", formatSourceAttribution(updatedBook.metadataSourcesJson))

        val updatedSeries = applyMetadataToSeries(series, selection, now = 200L)
        assertEquals("Duna", updatedSeries.name)
        assertEquals("Saga de ficção científica.", updatedSeries.summary)
        assertEquals("Q837705", updatedSeries.wikidataQid)
        assertEquals("Fontes: Wikidata", formatSourceAttribution(updatedSeries.metadataSourcesJson))
    }

    @Test
    fun applyMetadataMergesCharactersAndLocationsWithoutLosingExistingItems() {
        val existingChars = serializeNamedItems(
            listOf(
                MetadataNamedItem(
                    name = "Paul Atreides",
                    source = MetadataSource.OPEN_LIBRARY,
                    fromFreeText = true,
                )
            )
        )
        val book = sampleBook(charactersJson = existingChars)
        val series = sampleSeries(charactersJson = existingChars)

        val selection = BookMetadataReviewSelection(
            values = emptyMap(),
            sources = emptyMap(),
            appliedFields = emptySet(),
            characters = listOf(
                MetadataNamedItem(
                    name = "Paul Atreides",
                    description = "Protagonista",
                    aliases = listOf("Muad'Dib"),
                    source = MetadataSource.WIKIDATA,
                    fromFreeText = false,
                ),
                MetadataNamedItem(
                    name = "Chani",
                    description = "Guerreira Fremen",
                    source = MetadataSource.HARDCOVER,
                    fromFreeText = false,
                ),
            ),
            locations = listOf(
                MetadataNamedItem(
                    name = "Arrakis",
                    description = "Planeta desértico",
                    source = MetadataSource.WIKIDATA,
                    fromFreeText = false,
                ),
            ),
        )

        val updatedBook = applyMetadataToBook(book, selection, targetSeriesId = 1L)
        val savedBookChars = parseNamedItems(updatedBook.charactersJson)
        assertEquals(listOf("Paul Atreides", "Chani"), savedBookChars.map { it.name })
        assertEquals(MetadataSource.WIKIDATA, savedBookChars[0].source)
        assertEquals(setOf(MetadataSource.OPEN_LIBRARY), savedBookChars[0].additionalSources)
        assertEquals("Protagonista", savedBookChars[0].description)
        assertEquals(listOf("Arrakis"), parseNamedItems(updatedBook.locationsJson).map { it.name })

        val updatedSeries = applyMetadataToSeries(series, selection, now = 300L)
        assertEquals(listOf("Paul Atreides", "Chani"), parseNamedItems(updatedSeries.charactersJson).map { it.name })
        assertEquals(listOf("Arrakis"), parseNamedItems(updatedSeries.locationsJson).map { it.name })
        assertEquals(
            "Fontes: Open Library, Wikidata, Hardcover",
            formatSourceAttribution(updatedSeries.metadataSourcesJson, updatedSeries.charactersJson, updatedSeries.locationsJson),
        )
    }

    @Test
    fun buildNewGlossaryEntriesRespectsOptInAndNeverOverwritesExistingTerms() {
        val existingGlossary = listOf(
            GlossaryEntry(
                id = 1L,
                seriesId = 1L,
                sourceTerm = "Arrakis",
                targetTerm = "Duna (tradução do dono)",
                updatedAt = 100L,
            )
        )

        // Default opt-out (empty lists) produces zero glossary entries
        assertTrue(
            buildNewGlossaryEntries(1L, existingGlossary, emptyList(), emptyList(), now = 200L).isEmpty()
        )

        val entries = buildNewGlossaryEntries(
            seriesId = 1L,
            existingGlossary = existingGlossary,
            charactersForGlossary = listOf(
                MetadataNamedItem(
                    name = "Paul Atreides",
                    description = "Protagonista",
                    source = MetadataSource.WIKIDATA,
                    fromFreeText = false,
                )
            ),
            locationsForGlossary = listOf(
                // Already in glossary with different casing/accent -> must NOT overwrite
                MetadataNamedItem(name = "árrakis", source = MetadataSource.OPEN_LIBRARY, fromFreeText = true),
                MetadataNamedItem(name = "Caladan", source = MetadataSource.WIKIDATA, fromFreeText = false),
            ),
            now = 200L,
        )

        assertEquals(2, entries.size)
        assertEquals("Paul Atreides", entries[0].sourceTerm)
        assertEquals("Paul Atreides", entries[0].targetTerm)
        assertTrue(entries[0].notes!!.contains("Personagem"))
        assertTrue(entries[0].notes!!.contains("Wikidata"))

        assertEquals("Caladan", entries[1].sourceTerm)
        assertEquals("Caladan", entries[1].targetTerm)
        assertTrue(entries[1].notes!!.contains("Local"))
    }
}
