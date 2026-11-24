package io.github.playertwo1.bookconverter.data

import io.github.playertwo1.bookconverter.core.metadata.AggregatedBookCandidate
import io.github.playertwo1.bookconverter.core.metadata.MetadataField
import io.github.playertwo1.bookconverter.core.metadata.MetadataNamedItem
import io.github.playertwo1.bookconverter.core.metadata.MetadataSource
import io.github.playertwo1.bookconverter.core.metadata.normalizeEntityComparisonKey
import io.github.playertwo1.bookconverter.core.metadata.normalizeIsbn
import io.github.playertwo1.bookconverter.core.metadata.normalizeSeriesPosition
import io.github.playertwo1.bookconverter.core.metadata.parseMetadataSources
import io.github.playertwo1.bookconverter.core.metadata.parseNamedItems
import io.github.playertwo1.bookconverter.core.metadata.serializeMetadataSources
import io.github.playertwo1.bookconverter.core.metadata.serializeNamedItems
import io.github.playertwo1.bookconverter.core.metadata.trimToBriefSummary

/**
 * Confirmed review. Fields absent from the new result clear prior search values.
 * Characters and locations are replaced with the confirmed lists.
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
    val characters: List<MetadataNamedItem> = emptyList(),
    val locations: List<MetadataNamedItem> = emptyList(),
    val charactersForGlossary: List<MetadataNamedItem> = emptyList(),
    val locationsForGlossary: List<MetadataNamedItem> = emptyList(),
    val bookAuthorApplied: Boolean = MetadataField.AUTHOR in appliedFields,
) {
    fun appliedValue(field: MetadataField, book: Boolean = false): String? {
        if (field == MetadataField.AUTHOR && book) {
            if (!bookAuthorApplied) return null
        } else if (field !in appliedFields) return null
        val raw = values[field]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when (field) {
            MetadataField.ISBN -> normalizeIsbn(raw) ?: raw
            MetadataField.SERIES_POSITION -> normalizeSeriesPosition(raw) ?: raw
            MetadataField.SUMMARY, MetadataField.SERIES_SUMMARY -> trimToBriefSummary(raw, maxChars = 400) ?: raw
            else -> raw
        }
    }

    val allContributingSources: List<MetadataSource>
        get() = (sources.values + characters.flatMap { it.allSources } + locations.flatMap { it.allSources })
            .distinct()
            .sortedBy { it.ordinal }
}

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
    fun consider(field: MetadataField, existingValue: String?, sources: Map<MetadataField, MetadataSource>) {
        val proposed = candidate.value(field)?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val current = existingValue?.trim()?.takeIf { it.isNotEmpty() }
        if (current == null || current.equals(proposed, ignoreCase = true) || field in sources) {
            result += field
        }
    }

    val seriesSources = parseMetadataSources(existingSeries?.metadataSourcesJson)
    consider(MetadataField.SERIES_NAME, currentSeriesName, seriesSources)
    consider(MetadataField.SERIES_SUMMARY, currentSeriesSummary, seriesSources)
    consider(MetadataField.AUTHOR, currentSeriesAuthor, seriesSources)

    if (existingBook != null) {
        val bookSources = parseMetadataSources(existingBook.metadataSourcesJson)
        consider(MetadataField.TITLE, existingBook.title, bookSources)
        consider(MetadataField.ISBN, existingBook.isbn, bookSources)
        consider(MetadataField.PUBLISHER, existingBook.publisher, bookSources)
        consider(MetadataField.PUBLISHED_DATE, existingBook.publishedDate, bookSources)
        consider(MetadataField.LANGUAGE, existingBook.language, bookSources)
        consider(MetadataField.SUMMARY, existingBook.summary, bookSources)
        consider(MetadataField.SERIES_POSITION, existingBook.seriesPosition, bookSources)
    }
    return result
}

fun removedMetadataFields(
    json: String?,
    candidateFields: Set<MetadataField>,
): Set<MetadataField> = parseMetadataSources(json).keys - candidateFields

fun Book.hasLinkedMetadata(): Boolean =
    listOf(metadataSourcesJson, openLibraryKey, wikidataQid, hardcoverId, charactersJson, locationsJson).any { it != null }

fun Series.hasLinkedMetadata(): Boolean =
    listOf(metadataSourcesJson, openLibraryKey, wikidataQid, hardcoverId, charactersJson, locationsJson).any { it != null }

fun Book.unlinkMetadata(): Book = applyMetadataToBook(this, emptyMetadataSelection)

fun Series.unlinkMetadata(now: Long): Series = applyMetadataToSeries(this, emptyMetadataSelection, now)

private val emptyMetadataSelection = BookMetadataReviewSelection(emptyMap(), emptyMap(), emptySet())

fun applyMetadataToBook(
    existing: Book,
    selection: BookMetadataReviewSelection,
    targetSeriesId: Long? = existing.seriesId,
): Book {
    val removed = removedMetadataFields(existing.metadataSourcesJson, selection.values.keys)
    val sources = (parseMetadataSources(existing.metadataSourcesJson) - removed).toMutableMap()
    fun value(field: MetadataField, current: String?): String? =
        selection.resolveValue(field, current, removed, sources, book = true)

    return existing.copy(
        title = value(MetadataField.TITLE, existing.title)
            ?: existing.sourceName.substringBeforeLast('.').ifBlank { "Livro sem título" },
        author = value(MetadataField.AUTHOR, existing.author),
        isbn = value(MetadataField.ISBN, existing.isbn),
        publisher = value(MetadataField.PUBLISHER, existing.publisher),
        publishedDate = value(MetadataField.PUBLISHED_DATE, existing.publishedDate),
        language = value(MetadataField.LANGUAGE, existing.language),
        summary = value(MetadataField.SUMMARY, existing.summary),
        seriesPosition = value(MetadataField.SERIES_POSITION, existing.seriesPosition),
        seriesId = targetSeriesId,
        openLibraryKey = selection.openLibraryKey,
        wikidataQid = selection.wikidataQid,
        hardcoverId = selection.hardcoverId,
        metadataSourcesJson = serializeMetadataSources(sources),
        charactersJson = serializeNamedItems(selection.characters),
        locationsJson = serializeNamedItems(selection.locations),
    )
}

fun applyMetadataToSeries(
    existing: Series,
    selection: BookMetadataReviewSelection,
    now: Long,
): Series {
    val removed = removedMetadataFields(existing.metadataSourcesJson, selection.values.keys)
    val sources = (parseMetadataSources(existing.metadataSourcesJson) - removed).toMutableMap()
    fun value(field: MetadataField, current: String?): String? =
        selection.resolveValue(field, current, removed, sources, book = false)

    return existing.copy(
        name = value(MetadataField.SERIES_NAME, existing.name) ?: "Série sem nome",
        author = value(MetadataField.AUTHOR, existing.author),
        summary = value(MetadataField.SERIES_SUMMARY, existing.summary),
        openLibraryKey = selection.seriesOpenLibraryKey,
        wikidataQid = selection.seriesWikidataQid,
        hardcoverId = selection.seriesHardcoverId,
        metadataSourcesJson = serializeMetadataSources(sources),
        charactersJson = serializeNamedItems(selection.characters),
        locationsJson = serializeNamedItems(selection.locations),
        updatedAt = now,
    )
}

private fun BookMetadataReviewSelection.resolveValue(
    field: MetadataField,
    current: String?,
    removed: Set<MetadataField>,
    provenance: MutableMap<MetadataField, MetadataSource>,
    book: Boolean,
): String? {
    val incoming = appliedValue(field, book)
    if (incoming == null) return current.takeUnless { field in removed }
    if (incoming == current && field !in provenance) return current
    val source = sources[field]
    if (source == null) provenance.remove(field) else provenance[field] = source
    return incoming
}

/**
 * Builds new [GlossaryEntry] rows for characters/locations where the user explicitly enabled
 * "Adicionar ao glossário da série". Uses the original name for both sourceTerm and targetTerm
 * (proper nouns usually remain identical and the user can edit the translation later).
 * Never overwrites terms already present in [existingGlossary].
 */
fun buildNewGlossaryEntries(
    seriesId: Long,
    existingGlossary: List<GlossaryEntry>,
    charactersForGlossary: List<MetadataNamedItem>,
    locationsForGlossary: List<MetadataNamedItem>,
    now: Long,
): List<GlossaryEntry> {
    if (charactersForGlossary.isEmpty() && locationsForGlossary.isEmpty()) return emptyList()
    val existingKeys = existingGlossary
        .map { normalizeEntityComparisonKey(it.sourceTerm) }
        .toMutableSet()
    val out = mutableListOf<GlossaryEntry>()

    fun addItem(item: MetadataNamedItem, kindLabel: String) {
        val cleanName = item.name.trim()
        if (cleanName.isEmpty()) return
        val key = normalizeEntityComparisonKey(cleanName)
        if (key.isEmpty() || !existingKeys.add(key)) return
        val origin = formatNamedItemOrigin(item)
        val note = listOfNotNull(
            "$kindLabel ($origin)",
            item.description?.takeIf { it.isNotBlank() },
        ).joinToString(": ").take(180)
        out += GlossaryEntry(
            seriesId = seriesId,
            sourceTerm = cleanName,
            targetTerm = cleanName,
            notes = note,
            updatedAt = now,
        )
    }

    charactersForGlossary.forEach { addItem(it, "Personagem") }
    locationsForGlossary.forEach { addItem(it, "Local") }
    return out
}

fun formatNamedItemOrigin(item: MetadataNamedItem): String {
    val names = item.allSources.joinToString(", ") { it.displayName }
    return if (item.fromFreeText) "$names, texto livre" else names
}

fun formatSourceAttribution(
    metadataSourcesJson: String?,
    charactersJson: String? = null,
    locationsJson: String? = null,
): String? {
    val fieldSources = parseMetadataSources(metadataSourcesJson).values
    val charSources = parseNamedItems(charactersJson).flatMap { it.allSources }
    val locSources = parseNamedItems(locationsJson).flatMap { it.allSources }
    val sources = (fieldSources + charSources + locSources).distinct()
    if (sources.isEmpty()) return null
    val names = sources.sortedBy { it.ordinal }.joinToString(", ") { it.displayName }
    return "Fontes: $names"
}
