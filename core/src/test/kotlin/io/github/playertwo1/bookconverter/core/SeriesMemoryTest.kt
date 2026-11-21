package io.github.playertwo1.bookconverter.core

import io.github.playertwo1.bookconverter.core.provider.ProviderClient
import io.github.playertwo1.bookconverter.core.provider.Translation
import io.github.playertwo1.bookconverter.core.translate.GlossaryTerm
import io.github.playertwo1.bookconverter.core.translate.MemoryCategory
import io.github.playertwo1.bookconverter.core.translate.Prompt
import io.github.playertwo1.bookconverter.core.translate.SeriesMemoryExtractor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SeriesMemoryTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private class FakeProviderClient(
    val cannedResponse: (model: String, system: String, text: String, effort: String?) -> Translation,
  ) : ProviderClient {
    val calls = mutableListOf<String>()

    override fun listModels(): List<String> = listOf("fake-model")

    override fun translate(model: String, system: String, text: String, effort: String?): Translation {
      calls.add(text)
      return cannedResponse(model, system, text, effort)
    }
  }

  private fun createSyntheticEpub(
    title: String = "The Final Empire",
    chapters: List<String> = listOf("<p>Kelsier smiled as he used Allomancy to leap across the rooftops of Luthadel.</p>"),
  ): File {
    val file = tempFolder.newFile("test_book.epub")
    val manifestItems = StringBuilder()
    val spineItems = StringBuilder()
    val chapterFiles = mutableListOf<Pair<String, String>>()

    chapters.forEachIndexed { i, content ->
      val path = "text/ch$i.xhtml"
      manifestItems.append("""<item id="c$i" href="$path" media-type="application/xhtml+xml"/>""")
      spineItems.append("""<itemref idref="c$i"/>""")
      chapterFiles.add(
        "OEBPS/$path" to """<html xmlns="http://www.w3.org/1999/xhtml"><body>$content</body></html>"""
      )
    }

    val opf = """
      <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
          <dc:title>$title</dc:title>
        </metadata>
        <manifest>
          $manifestItems
        </manifest>
        <spine>
          $spineItems
        </spine>
      </package>
    """.trimIndent()

    val zipBytes = zip(
      "mimetype" to "application/epub+zip",
      "META-INF/container.xml" to """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""",
      "OEBPS/content.opf" to opf,
      *chapterFiles.toTypedArray(),
    )
    file.writeBytes(zipBytes)
    return file
  }

  private fun zip(vararg files: Pair<String, String>): ByteArray {
    val bos = ByteArrayOutputStream()
    ZipOutputStream(bos).use { z ->
      for ((n, c) in files) {
        val entry = ZipEntry(n)
        if (n == "mimetype") {
          entry.method = ZipEntry.STORED
          entry.size = c.toByteArray().size.toLong()
          entry.compressedSize = c.toByteArray().size.toLong()
          val crc = java.util.zip.CRC32()
          crc.update(c.toByteArray())
          entry.crc = crc.value
        }
        z.putNextEntry(entry)
        z.write(c.toByteArray())
        z.closeEntry()
      }
    }
    return bos.toByteArray()
  }

  @Test
  fun extractsTermsCharactersAndPairsFromSyntheticEpub() = runBlocking {
    val epubFile = createSyntheticEpub(
      title = "Mistborn: The Final Empire",
      chapters = listOf(
        "<p>Kelsier whispered to Vin: There is always another secret.</p>",
        "<p>The Lord Ruler suppressed Allomancy among the Skaa rebellion.</p>"
      )
    )

    val jsonOutput = """
      [
        {
          "category": "TERM",
          "source": "Allomancy",
          "target": "Alomancia",
          "notes": "Sistema de magia baseado em metais",
          "excerpt": "The Lord Ruler suppressed Allomancy"
        },
        {
          "category": "CHARACTER",
          "source": "Kelsier",
          "target": "Kelsier",
          "notes": "Líder da rebelião Skaa",
          "excerpt": "Kelsier whispered to Vin"
        },
        {
          "category": "PAIR",
          "source": "There is always another secret",
          "target": "Sempre há outro segredo",
          "notes": "Bordão icônico de Kelsier",
          "excerpt": "There is always another secret."
        }
      ]
    """.trimIndent()

    val client = FakeProviderClient { _, _, _, _ ->
      Translation(jsonOutput, "fake-model", inputTokens = 120L, outputTokens = 85L)
    }

    val progressEvents = mutableListOf<Pair<Int, Int>>()
    val result = SeriesMemoryExtractor.extractFromEpub(
      epubFile = epubFile,
      sourceBookTitle = "Mistborn: The Final Empire",
      client = client,
      model = "fake-model",
      maxSamples = 2,
      onProgress = { cur, tot -> progressEvents.add(cur to tot) }
    )

    assertEquals(3, result.proposals.size)
    assertEquals(120L, result.inputTokens)
    assertEquals(85L, result.outputTokens)
    assertTrue("Deveria registrar progresso", progressEvents.isNotEmpty())

    val termProp = result.proposals.first { it.category == MemoryCategory.TERM }
    assertEquals("Allomancy", termProp.source)
    assertEquals("Alomancia", termProp.target)
    assertEquals("Sistema de magia baseado em metais", termProp.notes)
    assertEquals("Mistborn: The Final Empire", termProp.sourceBookTitle)
    assertNull(termProp.conflictWarning)

    val charProp = result.proposals.first { it.category == MemoryCategory.CHARACTER }
    assertEquals("Kelsier", charProp.source)
    assertEquals("Kelsier", charProp.target)
    assertEquals("Líder da rebelião Skaa", charProp.notes)
    assertNull(charProp.conflictWarning)

    val pairProp = result.proposals.first { it.category == MemoryCategory.PAIR }
    assertEquals("There is always another secret", pairProp.source)
    assertEquals("Sempre há outro segredo", pairProp.target)
    assertEquals("Bordão icônico de Kelsier", pairProp.notes)
    assertNull(pairProp.conflictWarning)
  }

  @Test
  fun toleratesCodeFencesAndSkipsMalformedItems() = runBlocking {
    val epubFile = createSyntheticEpub()

    val messyJson = """
      Aqui estão as propostas identificadas:
      ```json
      [
        {
          "category": "INVALID_CAT",
          "source": "Foo",
          "target": "Bar"
        },
        {
          "category": "TERM",
          "source": "",
          "target": "Vazio"
        },
        {
          "category": "TERM",
          "source": "Steel Inquisitor",
          "target": "Inquisidor de Aço",
          "notes": "Guerreiro com pregos nos olhos",
          "excerpt": "A Steel Inquisitor approached."
        },
        {
          "broken": true
        }
      ]
      ```
      Espero que ajude!
    """.trimIndent()

    val client = FakeProviderClient { _, _, _, _ ->
      Translation(messyJson, "fake-model", 50L, 30L)
    }

    val result = SeriesMemoryExtractor.extractFromEpub(
      epubFile = epubFile,
      sourceBookTitle = "The Final Empire",
      client = client,
      model = "fake-model",
    )

    assertEquals(1, result.proposals.size)
    val prop = result.proposals.first()
    assertEquals(MemoryCategory.TERM, prop.category)
    assertEquals("Steel Inquisitor", prop.source)
    assertEquals("Inquisidor de Aço", prop.target)
    assertEquals("Guerreiro com pregos nos olhos", prop.notes)
  }

  @Test
  fun detectsConflictAgainstExistingGlossary() = runBlocking {
    val epubFile = createSyntheticEpub()

    val existingGlossary = listOf(
      GlossaryTerm(source = "Allomancy", target = "Alomancia"),
      GlossaryTerm(source = "Luthadel", target = "Luthadel"),
    )

    // Proposed translation uses conflicting target for Allomancy, but identical for Luthadel
    val json = """
      [
        {
          "category": "TERM",
          "source": "Allomancy",
          "target": "Magia dos Metais",
          "excerpt": "He learned Allomancy."
        },
        {
          "category": "TERM",
          "source": "Luthadel",
          "target": "Luthadel",
          "excerpt": "Welcome to Luthadel."
        }
      ]
    """.trimIndent()

    val client = FakeProviderClient { _, _, _, _ ->
      Translation(json, "fake-model", 40L, 20L)
    }

    val result = SeriesMemoryExtractor.extractFromEpub(
      epubFile = epubFile,
      sourceBookTitle = "The Final Empire",
      client = client,
      model = "fake-model",
      existingTerms = existingGlossary,
    )

    assertEquals(2, result.proposals.size)

    val conflicting = result.proposals.first { it.source == "Allomancy" }
    assertNotNull(conflicting.conflictWarning)
    assertEquals("Conflito: já traduzido como 'Alomancia'", conflicting.conflictWarning)

    val matching = result.proposals.first { it.source == "Luthadel" }
    assertNull("Termo concordante com glossário não deve ter aviso de conflito", matching.conflictWarning)
  }

  @Test
  fun detectsConflictWithinSameExtraction() = runBlocking {
    val epubFile = createSyntheticEpub()

    // Same source "Skaa" proposed with different targets in the same extraction
    val json = """
      [
        {
          "category": "TERM",
          "source": "Skaa",
          "target": "Skaa",
          "notes": "Povo oprimido",
          "excerpt": "The Skaa worked the fields."
        },
        {
          "category": "TERM",
          "source": "Skaa",
          "target": "Escravos",
          "notes": "Classe servil",
          "excerpt": "The Skaa were treated as beasts."
        }
      ]
    """.trimIndent()

    val client = FakeProviderClient { _, _, _, _ ->
      Translation(json, "fake-model", 40L, 25L)
    }

    val result = SeriesMemoryExtractor.extractFromEpub(
      epubFile = epubFile,
      sourceBookTitle = "The Final Empire",
      client = client,
      model = "fake-model",
      existingTerms = emptyList(),
    )

    assertEquals(2, result.proposals.size)
    val first = result.proposals[0]
    val second = result.proposals[1]

    assertNull("Primeira ocorrência define o padrão inicial da extração", first.conflictWarning)
    assertNotNull("Segunda ocorrência divergente deve registrar conflito", second.conflictWarning)
    assertEquals("Conflito: já traduzido como 'Skaa'", second.conflictWarning)
  }

  @Test
  fun promptSystemPromptFormatsCharactersAndPairsCorrectly() {
    // 1. Backward compatibility: empty characters and examplePairs
    val base = Prompt.systemPrompt()
    assertEquals(Prompt.SYSTEM, base)

    val withGlossary = Prompt.systemPrompt(
      glossary = listOf(GlossaryTerm("Mistborn", "Nascido da Bruma"))
    )
    assertTrue(withGlossary.contains("Glossário obrigatório"))
    assertTrue(withGlossary.contains("- Mistborn → Nascido da Bruma"))

    // 2. Characters injection with and without notes
    val characters = listOf(
      GlossaryTerm(source = "Vin", target = "Vin", notes = "Protagonista alomântica"),
      GlossaryTerm(source = "Elend Venture", target = "Elend Venture", notes = null)
    )
    val promptWithChars = Prompt.systemPrompt(characters = characters)
    assertTrue(promptWithChars.contains("Personagens da série:\n- Vin → Vin (Protagonista alomântica)\n- Elend Venture → Elend Venture"))

    // 3. Style dialogue pairs injection
    val examplePairs = listOf(
      GlossaryTerm(source = "I am, unfortunately, the Hero of Ages.", target = "Sou, infelizmente, o Herói das Eras.")
    )
    val promptWithPairs = Prompt.systemPrompt(examplePairs = examplePairs)
    assertTrue(promptWithPairs.contains("Exemplos de estilo EN↔PT:\n- \"I am, unfortunately, the Hero of Ages.\" → \"Sou, infelizmente, o Herói das Eras.\""))

    // 4. Combined prompt with glossary, characters, examplePairs, and style notes
    val fullPrompt = Prompt.systemPrompt(
      glossary = listOf(GlossaryTerm("Allomancy", "Alomancia")),
      styleNotes = "Mantenha o tom sóbrio e épico.",
      characters = characters,
      examplePairs = examplePairs,
    )

    assertTrue(fullPrompt.contains("Glossário obrigatório (use exatamente estas traduções):"))
    assertTrue(fullPrompt.contains("- Allomancy → Alomancia"))
    assertTrue(fullPrompt.contains("Personagens da série:"))
    assertTrue(fullPrompt.contains("- Vin → Vin (Protagonista alomântica)"))
    assertTrue(fullPrompt.contains("Exemplos de estilo EN↔PT:"))
    assertTrue(fullPrompt.contains("- \"I am, unfortunately, the Hero of Ages.\" → \"Sou, infelizmente, o Herói das Eras.\""))
    assertTrue(fullPrompt.contains("Notas de estilo da série:\nMantenha o tom sóbrio e épico."))
  }
}
