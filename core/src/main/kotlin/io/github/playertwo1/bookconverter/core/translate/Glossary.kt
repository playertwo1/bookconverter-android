package io.github.playertwo1.bookconverter.core.translate

data class GlossaryTerm(
  val source: String,
  val target: String,
  val notes: String? = null,
)

data class GlossaryParseResult(
  val terms: List<GlossaryTerm>,
  val conflicts: List<String>,
  val skippedLines: Int,
)

object Glossary {

  /**
   * Parses desktop glossary txt format:
   * Each line is either:
   * - Empty or comment (# ...) -> ignored
   * - `source → target` or `source -> target`
   * Conflicts (same source case-insensitive with different target) are registered in [GlossaryParseResult.conflicts],
   * keeping the first occurrence deterministically.
   */
  fun parseDesktopTxt(content: String): GlossaryParseResult {
    val termsMap = LinkedHashMap<String, GlossaryTerm>()
    val conflicts = mutableListOf<String>()
    var skipped = 0

    content.lineSequence().forEach { rawLine ->
      val trimmed = rawLine.trim()
      if (trimmed.isEmpty() || trimmed.startsWith("#")) {
        return@forEach
      }

      val arrowIndex = when {
        trimmed.contains("→") -> trimmed.indexOf("→")
        trimmed.contains("->") -> trimmed.indexOf("->")
        else -> -1
      }

      if (arrowIndex == -1) {
        skipped++
        return@forEach
      }

      val arrowLen = if (trimmed.substring(arrowIndex).startsWith("->")) 2 else 1
      val source = trimmed.substring(0, arrowIndex).trim()
      val target = trimmed.substring(arrowIndex + arrowLen).trim()

      if (source.isEmpty() || target.isEmpty()) {
        skipped++
        return@forEach
      }

      val key = source.lowercase()
      val existing = termsMap[key]
      if (existing != null) {
        if (existing.target != target) {
          conflicts.add("Conflito para '$source': mantendo '${existing.target}', ignorando '$target'")
        }
      } else {
        termsMap[key] = GlossaryTerm(source = source, target = target)
      }
    }

    return GlossaryParseResult(
      terms = termsMap.values.toList(),
      conflicts = conflicts,
      skippedLines = skipped,
    )
  }

  /**
   * Formats terms to desktop compatible text format (`source → target`).
   */
  fun formatDesktopTxt(terms: List<GlossaryTerm>): String {
    return terms.joinToString("\n") { "${it.source} → ${it.target}" }
  }

  fun serializeSnapshot(terms: List<GlossaryTerm>): String = formatDesktopTxt(terms)

  fun parseSnapshot(snapshot: String?): List<GlossaryTerm> =
    if (snapshot.isNullOrBlank()) emptyList() else parseDesktopTxt(snapshot).terms

  /**
   * Matches terms that appear in [text], respecting word boundaries (non-letter/non-digit)
   * to avoid false positives (e.g. "cat" inside "category"), supporting compound terms and Unicode.
   */
  fun matchTerms(text: String, glossary: List<GlossaryTerm>): List<GlossaryTerm> {
    if (glossary.isEmpty() || text.isEmpty()) return emptyList()
    return glossary.filter { term ->
      val source = term.source.trim()
      if (source.isEmpty()) return@filter false

      var startIndex = 0
      while (startIndex < text.length) {
        val idx = text.indexOf(source, startIndex, ignoreCase = true)
        if (idx == -1) return@filter false

        val beforeOk = idx == 0 || !text[idx - 1].isLetterOrDigit()
        val endIdx = idx + source.length
        val afterOk = endIdx == text.length || !text[endIdx].isLetterOrDigit()

        if (beforeOk && afterOk) {
          return@filter true
        }
        startIndex = idx + 1
      }
      false
    }
  }
}
