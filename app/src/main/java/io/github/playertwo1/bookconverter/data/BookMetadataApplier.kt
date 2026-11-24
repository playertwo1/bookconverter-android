package io.github.playertwo1.bookconverter.data

import io.github.playertwo1.bookconverter.core.metadata.AggregatedBookCandidate
import io.github.playertwo1.bookconverter.core.metadata.MetadataField
import io.github.playertwo1.bookconverter.core.metadata.MetadataSource
import io.github.playertwo1.bookconverter.core.metadata.normalizeIsbn
import io.github.playertwo1.bookconverter.core.metadata.normalizeSeriesPosition
import io.github.playertwo1.bookconverter.core.metadata.parseMetadataSources
import io.github.playertwo1.bookconverter.core.metadata.serializeMetadataSources
import io.github.playertwo1.bookconverter.core.metadata.trimToBriefSummary

/**
 * Confirmed metadata edits from the review screen. Only fields present in [appliedFields]
 * are written to the Book or Series; unchecked fields keep their existing values untouched.
 */
data class BookMetadataReviewSelection(
    val values: Map<MetadataField, String>,
    val sources: Map<MetadataField, MetadataSource>,
    val appliedFields: Set<MetadataField>,
    val openLibraryKey: String? = null,
    val wikidataQid: String? = null,
    val hardcoverId: String? = null,
    val seriesOpenLibraryKey: String? = null,
    val seriesWikidataQid: String? = null,
    val seriesHardcoverId: String? = null,
) {
    fun appliedValue(field: MetadataField): String? {
        if (field !in appliedFields) return null
        val raw = values[field]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when (field) {
            MetadataField.ISBN -> normalizeIsbn(raw) ?: raw
            MetadataField.SERIES_POSITION -> normalizeSeriesPosition(raw) ?: raw
            MetadataField.SUMMARY, MetadataField.SERIES_SUMMARY -> trimToBriefSummary(raw, maxChars = 400) ?: raw
            else -> raw
        }
    }
}

private val BOOK_FIELDS = setOf(
    MetadataField.TITLE,
    MetadataField.AUTHOR,
    MetadataField.ISBN,
    MetadataField.PUBLISHER,
    MetadataField.PUBLISHED_DATE,
    MetadataField.LANGUAGE,
    MetadataField.SUMMARY,
    MetadataField.SERIES_POSITION,
)

private val SERIES_FIELDS = setOf(
    MetadataField.SERIES_NAME,
    MetadataField.AUTHOR,
    MetadataField.SERIES_SUMMARY,
)

/**
 * Builds the default set of checked fields for the review screen:
 * auto-checks fields that are currently blank on the target book/series, and leaves fields
 * that already have a different value UNCHECKED so the user must explicitly confirm overwriting.
 */
fun defaultAppliedFields(
    candidate: AggregatedBookCandidate,
    existingBook: Book?,
    existingSeries: Series?,
    currentSeriesName: String = existingSeries?.name.orEmpty(),
    currentSeriesAuthor: String = existingSeries?.author.orEmpty(),
    currentSeriesSummary: String = existingSeries?.summary.orEmpty(),
): Set<MetadataField> {
    val result = linkedSetOf<MetadataField>()
    fun consider(field: MetadataField, existingValue: String?) {
        val proposed = candidate.value(field)?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val current = existingValue?.trim()?.takeIf { it.isNotEmpty() }
        if (current == null || current.equals(proposed, ignoreCase = true)) {
            result += field
        }
    }

    consider(MetadataField.SERIES_NAME, currentSeriesName)
    consider(MetadataField.SERIES_SUMMARY, currentSeriesSummary)
    consider(MetadataField.AUTHOR, currentSeriesAuthor.ifBlank { existingBook?.author.orEmpty() })

    if (existingBook != null) {
        consider(MetadataField.TITLE, existingBook.title)
        consider(MetadataField.ISBN, existingBook.isbn)
        consider(MetadataField.PUBLISHER, existingBook.publisher)
        consider(MetadataField.PUBLISHED_DATE, existingBook.publishedDate)
        consider(MetadataField.LANGUAGE, existingBook.language)
        consider(MetadataField.SUMMARY, existingBook.summary)
        consider(MetadataField.SERIES_POSITION, existingBook.seriesPosition)
    }
    return result
}

fun applyMetadataToBook(
    existing: Book,
    selection: BookMetadataReviewSelection,
    targetSeriesId: Long? = existing.seriesId,
): Book {
    val mergedSources = parseMetadataSources(existing.metadataSourcesJson).toMutableMap()
    val appliedBookFields = selection.appliedFields.intersect(BOOK_FIELDS)
    for (field in appliedBookFields) {
        if (selection.appliedValue(field) != null) {
            selection.sources[field]?.let { mergedSources[field] = it }
        }
    }
    val hasAnyBookUpdate = appliedBookFields.any { selection.appliedValue(it) != null }
    return existing.copy(
        title = selection.appliedValue(MetadataField.TITLE) ?: existing.title,
        author = selection.appliedValue(MetadataField.AUTHOR) ?: existing.author,
        isbn = selection.appliedValue(MetadataField.ISBN) ?: existing.isbn,
        publisher = selection.appliedValue(MetadataField.PUBLISHER) ?: existing.publisher,
        publishedDate = selection.appliedValue(MetadataField.PUBLISHED_DATE) ?: existing.publishedDate,
        language = selection.appliedValue(MetadataField.LANGUAGE) ?: existing.language,
        summary = selection.appliedValue(MetadataField.SUMMARY) ?: existing.summary,
        seriesPosition = selection.appliedValue(MetadataField.SERIES_POSITION) ?: existing.seriesPosition,
        seriesId = targetSeriesId ?: existing.seriesId,
        openLibraryKey = if (hasAnyBookUpdate) (selection.openLibraryKey ?: existing.openLibraryKey) else existing.openLibraryKey,
        wikidataQid = if (hasAnyBookUpdate) (selection.wikidataQid ?: existing.wikidataQid) else existing.wikidataQid,
        hardcoverId = if (hasAnyBookUpdate) (selection.hardcoverId ?: existing.hardcoverId) else existing.hardcoverId,
        metadataSourcesJson = serializeMetadataSources(mergedSources),
    )
}

fun applyMetadataToSeries(
    existing: Series,
    selection: BookMetadataReviewSelection,
    now: Long,
): Series {
    val mergedSources = parseMetadataSources(existing.metadataSourcesJson).toMutableMap()
    val appliedSeriesFields = selection.appliedFields.intersect(SERIES_FIELDS)
    for (field in appliedSeriesFields) {
        if (selection.appliedValue(field) != null) {
            selection.sources[field]?.let { mergedSources[field] = it }
        }
    }
    val hasAnySeriesUpdate = appliedSeriesFields.any { selection.appliedValue(it) != null }
    return existing.copy(
        name = selection.appliedValue(MetadataField.SERIES_NAME) ?: existing.name,
        author = selection.appliedValue(MetadataField.AUTHOR) ?: existing.author,
        summary = selection.appliedValue(MetadataField.SERIES_SUMMARY) ?: existing.summary,
        openLibraryKey = if (hasAnySeriesUpdate) (selection.seriesOpenLibraryKey ?: existing.openLibraryKey) else existing.openLibraryKey,
        wikidataQid = if (hasAnySeriesUpdate) (selection.seriesWikidataQid ?: existing.wikidataQid) else existing.wikidataQid,
        hardcoverId = if (hasAnySeriesUpdate) (selection.seriesHardcoverId ?: existing.hardcoverId) else existing.hardcoverId,
        metadataSourcesJson = serializeMetadataSources(mergedSources),
        updatedAt = now,
    )
}

fun formatSourceAttribution(metadataSourcesJson: String?): String? {
    val sources = parseMetadataSources(metadataSourcesJson).values.distinct()
    if (sources.isEmpty()) return null
    val names = sources.sortedBy { it.ordinal }.joinToString(", ") { it.displayName }
    return "Fontes: $names"
}
