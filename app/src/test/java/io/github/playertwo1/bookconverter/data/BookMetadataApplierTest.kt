package io.github.playertwo1.bookconverter.data

import io.github.playertwo1.bookconverter.core.metadata.AggregatedBookCandidate
import io.github.playertwo1.bookconverter.core.metadata.FieldOption
import io.github.playertwo1.bookconverter.core.metadata.FieldProposal
import io.github.playertwo1.bookconverter.core.metadata.MetadataField
import io.github.playertwo1.bookconverter.core.metadata.MetadataNamedItem
import io.github.playertwo1.bookconverter.core.metadata.MetadataSource
import io.github.playertwo1.bookconverter.core.metadata.parseMetadataSources
import io.github.playertwo1.bookconverter.core.metadata.parseNamedItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun correctionReplacesPreviousLookupWithoutClearingManualFields() {
        val source = MetadataSource.OPEN_LIBRARY
        val summary = MetadataField.SUMMARY
        val seriesSummary = MetadataField.SERIES_SUMMARY
        val wrong = BookMetadataReviewSelection(
            values = mapOf(summary to "Resumo errado", seriesSummary to "Saga errada"),
            sources = mapOf(summary to source, seriesSummary to source),
            appliedFields = setOf(summary, seriesSummary),
            openLibraryKey = "/works/wrong",
            seriesOpenLibraryKey = "wrong-series",
            characters = listOf(MetadataNamedItem("Personagem errado", source = source)),
            locations = listOf(MetadataNamedItem("Local errado", source = source)),
        )
        val wrongBook = applyMetadataToBook(sampleBook(), wrong)
        val wrongSeries = applyMetadataToSeries(sampleSeries(), wrong, now = 200L)
        val right = BookMetadataReviewSelection(
            values = mapOf(MetadataField.ISBN to "9780441172719"),
            sources = mapOf(MetadataField.ISBN to source),
            appliedFields = setOf(MetadataField.ISBN),
            characters = listOf(MetadataNamedItem("Personagem certo", source = MetadataSource.WIKIDATA)),
        )

        val correctedBook = applyMetadataToBook(wrongBook, right)
        val correctedSeries = applyMetadataToSeries(wrongSeries, right, now = 300L)
        assertEquals(setOf(summary), removedMetadataFields(wrongBook.metadataSourcesJson, right.values.keys))
        assertEquals(null, correctedBook.summary)
        assertEquals(null, correctedBook.openLibraryKey)
        assertEquals(null, correctedBook.locationsJson)
        assertEquals("Editora Local", correctedBook.publisher)
        assertEquals(listOf("Personagem certo"), parseNamedItems(correctedBook.charactersJson).map { it.name })
        assertEquals(null, correctedSeries.summary)
        assertEquals(null, correctedSeries.openLibraryKey)
        assertEquals(null, correctedSeries.locationsJson)
        assertEquals("Tom solene", correctedSeries.styleNotes)
        assertEquals(listOf("Personagem certo"), parseNamedItems(correctedSeries.charactersJson).map { it.name })
    }

    @Test
    fun unlinkClearsLookupAndKeepsManualValuesAndSeriesMembership() {
        val source = MetadataSource.WIKIDATA
        val author = MetadataField.AUTHOR
        val selection = BookMetadataReviewSelection(
            values = mapOf(author to "Autor da série"),
            sources = mapOf(author to source),
            appliedFields = setOf(author),
            bookAuthorApplied = false,
            wikidataQid = "Q123",
            seriesWikidataQid = "Q456",
            locations = listOf(MetadataNamedItem("Vale", source = source)),
        )
        val book = sampleBook(author = "Autor do livro")
        val series = sampleSeries(author = null)
        val linkedBook = applyMetadataToBook(book, selection)
        val linkedSeries = applyMetadataToSeries(series, selection, now = 200L)
        assertEquals("Autor do livro", linkedBook.author)
        assertEquals("Autor da série", linkedSeries.author)
        assertEquals(book, linkedBook.unlinkMetadata())
        assertEquals(series.copy(updatedAt = 300L), linkedSeries.unlinkMetadata(now = 300L))
    }

    @Test
    fun unlinkPreservesLookupTitleAndSeriesNameWhileClearingOtherLookupFields() {
        val selection = BookMetadataReviewSelection(
            values = mapOf(
                MetadataField.TITLE to "Duna: Edição Especial",
                MetadataField.AUTHOR to "Frank Herbert",
                MetadataField.ISBN to "9780441172719",
                MetadataField.PUBLISHER to "Ace Books",
                MetadataField.PUBLISHED_DATE to "1965",
                MetadataField.LANGUAGE to "pt",
                MetadataField.SUMMARY to "Sinopse vinda da busca.",
                MetadataField.SERIES_NAME to "Crônicas de Duna",
                MetadataField.SERIES_POSITION to "1",
                MetadataField.SERIES_SUMMARY to "Resumo da série vindo da busca.",
            ),
            sources = mapOf(
                MetadataField.TITLE to MetadataSource.OPEN_LIBRARY,
                MetadataField.AUTHOR to MetadataSource.OPEN_LIBRARY,
                MetadataField.ISBN to MetadataSource.OPEN_LIBRARY,
                MetadataField.PUBLISHER to MetadataSource.OPEN_LIBRARY,
                MetadataField.PUBLISHED_DATE to MetadataSource.OPEN_LIBRARY,
                MetadataField.LANGUAGE to MetadataSource.OPEN_LIBRARY,
                MetadataField.SUMMARY to MetadataSource.WIKIDATA,
                MetadataField.SERIES_NAME to MetadataSource.WIKIDATA,
                MetadataField.SERIES_POSITION to MetadataSource.HARDCOVER,
                MetadataField.SERIES_SUMMARY to MetadataSource.WIKIDATA,
            ),
            // Keep manual publisher on book and manual author on series unchecked
            appliedFields = setOf(
                MetadataField.TITLE,
                MetadataField.ISBN,
                MetadataField.PUBLISHED_DATE,
                MetadataField.LANGUAGE,
                MetadataField.SUMMARY,
                MetadataField.SERIES_NAME,
                MetadataField.SERIES_POSITION,
                MetadataField.SERIES_SUMMARY,
            ),
            bookAuthorApplied = true,
            openLibraryKey = "/works/OL893415W",
            wikidataQid = "Q190192",
            hardcoverId = "hc-book-1",
            seriesOpenLibraryKey = "/works/OL-series",
            seriesWikidataQid = "Q837705",
            seriesHardcoverId = "hc-series-1",
            characters = listOf(MetadataNamedItem("Paul Atreides", source = MetadataSource.WIKIDATA)),
            locations = listOf(MetadataNamedItem("Arrakis", source = MetadataSource.OPEN_LIBRARY)),
        )

        val book = sampleBook(
            title = "Título Inicial",
            author = null,
            isbn = null,
            publisher = "Editora Manual",
        ).copy(sourceName = "arquivo_bruto_123.epub")
        val series = sampleSeries(
            name = "Série Inicial",
            author = "Autor Manual",
            summary = null,
        )

        val linkedBook = applyMetadataToBook(book, selection, targetSeriesId = 1L)
        val linkedSeries = applyMetadataToSeries(series, selection, now = 200L)
        assertTrue(MetadataField.TITLE in parseMetadataSources(linkedBook.metadataSourcesJson))
        assertTrue(MetadataField.SERIES_NAME in parseMetadataSources(linkedSeries.metadataSourcesJson))
        assertTrue(linkedBook.hasLinkedMetadata())
        assertTrue(linkedSeries.hasLinkedMetadata())

        val unlinkedBook = linkedBook.unlinkMetadata()
        assertEquals("Duna: Edição Especial", unlinkedBook.title)
        assertFalse(MetadataField.TITLE in parseMetadataSources(unlinkedBook.metadataSourcesJson))
        assertNull(unlinkedBook.metadataSourcesJson)
        assertNull(unlinkedBook.author)
        assertNull(unlinkedBook.isbn)
        assertNull(unlinkedBook.publishedDate)
        assertNull(unlinkedBook.language)
        assertNull(unlinkedBook.summary)
        assertNull(unlinkedBook.seriesPosition)
        assertNull(unlinkedBook.openLibraryKey)
        assertNull(unlinkedBook.wikidataQid)
        assertNull(unlinkedBook.hardcoverId)
        assertNull(unlinkedBook.charactersJson)
        assertNull(unlinkedBook.locationsJson)
        assertEquals("Editora Manual", unlinkedBook.publisher)
        assertEquals(1L, unlinkedBook.seriesId)
        assertFalse(unlinkedBook.hasLinkedMetadata())

        val unlinkedSeries = linkedSeries.unlinkMetadata(now = 300L)
        assertEquals("Crônicas de Duna", unlinkedSeries.name)
        assertFalse(MetadataField.SERIES_NAME in parseMetadataSources(unlinkedSeries.metadataSourcesJson))
        assertNull(unlinkedSeries.metadataSourcesJson)
        assertNull(unlinkedSeries.summary)
        assertNull(unlinkedSeries.openLibraryKey)
        assertNull(unlinkedSeries.wikidataQid)
        assertNull(unlinkedSeries.hardcoverId)
        assertNull(unlinkedSeries.charactersJson)
        assertNull(unlinkedSeries.locationsJson)
        assertEquals("Autor Manual", unlinkedSeries.author)
        assertEquals("Tom solene", unlinkedSeries.styleNotes)
        assertEquals(300L, unlinkedSeries.updatedAt)
        assertFalse(unlinkedSeries.hasLinkedMetadata())
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
