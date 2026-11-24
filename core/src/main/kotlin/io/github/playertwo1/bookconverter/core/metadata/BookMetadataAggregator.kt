package io.github.playertwo1.bookconverter.core.metadata

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.Locale

data class FieldOption(
  val value: String,
  val source: MetadataSource,
)

data class FieldProposal(
  val field: MetadataField,
  val selectedValue: String,
  val selectedSource: MetadataSource,
  val options: List<FieldOption>,
) {
  val hasConflict: Boolean get() = options.size > 1
}

data class AggregatedBookCandidate(
  val id: String,
  val sources: Set<MetadataSource>,
  val fields: Map<MetadataField, FieldProposal>,
  val openLibraryKey: String? = null,
  val wikidataQid: String? = null,
  val hardcoverId: String? = null,
  val seriesOpenLibraryKey: String? = null,
  val seriesWikidataQid: String? = null,
  val seriesHardcoverId: String? = null,
  val characters: List<MetadataNamedItem> = emptyList(),
  val places: List<MetadataNamedItem> = emptyList(),
) {
  fun value(field: MetadataField): String? = fields[field]?.selectedValue
  fun sourceOf(field: MetadataField): MetadataSource? = fields[field]?.selectedSource
  val hasAnyConflict: Boolean get() = fields.values.any { it.hasConflict }
  val displayTitle: String get() = value(MetadataField.TITLE) ?: "Sem título"
  val displayAuthor: String? get() = value(MetadataField.AUTHOR)
  val displayYear: String? get() = value(MetadataField.PUBLISHED_DATE)?.let(::extractYear)
  val displayPublisher: String? get() = value(MetadataField.PUBLISHER)
  val displaySeries: String? get() = value(MetadataField.SERIES_NAME)
  val displayPosition: String? get() = value(MetadataField.SERIES_POSITION)
}

data class AggregatedSearchResponse(
  val candidates: List<AggregatedBookCandidate>,
  val sourceResults: List<SourceSearchResult>,
) {
  val activeSources: List<MetadataSource>
    get() = sourceResults.filterIsInstance<SourceSearchResult.Success>().map { it.source }

  val failedSources: List<SourceSearchResult.Failed>
    get() = sourceResults.filterIsInstance<SourceSearchResult.Failed>()

  val skippedSources: List<SourceSearchResult.Skipped>
    get() = sourceResults.filterIsInstance<SourceSearchResult.Skipped>()
}

/**
 * Queries all configured metadata providers in parallel, merges matching records across sources,
 * selects the best origin per field, preserves per-field provenance, and exposes conflicts so the
 * UI can let the user decide explicitly.
 */
class BookMetadataAggregator(
  private val providers: List<BookMetadataProvider>,
) {
  suspend fun search(query: BookMetadataQuery): AggregatedSearchResponse = coroutineScope {
    if (query.isBlank) {
      return@coroutineScope AggregatedSearchResponse(emptyList(), emptyList())
    }
    val results = providers.map { provider ->
      async { provider.search(query) }
    }.awaitAll()

    val allCandidates = results
      .filterIsInstance<SourceSearchResult.Success>()
      .flatMap { it.candidates }

    val requestedEdition = editionIsbn(query.normalizedIsbn)
    val merged = mergeCandidates(allCandidates).sortedByDescending {
      requestedEdition != null && editionIsbn(it.value(MetadataField.ISBN)) == requestedEdition
    }
    AggregatedSearchResponse(
      candidates = merged,
      sourceResults = results,
    )
  }

  companion object {
    private val PRIORITY_DEFAULT = listOf(
      MetadataSource.OPEN_LIBRARY,
      MetadataSource.HARDCOVER,
      MetadataSource.WIKIDATA,
    )
    private val PRIORITY_SERIES = listOf(
      MetadataSource.WIKIDATA,
      MetadataSource.HARDCOVER,
      MetadataSource.OPEN_LIBRARY,
    )
    // Open Library is unreliable for series position; only Wikidata and Hardcover are accepted.
    private val PRIORITY_SERIES_POSITION = listOf(
      MetadataSource.WIKIDATA,
      MetadataSource.HARDCOVER,
    )
    private val PRIORITY_SUMMARY = listOf(
      MetadataSource.HARDCOVER,
      MetadataSource.OPEN_LIBRARY,
      MetadataSource.WIKIDATA,
    )

    fun mergeCandidates(raw: List<SourceBookCandidate>): List<AggregatedBookCandidate> {
      val byIsbn = raw.groupBy { editionIsbn(it.isbn) }
      val editions = byIsbn.filterKeys { it != null }.values.toList()
      val workMatches = editions.map { mutableListOf<SourceBookCandidate>() }
      for (idx in editions.indices) {
        for (otherIdx in editions.indices) {
          if (otherIdx == idx) continue
          for (other in editions[otherIdx]) {
            if (other.hasWorkMetadata() && editions[idx].any { isSameWork(it, other) }) {
              workMatches[idx] += other
            }
          }
        }
      }
      val groups = mutableListOf<MutableList<SourceBookCandidate>>()
      for (candidate in byIsbn[null].orEmpty()) {
        // Only match against the edition records themselves. A work record cannot link two editions.
        val matches = editions.indices.filter { idx -> editions[idx].any { isSameWork(it, candidate) } }
        if (matches.isNotEmpty()) {
          matches.forEach { workMatches[it] += candidate }
          continue
        }
        val target = groups.firstOrNull { group -> group.any { isSameWork(it, candidate) } }
        if (target != null) {
          target += candidate
        } else {
          groups += mutableListOf(candidate)
        }
      }

      return (editions + groups).mapIndexed { idx, group ->
        buildAggregated("cand-$idx", group, workMatches.getOrNull(idx).orEmpty())
      }
        .sortedWith(
          compareByDescending<AggregatedBookCandidate> { it.sources.size }
            .thenByDescending { it.fields.size }
        )
    }

    private fun isSameWork(a: SourceBookCandidate, b: SourceBookCandidate): Boolean {
      if (a.source == b.source) return false

      if (a.openLibraryKey != null && b.openLibraryKey != null && a.openLibraryKey == b.openLibraryKey) return true
      if (a.wikidataQid != null && b.wikidataQid != null && a.wikidataQid == b.wikidataQid) return true
      if (a.hardcoverId != null && b.hardcoverId != null && a.hardcoverId == b.hardcoverId) return true

      val titlesA = listOfNotNull(normalizeMatchText(a.title), normalizeMatchText(a.alternateTitle)).toSet()
      val titlesB = listOfNotNull(normalizeMatchText(b.title), normalizeMatchText(b.alternateTitle)).toSet()
      if (titlesA.isEmpty() || titlesB.isEmpty()) return false
      if (titlesA.intersect(titlesB).isEmpty()) return false

      val authorA = normalizeMatchText(a.author)
      val authorB = normalizeMatchText(b.author)
      if (authorA != null && authorB != null) {
        return authorA == authorB || authorA.contains(authorB) || authorB.contains(authorA)
      }
      return authorA == null && authorB == null
    }

    private fun buildAggregated(
      id: String,
      group: List<SourceBookCandidate>,
      workMatches: List<SourceBookCandidate>,
    ): AggregatedBookCandidate {
      val fields = linkedMapOf<MetadataField, FieldProposal>()
      val workRecords = group + workMatches

      fun addField(
        field: MetadataField,
        priority: List<MetadataSource>,
        extractor: (SourceBookCandidate) -> String?,
        normalizer: (String) -> String = { normalizeCompareValue(it) },
        preferLongerSummary: Boolean = false,
        records: List<SourceBookCandidate> = group,
      ) {
        val rawOptions = records.mapNotNull { c ->
          if (c.source !in priority) return@mapNotNull null
          val rawVal = extractor(c)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
          val finalVal = when (field) {
            MetadataField.SUMMARY, MetadataField.SERIES_SUMMARY -> trimToBriefSummary(rawVal) ?: return@mapNotNull null
            MetadataField.SERIES_POSITION -> normalizeSeriesPosition(rawVal) ?: return@mapNotNull null
            MetadataField.ISBN -> normalizeIsbn(rawVal) ?: rawVal
            else -> rawVal
          }
          FieldOption(value = finalVal, source = c.source)
        }
        if (rawOptions.isEmpty()) return

        val ordered = if (preferLongerSummary) {
          // Prefer informative synopses (>= 80 chars) over one-line Wikidata descriptions before falling back to source order
          rawOptions.sortedWith(
            compareByDescending<FieldOption> { it.value.length >= 80 }
              .thenBy { priority.indexOf(it.source) }
          )
        } else {
          rawOptions.sortedBy { priority.indexOf(it.source) }
        }

        val deduped = mutableListOf<FieldOption>()
        val seenNorm = mutableSetOf<String>()
        for (opt in ordered) {
          val norm = normalizer(opt.value)
          if (seenNorm.add(norm)) {
            deduped += opt
          }
        }
        val winner = deduped.first()
        fields[field] = FieldProposal(
          field = field,
          selectedValue = winner.value,
          selectedSource = winner.source,
          options = deduped,
        )
      }

      addField(MetadataField.TITLE, PRIORITY_DEFAULT, { it.title })
      addField(MetadataField.AUTHOR, PRIORITY_DEFAULT, { it.author })
      addField(MetadataField.ISBN, PRIORITY_DEFAULT, { it.isbn }, normalizer = { editionIsbn(it) ?: normalizeCompareValue(it) })
      addField(MetadataField.PUBLISHER, PRIORITY_DEFAULT, { it.publisher })
      addField(MetadataField.PUBLISHED_DATE, PRIORITY_DEFAULT, { it.publishedDate }, normalizer = { extractYear(it) ?: normalizeCompareValue(it) })
      addField(MetadataField.LANGUAGE, PRIORITY_DEFAULT, { it.language })
      addField(MetadataField.SUMMARY, PRIORITY_SUMMARY, { it.summary }, preferLongerSummary = true, records = workRecords)
      addField(MetadataField.SERIES_NAME, PRIORITY_SERIES, { it.seriesName }, records = workRecords)
      addField(MetadataField.SERIES_POSITION, PRIORITY_SERIES_POSITION, { it.seriesPosition }, normalizer = { normalizeSeriesPosition(it) ?: it }, records = workRecords)
      addField(MetadataField.SERIES_SUMMARY, PRIORITY_SERIES, { it.seriesSummary }, preferLongerSummary = true, records = workRecords)

      return AggregatedBookCandidate(
        id = id,
        sources = workRecords.map { it.source }.toSet(),
        fields = fields,
        openLibraryKey = group.firstNotNullOfOrNull { it.openLibraryKey },
        wikidataQid = group.firstNotNullOfOrNull { it.wikidataQid },
        hardcoverId = group.firstNotNullOfOrNull { it.hardcoverId },
        seriesOpenLibraryKey = workRecords.firstNotNullOfOrNull { it.seriesOpenLibraryKey },
        seriesWikidataQid = workRecords.firstNotNullOfOrNull { it.seriesWikidataQid },
        seriesHardcoverId = workRecords.firstNotNullOfOrNull { it.seriesHardcoverId },
        characters = mergeNamedItems(emptyList(), workRecords.flatMap { it.characters }),
        places = mergeNamedItems(emptyList(), workRecords.flatMap { it.places }),
      )
    }
  }
}

private fun SourceBookCandidate.hasWorkMetadata(): Boolean =
  !summary.isNullOrBlank() ||
    !seriesName.isNullOrBlank() ||
    !seriesPosition.isNullOrBlank() ||
    !seriesSummary.isNullOrBlank() ||
    seriesOpenLibraryKey != null ||
    seriesWikidataQid != null ||
    seriesHardcoverId != null ||
    characters.isNotEmpty() ||
    places.isNotEmpty()

private fun editionIsbn(raw: String?): String? {
  val isbn = normalizeIsbn(raw) ?: return null
  if (isbn.length == 13) {
    if (!isbn.startsWith("978") && !isbn.startsWith("979")) return null
    return isbn.takeIf { isbn13CheckDigit(it.take(12)) == it.last().digitToInt() }
  }
  val checksum = isbn.mapIndexed { index, digit ->
    (10 - index) * (if (digit == 'X') 10 else digit.digitToInt())
  }.sum()
  if (checksum % 11 != 0) return null
  val prefix = "978" + isbn.take(9)
  return prefix + isbn13CheckDigit(prefix)
}

private fun isbn13CheckDigit(prefix: String): Int {
  val sum = prefix.mapIndexed { index, digit -> digit.digitToInt() * (if (index % 2 == 0) 1 else 3) }.sum()
  return (10 - sum % 10) % 10
}

private val NON_ALNUM_REGEX = Regex("[^\\p{L}\\p{N}]+")
private val YEAR_REGEX = Regex("\\b(1\\d{3}|20\\d{2})\\b")

private fun normalizeMatchText(raw: String?): String? =
  raw?.trim()?.lowercase(Locale.ROOT)?.replace(NON_ALNUM_REGEX, " ")?.trim()?.takeIf { it.isNotEmpty() }

private fun normalizeCompareValue(raw: String): String =
  raw.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

private fun extractYear(raw: String): String? =
  YEAR_REGEX.find(raw)?.groupValues?.getOrNull(1)
