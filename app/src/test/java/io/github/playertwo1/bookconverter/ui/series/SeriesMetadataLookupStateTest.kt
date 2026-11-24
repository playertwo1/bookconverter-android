package io.github.playertwo1.bookconverter.ui.series

import io.github.playertwo1.bookconverter.core.metadata.AggregatedBookCandidate
import io.github.playertwo1.bookconverter.core.metadata.AggregatedSearchResponse
import io.github.playertwo1.bookconverter.core.metadata.FieldOption
import io.github.playertwo1.bookconverter.core.metadata.FieldProposal
import io.github.playertwo1.bookconverter.core.metadata.MetadataField
import io.github.playertwo1.bookconverter.core.metadata.MetadataSource
import io.github.playertwo1.bookconverter.core.metadata.SourceSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesMetadataLookupStateTest {

    @Test
    fun partialSourceFailureKeepsCandidatesAndShowsWarningWithoutBlockingError() {
        val candidate = AggregatedBookCandidate(
            id = "cand-0",
            sources = setOf(MetadataSource.OPEN_LIBRARY),
            fields = mapOf(
                MetadataField.TITLE to FieldProposal(
                    MetadataField.TITLE,
                    "Dune",
                    MetadataSource.OPEN_LIBRARY,
                    listOf(FieldOption("Dune", MetadataSource.OPEN_LIBRARY)),
                )
            ),
        )
        val resp = AggregatedSearchResponse(
            candidates = listOf(candidate),
            sourceResults = listOf(
                SourceSearchResult.Success(MetadataSource.OPEN_LIBRARY, emptyList()),
                SourceSearchResult.Failed(MetadataSource.WIKIDATA, "Tempo esgotado ao consultar Wikidata."),
                SourceSearchResult.Skipped(MetadataSource.HARDCOVER, "Token da Hardcover não configurado."),
            ),
        )

        val ui = buildMetadataLookupUiState(resp)
        assertFalse(ui.isLoading)
        assertTrue(ui.hasSearched)
        assertEquals(1, ui.candidates.size)
        assertNull(ui.error)
        assertEquals(1, ui.warnings.size)
        assertTrue(ui.warnings.single().contains("Wikidata"))
    }

    @Test
    fun allActiveSourcesFailedProducesClearBlockingError() {
        val resp = AggregatedSearchResponse(
            candidates = emptyList(),
            sourceResults = listOf(
                SourceSearchResult.Failed(MetadataSource.OPEN_LIBRARY, "Sem conexão com Open Library."),
                SourceSearchResult.Failed(MetadataSource.WIKIDATA, "Sem conexão com Wikidata."),
                SourceSearchResult.Skipped(MetadataSource.HARDCOVER, "Token da Hardcover não configurado."),
            ),
        )

        val ui = buildMetadataLookupUiState(resp)
        assertFalse(ui.isLoading)
        assertTrue(ui.hasSearched)
        assertTrue(ui.candidates.isEmpty())
        assertNotNull(ui.error)
        assertTrue(ui.error!!.contains("Open Library"))
        assertTrue(ui.error!!.contains("Wikidata"))
    }
}
