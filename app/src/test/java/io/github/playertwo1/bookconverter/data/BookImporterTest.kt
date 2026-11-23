package io.github.playertwo1.bookconverter.data

import io.github.playertwo1.bookconverter.core.epub.DrmProtected
import io.github.playertwo1.bookconverter.core.epub.Epub
import io.github.playertwo1.bookconverter.core.epub.EpubException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BookImporterTest {
    @get:Rule val tmp = TemporaryFolder()

    // Unit tests run with app/ as the working directory; the real books live in :core's test resources.
    private val animalFarm = File("../core/src/test/resources/books/animal_farm.epub")

    private class FakeDao : BookDao {
        val rows = mutableListOf<Book>()
        override fun all(): Flow<List<Book>> = flowOf(rows)
        override suspend fun byId(id: Long) = rows.firstOrNull { it.id == id }
        override suspend fun bySha(sha256: String) = rows.firstOrNull { it.sha256 == sha256 }
        override suspend fun insert(book: Book): Long { rows += book.copy(id = rows.size + 1L); return rows.size.toLong() }
        override suspend fun setSeries(bookId: Long, seriesId: Long?) {
            rows.indexOfFirst { it.id == bookId }.takeIf { it >= 0 }?.let {
                rows[it] = rows[it].copy(seriesId = seriesId)
            }
        }
        override suspend fun setBookSeries(bookId: Long, seriesId: Long?) = setSeries(bookId, seriesId)
        override fun observeBySeries(seriesId: Long): Flow<List<Book>> = flowOf(rows.filter { it.seriesId == seriesId })
        override suspend fun booksInSeries(seriesId: Long): List<Book> = rows.filter { it.seriesId == seriesId }
    }

    private class FakeSeriesDao : SeriesDao {
        val rows = mutableListOf<Series>()
        private var nextId = 1L

        override fun observeAllSummaries(): Flow<List<SeriesSummary>> = flowOf(emptyList())
        override fun observeAll(): Flow<List<Series>> = flowOf(rows)
        override fun observeById(seriesId: Long): Flow<Series?> = flowOf(rows.firstOrNull { it.id == seriesId })
        override suspend fun byId(seriesId: Long): Series? = rows.firstOrNull { it.id == seriesId }
        override suspend fun findByNameIgnoreCase(name: String): Series? =
            rows.firstOrNull { it.name.trim().equals(name.trim(), ignoreCase = true) }

        override suspend fun insertSeries(series: Series): Long {
            val id = nextId++
            rows += series.copy(id = id)
            return id
        }

        override suspend fun updateSeries(series: Series) {
            val idx = rows.indexOfFirst { it.id == series.id }
            if (idx >= 0) rows[idx] = series
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

    private fun createSyntheticEpub(
        title: String,
        author: String? = null,
        seriesMetaXml: String = "",
        bodyText: String = "Chapter one content.",
    ): ByteArray {
        val bos = ByteArrayOutputStream()
        val authorXml = if (author != null) "<dc:creator>$author</dc:creator>" else ""
        ZipOutputStream(bos).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write("application/epub+zip".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write("""<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zip.write("""<package version="2.0" xmlns="http://www.idpf.org/2007/opf" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>$title</dc:title>$authorXml<dc:language>en</dc:language><dc:identifier id="id">$title</dc:identifier>$seriesMetaXml</metadata><manifest><item id="p1" href="part.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="p1"/></spine></package>""".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/part.xhtml"))
            zip.write("""<html xmlns="http://www.w3.org/1999/xhtml"><body><p>$bodyText</p></body></html>""".toByteArray())
            zip.closeEntry()
        }
        return bos.toByteArray()
    }

    private fun createSyntheticMobi(version: Int, encryption: Int, record0Offset: Int = 82): ByteArray {
        val size = record0Offset + 40
        val bytes = ByteArray(size)
        "BOOKMOBI".toByteArray(Charsets.US_ASCII).copyInto(bytes, destinationOffset = 60)
        bytes[78] = ((record0Offset shr 24) and 0xFF).toByte()
        bytes[79] = ((record0Offset shr 16) and 0xFF).toByte()
        bytes[80] = ((record0Offset shr 8) and 0xFF).toByte()
        bytes[81] = (record0Offset and 0xFF).toByte()

        bytes[record0Offset + 12] = ((encryption shr 8) and 0xFF).toByte()
        bytes[record0Offset + 13] = (encryption and 0xFF).toByte()

        bytes[record0Offset + 36] = ((version shr 24) and 0xFF).toByte()
        bytes[record0Offset + 37] = ((version shr 16) and 0xFF).toByte()
        bytes[record0Offset + 38] = ((version shr 8) and 0xFF).toByte()
        bytes[record0Offset + 39] = (version and 0xFF).toByte()
        return bytes
    }

    private fun sha256Of(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    @Test fun importsRealEpubAsPrivateCopy() = runBlocking {
        val dir = tmp.newFolder("books")
        val dao = FakeDao()
        val r = animalFarm.inputStream().use { importEpub(it, "animal_farm.epub", dir, dao) }

        assertFalse(r.alreadyInLibrary)
        assertEquals("en", r.book.language)
        assertTrue(r.book.title.isNotBlank())
        assertEquals(animalFarm.length(), r.book.sizeBytes)
        assertEquals(listOf("${r.book.sha256}.epub"), dir.list()!!.toList())
        assertTrue(File(r.book.privatePath).readBytes().contentEquals(animalFarm.readBytes()))
    }

    @Test fun sameFileTwiceKeepsOneCopy() = runBlocking {
        val dir = tmp.newFolder("books")
        val dao = FakeDao()
        val first = animalFarm.inputStream().use { importEpub(it, "a.epub", dir, dao) }
        val second = animalFarm.inputStream().use { importEpub(it, "b.epub", dir, dao) }

        assertTrue(second.alreadyInLibrary)
        assertEquals(first.book.id, second.book.id)
        assertEquals(1, dao.rows.size)
        assertEquals(1, dir.list()!!.size)
    }

    @Test fun rejectsNonEpubAndLeavesNothingBehind() = runBlocking {
        val dir = tmp.newFolder("books")
        val dao = FakeDao()
        try {
            importEpub("not a zip".byteInputStream(), "x.epub", dir, dao)
            fail("expected EpubException")
        } catch (_: EpubException) {
        }
        assertEquals(0, dao.rows.size)
        assertEquals(0, dir.list()!!.size)
    }

    @Test fun importReturnsVerification() = runBlocking {
        val r = animalFarm.inputStream().use { importEpub(it, "a.epub", tmp.newFolder("books"), FakeDao()) }
        assertEquals(393, r.verification.blocks)
        assertTrue(r.verification.warnings.isEmpty())
    }

    @Test fun namesMobiAndPdfInsteadOfGenericError() = runBlocking {
        val mobi = ByteArray(100).also { "BOOKMOBI".toByteArray().copyInto(it, 60) }
        for ((bytes, word) in listOf(mobi to "MOBI", "%PDF-1.7".toByteArray() to "PDF")) {
            val dir = tmp.newFolder()
            try {
                importEpub(bytes.inputStream(), "x", dir, FakeDao())
                fail("expected EpubException for $word")
            } catch (e: EpubException) {
                assertTrue(e.message!!, word in e.message!!)
            }
            assertEquals(0, dir.list()!!.size)
        }
    }

    @Test fun refusesDrmEpubWithMessage() = runBlocking {
        val dir = tmp.newFolder("books")
        try {
            importEpub(drmCopyOf(animalFarm).inputStream(), "drm.epub", dir, FakeDao())
            fail("expected DrmProtected")
        } catch (e: DrmProtected) {
            assertTrue(e.message!!, "DRM" in e.message!!)
        }
        assertEquals(0, dir.list()!!.size)
    }

    @Test fun refusesDrmMobiWithoutSavingOrInserting() = runBlocking {
        val dir = tmp.newFolder("books")
        val dao = FakeDao()
        val drmMobi = createSyntheticMobi(version = 6, encryption = 2)

        try {
            importEpub(drmMobi.inputStream(), "protected.mobi", dir, dao)
            fail("expected DrmProtected")
        } catch (e: DrmProtected) {
            assertTrue(e.message!!, "DRM" in e.message!!)
        }

        assertEquals(0, dao.rows.size)
        assertEquals(0, dir.list()!!.size)
    }

    @Test fun refusesDrmAzw3WithoutSavingOrInserting() = runBlocking {
        val dir = tmp.newFolder("books")
        val dao = FakeDao()
        val drmAzw3 = createSyntheticMobi(version = 8, encryption = 1)

        try {
            importEpub(drmAzw3.inputStream(), "protected.azw3", dir, dao)
            fail("expected DrmProtected")
        } catch (e: DrmProtected) {
            assertTrue(e.message!!, "DRM" in e.message!!)
        }

        assertEquals(0, dao.rows.size)
        assertEquals(0, dir.list()!!.size)
    }

    @Test fun importsMobiSuccessfullyWithStubPackager() = runBlocking {
        val dir = tmp.newFolder("books")
        val dao = FakeDao()
        val mobiBytes = createSyntheticMobi(version = 6, encryption = 0)
        val expectedSha = sha256Of(mobiBytes)

        val r = importEpub(
            input = mobiBytes.inputStream(),
            sourceName = "my_book.mobi",
            booksDir = dir,
            dao = dao,
            mobiToEpub = { _, target ->
                animalFarm.copyTo(target, overwrite = true)
                MobiConvertedMeta(
                    format = "MOBI",
                    title = "Livro MOBI Teste",
                    author = "Autor MOBI",
                    language = "pt"
                )
            }
        )

        assertFalse(r.alreadyInLibrary)
        assertEquals("MOBI", r.book.format)
        assertEquals("Livro MOBI Teste", r.book.title)
        assertEquals("Autor MOBI", r.book.author)
        assertEquals("pt", r.book.language)
        assertEquals(expectedSha, r.book.sha256)
        assertEquals(mobiBytes.size.toLong(), r.book.sizeBytes)
        assertEquals("my_book.mobi", r.book.sourceName)
        assertEquals(File(dir, "$expectedSha.epub").absolutePath, r.book.privatePath)
        assertTrue(r.verification.blocks > 0)

        // The converted EPUB is valid and readable
        val readEpub = File(r.book.privatePath).inputStream().buffered().use { Epub.read(it) }
        assertTrue(readEpub.verify().blocks > 0)
        assertEquals(1, dao.rows.size)
        assertEquals(1, dir.list()!!.size)
    }

    @Test fun importsAzw3SuccessfullyWithStubPackager() = runBlocking {
        val dir = tmp.newFolder("books")
        val dao = FakeDao()
        val azw3Bytes = createSyntheticMobi(version = 8, encryption = 0)
        val expectedSha = sha256Of(azw3Bytes)

        val r = importEpub(
            input = azw3Bytes.inputStream(),
            sourceName = "kindle_fire.azw3",
            booksDir = dir,
            dao = dao,
            mobiToEpub = { _, target ->
                animalFarm.copyTo(target, overwrite = true)
                MobiConvertedMeta(
                    format = "AZW3",
                    title = null, // Should fall back to epub title
                    author = null, // Should fall back to epub author
                    language = null // Should fall back to epub language
                )
            }
        )

        assertFalse(r.alreadyInLibrary)
        assertEquals("AZW3", r.book.format)
        // Fell back to animalFarm metadata
        assertTrue(r.book.title.isNotBlank())
        assertEquals("George Orwell", r.book.author)
        assertEquals("en", r.book.language)
        assertEquals(expectedSha, r.book.sha256)
        assertEquals(azw3Bytes.size.toLong(), r.book.sizeBytes)
        assertEquals("kindle_fire.azw3", r.book.sourceName)
        assertEquals(File(dir, "$expectedSha.epub").absolutePath, r.book.privatePath)

        val readEpub = File(r.book.privatePath).inputStream().buffered().use { Epub.read(it) }
        assertTrue(readEpub.verify().blocks > 0)
        assertEquals(1, dao.rows.size)
        assertEquals(1, dir.list()!!.size)
    }

    @Test fun duplicateMobiImportReturnsExistingBook() = runBlocking {
        val dir = tmp.newFolder("books")
        val dao = FakeDao()
        val mobiBytes = createSyntheticMobi(version = 6, encryption = 0)
        val stubConverter: (File, File) -> MobiConvertedMeta = { _, target ->
            animalFarm.copyTo(target, overwrite = true)
            MobiConvertedMeta("MOBI", "Repetido", "Autor", "pt")
        }

        val first = importEpub(mobiBytes.inputStream(), "a.mobi", dir, dao, mobiToEpub = stubConverter)
        assertFalse(first.alreadyInLibrary)

        val second = importEpub(mobiBytes.inputStream(), "b.mobi", dir, dao, mobiToEpub = stubConverter)
        assertTrue(second.alreadyInLibrary)
        assertEquals(first.book.id, second.book.id)
        assertEquals(1, dao.rows.size)
        assertEquals(1, dir.list()!!.size)
    }

    @Test fun emptyTextMobiThrowsExceptionAndDeletesTarget() = runBlocking {
        val dir = tmp.newFolder("books")
        val dao = FakeDao()
        val mobiBytes = createSyntheticMobi(version = 6, encryption = 0)

        try {
            importEpub(
                input = mobiBytes.inputStream(),
                sourceName = "empty.mobi",
                booksDir = dir,
                dao = dao,
                mobiToEpub = { _, target ->
                    // Create an empty or zero-block epub
                    writeEmptyEpub(target)
                    MobiConvertedMeta("MOBI", "Sem Texto", null, null)
                }
            )
            fail("expected EpubException")
        } catch (e: EpubException) {
            assertTrue(e.message!!, "sem texto extraível" in e.message!!.lowercase())
        }

        assertEquals(0, dao.rows.size)
        assertEquals(0, dir.list()!!.size)
    }

    @Test fun autoCreatesSeriesFromEpubMetadataWithAuthor() = runBlocking {
        val dir = tmp.newFolder("books")
        val bookDao = FakeDao()
        val seriesDao = FakeSeriesDao()
        val epubBytes = createSyntheticEpub(
            title = "O Nome do Vento",
            author = "Patrick Rothfuss",
            seriesMetaXml = """<meta name="calibre:series" content="A Crônica do Matador do Rei"/>""",
        )

        val res = importEpub(
            input = epubBytes.inputStream(),
            sourceName = "nome_do_vento.epub",
            booksDir = dir,
            dao = bookDao,
            seriesDao = seriesDao,
        )

        assertEquals("A Crônica do Matador do Rei", res.linkedSeriesName)
        assertEquals(1, seriesDao.rows.size)
        val createdSeries = seriesDao.rows.first()
        assertEquals("A Crônica do Matador do Rei", createdSeries.name)
        assertEquals("Patrick Rothfuss", createdSeries.author)
        assertEquals(createdSeries.id, res.book.seriesId)
    }

    @Test fun reusesExistingSeriesIgnoreCaseAndFillsBlankAuthor() = runBlocking {
        val dir = tmp.newFolder("books")
        val bookDao = FakeDao()
        val seriesDao = FakeSeriesDao()
        val existingId = seriesDao.insertSeries(
            Series(
                name = "Mistborn",
                author = null,
                createdAt = 100L,
                updatedAt = 100L,
            )
        )
        val epubBytes = createSyntheticEpub(
            title = "The Well of Ascension",
            author = "Brandon Sanderson",
            seriesMetaXml = """<meta property="belongs-to-collection" id="col1">  mistborn  </meta><meta refines="#col1" property="collection-type">series</meta>""",
            bodyText = "Book two content.",
        )

        val res = importEpub(
            input = epubBytes.inputStream(),
            sourceName = "mistborn2.epub",
            booksDir = dir,
            dao = bookDao,
            seriesDao = seriesDao,
        )

        assertEquals(1, seriesDao.rows.size)
        assertEquals(existingId, res.book.seriesId)
        assertEquals("Mistborn", res.linkedSeriesName)
        assertEquals("Brandon Sanderson", seriesDao.byId(existingId)?.author)
    }

    @Test fun epubWithoutSeriesMetadataDoesNotCreateSeries() = runBlocking {
        val dir = tmp.newFolder("books")
        val bookDao = FakeDao()
        val seriesDao = FakeSeriesDao()
        val epubBytes = createSyntheticEpub(title = "Standalone Book", author = "Some Author")

        val res = importEpub(
            input = epubBytes.inputStream(),
            sourceName = "standalone.epub",
            booksDir = dir,
            dao = bookDao,
            seriesDao = seriesDao,
        )

        assertNull(res.book.seriesId)
        assertNull(res.linkedSeriesName)
        assertEquals(0, seriesDao.rows.size)
    }

    @Test fun suggestSeriesNameStripsTrailingVolumeMarkersOnly() {
        assertEquals("Duna", suggestSeriesName("Duna - Livro 1"))
        assertEquals("Mistborn", suggestSeriesName("Mistborn (Vol. 2)"))
        assertEquals("Foundation", suggestSeriesName("Foundation #3"))
        assertEquals("Saga", suggestSeriesName("Saga: Book 4"))
        assertEquals("O Hobbit", suggestSeriesName("O Hobbit"))
        assertEquals("1984", suggestSeriesName("1984"))
    }
}

private fun writeEmptyEpub(output: File) {
    output.parentFile?.mkdirs()
    ZipOutputStream(output.outputStream()).use { zip ->
        zip.putNextEntry(ZipEntry("mimetype"))
        zip.write("application/epub+zip".toByteArray())
        zip.closeEntry()

        zip.putNextEntry(ZipEntry("META-INF/container.xml"))
        zip.write("""<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray())
        zip.closeEntry()

        zip.putNextEntry(ZipEntry("content.opf"))
        zip.write("""<package version="2.0" xmlns="http://www.idpf.org/2007/opf" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Empty</dc:title><dc:language>en</dc:language><dc:identifier id="id">empty</dc:identifier></metadata><manifest><item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/><item id="p1" href="part.xhtml" media-type="application/xhtml+xml"/></manifest><spine toc="ncx"><itemref idref="p1"/></spine></package>""".toByteArray())
        zip.closeEntry()

        zip.putNextEntry(ZipEntry("toc.ncx"))
        zip.write("""<ncx version="2005-1" xmlns="http://www.daisy.org/z3986/2005/ncx/"><head><meta name="dtb:uid" content="empty"/></head><docTitle><text>Empty</text></docTitle><navMap><navPoint id="np1" playOrder="1"><navLabel><text>P1</text></navLabel><content src="part.xhtml"/></navPoint></navMap></ncx>""".toByteArray())
        zip.closeEntry()

        zip.putNextEntry(ZipEntry("part.xhtml"))
        zip.write("""<!DOCTYPE html><html><head><title>P1</title></head><body></body></html>""".toByteArray())
        zip.closeEntry()
    }
}

/** A real book plus an Adobe-style encryption.xml (AES), i.e. what a DRM'd store EPUB looks like. AC04 fixture. */
internal fun drmCopyOf(epub: File): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        ZipInputStream(epub.inputStream()).use { src ->
            while (true) {
                val e = src.nextEntry ?: break
                zip.putNextEntry(ZipEntry(e.name)); src.copyTo(zip); zip.closeEntry()
            }
        }
        zip.putNextEntry(ZipEntry("META-INF/encryption.xml"))
        zip.write("""<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><EncryptedData xmlns="http://www.w3.org/2001/04/xmlenc#"><EncryptionMethod Algorithm="http://www.w3.org/2001/04/xmlenc#aes128-cbc"/></EncryptedData></encryption>""".toByteArray())
        zip.closeEntry()
    }
    return out.toByteArray()
}
