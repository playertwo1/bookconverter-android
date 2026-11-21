package io.github.playertwo1.bookconverter.core.translate

import kotlin.math.ln

object SeriesRag {

  private val WORD_REGEX = Regex("""[\p{L}\p{N}]+""")

  private val STOP_WORDS = setOf(
    "a", "about", "above", "after", "again", "against", "all", "am", "an", "and", "any", "are", "aren't",
    "as", "at", "be", "because", "been", "before", "being", "below", "between", "both", "but", "by",
    "can", "can't", "cannot", "could", "couldn't", "did", "didn't", "do", "does", "doesn't", "doing",
    "don't", "down", "during", "each", "few", "for", "from", "further", "had", "hadn't", "has", "hasn't",
    "have", "haven't", "having", "he", "he'd", "he'll", "he's", "her", "here", "here's", "hers", "herself",
    "him", "himself", "his", "how", "how's", "i", "i'd", "i'll", "i'm", "i've", "if", "in", "into", "is",
    "isn't", "it", "it's", "its", "itself", "let's", "me", "more", "most", "mustn't", "my", "myself",
    "no", "nor", "not", "of", "off", "on", "once", "only", "or", "other", "ought", "our", "ours", "ourselves",
    "out", "over", "own", "same", "shan't", "she", "she'd", "she'll", "she's", "should", "shouldn't", "so",
    "some", "such", "than", "that", "that's", "the", "their", "theirs", "them", "themselves", "then", "there",
    "there's", "these", "they", "they'd", "they'll", "they're", "they've", "this", "those", "through", "to",
    "too", "under", "until", "up", "very", "was", "wasn't", "we", "we'd", "we'll", "we're", "we've", "were",
    "weren't", "what", "what's", "when", "when's", "where", "where's", "which", "while", "who", "who's",
    "whom", "why", "why's", "with", "won't", "would", "wouldn't", "you", "you'd", "you'll", "you're",
    "you've", "your", "yours", "yourself", "yourselves",
    "de", "a", "o", "que", "e", "do", "da", "em", "um", "para", "é", "com", "não", "uma", "os", "no", "se",
    "na", "por", "mais", "as", "dos", "como", "mas", "foi", "ao", "ele", "das", "tem", "à", "seu", "sua",
    "ou", "ser", "quando", "muito", "nos", "já", "está", "eu", "também", "só", "pelo", "pela", "até", "isso",
    "ela", "entre", "era", "depois", "sem", "mesmo", "aos", "ter", "seus", "quem", "nas", "me", "esse", "eles"
  )

  /**
   * Ranks candidates by hybrid semantic similarity (TF-IDF token overlap + character trigrams for morphological stems)
   * between [queryText] and each candidate's `source` + `notes`.
   * Breaks ties by preserving original candidate order.
   */
  fun rankRelevantPairs(
    queryText: String,
    candidates: List<GlossaryTerm>,
    topK: Int = 5,
  ): List<GlossaryTerm> {
    if (candidates.isEmpty() || topK <= 0) return emptyList()
    if (queryText.isBlank()) return candidates.take(topK)

    val queryTokens = tokenize(queryText).toSet()
    val queryTrigrams = extractTrigrams(queryText)

    // Precompute document frequency of tokens across all candidates
    val candidateTokensList = candidates.map { candidate ->
      tokenize(candidateCombinedText(candidate))
    }

    val numDocs = candidates.size.toDouble()
    val docFreq = mutableMapOf<String, Int>()
    candidateTokensList.forEach { tokens ->
      tokens.toSet().forEach { token ->
        docFreq[token] = (docFreq[token] ?: 0) + 1
      }
    }

    // IDF function: higher score for rarer terms among candidates
    fun idf(term: String): Double {
      val df = docFreq[term] ?: 1
      return ln((numDocs + 1.0) / (df + 1.0)) + 1.0
    }

    data class ScoredCandidate(val term: GlossaryTerm, val index: Int, val score: Double)

    val scored = candidates.mapIndexed { index, candidate ->
      val candTokens = candidateTokensList[index]
      val combinedText = candidateCombinedText(candidate)

      // 1. TF-IDF Token Overlap Score
      val tokenScore = if (candTokens.isNotEmpty()) {
        val totalIdf = candTokens.sumOf { idf(it) }
        val matchedIdf = candTokens.filter { it in queryTokens }.sumOf { idf(it) }
        if (totalIdf > 0.0) matchedIdf / totalIdf else 0.0
      } else {
        0.0
      }

      // 2. Character Trigram Score (Morphological similarity)
      val candTrigrams = extractTrigrams(combinedText)
      val trigramScore = if (candTrigrams.isNotEmpty()) {
        val matches = candTrigrams.count { it in queryTrigrams }
        matches.toDouble() / candTrigrams.size
      } else {
        0.0
      }

      // Hybrid combination (60% TF-IDF token overlap + 40% character trigrams)
      val hybridScore = (0.6 * tokenScore) + (0.4 * trigramScore)

      ScoredCandidate(candidate, index, hybridScore)
    }

    return scored
      .sortedWith(compareByDescending<ScoredCandidate> { it.score }.thenBy { it.index })
      .take(topK)
      .map { it.term }
  }

  private fun candidateCombinedText(candidate: GlossaryTerm): String {
    return if (!candidate.notes.isNullOrBlank()) {
      "${candidate.source} ${candidate.notes}"
    } else {
      candidate.source
    }
  }

  private fun tokenize(text: String): List<String> {
    val all = WORD_REGEX.findAll(text.lowercase()).map { it.value }.toList()
    val filtered = all.filter { it.length > 1 && it !in STOP_WORDS }
    return if (filtered.isNotEmpty()) filtered else all
  }

  private fun extractTrigrams(text: String): Set<String> {
    val clean = text.lowercase().replace(Regex("""\s+"""), " ").trim()
    if (clean.length < 3) {
      return if (clean.isNotEmpty()) setOf(clean) else emptySet()
    }
    val result = HashSet<String>(clean.length - 2)
    for (i in 0..clean.length - 3) {
      result.add(clean.substring(i, i + 3))
    }
    return result
  }
}
