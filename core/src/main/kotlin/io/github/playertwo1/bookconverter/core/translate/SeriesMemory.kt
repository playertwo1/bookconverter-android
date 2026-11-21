package io.github.playertwo1.bookconverter.core.translate

import io.github.playertwo1.bookconverter.core.epub.Block
import io.github.playertwo1.bookconverter.core.epub.Epub
import io.github.playertwo1.bookconverter.core.provider.ProviderClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

enum class MemoryCategory { TERM, CHARACTER, PAIR }

enum class ProposalStatus { PENDING, APPROVED, REJECTED }

data class MemoryProposal(
  val category: MemoryCategory,
  val source: String,
  val target: String,
  val notes: String? = null,
  val excerpt: String,
  val sourceBookTitle: String,
  val conflictWarning: String? = null,
)

data class ExtractionResult(
  val proposals: List<MemoryProposal>,
  val inputTokens: Long,
  val outputTokens: Long,
  val chunksScanned: Int,
)

object SeriesMemoryExtractor {

  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  val SYSTEM_PROMPT = """
    You are an expert literary translator and editor extracting series memory for a book series.
    Analyze the provided text excerpt from the book and extract key terms, character names, and style/dialogue examples that should remain consistent across the series.

    Categories:
    1. "TERM": Fictional or world-building terms, special places, organizations, spells, weapons, titles, or concepts.
    2. "CHARACTER": Character names, honorifics, titles, or nicknames.
    3. "PAIR": Distinctive expressions, catchphrases, recurring idioms, or stylistic dialogue pairs.

    For each item, specify:
    - "category": "TERM", "CHARACTER", or "PAIR"
    - "source": Original term, name, or expression as written in the text.
    - "target": Brazilian Portuguese (pt-BR) translation, transliteration, or maintained proper name.
    - "notes": Brief context, explanation, role, or gender (optional).
    - "excerpt": Short exact sentence or phrase from the text where the term appears.

    Return ONLY a valid JSON array of objects with keys "category", "source", "target", "notes", and "excerpt".
    If no relevant items are found, return an empty array [].
    Do NOT include markdown formatting or commentary outside the JSON array.
  """.trimIndent()

  suspend fun extractFromEpub(
    epubFile: File,
    sourceBookTitle: String,
    client: ProviderClient,
    model: String,
    effort: String? = null,
    existingTerms: List<GlossaryTerm> = emptyList(),
    maxSamples: Int = 4,
    onProgress: (Int, Int) -> Unit = { _, _ -> },
  ): ExtractionResult = withContext(Dispatchers.IO) {
    val epub = try {
      epubFile.inputStream().use { Epub.read(it) }
    } catch (_: Exception) {
      return@withContext ExtractionResult(emptyList(), 0L, 0L, 0)
    }

    val blocks = epub.blocks()
    if (blocks.isEmpty() || maxSamples <= 0) {
      return@withContext ExtractionResult(emptyList(), 0L, 0L, 0)
    }

    val samples = sampleBlocks(blocks, maxSamples = maxSamples, maxCharsPerSample = 3000)
    if (samples.isEmpty()) {
      return@withContext ExtractionResult(emptyList(), 0L, 0L, 0)
    }

    var totalInputTokens = 0L
    var totalOutputTokens = 0L
    var chunksScanned = 0
    val rawProposals = mutableListOf<MemoryProposal>()

    for ((index, sampleText) in samples.withIndex()) {
      val userPrompt = """
        Livro: "$sourceBookTitle"

        Trecho do livro para análise:
        $sampleText

        Retorne o array JSON com os termos extraídos.
      """.trimIndent()

      val translation = try {
        client.translate(
          model = model,
          system = SYSTEM_PROMPT,
          text = userPrompt,
          effort = effort,
        )
      } catch (_: Exception) {
        // Tolerates chunk failure gracefully
        null
      }

      if (translation != null) {
        totalInputTokens += (translation.inputTokens ?: 0L)
        totalOutputTokens += (translation.outputTokens ?: 0L)
        val parsed = parseProposals(translation.text, sourceBookTitle, sampleText)
        rawProposals.addAll(parsed)
      }

      chunksScanned++
      onProgress(chunksScanned, samples.size)
    }

    val finalized = applyConflictWarnings(rawProposals, existingTerms)
    ExtractionResult(
      proposals = finalized,
      inputTokens = totalInputTokens,
      outputTokens = totalOutputTokens,
      chunksScanned = chunksScanned,
    )
  }

  internal fun sampleBlocks(
    blocks: List<Block>,
    maxSamples: Int = 4,
    maxCharsPerSample: Int = 3000,
  ): List<String> {
    if (blocks.isEmpty() || maxSamples <= 0) return emptyList()

    val totalChars = blocks.sumOf { it.text.length }
    if (totalChars <= maxCharsPerSample) {
      val joined = blocks.joinToString("\n\n") { it.text.trim() }.trim()
      return if (joined.isNotEmpty()) listOf(joined) else emptyList()
    }

    val sampleCount = minOf(maxSamples, blocks.size)
    val step = blocks.size.toDouble() / sampleCount
    val samples = mutableListOf<String>()

    for (i in 0 until sampleCount) {
      val startIndex = (i * step).toInt()
      val endIndex = if (i + 1 < sampleCount) ((i + 1) * step).toInt() else blocks.size
      val sb = StringBuilder()

      for (idx in startIndex until endIndex) {
        val text = blocks[idx].text.trim()
        if (text.isEmpty()) continue
        if (sb.isNotEmpty() && sb.length + text.length + 2 > maxCharsPerSample) {
          if (sb.isEmpty()) {
            sb.append(text.take(maxCharsPerSample))
          }
          break
        }
        if (sb.isNotEmpty()) sb.append("\n\n")
        sb.append(text)
      }

      val sample = sb.toString().trim()
      if (sample.isNotEmpty()) {
        samples.add(sample)
      }
    }
    return samples
  }

  internal fun extractJson(raw: String): String {
    var text = raw.trim()
    if (text.contains("```")) {
      val fenceContent = text.substringAfter("```")
        .let { if (it.startsWith("json", ignoreCase = true)) it.substring(4) else it }
        .substringBefore("```")
        .trim()
      if (fenceContent.isNotEmpty()) {
        text = fenceContent
      }
    }

    val start = text.indexOf('[')
    val end = text.lastIndexOf(']')
    if (start != -1 && end != -1 && end > start) {
      return text.substring(start, end + 1).trim()
    }

    val braceStart = text.indexOf('{')
    val braceEnd = text.lastIndexOf('}')
    if (braceStart != -1 && braceEnd != -1 && braceEnd > braceStart) {
      return text.substring(braceStart, braceEnd + 1).trim()
    }
    return text
  }

  internal fun parseProposals(
    rawJson: String,
    sourceBookTitle: String,
    sampleText: String,
  ): List<MemoryProposal> {
    if (rawJson.isBlank()) return emptyList()
    val cleaned = extractJson(rawJson)

    val element = try {
      json.parseToJsonElement(cleaned)
    } catch (_: Exception) {
      val lastStart = cleaned.lastIndexOf('[')
      val lastEnd = cleaned.lastIndexOf(']')
      if (lastStart != -1 && lastEnd > lastStart) {
        try {
          json.parseToJsonElement(cleaned.substring(lastStart, lastEnd + 1))
        } catch (_: Exception) {
          return emptyList()
        }
      } else {
        return emptyList()
      }
    }

    val array = when (element) {
      is JsonArray -> element
      is JsonObject -> element.values.firstOrNull { it is JsonArray } as? JsonArray
      else -> null
    } ?: return emptyList()

    val proposals = mutableListOf<MemoryProposal>()
    for (item in array) {
      val obj = item as? JsonObject ?: continue
      val catStr = obj["category"]?.jsonPrimitive?.contentOrNull?.trim()?.uppercase() ?: continue
      val category = try {
        MemoryCategory.valueOf(catStr)
      } catch (_: Exception) {
        continue
      }

      val source = obj["source"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
      val target = obj["target"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
      if (source.isEmpty() || target.isEmpty()) continue

      val notes = obj["notes"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
      val rawExcerpt = obj["excerpt"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
      val excerpt = if (rawExcerpt.isNotEmpty()) rawExcerpt else findExcerpt(sampleText, source)

      proposals.add(
        MemoryProposal(
          category = category,
          source = source,
          target = target,
          notes = notes,
          excerpt = excerpt,
          sourceBookTitle = sourceBookTitle,
          conflictWarning = null,
        )
      )
    }
    return proposals
  }

  internal fun findExcerpt(sampleText: String, source: String): String {
    if (sampleText.isBlank()) return source
    val idx = sampleText.indexOf(source, ignoreCase = true)
    if (idx == -1) return sampleText.take(150).trim()
    val start = maxOf(0, idx - 60)
    val end = minOf(sampleText.length, idx + source.length + 60)
    val snippet = sampleText.substring(start, end).replace('\n', ' ').trim()
    val prefix = if (start > 0) "…" else ""
    val suffix = if (end < sampleText.length) "…" else ""
    return "$prefix$snippet$suffix"
  }

  internal fun applyConflictWarnings(
    rawProposals: List<MemoryProposal>,
    existingTerms: List<GlossaryTerm>,
  ): List<MemoryProposal> {
    val result = mutableListOf<MemoryProposal>()
    val extractionTargets = mutableMapOf<String, String>() // lowercase source -> first seen target

    for (proposal in rawProposals) {
      val lowerSource = proposal.source.trim().lowercase()
      val existingMatch = existingTerms.firstOrNull { it.source.trim().equals(proposal.source.trim(), ignoreCase = true) }

      val warning = when {
        existingMatch != null -> {
          if (!existingMatch.target.trim().equals(proposal.target.trim(), ignoreCase = true)) {
            "Conflito: já traduzido como '${existingMatch.target.trim()}'"
          } else {
            null
          }
        }
        extractionTargets.containsKey(lowerSource) && !extractionTargets[lowerSource]!!.equals(proposal.target.trim(), ignoreCase = true) -> {
          "Conflito: já traduzido como '${extractionTargets[lowerSource]}'"
        }
        else -> null
      }

      if (!extractionTargets.containsKey(lowerSource)) {
        extractionTargets[lowerSource] = proposal.target.trim()
      }

      result.add(proposal.copy(conflictWarning = warning))
    }
    return result
  }
}
