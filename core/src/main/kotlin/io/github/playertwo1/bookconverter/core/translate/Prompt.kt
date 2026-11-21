package io.github.playertwo1.bookconverter.core.translate

import io.github.playertwo1.bookconverter.core.provider.ErrorKind
import io.github.playertwo1.bookconverter.core.provider.ProviderError

/** Bump when [SYSTEM] or the marker format changes: P2 records it per block for reproducibility. */
const val PROMPT_VERSION = 1

object Prompt {
  val SYSTEM = """
    You are a literary translator. Translate the English passages the user sends into Brazilian Portuguese (pt-BR) for publication: natural, fluent prose that keeps the author's tone, register, rhythm and meaning, with no omissions, additions or explanations.
    Rules:
    1. The input is a sequence of segments. Each starts with a marker such as ⟦1⟧ on its own line. Reply with exactly the same markers, in the same order, each followed by the translation of that segment only. Never merge, split, drop or renumber segments.
    2. Segments contain inline HTML (for example <i>, <b>, <em>, <a href="…">, <span class="…">, <br/>). Keep every tag and attribute exactly as given, around the translated words they belong to.
    3. Keep proper names as they are unless a glossary says otherwise.
    4. Reply with the marked segments only: no preamble, notes or code fences.
  """.trimIndent()

  private val MARKER = Regex("⟦(\\d+)⟧")

  fun systemPrompt(
    glossary: List<GlossaryTerm> = emptyList(),
    styleNotes: String? = null,
    basePrompt: String = SYSTEM,
    characters: List<GlossaryTerm> = emptyList(),
    examplePairs: List<GlossaryTerm> = emptyList(),
  ): String {
    if (glossary.isEmpty() && characters.isEmpty() && examplePairs.isEmpty() && styleNotes.isNullOrBlank()) {
      return basePrompt
    }
    val sb = StringBuilder(basePrompt)
    if (glossary.isNotEmpty()) {
      sb.append("\n\nGlossário obrigatório (use exatamente estas traduções):\n")
      glossary.forEach { term ->
        sb.append("- ${term.source} → ${term.target}\n")
      }
    }
    if (characters.isNotEmpty()) {
      sb.append("\n\nPersonagens da série:\n")
      characters.forEach { char ->
        val noteSuffix = if (!char.notes.isNullOrBlank()) " (${char.notes.trim()})" else ""
        sb.append("- ${char.source} → ${char.target}$noteSuffix\n")
      }
    }
    if (examplePairs.isNotEmpty()) {
      sb.append("\n\nExemplos de estilo EN↔PT:\n")
      examplePairs.forEach { pair ->
        sb.append("- \"${pair.source}\" → \"${pair.target}\"\n")
      }
    }
    if (!styleNotes.isNullOrBlank()) {
      sb.append("\n\nNotas de estilo da série:\n").append(styleNotes.trim())
    }
    return sb.toString().trimEnd()
  }

  fun encode(chunk: Chunk): String = chunk.blocks.mapIndexed { i, b -> "⟦${i + 1}⟧\n${b.html}" }.joinToString("\n\n")

  /**
   * Splits the reply back into one inner-HTML string per block. Text before the first marker
   * (a stray "Here is the translation:") is ignored; anything else off is [ErrorKind.BAD_OUTPUT].
   */
  fun decode(reply: String, expected: Int): List<String> {
    val marks = MARKER.findAll(reply).toList()
    val numbers = marks.map { it.groupValues[1].toInt() }
    if (numbers != (1..expected).toList()) {
      throw ProviderError(200, "A IA devolveu ${marks.size} segmento(s) numerados ${numbers.take(8)}; eram $expected", ErrorKind.BAD_OUTPUT)
    }
    return marks.mapIndexed { i, m ->
      val end = if (i + 1 < marks.size) marks[i + 1].range.first else reply.length
      reply.substring(m.range.last + 1, end).trim().removeSuffix("```").trim()
    }
  }
}
