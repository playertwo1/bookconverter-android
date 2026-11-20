package io.github.playertwo1.bookconverter.core

import io.github.playertwo1.bookconverter.core.epub.ChunkTranslationInput
import io.github.playertwo1.bookconverter.core.epub.Epub
import io.github.playertwo1.bookconverter.core.epub.EpubExporter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class EpubExporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

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
                    <item id="img" href="images/cover.png" media-type="image/png"/>
                    <item id="c1" href="text/c1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="text/c2.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c3" href="text/c3.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine toc="ncx">
                    <itemref idref="c1"/>
                    <itemref idref="c2"/>
                    <itemref idref="c3"/>
                </spine>
            </package>
        """.trimIndent()

        val ncx = """
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/">
                <navMap>
                    <navPoint id="p1"><navLabel><text>Capítulo 1</text></navLabel><content src="text/c1.xhtml"/></navPoint>
                    <navPoint id="p2"><navLabel><text>Capítulo 2</text></navLabel><content src="text/c2.xhtml"/></navPoint>
                    <navPoint id="p3"><navLabel><text>Capítulo 3</text></navLabel><content src="text/c3.xhtml"/></navPoint>
                </navMap>
            </ncx>
        """.trimIndent()

        val c1 = """<html xmlns="http://www.w3.org/1999/xhtml"><head><link rel="stylesheet" href="../styles/style.css"/></head><body><h1>Chapter One</h1><p>It was a dark and stormy night.</p><p>The wind was howling fiercely.</p></body></html>"""
        val c2 = """<html xmlns="http://www.w3.org/1999/xhtml"><head><link rel="stylesheet" href="../styles/style.css"/></head><body><h1>Chapter Two</h1><p>Morning came with bright sunshine.</p></body></html>"""
        val c3 = """<html xmlns="http://www.w3.org/1999/xhtml"><head><link rel="stylesheet" href="../styles/style.css"/></head><body><h1>Chapter Three</h1><p><img src="../images/cover.png" alt="Cover"/>End of sample.</p></body></html>"""

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
            put("OEBPS/images/cover.png", dummyImageBytes)
            put("OEBPS/text/c1.xhtml", c1.toByteArray())
            put("OEBPS/text/c2.xhtml", c2.toByteArray())
            put("OEBPS/text/c3.xhtml", c3.toByteArray())
        }
        file.writeBytes(bos.toByteArray())
    }

    @Test
    fun roundTripEncodeAndDecodeBlocks() {
        val originalList = listOf(
            "Primeiro parágrafo de teste com <i>itálico</i>.",
            "Segundo parágrafo com <b>negrito</b>.",
            "Terceiro parágrafo com diálogo: — Olá mundo!",
        )

        val encoded = EpubExporter.encodeEditedBlocks(originalList)
        assertTrue(encoded.contains("⟦1⟧"))
        assertTrue(encoded.contains("⟦2⟧"))
        assertTrue(encoded.contains("⟦3⟧"))

        val decoded = EpubExporter.decodeChunkBlocks(encoded, 3)
        assertNotNull(decoded)
        assertEquals(originalList, decoded)

        // Single block without marker (hand-edited)
        val singleHandEdited = "Parágrafo único editado diretamente sem marcadores."
        val decodedSingle = EpubExporter.decodeChunkBlocks(singleHandEdited, 1)
        assertEquals(listOf(singleHandEdited), decodedSingle)

        // Single block with marker
        val singleWithMarker = "⟦1⟧\nParágrafo único com marcador."
        val decodedSingleMarker = EpubExporter.decodeChunkBlocks(singleWithMarker, 1)
        assertEquals(listOf("Parágrafo único com marcador."), decodedSingleMarker)

        // Empty / null handling
        assertNull(EpubExporter.decodeChunkBlocks(null, 1))
        assertNull(EpubExporter.decodeChunkBlocks("", 1))
        assertNull(EpubExporter.decodeChunkBlocks("    ", 1))
        assertNull(EpubExporter.decodeChunkBlocks("qualquer coisa", 0))
        assertEquals("", EpubExporter.encodeEditedBlocks(emptyList()))
    }

    @Test
    fun buildBlockMapFromChunksConvertsAccurately() {
        val originalFile = tmp.newFile("map_test.epub")
        val dummyImg = byteArrayOf(1, 2, 3)
        val css = "p { color: red; }"
        createSyntheticEpub(originalFile, dummyImg, css)
        val originalBook = Epub.read(originalFile.readBytes())

        val chunks = listOf(
            ChunkTranslationInput(
                spineIndex = 0,
                startBlockIndex = 0,
                endBlockIndex = 2,
                blockCount = 3,
                translatedText = "⟦1⟧\nCapítulo Um\n\n⟦2⟧\nEra uma noite escura.\n\n⟦3⟧\nO vento uivava.",
            ),
            ChunkTranslationInput(
                spineIndex = 1,
                startBlockIndex = 0,
                endBlockIndex = 0,
                blockCount = 1,
                translatedText = "Capítulo Dois editado",
            ),
            ChunkTranslationInput(
                spineIndex = 1,
                startBlockIndex = 1,
                endBlockIndex = 1,
                blockCount = 1,
                translatedText = null, // Pending chunk
            ),
        )

        val blockMap = EpubExporter.buildBlockMapFromChunks(originalBook, chunks)
        assertEquals(4, blockMap.size)
        assertEquals("Capítulo Um", blockMap["0:0"])
        assertEquals("Era uma noite escura.", blockMap["0:1"])
        assertEquals("O vento uivava.", blockMap["0:2"])
        assertEquals("Capítulo Dois editado", blockMap["1:0"])
        assertNull(blockMap["1:1"]) // untranslated
    }

    @Test
    fun exportPtBrFullBookPreservesAssetsAndValidatesCleanly() {
        val originalFile = tmp.newFile("original.epub")
        val outputFile = tmp.newFile("exported_ptbr.epub")
        val dummyImageBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val cssContent = "body { margin: 0; } p { line-height: 1.5; }"

        createSyntheticEpub(originalFile, dummyImageBytes, cssContent)
        val sha256Before = fileSha256(originalFile)

        val translations = mapOf(
            "0:0" to "Capítulo Um",
            "0:1" to "Era uma noite escura e tempestuosa.",
            "0:2" to "O vento uivava ferozmente.",
            "1:0" to "Capítulo Dois",
            "1:1" to "A manhã chegou com um sol brilhante.",
            "2:0" to "Capítulo Três",
            "2:1" to "<img src=\"../images/cover.png\" alt=\"Cover\"/>Fim da amostra.",
        )

        val report = EpubExporter.exportPtBr(originalFile, outputFile, translations)

        // 1. Original file must be byte-for-byte untouched
        val sha256After = fileSha256(originalFile)
        assertEquals("originalFile não pode ser alterado durante a exportação", sha256Before, sha256After)

        // 2. Report validation checks
        assertTrue("Exportação deve ser válida: issues=${report.issues}", report.valid)
        assertEquals(3, report.spineCountOriginal)
        assertEquals(3, report.spineCountExported)
        assertEquals(7, report.totalBlocks)
        assertEquals(7, report.translatedBlocks)
        assertEquals(0, report.untranslatedBlocks)
        assertTrue(report.issues.isEmpty())

        // 3. Re-read exported EPUB and verify content & assets
        val exportedBook = Epub.read(outputFile.readBytes())
        assertEquals(3, exportedBook.spine.size)
        val exportedBlocks = exportedBook.blocks()
        assertEquals(7, exportedBlocks.size)

        val expectedTexts = listOf(
            "Capítulo Um",
            "Era uma noite escura e tempestuosa.",
            "O vento uivava ferozmente.",
            "Capítulo Dois",
            "A manhã chegou com um sol brilhante.",
            "Capítulo Três",
            "Fim da amostra.",
        )
        assertEquals(expectedTexts, exportedBlocks.map { it.text })

        // Image and CSS preservation
        assertArrayEquals(dummyImageBytes, exportedBook.entries["OEBPS/images/cover.png"])
        assertEquals(cssContent, String(exportedBook.entries.getValue("OEBPS/styles/style.css"), Charsets.UTF_8))

        // Check mimetype as first STORED entry
        ZipInputStream(ByteArrayInputStream(outputFile.readBytes())).use { zip ->
            val first = zip.nextEntry
            assertNotNull(first)
            assertEquals("mimetype", first!!.name)
            assertEquals(ZipEntry.STORED, first.method)
        }
    }

    @Test
    fun exportPtBrPartialTranslationKeepsOriginalAsFallback() {
        val originalFile = tmp.newFile("partial_orig.epub")
        val outputFile = tmp.newFile("partial_out.epub")
        val dummyImg = byteArrayOf(10, 20, 30)
        val css = "p { font-size: 14px; }"

        createSyntheticEpub(originalFile, dummyImg, css)
        val sha256Before = fileSha256(originalFile)

        // Translate only Chapter 1 (3 blocks)
        val translations = mapOf(
            "0:0" to "Capítulo Um Traduzido",
            "0:1" to "Noite escura e tempestuosa traduzida.",
            "0:2" to "Vento uivava traduzido.",
        )

        val report = EpubExporter.exportPtBr(originalFile, outputFile, translations)

        assertEquals("originalFile SHA-256 deve permanecer inalterado", sha256Before, fileSha256(originalFile))
        assertTrue("Tradução parcial ainda é estruturalmente válida", report.valid)
        assertEquals(3, report.spineCountOriginal)
        assertEquals(3, report.spineCountExported)
        assertEquals(7, report.totalBlocks)
        assertEquals(3, report.translatedBlocks)
        assertEquals(4, report.untranslatedBlocks)
        assertTrue(report.issues.isEmpty())

        val exportedBook = Epub.read(outputFile.readBytes())
        val texts = exportedBook.blocks().map { it.text }

        // Chapter 1 is translated
        assertEquals("Capítulo Um Traduzido", texts[0])
        assertEquals("Noite escura e tempestuosa traduzida.", texts[1])
        assertEquals("Vento uivava traduzido.", texts[2])

        // Chapters 2 and 3 kept original text
        assertEquals("Chapter Two", texts[3])
        assertEquals("Morning came with bright sunshine.", texts[4])
        assertEquals("Chapter Three", texts[5])
        assertEquals("End of sample.", texts[6])
    }

    @Test
    fun exportPtBrRefusesSameFile() {
        val file = tmp.newFile("same.epub")
        createSyntheticEpub(file, byteArrayOf(1), "body{}")

        try {
            EpubExporter.exportPtBr(file, file, emptyMap())
            org.junit.Assert.fail("Deveria recusar originalFile == outputFile")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("não podem ser o mesmo arquivo"))
        }
    }

    @Test
    fun structuralValidationDetectsDiscrepancies() {
        val f1 = tmp.newFile("v1.epub")
        val f2 = tmp.newFile("v2.epub")
        createSyntheticEpub(f1, byteArrayOf(1), "body{}")
        createSyntheticEpub(f2, byteArrayOf(1), "body{}")

        val b1 = Epub.read(f1.readBytes())
        val b2 = Epub.read(f2.readBytes())

        val cleanReport = EpubExporter.validate(b1, b2, translatedBlocks = 7, untranslatedBlocks = 0)
        assertTrue(cleanReport.valid)
        assertTrue(cleanReport.issues.isEmpty())

        // Simulated mutated book with an empty chapter (no blocks)
        val mutatedBytes = b2.rewrite { block ->
            if (block.spineIndex == 1) "" else null // empty chapter 1
        }
        val mutatedBook = Epub.read(mutatedBytes)

        val invalidReport = EpubExporter.validate(b1, mutatedBook)
        assertFalse(invalidReport.valid)
        assertTrue(invalidReport.issues.isNotEmpty())
        assertTrue(invalidReport.issues.any { it.contains("Capítulo 1") || it.contains("contagem") })
    }

    @Test
    fun exportRealBookEndToEndWithImagesAndCss() {
        val booksDir = File(System.getProperty("books.dir") ?: "core/src/test/resources/books")
        val lemo = File(booksDir, "lemo.epub")
        assertTrue("lemo.epub deve existir em ${booksDir.absolutePath}", lemo.exists())

        val originalSha256 = fileSha256(lemo)
        val originalBook = Epub.read(lemo.readBytes())
        val originalBlocks = originalBook.blocks()
        assertTrue(originalBlocks.isNotEmpty())

        val translations = originalBlocks.associate { it.id to "[PT-BR] " + it.html }
        val outputFile = tmp.newFile("lemo_ptbr.epub")

        val report = EpubExporter.exportPtBr(lemo, outputFile, translations)

        // 1. Original file untouched
        assertEquals(originalSha256, fileSha256(lemo))

        // 2. Report is valid and fully translated
        assertTrue("Report deve ser válido: issues=${report.issues}", report.valid)
        assertEquals(originalBook.spine.size, report.spineCountExported)
        assertEquals(originalBlocks.size, report.totalBlocks)
        assertEquals(originalBlocks.size, report.translatedBlocks)
        assertEquals(0, report.untranslatedBlocks)

        // 3. Exported book preserves assets and has translated blocks
        val exported = Epub.read(outputFile.readBytes())
        assertEquals(originalBook.spine.size, exported.spine.size)
        assertEquals(originalBlocks.size, exported.blocks().size)

        // Non-spine entries (CSS, images, fonts) must be byte-for-byte identical
        for ((name, bytes) in originalBook.entries) {
            if (name !in originalBook.spine) {
                assertArrayEquals("Asset $name deve ser preservado byte-a-byte", bytes, exported.entries[name])
            }
        }

        // All blocks must contain "[PT-BR]"
        for (block in exported.blocks()) {
            assertTrue("Bloco ${block.id} deve conter prefixo traduzido: ${block.text}", block.text.startsWith("[PT-BR]"))
        }
    }
}
