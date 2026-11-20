package io.github.playertwo1.bookconverter.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playertwo1.bookconverter.core.epub.DrmProtected
import io.github.playertwo1.bookconverter.core.epub.Epub
import io.github.playertwo1.bookconverter.core.epub.EpubException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class MobiNativeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private lateinit var dao: BookDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.books()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * Builds a minimal, valid PalmDOC/MOBI6 file in bytes.
     * Record 0 contains PalmDOC header + MOBI header (v6) + EXTH header + title.
     * Record 1 contains HTML content.
     * Record 2: FLIS.
     * Record 3: FCIS.
     * Record 4: EOF marker.
     */
    private fun buildSyntheticMobi(
        title: String = "Minimal Book",
        author: String = "Native Tester",
        language: String = "en",
        htmlContent: String = "<html><body><h1>Chapter 1</h1><p>Hello from native libmobi on Android.</p></body></html>",
        encryptionType: Int = 0,
    ): ByteArray {
        val textBytes = htmlContent.toByteArray(Charsets.UTF_8)
        val titleBytes = title.toByteArray(Charsets.UTF_8)
        val authorBytes = author.toByteArray(Charsets.UTF_8)
        val langBytes = language.toByteArray(Charsets.UTF_8)

        // EXTH records
        val exthBuf = ByteArrayOutputStream()
        fun writeExth(tag: Int, bytes: ByteArray) {
            val dos = DataOutputStream(exthBuf)
            dos.writeInt(tag)
            dos.writeInt(bytes.size + 8)
            dos.write(bytes)
        }
        writeExth(100, authorBytes) // EXTH_AUTHOR
        writeExth(503, titleBytes)  // EXTH_UPDATEDTITLE
        writeExth(524, langBytes)   // EXTH_LANGUAGE

        val rawExthRecords = exthBuf.toByteArray()
        val rawExthLen = 12 + rawExthRecords.size
        val padding = (4 - (rawExthLen % 4)) % 4
        val exthHeaderBuf = ByteArrayOutputStream()
        val exthDos = DataOutputStream(exthHeaderBuf)
        exthDos.write("EXTH".toByteArray(Charsets.US_ASCII))
        exthDos.writeInt(rawExthLen + padding)
        exthDos.writeInt(3) // 3 records
        exthDos.write(rawExthRecords)
        if (padding > 0) {
            exthDos.write(ByteArray(padding))
        }
        val exthBytes = exthHeaderBuf.toByteArray()

        val mobiHdrStart = 16
        val mobiHdrLen = 264
        val exthStart = mobiHdrStart + mobiHdrLen
        val fullNameStart = exthStart + exthBytes.size
        val fullNameLen = titleBytes.size

        val mobiBuf = ByteBuffer.allocate(mobiHdrLen).order(ByteOrder.BIG_ENDIAN)
        mobiBuf.put("MOBI".toByteArray(Charsets.US_ASCII)) // 0..3
        mobiBuf.putInt(mobiHdrLen)                         // 4..7
        mobiBuf.putInt(2)                                   // 8..11: mobi_type = BOOK
        mobiBuf.putInt(65001)                               // 12..15: encoding = UTF-8
        mobiBuf.putInt(12345)                               // 16..19: uid
        mobiBuf.putInt(6)                                   // 20..23: version = 6
        for (i in 0 until 11) {
            mobiBuf.putInt(-1)                              // 24..67: indices 0xFFFFFFFF
        }
        mobiBuf.putInt(fullNameStart)                       // 68..71: full_name_offset
        mobiBuf.putInt(fullNameLen)                         // 72..75: full_name_length
        mobiBuf.putInt(1033)                                // 76..79: locale
        mobiBuf.putInt(0)                                   // 80..83: dict_input_lang
        mobiBuf.putInt(0)                                   // 84..87: dict_output_lang
        mobiBuf.putInt(6)                                   // 88..91: min_version
        mobiBuf.putInt(-1)                                  // 92..95: image_index = 0xFFFFFFFF
        mobiBuf.putInt(0)                                   // 96..99: huff_rec_index
        mobiBuf.putInt(0)                                   // 100..103: huff_rec_count
        mobiBuf.putInt(0)                                   // 104..107: datp_rec_index
        mobiBuf.putInt(0)                                   // 108..111: datp_rec_count
        mobiBuf.putInt(0x50)                                // 112..115: exth_flags
        mobiBuf.put(ByteArray(32))                          // 116..147: 32 zeroes
        mobiBuf.putInt(-1)                                  // 148..151: unknown6
        mobiBuf.putInt(-1)                                  // 152..155: drm_offset
        mobiBuf.putInt(0)                                   // 156..159: drm_count
        mobiBuf.putInt(0)                                   // 160..163: drm_size
        mobiBuf.putInt(0)                                   // 164..167: drm_flags
        mobiBuf.put(ByteArray(8))                           // 168..175: 8 zeroes
        mobiBuf.putShort(1.toShort())                       // 176..177: first_text_index
        mobiBuf.putShort(1.toShort())                       // 178..179: last_text_index
        mobiBuf.putInt(0)                                   // 180..183: fdst_section_count
        mobiBuf.putInt(3)                                   // 184..187: fcis_index = record 3
        mobiBuf.putInt(1)                                   // 188..191: fcis_count = 1
        mobiBuf.putInt(2)                                   // 192..195: flis_index = record 2
        mobiBuf.putInt(1)                                   // 196..199: flis_count = 1
        mobiBuf.putInt(0)                                   // 200..203: unknown10
        mobiBuf.putInt(0)                                   // 204..207: unknown11
        mobiBuf.putInt(-1)                                  // 208..211: srcs_index
        mobiBuf.putInt(0)                                   // 212..215: srcs_count
        mobiBuf.putInt(0)                                   // 216..219: unknown12
        mobiBuf.putInt(0)                                   // 220..223: unknown13
        mobiBuf.putShort(0.toShort())                       // 224..225: 2 zeroes
        mobiBuf.putShort(0.toShort())                       // 226..227: extra_flags
        for (i in 0 until 9) {
            mobiBuf.putInt(-1)                              // 228..263: indices 0xFFFFFFFF
        }
        val mobiBytes = mobiBuf.array()

        // PalmDOC header: 16 bytes
        val palmdocBuf = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        palmdocBuf.putShort(1.toShort())                    // compression = 1 (uncompressed)
        palmdocBuf.putShort(0.toShort())                    // unused
        palmdocBuf.putInt(textBytes.size)                   // text_length
        palmdocBuf.putShort(1.toShort())                    // text_record_count = 1
        palmdocBuf.putShort(4096.toShort())                 // text_record_size = 4096
        palmdocBuf.putShort(encryptionType.toShort())       // encryption_type = 0 or 2
        palmdocBuf.putShort(0.toShort())                    // unknown1
        val palmdocBytes = palmdocBuf.array()

        val rec0Stream = ByteArrayOutputStream()
        rec0Stream.write(palmdocBytes)
        rec0Stream.write(mobiBytes)
        rec0Stream.write(exthBytes)
        rec0Stream.write(titleBytes)
        rec0Stream.write(byteArrayOf(0, 0))
        while (rec0Stream.size() % 4 != 0) {
            rec0Stream.write(0)
        }
        val rec0 = rec0Stream.toByteArray()

        val rec1 = textBytes

        // Record 2: FLIS (36 bytes)
        val rec2 = byteArrayOf(
            0x46, 0x4C, 0x49, 0x53, 0x00, 0x00, 0x00, 0x08, 0x00, 0x41, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x01, 0x00, 0x03, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()
        )

        // Record 3: FCIS (52 bytes)
        val fcisBuf = ByteBuffer.allocate(52).order(ByteOrder.BIG_ENDIAN)
        fcisBuf.put("FCIS".toByteArray(Charsets.US_ASCII))
        fcisBuf.putInt(20)
        fcisBuf.putInt(16)
        fcisBuf.putInt(2)
        fcisBuf.putInt(0)
        fcisBuf.putInt(textBytes.size)
        fcisBuf.putInt(0)
        fcisBuf.putInt(40)
        fcisBuf.putInt(0)
        fcisBuf.putInt(40)
        fcisBuf.putInt(8)
        fcisBuf.putInt(0x00010001)
        fcisBuf.putInt(0)
        val rec3 = fcisBuf.array()

        // Record 4: EOF (4 bytes)
        val rec4 = byteArrayOf(0xE9.toByte(), 0x8E.toByte(), 0x0D, 0x0A)

        val records = listOf(rec0, rec1, rec2, rec3, rec4)
        val numRecs = records.size

        val recordListSize = numRecs * 8 + 2
        val firstRecordOffset = 78 + recordListSize

        val recOffsets = ArrayList<Int>()
        var currOff = firstRecordOffset
        for (r in records) {
            recOffsets.add(currOff)
            currOff += r.size
        }

        val palmHdrBuf = ByteBuffer.allocate(78).order(ByteOrder.BIG_ENDIAN)
        val shortName = title.take(31).toByteArray(Charsets.US_ASCII)
        palmHdrBuf.put(shortName)
        palmHdrBuf.position(60)
        palmHdrBuf.put("BOOKMOBI".toByteArray(Charsets.US_ASCII))
        palmHdrBuf.position(76)
        palmHdrBuf.putShort(numRecs.toShort())

        val recListBuf = ByteBuffer.allocate(recordListSize).order(ByteOrder.BIG_ENDIAN)
        for (i in recOffsets.indices) {
            recListBuf.putInt(recOffsets[i])
            recListBuf.put(0.toByte())
            recListBuf.put(0.toByte())
            recListBuf.putShort((2 * i).toShort())
        }
        recListBuf.putShort(0.toShort()) // 2 gap bytes

        val out = ByteArrayOutputStream()
        out.write(palmHdrBuf.array())
        out.write(recListBuf.array())
        for (r in records) {
            out.write(r)
        }
        return out.toByteArray()
    }

    @Test
    fun nativeBridgeExtractsValidMobiSuccessfully() {
        val mobiBytes = buildSyntheticMobi(
            title = "Android Native Test",
            author = "Libmobi Author",
            htmlContent = "<html><body><h1>Chapter 1</h1><p>Hello from native libmobi on Android.</p></body></html>"
        )
        val mobiFile = tmp.newFile("valid_sample.mobi")
        mobiFile.writeBytes(mobiBytes)

        val outDir = tmp.newFolder("native_extract")
        val res = NativeMobiBridge.extract(mobiFile, outDir)

        assertEquals("OK", res.status)
        assertEquals("MOBI", res.format)
        assertEquals("Android Native Test", res.title)
        assertEquals("Libmobi Author", res.author)

        val markupDir = File(outDir, "markup")
        assertTrue("Markup directory must exist", markupDir.isDirectory)
        val markupFiles = markupDir.listFiles { f -> f.extension == "html" }
        assertNotNull(markupFiles)
        assertTrue("At least one html markup file must be extracted", markupFiles!!.isNotEmpty())
        val htmlContent = markupFiles[0].readText()
        assertTrue(
            "Extracted HTML must contain the source chapter text",
            htmlContent.contains("Hello from native libmobi on Android.")
        )
    }

    @Test
    fun nativeBridgeDetectsDrmMobi() {
        val drmBytes = buildSyntheticMobi(
            title = "Encrypted Book",
            encryptionType = 2
        )
        val drmFile = tmp.newFile("encrypted.mobi")
        drmFile.writeBytes(drmBytes)

        val outDir = tmp.newFolder("drm_extract")
        val res = NativeMobiBridge.extract(drmFile, outDir)

        assertEquals("DRM", res.status)
        assertEquals("Document is protected by DRM", res.errorMessage)
    }

    @Test
    fun importBookImportsValidMobiWithRealLibmobi() = runBlocking {
        val mobiBytes = buildSyntheticMobi(
            title = "Full Import Test",
            author = "Integration Tester",
            htmlContent = "<html><body><h1>Chapter 1</h1><p>Hello from native libmobi on Android.</p></body></html>"
        )
        val booksDir = tmp.newFolder("books_valid")

        val result = importBook(
            input = mobiBytes.inputStream(),
            sourceName = "full_import.mobi",
            booksDir = booksDir,
            dao = dao
        )

        assertFalse(result.alreadyInLibrary)
        assertEquals("MOBI", result.book.format)
        assertEquals("Full Import Test", result.book.title)
        assertEquals("Integration Tester", result.book.author)
        assertTrue("Verification blocks must be > 0", result.verification.blocks > 0)

        val privateFile = File(result.book.privatePath)
        assertTrue("Target EPUB private file must exist", privateFile.exists())
        assertEquals(listOf("${result.book.sha256}.epub"), booksDir.list()!!.toList())

        // Verify the packaged EPUB can be parsed back with core Epub reader
        val verification = privateFile.inputStream().buffered().use { Epub.read(it).verify() }
        assertTrue("EPUB verification blocks must be > 0", verification.blocks > 0)

        // Verify book is inserted in Room database
        val saved = dao.byId(result.book.id)
        assertNotNull(saved)
        assertEquals(result.book.sha256, saved!!.sha256)
        assertEquals("MOBI", saved.format)
    }

    @Test
    fun importBookRejectsDrmMobiWithoutSavingOrInserting() = runBlocking {
        val drmBytes = buildSyntheticMobi(
            title = "Protected Book",
            encryptionType = 2
        )
        val booksDir = tmp.newFolder("books_drm")

        try {
            importBook(
                input = drmBytes.inputStream(),
                sourceName = "drm_protected.mobi",
                booksDir = booksDir,
                dao = dao
            )
            fail("Expected DrmProtected exception")
        } catch (e: DrmProtected) {
            assertTrue("Exception message should indicate DRM", e.message!!.contains("DRM"))
        }

        assertEquals(0, dao.all().first().size)
        val epubFiles = booksDir.listFiles { f -> f.extension == "epub" } ?: emptyArray()
        assertEquals(0, epubFiles.size)
    }

    @Test
    fun importBookRejectsCorruptedMobiWithEpubException() = runBlocking {
        // Construct a corrupted file: BOOKMOBI signature at 60, but invalid truncated payload
        val corruptedBytes = ByteArray(120)
        "BOOKMOBI".toByteArray(Charsets.US_ASCII).copyInto(corruptedBytes, destinationOffset = 60)
        corruptedBytes[78] = 0
        corruptedBytes[79] = 0
        corruptedBytes[80] = 0
        corruptedBytes[81] = 82 // points to 82, but file has only 120 bytes and all zeroes

        val booksDir = tmp.newFolder("books_corrupted")

        try {
            importBook(
                input = corruptedBytes.inputStream(),
                sourceName = "corrupted.mobi",
                booksDir = booksDir,
                dao = dao
            )
            fail("Expected EpubException for corrupted mobi")
        } catch (e: EpubException) {
            assertTrue(
                "EpubException message should not be empty",
                !e.message.isNullOrBlank()
            )
        }

        assertEquals(0, dao.all().first().size)
        val epubFiles = booksDir.listFiles { f -> f.extension == "epub" } ?: emptyArray()
        assertEquals(0, epubFiles.size)
    }
}
