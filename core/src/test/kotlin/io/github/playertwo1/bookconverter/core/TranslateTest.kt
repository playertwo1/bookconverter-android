package io.github.playertwo1.bookconverter.core

import io.github.playertwo1.bookconverter.core.epub.Block
import io.github.playertwo1.bookconverter.core.epub.Epub
import io.github.playertwo1.bookconverter.core.provider.ErrorKind
import io.github.playertwo1.bookconverter.core.provider.ProviderClient
import io.github.playertwo1.bookconverter.core.provider.ProviderError
import io.github.playertwo1.bookconverter.core.provider.Translation
import io.github.playertwo1.bookconverter.core.translate.Chunk
import io.github.playertwo1.bookconverter.core.translate.MAX_CHUNK_CHARS
import io.github.playertwo1.bookconverter.core.translate.Prompt
import io.github.playertwo1.bookconverter.core.translate.chunk
import io.github.playertwo1.bookconverter.core.translate.estimate
import io.github.playertwo1.bookconverter.core.translate.preview
import io.github.playertwo1.bookconverter.core.translate.sampleChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class TranslateTest {
  private val books = File(System.getProperty("books.dir")).listFiles { f -> f.extension == "epub" }!!.sortedBy { it.name }

  private fun block(spine: Int, i: Int, html: String) = Block(spine, i, html, html, Epub.sha256(html))

  @Test fun chunksKeepOrderStayInOneDocumentAndRespectTheLimit() {
    for (f in books) {
      val blocks = Epub.read(f.readBytes()).blocks()
      val chunks = chunk(blocks)
      assertEquals(f.name, blocks, chunks.flatMap { it.blocks })
      for (c in chunks) {
        assertEquals("${f.name} ${c.id}: cruza documentos", 1, c.blocks.map { it.spineIndex }.toSet().size)
        assertTrue("${f.name} ${c.id}: ${c.htmlChars}", c.htmlChars <= MAX_CHUNK_CHARS || c.blocks.size == 1)
      }
      assertEquals(f.name, chunks.map { it.id }, chunk(Epub.read(f.readBytes()).blocks()).map { it.id })
      println("CHUNKS ${f.name}: blocks=${blocks.size} chunks=${chunks.size} sample=${sampleChunk(chunks).id}")
    }
  }

  @Test fun oversizedBlockTravelsAlone() {
    val chunks = chunk(listOf(block(0, 0, "a".repeat(10)), block(0, 1, "b".repeat(50)), block(0, 2, "c".repeat(10))), maxChars = 30)
    assertEquals(listOf(1, 1, 1), chunks.map { it.blocks.size })
  }

  @Test fun promptRoundTripsMarkersAndIgnoresPreamble() {
    val c = Chunk(listOf(block(0, 0, "Hello <i>there</i>."), block(0, 1, "Bye.")))
    assertEquals("⟦1⟧\nHello <i>there</i>.\n\n⟦2⟧\nBye.", Prompt.encode(c))
    assertEquals(listOf("Olá <i>aí</i>.", "Tchau."), Prompt.decode("Aqui está:\n⟦1⟧\nOlá <i>aí</i>.\n\n⟦2⟧\nTchau.\n", 2))
  }

  @Test fun promptRejectsMissingOrReorderedSegments() {
    for (reply in listOf("⟦1⟧ a", "⟦2⟧ b ⟦1⟧ a", "⟦1⟧ a ⟦2⟧ b ⟦3⟧ c", "sem marcador")) {
      try {
        Prompt.decode(reply, 2); fail(reply)
      } catch (e: ProviderError) {
        assertEquals(reply, ErrorKind.BAD_OUTPUT, e.kind)
      }
    }
  }

  /** Echoes the request, uppercased, so decode() has real markers to find. */
  private class FakeClient(private val rejectEffort: Boolean) : ProviderClient {
    val efforts = mutableListOf<String?>()
    override fun listModels() = listOf("m")
    override fun translate(model: String, system: String, text: String, effort: String?): Translation {
      efforts += effort
      if (effort != null && rejectEffort) throw ProviderError(400, "Unsupported parameter: 'reasoning.effort' is not supported with this model.")
      return Translation(text.uppercase(), "$model-2026", 1000, 800)
    }
  }

  @Test fun previewDropsRefusedEffortInsteadOfForcingIt() {
    val sample = Chunk(listOf(block(0, 0, "one"), block(0, 1, "two")))
    val client = FakeClient(rejectEffort = true)
    val r = preview(client, "m", "high", sample)
    assertEquals(listOf("high", null), client.efforts)
    assertTrue(r.effortRejected)
    assertNull(r.effortApplied)
    assertEquals(listOf("ONE", "TWO"), r.translated)

    val ok = preview(FakeClient(rejectEffort = false), "m", "low", sample)
    assertFalse(ok.effortRejected)
    assertEquals("low", ok.effortApplied)
  }

  @Test fun previewDoesNotSwallowOtherErrors() {
    val client = object : ProviderClient {
      override fun listModels() = emptyList<String>()
      override fun translate(model: String, system: String, text: String, effort: String?): Translation = throw ProviderError(401, "bad key")
    }
    try {
      preview(client, "m", "high", Chunk(listOf(block(0, 0, "x")))); fail()
    } catch (e: ProviderError) {
      assertEquals(ErrorKind.INVALID_KEY, e.kind)
    }
  }

  @Test fun estimateScalesWithBookSize() {
    val chunks = chunk(Epub.read(File(System.getProperty("books.dir"), "animal_farm.epub").readBytes()).blocks())
    val r = preview(FakeClient(false), "m", null, sampleChunk(chunks))
    val e = estimate(r, chunks)!!
    val ratio = chunks.sumOf { it.htmlChars }.toDouble() / r.sample.htmlChars
    assertEquals(chunks.size, e.calls)
    assertEquals((ratio * 800).toLong(), e.outputTokens)
    assertTrue("entrada ${e.inputTokens} deveria passar de ${(ratio * 1000).toLong()} pelo prompt fixo por chamada", e.inputTokens > (ratio * 1000).toLong())
    assertTrue(e.low.total < e.total && e.total < e.high.total)
    println("ESTIMATE animal_farm: $e ratio=$ratio")
  }
}
