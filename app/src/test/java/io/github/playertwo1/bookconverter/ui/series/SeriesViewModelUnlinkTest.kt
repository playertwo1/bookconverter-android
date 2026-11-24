package io.github.playertwo1.bookconverter.ui.series

import io.github.playertwo1.bookconverter.core.metadata.MetadataField
import io.github.playertwo1.bookconverter.core.metadata.MetadataNamedItem
import io.github.playertwo1.bookconverter.core.metadata.MetadataSource
import io.github.playertwo1.bookconverter.core.metadata.parseMetadataSources
import io.github.playertwo1.bookconverter.data.Book
import io.github.playertwo1.bookconverter.data.BookDao
import io.github.playertwo1.bookconverter.data.BookMetadataReviewSelection
import io.github.playertwo1.bookconverter.data.GlossaryEntry
import io.github.playertwo1.bookconverter.data.Series
import io.github.playertwo1.bookconverter.data.SeriesDao
import io.github.playertwo1.bookconverter.data.SeriesMemoryEntry
import io.github.playertwo1.bookconverter.data.SeriesSummary
import io.github.playertwo1.bookconverter.data.applyMetadataToBook
import io.github.playertwo1.bookconverter.data.applyMetadataToSeries
import io.github.playertwo1.bookconverter.data.hasLinkedMetadata
import io.github.playertwo1.bookconverter.data.matchExistingSeriesForAuthor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesViewModelUnlinkTest {

    private class InMemoryBookDao : BookDao {
        val rows = mutableListOf<Book>()
        private var nextId = 1L

        override fun all(): Flow<List<Book>> = flowOf(rows)
        override suspend fun byId(id: Long): Book? = rows.firstOrNull { it.id == id }
        override suspend fun bySha(sha256: String): Book? = rows.firstOrNull { it.sha256 == sha256 }
        override suspend fun insert(book: Book): Long {
            val id = if (book.id != 0L) book.id else nextId++
            rows += book.copy(id = id)
            return id
        }

        override suspend fun updateBook(book: Book) {
            rows.indexOfFirst { it.id == book.id }.takeIf { it >= 0 }?.let { rows[it] = book }
        }

        override suspend fun setSeries(bookId: Long, seriesId: Long?) {
            rows.indexOfFirst { it.id == bookId }.takeIf { it >= 0 }?.let {
                rows[it] = rows[it].copy(seriesId = seriesId)
            }
        }

        override suspend fun setBookSeries(bookId: Long, seriesId: Long?) = setSeries(bookId, seriesId)
        override fun observeBySeries(seriesId: Long): Flow<List<Book>> = flowOf(rows.filter { it.seriesId == seriesId })
        override suspend fun booksInSeries(seriesId: Long): List<Book> = rows.filter { it.seriesId == seriesId }
    }

    private class InMemorySeriesDao : SeriesDao {
        val rows = mutableListOf<Series>()
        private var nextId = 1L

        override fun observeAllSummaries(): Flow<List<SeriesSummary>> = flowOf(emptyList())
        override fun observeAll(): Flow<List<Series>> = flowOf(rows)
        override fun observeById(seriesId: Long): Flow<Series?> = flowOf(rows.firstOrNull { it.id == seriesId })
        override suspend fun byId(seriesId: Long): Series? = rows.firstOrNull { it.id == seriesId }
        override suspend fun findByNameIgnoreCase(name: String): Series? =
            rows.firstOrNull { it.name.trim().equals(name.trim(), ignoreCase = true) }

        override suspend fun findAllByNameIgnoreCase(name: String): List<Series> =
            rows.filter { it.name.trim().equals(name.trim(), ignoreCase = true) }.sortedBy { it.id }

        override suspend fun insertSeries(series: Series): Long {
            val id = if (series.id != 0L) series.id else nextId++
            rows += series.copy(id = id)
            return id
        }

        override suspend fun updateSeries(series: Series) {
            rows.indexOfFirst { it.id == series.id }.takeIf { it >= 0 }?.let { rows[it] = series }
        }

        override suspend fun bumpGlossaryVersion(seriesId: Long, updatedAt: Long) {}
        override suspend fun unlinkBooksFromSeries(seriesId: Long) {}
        override suspend fun deleteSeriesById(seriesId: Long) { rows.removeAll { it.id == seriesId } }
        override fun observeGlossary(seriesId: Long): Flow<List<GlossaryEntry>> = flowOf(emptyList())
        override suspend fun glossaryForSeries(seriesId: Long): List<GlossaryEntry> = emptyList()
        override suspend fun upsertGlossaryEntry(entry: GlossaryEntry): Long = 0L
        override suspend fun deleteGlossaryEntryById(entryId: Long) {}
        override suspend fun upsertGlossaryEntries(entries: List<GlossaryEntry>): List<Long> = emptyList()
        override fun observeMemoryEntries(seriesId: Long): Flow<List<SeriesMemoryEntry>> = flowOf(emptyList())
        override suspend fun approvedMemoryForSeries(seriesId: Long): List<SeriesMemoryEntry> = emptyList()
        override suspend fun memoryEntryById(entryId: Long): SeriesMemoryEntry? = null
        override suspend fun insertMemoryEntries(entries: List<SeriesMemoryEntry>) {}
        override suspend fun updateMemoryStatus(entryId: Long, status: String, targetTerm: String, updatedAt: Long) {}
        override suspend fun deleteMemoryEntry(entryId: Long) {}
    }

    @Test
    fun unlinkMetadataInStorageKeepsBookTitleAndSeriesNameRecognizableAndPreservesManualData() = runBlocking {
        val seriesDao = InMemorySeriesDao()
        val bookDao = InMemoryBookDao()

        val selection = BookMetadataReviewSelection(
            values = mapOf(
                MetadataField.TITLE to "O Nome do Vento",
                MetadataField.AUTHOR to "Patrick Rothfuss",
                MetadataField.ISBN to "9788599296493",
                MetadataField.PUBLISHED_DATE to "2007",
                MetadataField.LANGUAGE to "pt",
                MetadataField.SUMMARY to "Sinopse da busca",
                MetadataField.SERIES_NAME to "A Crônica do Matador do Rei",
                MetadataField.SERIES_POSITION to "1",
                MetadataField.SERIES_SUMMARY to "Trilogia de fantasia",
            ),
            sources = mapOf(
                MetadataField.TITLE to MetadataSource.OPEN_LIBRARY,
                MetadataField.AUTHOR to MetadataSource.OPEN_LIBRARY,
                MetadataField.ISBN to MetadataSource.OPEN_LIBRARY,
                MetadataField.PUBLISHED_DATE to MetadataSource.OPEN_LIBRARY,
                MetadataField.LANGUAGE to MetadataSource.OPEN_LIBRARY,
                MetadataField.SUMMARY to MetadataSource.WIKIDATA,
                MetadataField.SERIES_NAME to MetadataSource.WIKIDATA,
                MetadataField.SERIES_POSITION to MetadataSource.HARDCOVER,
                MetadataField.SERIES_SUMMARY to MetadataSource.WIKIDATA,
            ),
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
            openLibraryKey = "/works/OL8479867W",
            wikidataQid = "Q765432",
            seriesWikidataQid = "Q123456",
            characters = listOf(MetadataNamedItem("Kvothe", source = MetadataSource.WIKIDATA)),
            locations = listOf(MetadataNamedItem("Tarbean", source = MetadataSource.OPEN_LIBRARY)),
        )

        val baseSeries = Series(
            name = "Nome Anterior",
            author = "Autor Manual da Série",
            styleNotes = "Manter nomes próprios",
            createdAt = 100L,
            updatedAt = 100L,
        )
        val seriesId = seriesDao.insertSeries(applyMetadataToSeries(baseSeries, selection, now = 200L))

        val baseBook = Book(
            title = "Titulo Anterior",
            author = null,
            language = null,
            format = "EPUB",
            sha256 = "sha_kvothe",
            privatePath = "/tmp/kvothe.epub",
            sourceName = "arquivo_qualquer.epub",
            sizeBytes = 2048L,
            importedAt = 100L,
            seriesId = seriesId,
            publisher = "Editora Manual",
        )
        val bookId = bookDao.insert(applyMetadataToBook(baseBook, selection, targetSeriesId = seriesId))

        // Unlink book metadata via ViewModel storage helper
        assertTrue(unlinkMetadataInStorage(seriesDao, bookDao, seriesId = seriesId, bookId = bookId, now = 300L))
        val unlinkedBook = bookDao.byId(bookId)!!
        assertEquals("O Nome do Vento", unlinkedBook.title)
        assertTrue(parseMetadataSources(unlinkedBook.metadataSourcesJson).isEmpty())
        assertNull(unlinkedBook.metadataSourcesJson)
        assertNull(unlinkedBook.summary)
        assertNull(unlinkedBook.isbn)
        assertNull(unlinkedBook.publishedDate)
        assertNull(unlinkedBook.language)
        assertNull(unlinkedBook.seriesPosition)
        assertNull(unlinkedBook.author)
        assertNull(unlinkedBook.openLibraryKey)
        assertNull(unlinkedBook.wikidataQid)
        assertNull(unlinkedBook.charactersJson)
        assertNull(unlinkedBook.locationsJson)
        assertEquals("Editora Manual", unlinkedBook.publisher)
        assertEquals(seriesId, unlinkedBook.seriesId)
        assertFalse(unlinkedBook.hasLinkedMetadata())

        // Wrong seriesId must not unlink book
        assertFalse(unlinkMetadataInStorage(seriesDao, bookDao, seriesId = seriesId + 99, bookId = bookId, now = 310L))

        // Unlink series metadata via ViewModel storage helper
        assertTrue(unlinkMetadataInStorage(seriesDao, bookDao, seriesId = seriesId, bookId = null, now = 400L))
        val unlinkedSeries = seriesDao.byId(seriesId)!!
        assertEquals("A Crônica do Matador do Rei", unlinkedSeries.name)
        assertTrue(parseMetadataSources(unlinkedSeries.metadataSourcesJson).isEmpty())
        assertNull(unlinkedSeries.metadataSourcesJson)
        assertNull(unlinkedSeries.summary)
        assertNull(unlinkedSeries.wikidataQid)
        assertNull(unlinkedSeries.charactersJson)
        assertNull(unlinkedSeries.locationsJson)
        assertEquals("Autor Manual da Série", unlinkedSeries.author)
        assertEquals("Manter nomes próprios", unlinkedSeries.styleNotes)
        assertEquals(400L, unlinkedSeries.updatedAt)
        assertFalse(unlinkedSeries.hasLinkedMetadata())

        // Series remains recognizable by name after unlinking
        val byName = seriesDao.findByNameIgnoreCase("a crônica do matador do rei")
        assertNotNull(byName)
        assertEquals(seriesId, byName?.id)

        val matched = matchExistingSeriesForAuthor(
            matches = seriesDao.findAllByNameIgnoreCase("A Crônica do Matador do Rei"),
            cleanAuthor = "Autor Manual da Série",
            seriesDao = seriesDao,
            now = 500L,
        )
        assertEquals(seriesId, matched?.id)
    }
}
