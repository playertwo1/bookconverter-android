package io.github.playertwo1.bookconverter.data

import io.github.playertwo1.bookconverter.core.epub.DrmProtected
import io.github.playertwo1.bookconverter.core.epub.EpubException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
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
