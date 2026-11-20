package io.github.playertwo1.bookconverter.data

import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playertwo1.bookconverter.core.epub.ChunkTranslationInput
import io.github.playertwo1.bookconverter.core.epub.Epub
import io.github.playertwo1.bookconverter.core.epub.EpubExporter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ReviewAndEpubExportTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun openDb(name: String): AppDatabase {
        return Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .build()
    }

    private fun deleteDb(name: String) {
        context.deleteDatabase(name)
    }

    private fun fileSha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            var n: Int
            while (input.read(buf).also { n = it } > 0) {
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun createSyntheticEpub(
        file: File,
        dummyImageBytes: ByteArray,
        cssContent: String,
    ) {
        val opf = """
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
                <metadata>
                    <dc:title xmlns:dc="http://purl.org/dc/elements/1.1/">Synthetic Test Book</dc:title>
                    <dc:language xmlns:dc="http://purl.org/dc/elements/1.1/">en</dc:language>
                </metadata>
                <manifest>
                    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    <item id="css" href="styles/style.css" media-type="text/css"/>
                    <item id="img" href="images/icon.png" media-type="image/png"/>
                    <item id="c1" href="text/c1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="text/c2.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine toc="ncx">
                    <itemref idref="c1"/>
                    <itemref idref="c2"/>
                </spine>
            </package>
        """.trimIndent()

        val ncx = """
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/">
                <navMap>
                    <navPoint id="p1"><navLabel><text>Chapter One</text></navLabel><content src="text/c1.xhtml"/></navPoint>
                    <navPoint id="p2"><navLabel><text>Chapter Two</text></navLabel><content src="text/c2.xhtml"/></navPoint>
                </navMap>
            </ncx>
        """.trimIndent()

        val c1 = """<html xmlns="http://www.w3.org/1999/xhtml"><head><link rel="stylesheet" href="../styles/style.css"/></head><body><h1>Chapter One</h1><p>First paragraph of chapter one.</p></body></html>"""
        val c2 = """<html xmlns="http://www.w3.org/1999/xhtml"><head><link rel="stylesheet" href="../styles/style.css"/></head><body><h1>Chapter Two</h1><p><img src="../images/icon.png" alt="Icon"/>Second paragraph of chapter two.</p></body></html>"""

        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            fun put(name: String, bytes: ByteArray, stored: Boolean = false) {
                val entry = ZipEntry(name)
                if (stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                z.putNextEntry(entry)
                z.write(bytes)
                z.closeEntry()
            }

            put("mimetype", "application/epub+zip".toByteArray(), stored = true)
            put("META-INF/container.xml", """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""".toByteArray())
            put("OEBPS/content.opf", opf.toByteArray())
            put("OEBPS/toc.ncx", ncx.toByteArray())
            put("OEBPS/styles/style.css", cssContent.toByteArray())
            put("OEBPS/images/icon.png", dummyImageBytes)
            put("OEBPS/text/c1.xhtml", c1.toByteArray())
            put("OEBPS/text/c2.xhtml", c2.toByteArray())
        }
        file.parentFile?.mkdirs()
        file.writeBytes(bos.toByteArray())
    }

    /**
     * AC13: Validação completa no Android real:
     * - Criação de EPUB multi-capítulo (2 XHTMLs, CSS e imagem PNG sintética).
     * - Cálculo e verificação de SHA-256 do arquivo original.
     * - Persistência de Book, TranslationJob e TranslationChunks no Room.
     * - Edição manual de bloco com encodeEditedBlocks e saveEditedChunkAndSyncJob.
     * - Verificação da invariante job.blockDone == dao.countCompletedChunks(jobId).
     * - Mapeamento com buildBlockMapFromChunks e exportação para cacheDir/exports/test-pt-BR.epub via exportPtBr.
     * - Validação estrutural do relatório (valid == true, 2 spines, 0 untranslated, 0 issues).
     * - SHA-256 do arquivo original 100% inalterado!
     * - Reabertura do EPUB exportado: texto traduzido e editado à mão na ordem certa, sem omissões/duplicações,
     *   CSS e imagem preservados byte-a-byte, mimetype STORED como primeira entrada.
     * - FileProvider.getUriForFile gerando content:// URI válida legível via ContentResolver.
     */
    @Test
    fun ac13EpubExportManualEditStructuralValidationAndFileProvider() = runBlocking {
        val dbName = "ac13-review-export.db"
        deleteDb(dbName)
        val db = openDb(dbName)

        val testDir = File(context.cacheDir, "test_books_${System.currentTimeMillis()}").apply { mkdirs() }
        val originalFile = File(testDir, "original.epub")
        val dummyImageBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01)
        val cssContent = "body { margin: 0; } h1 { color: #111; } p { font-size: 16px; }"

        try {
            // 1. Cria EPUB válido multi-capítulo e calcula SHA-256
            createSyntheticEpub(originalFile, dummyImageBytes, cssContent)
            assertTrue("original.epub deve existir", originalFile.exists())
            val sha256Original = fileSha256(originalFile)

            // 2. Persistir Book, TranslationJob e TranslationChunks no Room
            val now = System.currentTimeMillis()
            val bookId = db.books().insert(
                Book(
                    title = "Livro de Teste AC13",
                    author = "Autor de Teste",
                    language = "en",
                    format = "EPUB",
                    sha256 = sha256Original,
                    privatePath = originalFile.absolutePath,
                    sourceName = "original.epub",
                    sizeBytes = originalFile.length(),
                    importedAt = now,
                )
            )

            // Chunk 0: Spine 0 (Chapter One + Paragraph) - 2 blocos
            val chunk0 = TranslationChunk(
                chunkIndex = 0,
                spineIndex = 0,
                startBlockIndex = 0,
                endBlockIndex = 1,
                blockCount = 2,
                sourceText = "⟦1⟧\nChapter One\n\n⟦2⟧\nFirst paragraph of chapter one.",
                translatedText = "⟦1⟧\nCapítulo Um\n\n⟦2⟧\nPrimeiro parágrafo do capítulo um traduzido pelo modelo.",
                status = ChunkStatus.COMPLETED,
                updatedAt = now,
            )

            // Chunk 1: Spine 1, Bloco 0 (Chapter Two) - 1 bloco
            val chunk1 = TranslationChunk(
                chunkIndex = 1,
                spineIndex = 1,
                startBlockIndex = 0,
                endBlockIndex = 0,
                blockCount = 1,
                sourceText = "⟦1⟧\nChapter Two",
                translatedText = "⟦1⟧\nCapítulo Dois",
                status = ChunkStatus.COMPLETED,
                updatedAt = now,
            )

            // Chunk 2: Spine 1, Bloco 1 (Paragraph com imagem) - 1 bloco
            val chunk2 = TranslationChunk(
                chunkIndex = 2,
                spineIndex = 1,
                startBlockIndex = 1,
                endBlockIndex = 1,
                blockCount = 1,
                sourceText = "⟦1⟧\n<img src=\"../images/icon.png\" alt=\"Icon\"/>Second paragraph of chapter two.",
                translatedText = "⟦1⟧\n<img src=\"../images/icon.png\" alt=\"Icon\"/>Segundo parágrafo do capítulo dois traduzido.",
                status = ChunkStatus.COMPLETED,
                updatedAt = now,
            )

            val job = TranslationJob(
                bookId = bookId,
                status = JobStatus.COMPLETED,
                provider = TranslationProvider.OPENAI,
                modelId = "gpt-4o",
                blockTotal = 4,
                blockDone = 0,
                createdAt = now,
                updatedAt = now,
            )

            val jobId = db.translations().createJobWithChunks(job, listOf(chunk0, chunk1, chunk2))
            db.translations().syncJobProgress(jobId, now)

            val initialJob = db.translations().jobById(jobId)
            assertNotNull(initialJob)
            assertEquals(3, db.translations().countCompletedChunks(jobId))
            assertEquals(db.translations().countCompletedChunks(jobId), initialJob?.blockDone)

            // 3. Simula edição manual de um bloco via EpubExporter.encodeEditedBlocks + dao.saveEditedChunkAndSyncJob
            val dbChunks = db.translations().chunksForJob(jobId)
            val chunk0Db = dbChunks.first { it.chunkIndex == 0 }
            val decodedBlocks = EpubExporter.decodeChunkBlocks(chunk0Db.translatedText, chunk0Db.blockCount)
            assertNotNull(decodedBlocks)
            assertEquals(2, decodedBlocks!!.size)

            val handEditedText = "Primeiro parágrafo do capítulo um com edição manual refinada pelo revisor humano!"
            val editedBlocks = listOf(decodedBlocks[0], handEditedText)
            val encodedEdited = EpubExporter.encodeEditedBlocks(editedBlocks)

            val editTime = System.currentTimeMillis()
            db.translations().saveEditedChunkAndSyncJob(jobId, chunk0Db.id, encodedEdited, editTime)

            // Verifica invariante: job.blockDone == dao.countCompletedChunks(jobId)
            val updatedJob = db.translations().jobById(jobId)
            val completedCount = db.translations().countCompletedChunks(jobId)
            assertEquals("Invariante de blocos concluídos deve ser mantida", completedCount, updatedJob?.blockDone)
            assertEquals(3, completedCount)

            // 4. Gera blockMap com EpubExporter.buildBlockMapFromChunks
            val chunksAfterEdit = db.translations().chunksForJob(jobId)
            val chunkInputs = chunksAfterEdit.map { c ->
                ChunkTranslationInput(
                    spineIndex = c.spineIndex,
                    startBlockIndex = c.startBlockIndex,
                    endBlockIndex = c.endBlockIndex,
                    blockCount = c.blockCount,
                    translatedText = c.translatedText,
                )
            }

            val blockMap = EpubExporter.buildBlockMapFromChunks(originalFile, chunkInputs)
            assertEquals(4, blockMap.size)
            assertEquals("Capítulo Um", blockMap["0:0"])
            assertEquals(handEditedText, blockMap["0:1"])
            assertEquals("Capítulo Dois", blockMap["1:0"])
            assertEquals("<img src=\"../images/icon.png\" alt=\"Icon\"/>Segundo parágrafo do capítulo dois traduzido.", blockMap["1:1"])

            // 5. Exporta para File(context.cacheDir, "exports/test-pt-BR.epub") via EpubExporter.exportPtBr
            val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val outFile = File(exportsDir, "test-pt-BR.epub")
            if (outFile.exists()) {
                outFile.delete()
            }

            val report = EpubExporter.exportPtBr(originalFile, outFile, blockMap)

            // 6. Verificações do Relatório
            assertTrue("Relatório deve ser válido: issues=${report.issues}", report.valid)
            assertEquals(2, report.spineCountOriginal)
            assertEquals(2, report.spineCountExported)
            assertEquals(4, report.totalBlocks)
            assertEquals(4, report.translatedBlocks)
            assertEquals(0, report.untranslatedBlocks)
            assertTrue("Issues deve ser vazia", report.issues.isEmpty())

            // 7. SHA-256 de original.epub 100% inalterado!
            val sha256After = fileSha256(originalFile)
            assertEquals("SHA-256 de original.epub deve ser 100% inalterado", sha256Original, sha256After)

            // 8. Reabrindo o EPUB exportado com Epub.read(...)
            val exportedBook = outFile.inputStream().buffered().use { Epub.read(it) }
            assertEquals(2, exportedBook.spine.size)
            assertEquals(listOf("OEBPS/text/c1.xhtml", "OEBPS/text/c2.xhtml"), exportedBook.spine)

            val exportedBlocks = exportedBook.blocks()
            assertEquals(4, exportedBlocks.size)
            assertEquals("Capítulo Um", exportedBlocks[0].text)
            assertEquals(handEditedText, exportedBlocks[1].text)
            assertEquals("Capítulo Dois", exportedBlocks[2].text)
            assertEquals("Segundo parágrafo do capítulo dois traduzido.", exportedBlocks[3].text)

            // CSS e imagem PNG sintética preservados
            assertArrayEquals("Imagem icon.png deve ser preservada byte-a-byte", dummyImageBytes, exportedBook.entries["OEBPS/images/icon.png"])
            assertEquals("CSS deve ser preservado intacto", cssContent, String(exportedBook.entries.getValue("OEBPS/styles/style.css"), Charsets.UTF_8))

            // Mimetype como primeiro registro STORED
            ZipInputStream(outFile.inputStream()).use { zis ->
                val first = zis.nextEntry
                assertNotNull(first)
                assertEquals("mimetype", first!!.name)
                assertEquals(ZipEntry.STORED, first.method)
            }

            // 9. FileProvider.getUriForFile retorna URI content:// válida
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
            assertNotNull("URI do FileProvider não pode ser nula", uri)
            assertEquals("content", uri.scheme)
            assertEquals("${context.packageName}.fileprovider", uri.authority)

            // Verifica leitura real via ContentResolver
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val header = ByteArray(4)
                val bytesRead = stream.read(header)
                assertEquals(4, bytesRead)
                assertEquals(0x50.toByte(), header[0]) // 'P'
                assertEquals(0x4B.toByte(), header[1]) // 'K'
            } ?: org.junit.Assert.fail("ContentResolver falhou ao abrir stream para $uri")
        } finally {
            db.close()
            deleteDb(dbName)
            testDir.deleteRecursively()
        }
    }

    /**
     * Valida exportação parcial: capítulos não traduzidos permanecem intactos como fallback,
     * e o relatório indica a quantidade exata de blocos traduzidos e não traduzidos.
     */
    @Test
    fun partialTranslationKeepsFallbackAndValidatesAccurately() = runBlocking {
        val testDir = File(context.cacheDir, "partial_test_${System.currentTimeMillis()}").apply { mkdirs() }
        val originalFile = File(testDir, "partial_orig.epub")
        val outFile = File(testDir, "partial_ptbr.epub")
        val dummyImage = byteArrayOf(1, 2, 3, 4)
        val css = "p { color: blue; }"

        try {
            createSyntheticEpub(originalFile, dummyImage, css)
            val sha256Before = fileSha256(originalFile)

            // Traduz somente o capítulo 1 (blocos 0:0 e 0:1)
            val translations = mapOf(
                "0:0" to "Capítulo Um Parcial",
                "0:1" to "Primeiro parágrafo traduzido apenas.",
            )

            val report = EpubExporter.exportPtBr(originalFile, outFile, translations)

            assertEquals("SHA-256 do arquivo original inalterado", sha256Before, fileSha256(originalFile))
            assertTrue(report.valid)
            assertEquals(2, report.spineCountOriginal)
            assertEquals(2, report.spineCountExported)
            assertEquals(4, report.totalBlocks)
            assertEquals(2, report.translatedBlocks)
            assertEquals(2, report.untranslatedBlocks)
            assertTrue(report.issues.isEmpty())

            val exportedBook = Epub.read(outFile.readBytes())
            val blocks = exportedBook.blocks()
            assertEquals(4, blocks.size)
            assertEquals("Capítulo Um Parcial", blocks[0].text)
            assertEquals("Primeiro parágrafo traduzido apenas.", blocks[1].text)
            // Cap 2 manteve texto original em inglês
            assertEquals("Chapter Two", blocks[2].text)
            assertEquals("Second paragraph of chapter two.", blocks[3].text)
        } finally {
            testDir.deleteRecursively()
        }
    }
}
