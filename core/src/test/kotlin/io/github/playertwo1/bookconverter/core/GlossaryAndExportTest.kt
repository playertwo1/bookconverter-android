package io.github.playertwo1.bookconverter.core

import io.github.playertwo1.bookconverter.core.provider.ProviderClient
import io.github.playertwo1.bookconverter.core.provider.Translation
import io.github.playertwo1.bookconverter.core.translate.ChunkStatus
import io.github.playertwo1.bookconverter.core.translate.Effort
import io.github.playertwo1.bookconverter.core.translate.Glossary
import io.github.playertwo1.bookconverter.core.translate.GlossaryTerm
import io.github.playertwo1.bookconverter.core.translate.JobOutcome
import io.github.playertwo1.bookconverter.core.translate.JobProgress
import io.github.playertwo1.bookconverter.core.translate.JobStatus
import io.github.playertwo1.bookconverter.core.translate.Prompt
import io.github.playertwo1.bookconverter.core.translate.StoredChunk
import io.github.playertwo1.bookconverter.core.translate.TextExporter
import io.github.playertwo1.bookconverter.core.translate.TranslationEngine
import io.github.playertwo1.bookconverter.core.translate.TranslationStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class GlossaryAndExportTest {

  private class TestStore : TranslationStore {
    data class ChunkEntry(
      val id: Long,
      val chunkIndex: Int,
      val blockCount: Int,
      val sourceText: String,
      var status: ChunkStatus = ChunkStatus.PENDING,
      var attemptCount: Int = 0,
      var translatedText: String? = null,
      var inputTokens: Long = 0,
      var outputTokens: Long = 0,
    )

    val chunks = mutableListOf<ChunkEntry>()

    fun add(id: Long, chunkIndex: Int, sourceText: String, blockCount: Int = 1) {
      chunks.add(ChunkEntry(id, chunkIndex, blockCount, sourceText))
    }

    override suspend fun reconcileInProgress(jobId: Long) {
      chunks.filter { it.status == ChunkStatus.IN_PROGRESS }.forEach { it.status = ChunkStatus.PENDING }
    }

    override suspend fun nextPendingChunk(jobId: Long): StoredChunk? {
      val c = chunks.firstOrNull { it.status == ChunkStatus.PENDING } ?: return null
      return StoredChunk(c.id, c.chunkIndex, c.blockCount, c.sourceText, c.attemptCount)
    }

    override suspend fun markChunkInProgress(chunkId: Long) {
      chunks.first { it.id == chunkId }.status = ChunkStatus.IN_PROGRESS
    }

    override suspend fun resetChunkToPending(chunkId: Long) {
      chunks.first { it.id == chunkId }.status = ChunkStatus.PENDING
    }

    override suspend fun markChunkCompleted(
      jobId: Long,
      chunkId: Long,
      translatedText: String,
      inputTokens: Long,
      outputTokens: Long,
    ) {
      val c = chunks.first { it.id == chunkId }
      c.status = ChunkStatus.COMPLETED
      c.translatedText = translatedText
      c.inputTokens = inputTokens
      c.outputTokens = outputTokens
    }

    override suspend fun markChunkFailed(chunkId: Long, attemptCount: Int, errorMessage: String) {
      val c = chunks.first { it.id == chunkId }
      c.status = ChunkStatus.FAILED
      c.attemptCount = attemptCount
    }

    override suspend fun currentProgress(jobId: Long): JobProgress {
      val done = chunks.count { it.status == ChunkStatus.COMPLETED }
      val total = chunks.size
      val tokens = chunks.filter { it.status == ChunkStatus.COMPLETED }.sumOf { it.inputTokens + it.outputTokens }
      return JobProgress(done, total, tokens)
    }

    override suspend fun stopSignal(jobId: Long): JobStatus? = null

    override suspend fun onEffortDowngraded(jobId: Long, newEffort: Effort?) = Unit
  }

  private class RecordedCall(
    val model: String,
    val system: String,
    val text: String,
    val effort: String?,
  )

  private class TestClient : ProviderClient {
    val calls = mutableListOf<RecordedCall>()

    override fun listModels(): List<String> = listOf("model-test")

    override fun translate(model: String, system: String, text: String, effort: String?): Translation {
      calls.add(RecordedCall(model, system, text, effort))
      return Translation("⟦1⟧\nTraduzido", model, 10L, 20L)
    }
  }

  /**
   * AC12: 20 synthetic terms applied in TranslationEngine.
   * Verifies that ONLY the terms present in each chunk go into the provider prompt,
   * and all 20 terms are applied across their respective chunks.
   */
  @Test
  fun ac12_twentyTermsAppliedSelectivelyPerChunkInTranslationEngine() = runBlocking {
    val store = TestStore()

    // 20 synthetic terms
    val glossaryTerms = (1..20).map { i ->
      GlossaryTerm(source = "AncientSword$i", target = "EspadaAncestral$i")
    }

    // 4 chunks, each containing 5 terms
    store.add(id = 1L, chunkIndex = 0, sourceText = "⟦1⟧\nBehold AncientSword1, AncientSword2, AncientSword3, AncientSword4 and AncientSword5.")
    store.add(id = 2L, chunkIndex = 1, sourceText = "⟦1⟧\nForged AncientSword6, AncientSword7, AncientSword8, AncientSword9, AncientSword10.")
    store.add(id = 3L, chunkIndex = 2, sourceText = "⟦1⟧\nCarried AncientSword11, AncientSword12, AncientSword13, AncientSword14, AncientSword15.")
    store.add(id = 4L, chunkIndex = 3, sourceText = "⟦1⟧\nLost AncientSword16, AncientSword17, AncientSword18, AncientSword19 and AncientSword20.")

    val client = TestClient()
    val engine = TranslationEngine(delayFn = {})

    val outcome = engine.runJob(
      jobId = 100L,
      client = client,
      model = "model-test",
      store = store,
      glossary = glossaryTerms,
      styleNotes = "Mantenha o tom épico.",
    )

    assertEquals(JobOutcome.Completed, outcome)
    assertEquals(4, client.calls.size)

    val termsFoundAcrossAllChunks = mutableSetOf<String>()

    for (callIndex in 0 until 4) {
      val prompt = client.calls[callIndex].system
      assertTrue("System prompt deve incluir notas de estilo", prompt.contains("Notas de estilo da série:\nMantenha o tom épico."))
      assertTrue("System prompt deve incluir o cabeçalho de glossário", prompt.contains("Glossário obrigatório (use exatamente estas traduções):"))

      val expectedRange = (callIndex * 5 + 1)..(callIndex * 5 + 5)
      for (i in 1..20) {
        val termPattern = "AncientSword$i → EspadaAncestral$i"
        if (i in expectedRange) {
          assertTrue("Chunk $callIndex deve conter $termPattern", prompt.contains(termPattern))
          termsFoundAcrossAllChunks.add("AncientSword$i")
        } else {
          assertFalse("Chunk $callIndex NÃO deve conter $termPattern", prompt.contains(termPattern))
        }
      }
    }

    // All 20 terms were applied
    assertEquals(20, termsFoundAcrossAllChunks.size)
  }

  /**
   * Round-trip lossless parsing and formatting of desktop glossary txt,
   * including `→`, `->`, comments `#`, empty lines, and conflict detection.
   */
  @Test
  fun testGlossaryDesktopTxtRoundTripAndConflictDetection() {
    val rawContent = """
      # Comentário no início
      Wuthering Heights → O Morro dos Ventos Uivantes
      Heathcliff -> Heathcliff

      # Linha em branco acima e comentário aqui
      Catherine Earnshaw → Catherine Earnshaw
      Nelly Dean -> Nelly Dean
      
      # Termo com duplicata conflituosa
      wuthering heights -> Alturas Uivantes
      
      # Linha sem seta (deve ser skipped)
      TermoSemSetaInvalido
      
      # Linha com source vazio
      -> TraduçãoSemOrigem
    """.trimIndent()

    val result = Glossary.parseDesktopTxt(rawContent)

    assertEquals(4, result.terms.size)
    assertEquals(1, result.conflicts.size)
    assertTrue(result.conflicts[0].contains("wuthering heights", ignoreCase = true))
    // 2 skipped: "TermoSemSetaInvalido" and "-> TraduçãoSemOrigem"
    assertEquals(2, result.skippedLines)

    // First occurrence kept deterministically
    assertEquals("O Morro dos Ventos Uivantes", result.terms.first { it.source.equals("Wuthering Heights", ignoreCase = true) }.target)

    // Format back
    val formatted = Glossary.formatDesktopTxt(result.terms)
    val expectedFormatted = """
      Wuthering Heights → O Morro dos Ventos Uivantes
      Heathcliff → Heathcliff
      Catherine Earnshaw → Catherine Earnshaw
      Nelly Dean → Nelly Dean
    """.trimIndent()
    assertEquals(expectedFormatted, formatted)

    // Lossless round-trip
    val reParsed = Glossary.parseDesktopTxt(formatted)
    assertEquals(result.terms, reParsed.terms)
    assertEquals(0, reParsed.conflicts.size)
    assertEquals(0, reParsed.skippedLines)

    // Snapshot serialization round-trip
    val snapshot = Glossary.serializeSnapshot(result.terms)
    val fromSnapshot = Glossary.parseSnapshot(snapshot)
    assertEquals(result.terms, fromSnapshot)
    assertEquals(emptyList<GlossaryTerm>(), Glossary.parseSnapshot(null))
    assertEquals(emptyList<GlossaryTerm>(), Glossary.parseSnapshot("   "))
  }

  /**
   * matchTerms avoiding substring false-positives ("cat" vs "category")
   * and supporting compound terms and Unicode characters.
   */
  @Test
  fun testMatchTermsWordBoundariesAndUnicode() {
    val glossary = listOf(
      GlossaryTerm("cat", "gato"),
      GlossaryTerm("dog", "cachorro"),
      GlossaryTerm("Wuthering Heights", "O Morro dos Ventos Uivantes"),
      GlossaryTerm("maçã", "apple"),
      GlossaryTerm("irmã", "sister"),
    )

    // 1. Substrings that should NOT match: "cat" inside "category", "scat", "concatenation"
    val falsePositivesText = "The category was broad, scattering concatenation everywhere."
    val matchedFalse = Glossary.matchTerms(falsePositivesText, glossary)
    assertTrue("cat não deve casar em category/scattering/concatenation", matchedFalse.isEmpty())

    // 2. Real match of "cat" and "dog" with punctuation and boundaries
    val realMatchText = "The Cat jumped over a dog! A cat's tail is long."
    val matchedReal = Glossary.matchTerms(realMatchText, glossary)
    assertEquals(2, matchedReal.size)
    assertTrue(matchedReal.any { it.source == "cat" })
    assertTrue(matchedReal.any { it.source == "dog" })

    // 3. Compound terms
    val compoundText = "We visited Wuthering Heights in England."
    val matchedCompound = Glossary.matchTerms(compoundText, glossary)
    assertEquals(1, matchedCompound.size)
    assertEquals("Wuthering Heights", matchedCompound.first().source)

    // 4. Unicode with accents
    val unicodeText = "Ela comeu uma maçã vermelha com sua irmã."
    val matchedUnicode = Glossary.matchTerms(unicodeText, glossary)
    assertEquals(2, matchedUnicode.size)
    assertTrue(matchedUnicode.any { it.source == "maçã" })
    assertTrue(matchedUnicode.any { it.source == "irmã" })

    // 5. Unicode boundaries: "maçã" should not match "maçaneta", "irmã" should not match "irmãos"
    val unicodeFalseText = "Comprei uma maçaneta para os irmãos."
    val matchedUnicodeFalse = Glossary.matchTerms(unicodeFalseText, glossary)
    assertTrue("maçã não deve casar em maçaneta nem irmã em irmãos", matchedUnicodeFalse.isEmpty())
  }

  /**
   * Prompt construction with glossary and styleNotes, retrocompatible when empty.
   */
  @Test
  fun testPromptSystemPromptConstruction() {
    // Both empty: identical to Prompt.SYSTEM
    assertEquals(Prompt.SYSTEM, Prompt.systemPrompt(emptyList(), null))
    assertEquals(Prompt.SYSTEM, Prompt.systemPrompt(emptyList(), ""))

    // With glossary only
    val terms = listOf(GlossaryTerm("Ring", "Anel"), GlossaryTerm("Mordor", "Mordor"))
    val promptWithGlossary = Prompt.systemPrompt(glossary = terms)
    assertTrue(promptWithGlossary.startsWith(Prompt.SYSTEM))
    assertTrue(promptWithGlossary.contains("Glossário obrigatório (use exatamente estas traduções):"))
    assertTrue(promptWithGlossary.contains("- Ring → Anel"))
    assertTrue(promptWithGlossary.contains("- Mordor → Mordor"))
    assertFalse(promptWithGlossary.contains("Notas de estilo"))

    // With styleNotes only
    val promptWithStyle = Prompt.systemPrompt(styleNotes = "Tom solene e arcaico.")
    assertTrue(promptWithStyle.startsWith(Prompt.SYSTEM))
    assertFalse(promptWithStyle.contains("Glossário obrigatório"))
    assertTrue(promptWithStyle.contains("Notas de estilo da série:\nTom solene e arcaico."))

    // Both present
    val promptBoth = Prompt.systemPrompt(glossary = terms, styleNotes = "Tom solene.")
    assertTrue(promptBoth.contains("Glossário obrigatório"))
    assertTrue(promptBoth.contains("Notas de estilo da série:\nTom solene."))
  }

  /**
   * TextExporter: toTxt, toMarkdown and toDocx (opening the generated .docx with ZipInputStream,
   * verifying the 4 OOXML entries, pt-BR accents and XML escaping of '&', '<', '>', '"', '\'').
   */
  @Test
  fun testTextExporterTxtMarkdownAndDocx() {
    val sampleText = "Capítulo 1: Começo & Fim.\nJoão & Maria disseram \"olá\" para o 'mágico' <sob a lua>.\nTerceiro parágrafo: café, maçã, ímã."
    val sampleTitle = "Aventura dos Irmãos & Magia <Edição 1>"

    // 1. toTxt
    val txtBytes = TextExporter.toTxt(sampleText)
    assertEquals(sampleText, String(txtBytes, Charsets.UTF_8))

    // 2. toMarkdown
    val mdWithTitle = TextExporter.toMarkdown(sampleText, sampleTitle)
    assertEquals("# $sampleTitle\n\n$sampleText", String(mdWithTitle, Charsets.UTF_8))

    val mdNoTitle = TextExporter.toMarkdown(sampleText, null)
    assertEquals(sampleText, String(mdNoTitle, Charsets.UTF_8))

    // 3. toDocx
    val docxBytes = TextExporter.toDocx(sampleText, sampleTitle)
    assertNotNull(docxBytes)
    assertTrue(docxBytes.isNotEmpty())

    // Read with ZipInputStream and inspect entries
    val entries = mutableMapOf<String, String>()
    ZipInputStream(ByteArrayInputStream(docxBytes)).use { zis ->
      var entry = zis.nextEntry
      while (entry != null) {
        val bytes = zis.readBytes()
        entries[entry.name] = String(bytes, Charsets.UTF_8)
        entry = zis.nextEntry
      }
    }

    // Verify all 4 required OOXML entries are present
    assertTrue("Deve conter [Content_Types].xml", entries.containsKey("[Content_Types].xml"))
    assertTrue("Deve conter _rels/.rels", entries.containsKey("_rels/.rels"))
    assertTrue("Deve conter word/_rels/document.xml.rels", entries.containsKey("word/_rels/document.xml.rels"))
    assertTrue("Deve conter word/document.xml", entries.containsKey("word/document.xml"))
    assertEquals(4, entries.size)

    val docXml = entries["word/document.xml"]!!
    // Check root and body structure
    assertTrue(docXml.contains("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">"""))
    assertTrue(docXml.contains("<w:body>"))
    assertTrue(docXml.contains("</w:body>"))
    assertTrue(docXml.contains("</w:document>"))

    // Check proper XML escaping
    assertTrue("Deve escapar & para &amp;", docXml.contains("Começo &amp; Fim"))
    assertTrue("Deve escapar < para &lt;", docXml.contains("&lt;sob a lua&gt;"))
    assertTrue("Deve escapar > para &gt;", docXml.contains("&lt;Edição 1&gt;"))
    assertTrue("Deve escapar \" para &quot;", docXml.contains("&quot;olá&quot;"))
    assertTrue("Deve escapar ' para &apos;", docXml.contains("&apos;mágico&apos;"))

    // Check that unescaped special characters DO NOT exist in XML text content
    assertFalse(docXml.contains("<sob a lua>"))
    assertFalse(docXml.contains("<Edição 1>"))

    // Check that pt-BR accents are preserved directly in UTF-8
    assertTrue(docXml.contains("Aventura dos Irmãos"))
    assertTrue(docXml.contains("café, maçã, ímã"))

    // Check paragraph structure
    assertTrue(docXml.contains("""<w:p><w:r><w:t xml:space="preserve">"""))
  }
}
