package io.github.playertwo1.bookconverter.core

import io.github.playertwo1.bookconverter.core.epub.Epub
import io.github.playertwo1.bookconverter.core.epub.MobiEpubPackager
import io.github.playertwo1.bookconverter.core.epub.MobiKind
import io.github.playertwo1.bookconverter.core.epub.MobiSniffer
import io.github.playertwo1.bookconverter.core.epub.Verification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class MobiPackagerTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

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

  @Test
  fun probeDetectsMobi6NonDrm() {
    val mobiBytes = createSyntheticMobi(version = 6, encryption = 0)
    val file = tempFolder.newFile("test.mobi").apply { writeBytes(mobiBytes) }

    val probe = MobiSniffer.probe(file)
    assertNotNull(probe)
    assertEquals(MobiKind.MOBI, probe!!.kind)
    assertFalse(probe.isDrm)
    assertEquals(6, probe.headerVersion)
    assertEquals(0, probe.encryptionType)

    // ByteArray overload
    val probeBytes = MobiSniffer.probe(mobiBytes)
    assertEquals(probe, probeBytes)
  }

  @Test
  fun probeDetectsAzw3NonDrm() {
    val azw3Bytes = createSyntheticMobi(version = 8, encryption = 0)
    val file = tempFolder.newFile("test.azw3").apply { writeBytes(azw3Bytes) }

    val probe = MobiSniffer.probe(file)
    assertNotNull(probe)
    assertEquals(MobiKind.AZW3, probe!!.kind)
    assertFalse(probe.isDrm)
    assertEquals(8, probe.headerVersion)
    assertEquals(0, probe.encryptionType)
  }

  @Test
  fun probeDetectsDrm() {
    val drmBytes = createSyntheticMobi(version = 6, encryption = 2)
    val file = tempFolder.newFile("drm.mobi").apply { writeBytes(drmBytes) }

    val probe = MobiSniffer.probe(file)
    assertNotNull(probe)
    assertTrue(probe!!.isDrm)
    assertEquals(2, probe.encryptionType)
  }

  @Test
  fun probeDetectsOldMobipocketDrm() {
    val drmBytes = createSyntheticMobi(version = 6, encryption = 1)
    val file = tempFolder.newFile("old_drm.mobi").apply { writeBytes(drmBytes) }

    val probe = MobiSniffer.probe(file)
    assertNotNull(probe)
    assertTrue(probe!!.isDrm)
    assertEquals(1, probe.encryptionType)
  }

  @Test
  fun probeReturnsNullOnNonMobi() {
    val dummy = ByteArray(120) { 0x41 }
    val file = tempFolder.newFile("dummy.txt").apply { writeBytes(dummy) }
    assertNull(MobiSniffer.probe(file))
    assertNull(MobiSniffer.probe(dummy))
  }

  @Test
  fun probeReturnsNullOnTruncatedFile() {
    val truncated = ByteArray(50) { 0x42 }
    val file = tempFolder.newFile("short.bin").apply { writeBytes(truncated) }
    assertNull(MobiSniffer.probe(file))
  }

  @Test
  fun probeReturnsNullOnOutOfBoundsRecord0() {
    val mobiBytes = createSyntheticMobi(version = 6, encryption = 0, record0Offset = 500)
    // Write only 100 bytes, so record 0 at 500 is out of bounds
    val truncatedMobi = mobiBytes.sliceArray(0 until 100)
    val file = tempFolder.newFile("corrupt.mobi").apply { writeBytes(truncatedMobi) }
    assertNull(MobiSniffer.probe(file))
  }

  @Test
  fun packageExtractedDirProducesValidEpubWithHighCoverageAndCleanTOC() {
    val extractedDir = tempFolder.newFolder("extracted")
    val markupDir = File(extractedDir, "markup").apply { mkdirs() }
    val resourcesDir = File(extractedDir, "resources").apply { mkdirs() }

    // Chapter 1: loose text in body + accents + img + p + ending loose text
    val part0 = File(markupDir, "part_0000.html")
    part0.writeText(
      """<!DOCTYPE html>
<html>
<head><title>Primeiro Capítulo</title></head>
<body>
  <h1>Capítulo 1: O Início</h1>
  Texto solto direto no body com acentuação: coração, bênção, ação e história.
  <img src="cover.png" alt="Capa do livro"/>
  <p>Este é um parágrafo perfeitamente estruturado com <b>negrito</b> e <i>itálico</i>.</p>
  Mais texto solto após o parágrafo no corpo do documento para testar cobertura completa.
</body>
</html>""",
      Charsets.UTF_8,
    )

    // Chapter 2: another chapter with loose text and recindex reference
    val part1 = File(markupDir, "part_0001.html")
    part1.writeText(
      """<!DOCTYPE html>
<html>
<body>
  <h2>Capítulo 2: A Continuação</h2>
  <p>Segundo capítulo contendo informações importantes e traduzíveis.</p>
  <img recindex="1" />
  Texto final avulso no capítulo dois.
</body>
</html>""",
      Charsets.UTF_8,
    )

    // Dummy 1x1 PNG resource
    val dummyPng = byteArrayOf(
      0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
      0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
      0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
      0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(), 0x89.toByte(),
      0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54,
      0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00, 0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4.toByte(),
      0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
      0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
    )
    val coverFile = File(resourcesDir, "cover.png")
    coverFile.writeBytes(dummyPng)

    // CSS resource
    val styleFile = File(resourcesDir, "style.css")
    styleFile.writeText("body { margin: 1em; font-family: serif; } h1 { color: #1a237e; }")

    val outputEpub = File(tempFolder.root, "book.epub")

    // Package
    MobiEpubPackager.packageExtractedDir(
      extractedDir = extractedDir,
      outputEpub = outputEpub,
      title = "Livro de Teste MOBI",
      author = "Autor de Teste",
      language = "pt-BR",
    )

    assertTrue("outputEpub deve existir", outputEpub.exists())
    assertTrue("outputEpub deve conter dados", outputEpub.length() > 0)

    // Validate ZIP: mimetype must be first and STORED
    ZipInputStream(FileInputStream(outputEpub)).use { zip ->
      val first = zip.nextEntry
      assertNotNull("Primeira entrada do zip não pode ser nula", first)
      assertEquals("mimetype", first!!.name)
      assertEquals(ZipEntry.STORED, first.method)
    }

    // Read with Epub.kt
    val epub = Epub.read(outputEpub.readBytes())
    assertEquals("Livro de Teste MOBI", epub.title)
    assertEquals("Autor de Teste", epub.author)
    assertEquals("pt-BR", epub.language)
    assertEquals(2, epub.spine.size)

    // Verify verification metrics
    val v = epub.verify()
    assertTrue("Deve possuir blocos: ${v.blocks}", v.blocks > 0)
    assertTrue("Cobertura deve ser >= 0.98, atual: ${v.coverage}", v.coverage >= Verification.MIN_COVERAGE)
    assertTrue("Deve possuir entradas de TOC: ${v.tocEntries}", v.tocEntries > 0)
    assertEquals("Não deve ter links quebrados no TOC", 0, v.tocBroken)
    assertTrue("Não deve ter advertências: ${v.warnings}", v.warnings.isEmpty())

    // Verify resources preserved
    assertTrue("cover.png deve estar preservado no EPUB", "OEBPS/resources/cover.png" in epub.entries)
    assertTrue("style.css deve estar preservado no EPUB", "OEBPS/resources/style.css" in epub.entries)

    // Verify rewritten chapter content
    val ch1Bytes = epub.entries["OEBPS/chapters/part_0000.xhtml"]
    assertNotNull("Capítulo 1 deve existir", ch1Bytes)
    val ch1Text = String(ch1Bytes!!, Charsets.UTF_8)
    assertTrue("Referência de imagem deve apontar para ../resources/cover.png", ch1Text.contains("../resources/cover.png"))
    assertTrue("Acentuação deve ser mantida", ch1Text.contains("coração") && ch1Text.contains("bênção"))

    val ch2Bytes = epub.entries["OEBPS/chapters/part_0001.xhtml"]
    assertNotNull("Capítulo 2 deve existir", ch2Bytes)
    val ch2Text = String(ch2Bytes!!, Charsets.UTF_8)
    assertTrue("recindex deve ser resolvido para ../resources/cover.png", ch2Text.contains("../resources/cover.png"))
  }

  @Test
  fun packageExtractedDirWithExistingNcxPreservesNavPoints() {
    val extractedDir = tempFolder.newFolder("extracted_with_ncx")
    val markupDir = File(extractedDir, "markup").apply { mkdirs() }
    val resourcesDir = File(extractedDir, "resources").apply { mkdirs() }

    val part0 = File(markupDir, "part_0000.html")
    part0.writeText("<html><body><h1>Prólogo</h1><p>Texto do prólogo.</p></body></html>")

    val part1 = File(markupDir, "part_0001.html")
    part1.writeText("<html><body><h1>Capítulo Principal</h1><p>Texto do capítulo.</p></body></html>")

    val ncx = File(resourcesDir, "toc.ncx")
    ncx.writeText(
      """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <navMap>
    <navPoint id="p1" playOrder="1">
      <navLabel><text>Introdução ao Livro</text></navLabel>
      <content src="part_0000.html#intro"/>
    </navPoint>
    <navPoint id="p2" playOrder="2">
      <navLabel><text>Capítulo Central</text></navLabel>
      <content src="part_0001.html"/>
    </navPoint>
  </navMap>
</ncx>"""
    )

    val outputEpub = File(tempFolder.root, "ncx_mapped.epub")
    MobiEpubPackager.packageExtractedDir(
      extractedDir = extractedDir,
      outputEpub = outputEpub,
      title = "Livro com NCX",
      author = "Escritor",
      language = "pt",
    )

    val epub = Epub.read(outputEpub.readBytes())
    val v = epub.verify()
    assertEquals(2, v.tocEntries)
    assertEquals(0, v.tocBroken)
    assertEquals("Introdução ao Livro", epub.toc[0].title)
    assertEquals("Capítulo Central", epub.toc[1].title)
  }

  @Test
  fun packageExtractedDirFallsBackToFlatDirectoryIfNoMarkupFolder() {
    val flatDir = tempFolder.newFolder("flat_dir")
    val htmlFile = File(flatDir, "single_part.xhtml")
    htmlFile.writeText("<html><body><h1>Apenas Um Capítulo</h1><p>Conteúdo direto na raiz.</p></body></html>")

    val outputEpub = File(tempFolder.root, "flat.epub")
    MobiEpubPackager.packageExtractedDir(
      extractedDir = flatDir,
      outputEpub = outputEpub,
      title = "Flat Book",
      author = "Author",
      language = "en",
    )

    val epub = Epub.read(outputEpub.readBytes())
    val v = epub.verify()
    assertEquals(1, v.spineDocs)
    assertEquals(1, v.tocEntries)
    assertEquals(0, v.tocBroken)
    assertTrue(v.coverage >= Verification.MIN_COVERAGE)
  }
}
