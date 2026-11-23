package io.github.playertwo1.bookconverter.core

import io.github.playertwo1.bookconverter.core.epub.DrmProtected
import io.github.playertwo1.bookconverter.core.epub.Epub
import io.github.playertwo1.bookconverter.core.epub.EpubException
import io.github.playertwo1.bookconverter.core.epub.TocEntry
import io.github.playertwo1.bookconverter.core.epub.Verification
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class EpubSpikeTest {
  private val books = File(System.getProperty("books.dir")).listFiles { f -> f.extension == "epub" }!!.sortedBy { it.name }

  @Test fun readsRealBooks() {
    assertTrue("sem livros de teste", books.isNotEmpty())
    for (f in books) {
      val epub = Epub.read(f.readBytes())
      val blocks = epub.blocks()
      println("SPIKE ${f.name}: title=${epub.title} lang=${epub.language} spine=${epub.spine.size} blocks=${blocks.size} " +
        "chars=${blocks.sumOf { it.text.length }} coverage=${"%.3f".format(epub.coverage())}")
      assertTrue("${f.name}: sem blocos", blocks.isNotEmpty())
    }
  }

  @Test fun identityRewriteKeepsStructure() {
    for (f in books) {
      val original = Epub.read(f.readBytes())
      val bytes = original.rewrite { it.html }
      val again = Epub.read(bytes)
      assertEquals(f.name, original.spine, again.spine)
      assertEquals(f.name, original.blocks().map { it.text }, again.blocks().map { it.text })
      for ((name, data) in original.entries) if (name !in original.spine) assertArrayEquals("${f.name}:$name", data, again.entries.getValue(name))
      assertEquals(original.entries.keys.toList(), again.entries.keys.toList())
      ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        val first = zip.nextEntry!!
        assertEquals("mimetype", first.name)
        assertEquals(ZipEntry.STORED, first.method)
      }
    }
  }

  @Test fun replacedBlocksLandInPlace() {
    for (f in books) {
      val original = Epub.read(f.readBytes())
      val again = Epub.read(original.rewrite { "[PT] " + it.html })
      val before = original.blocks()
      val after = again.blocks()
      assertEquals(f.name, before.size, after.size)
      after.zip(before).forEach { (a, b) -> assertEquals("${f.name} ${b.id}", "[PT] " + b.text, a.text) }
    }
  }

  @Test fun realBooksVerifyClean() {
    for (f in books) {
      val v = Epub.read(f.readBytes()).verify()
      println("VERIFY ${f.name}: $v warnings=${v.warnings}")
      assertTrue("${f.name}: sem índice", v.tocEntries > 0)
      assertEquals("${f.name}: links quebrados no índice", 0, v.tocBroken)
      assertTrue("${f.name}: cobertura ${v.coverage}", v.coverage >= Verification.MIN_COVERAGE)
    }
  }

  @Test fun blockIdsAreStableAcrossReads() {
    for (f in books) {
      val ids = { Epub.read(f.readBytes()).blocks().map { it.id to it.sha256 } }
      val first = ids()
      assertEquals(f.name, first, ids())
      assertEquals("${f.name}: IDs duplicados", first.size, first.map { it.first }.toSet().size)
    }
  }

  @Test fun readsEpub3NavToc() {
    val epub = Epub.read(book(
      opf = """<package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata/><manifest>
        <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
        <item id="c1" href="text/c1.xhtml" media-type="application/xhtml+xml"/></manifest>
        <spine><itemref idref="c1"/></spine></package>""",
      "OEBPS/nav.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>
        <nav epub:type="landmarks"><ol><li><a href="text/c1.xhtml">Início</a></li></ol></nav>
        <nav epub:type="toc"><ol><li><a href="text/c1.xhtml#s1">Um</a></li><li><a href="text/gone.xhtml">Dois</a></li></ol></nav></body></html>""",
    ))
    assertEquals("3.0", epub.version)
    assertEquals(listOf(TocEntry("Um", "OEBPS/text/c1.xhtml"), TocEntry("Dois", "OEBPS/text/gone.xhtml")), epub.toc)
    assertEquals(1, epub.verify().tocBroken)
  }

  @Test fun readsEpub2NcxToc() {
    val epub = Epub.read(book(
      opf = """<package xmlns="http://www.idpf.org/2007/opf" version="2.0"><metadata/><manifest>
        <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
        <item id="c1" href="text/c1.xhtml" media-type="application/xhtml+xml"/></manifest>
        <spine toc="ncx"><itemref idref="c1"/></spine></package>""",
      "OEBPS/toc.ncx" to """<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap>
        <navPoint id="p1"><navLabel><text>Parte</text></navLabel><content src="text/c1.xhtml"/>
          <navPoint id="p2"><navLabel><text>Cap. 1</text></navLabel><content src="text/c1.xhtml#c1"/></navPoint></navPoint>
        </navMap></ncx>""",
    ))
    assertEquals("2.0", epub.version)
    assertEquals(listOf(TocEntry("Parte", "OEBPS/text/c1.xhtml"), TocEntry("Cap. 1", "OEBPS/text/c1.xhtml")), epub.toc)
    val v = epub.verify()
    assertEquals(0, v.tocBroken)
    assertEquals(1, v.blocks)
    assertTrue(v.warnings.isEmpty())
  }

  @Test(expected = DrmProtected::class) fun refusesAdobeDrm() {
    Epub.read(zip("META-INF/encryption.xml" to """<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><EncryptedData xmlns="http://www.w3.org/2001/04/xmlenc#"><EncryptionMethod Algorithm="http://www.w3.org/2001/04/xmlenc#aes128-cbc"/></EncryptedData></encryption>"""))
  }

  @Test(expected = DrmProtected::class) fun refusesAdeptRights() {
    Epub.read(zip("META-INF/rights.xml" to "<rights/>"))
  }

  @Test(expected = EpubException::class) fun refusesZipSlip() {
    Epub.read(zip("../evil.xhtml" to "x"))
  }

  @Test(expected = EpubException::class) fun refusesNonEpub() {
    Epub.read(zip("readme.txt" to "oi"))
  }

  @Test fun readsCalibreSeriesMetadata() {
    val epub = Epub.read(book(
      opf = """<package xmlns="http://www.idpf.org/2007/opf" version="2.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
        <dc:title>Dune</dc:title><dc:creator>Frank Herbert</dc:creator>
        <meta name="calibre:series" content="Dune Chronicles"/>
        <meta name="calibre:series_index" content="1"/>
        </metadata><manifest>
        <item id="c1" href="text/c1.xhtml" media-type="application/xhtml+xml"/></manifest>
        <spine><itemref idref="c1"/></spine></package>"""
    ))
    assertEquals("Dune Chronicles", epub.series)
  }

  @Test fun readsEpub3BelongsToCollectionSeriesWithType() {
    val epub = Epub.read(book(
      opf = """<package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
        <dc:title>The Final Empire</dc:title><dc:creator>Brandon Sanderson</dc:creator>
        <meta property="belongs-to-collection" id="c-set">Cosmere</meta>
        <meta refines="#c-set" property="collection-type">set</meta>
        <meta property="belongs-to-collection" id="c-series">Mistborn</meta>
        <meta refines="#c-series" property="collection-type">series</meta>
        </metadata><manifest>
        <item id="c1" href="text/c1.xhtml" media-type="application/xhtml+xml"/></manifest>
        <spine><itemref idref="c1"/></spine></package>"""
    ))
    assertEquals("Mistborn", epub.series)
  }

  @Test fun readsEpub3BelongsToCollectionWithoutType() {
    val epub = Epub.read(book(
      opf = """<package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
        <dc:title>Foundation</dc:title><dc:creator>Isaac Asimov</dc:creator>
        <meta property="belongs-to-collection" id="col1">Foundation Saga</meta>
        </metadata><manifest>
        <item id="c1" href="text/c1.xhtml" media-type="application/xhtml+xml"/></manifest>
        <spine><itemref idref="c1"/></spine></package>"""
    ))
    assertEquals("Foundation Saga", epub.series)
  }

  @Test fun returnsNullWhenNoSeriesMetadata() {
    val epub = Epub.read(book(
      opf = """<package xmlns="http://www.idpf.org/2007/opf" version="2.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
        <dc:title>Standalone</dc:title><dc:creator>Author</dc:creator>
        </metadata><manifest>
        <item id="c1" href="text/c1.xhtml" media-type="application/xhtml+xml"/></manifest>
        <spine><itemref idref="c1"/></spine></package>"""
    ))
    assertNull(epub.series)
  }

  /** Minimal EPUB: container, OPF at OEBPS/content.opf, one chapter, plus [extra] files. */
  private fun book(opf: String, vararg extra: Pair<String, String>) = zip(
    "mimetype" to "application/epub+zip",
    "META-INF/container.xml" to """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""",
    "OEBPS/content.opf" to opf,
    "OEBPS/text/c1.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><body><p id="c1">Hello world.</p></body></html>""",
    *extra,
  )

  private fun zip(vararg files: Pair<String, String>): ByteArray {
    val bos = ByteArrayOutputStream()
    ZipOutputStream(bos).use { z -> for ((n, c) in files) { z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }
    return bos.toByteArray()
  }
}
