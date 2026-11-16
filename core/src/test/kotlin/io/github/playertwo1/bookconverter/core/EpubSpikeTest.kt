package io.github.playertwo1.bookconverter.core

import io.github.playertwo1.bookconverter.core.epub.DrmProtected
import io.github.playertwo1.bookconverter.core.epub.Epub
import io.github.playertwo1.bookconverter.core.epub.EpubException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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

  private fun zip(vararg files: Pair<String, String>): ByteArray {
    val bos = ByteArrayOutputStream()
    ZipOutputStream(bos).use { z -> for ((n, c) in files) { z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }
    return bos.toByteArray()
  }
}
