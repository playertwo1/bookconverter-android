package io.github.playertwo1.bookconverter.core.epub

import io.github.playertwo1.bookconverter.core.translate.Prompt
import java.io.File

/** Report of structural validation performed on an exported EPUB (AC13). */
data class ExportValidationReport(
    val valid: Boolean,
    val spineCountOriginal: Int,
    val spineCountExported: Int,
    val totalBlocks: Int,
    val translatedBlocks: Int,
    val untranslatedBlocks: Int,
    val issues: List<String>,
)

/** Minimal input model representing a translation chunk for EPUB block mapping. */
data class ChunkTranslationInput(
    val spineIndex: Int,
    val startBlockIndex: Int,
    val endBlockIndex: Int,
    val blockCount: Int,
    val translatedText: String?,
)

typealias EpubBook = Epub

object EpubExporter {

    /**
     * Splits a chunk's translated text into individual block strings.
     * Uses [Prompt.decode] for standard marker-based text (⟦1⟧...⟦n⟧).
     * If [blockCount] is 1 and the manually-edited text does not contain marker ⟦1⟧,
     * accepts the trimmed text directly.
     * Returns null if [translatedText] is null/blank, [blockCount] <= 0, or decoding fails.
     */
    fun decodeChunkBlocks(translatedText: String?, blockCount: Int): List<String>? {
        if (translatedText.isNullOrBlank() || blockCount <= 0) return null
        if (blockCount == 1 && !translatedText.contains("⟦1⟧")) {
            return listOf(translatedText.trim())
        }
        return try {
            Prompt.decode(translatedText, blockCount)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Re-encodes a list of edited blocks into the marker-based format (⟦1⟧...\n\n⟦2⟧...)
     * compatible with [Prompt.decode].
     */
    fun encodeEditedBlocks(blocks: List<String>): String {
        if (blocks.isEmpty()) return ""
        return blocks.mapIndexed { index, block ->
            "⟦${index + 1}⟧\n$block"
        }.joinToString("\n\n")
    }

    /**
     * Converts a list of [ChunkTranslationInput]s into a map of blockId (`spineIndex:blockIndex`)
     * to translated inner HTML. Blocks belonging to pending or unparsable chunks are omitted,
     * so they naturally fall back to their original text during export.
     */
    fun buildBlockMapFromChunks(
        originalBook: EpubBook,
        chunkTranslations: List<ChunkTranslationInput>,
    ): Map<String, String> {
        val validBlockIds = originalBook.blocks().map { it.id }.toSet()
        val result = LinkedHashMap<String, String>()
        for (chunk in chunkTranslations) {
            val blocks = decodeChunkBlocks(chunk.translatedText, chunk.blockCount) ?: continue
            if (blocks.size == chunk.blockCount) {
                for (offset in 0 until chunk.blockCount) {
                    val blockId = "${chunk.spineIndex}:${chunk.startBlockIndex + offset}"
                    if (blockId in validBlockIds) {
                        result[blockId] = blocks[offset]
                    }
                }
            }
        }
        return result
    }

    fun buildBlockMapFromChunks(
        originalFile: File,
        chunkTranslations: List<ChunkTranslationInput>,
    ): Map<String, String> {
        val book = originalFile.inputStream().buffered().use { Epub.read(it) }
        return buildBlockMapFromChunks(book, chunkTranslations)
    }

    /**
     * Exports a pt-BR EPUB by replacing blocks in [originalFile] with translations from
     * [translatedByBlockId], writing the result to [outputFile] and running structural
     * validation (AC13) to ensure 100% integrity.
     *
     * Rules:
     * - [originalFile] is NEVER modified.
     * - Untranslated blocks fall back to original HTML.
     * - Images, CSS, OPF, NCX/NAV, and uncompressed first-entry mimetype are preserved.
     * - Structural validation verifies spine count, block count, order, and non-empty chapters.
     */
    fun exportPtBr(
        originalFile: File,
        outputFile: File,
        translatedByBlockId: Map<String, String>,
    ): ExportValidationReport {
        require(originalFile.canonicalPath != outputFile.canonicalPath) {
            "originalFile e outputFile não podem ser o mesmo arquivo: originalFile não deve ser modificado"
        }
        require(originalFile.exists()) {
            "originalFile não existe: ${originalFile.absolutePath}"
        }

        val originalBook = originalFile.inputStream().buffered().use { Epub.read(it) }
        val originalBlocks = originalBook.blocks()
        val totalBlocks = originalBlocks.size
        val spineCountOriginal = originalBook.spine.size

        val translatedBlocks = originalBlocks.count { block ->
            translatedByBlockId[block.id]?.isNotBlank() == true
        }
        val untranslatedBlocks = totalBlocks - translatedBlocks

        val rewrittenBytes = originalBook.rewrite { block ->
            translatedByBlockId[block.id]?.takeIf { it.isNotBlank() }
        }

        outputFile.parentFile?.mkdirs()
        outputFile.writeBytes(rewrittenBytes)

        val exportedBook = try {
            outputFile.inputStream().buffered().use { Epub.read(it) }
        } catch (e: Exception) {
            return ExportValidationReport(
                valid = false,
                spineCountOriginal = spineCountOriginal,
                spineCountExported = 0,
                totalBlocks = totalBlocks,
                translatedBlocks = translatedBlocks,
                untranslatedBlocks = untranslatedBlocks,
                issues = listOf("Falha ao ler EPUB exportado: ${e.message}"),
            )
        }

        return validate(
            originalBook = originalBook,
            exportedBook = exportedBook,
            translatedBlocks = translatedBlocks,
            untranslatedBlocks = untranslatedBlocks,
        )
    }

    /**
     * Validates structural equivalence between original and exported books.
     */
    fun validate(
        originalBook: Epub,
        exportedBook: Epub,
        translatedBlocks: Int = -1,
        untranslatedBlocks: Int = -1,
    ): ExportValidationReport {
        val originalBlocks = originalBook.blocks()
        val exportedBlocks = exportedBook.blocks()
        val spineCountOriginal = originalBook.spine.size
        val spineCountExported = exportedBook.spine.size
        val totalBlocks = originalBlocks.size

        val issues = mutableListOf<String>()

        if (spineCountExported != spineCountOriginal) {
            issues.add("Contagem de capítulos divergente: original=$spineCountOriginal, exportado=$spineCountExported")
        }

        if (exportedBook.spine != originalBook.spine) {
            issues.add("Spine divergente do original: caminhos ou ordem alterados")
        }

        if (exportedBlocks.size != originalBlocks.size) {
            issues.add("Contagem total de blocos divergente: original=$totalBlocks, exportado=${exportedBlocks.size}")
        }

        for (i in originalBook.spine.indices) {
            val origCount = originalBlocks.count { it.spineIndex == i }
            val expCount = exportedBlocks.count { it.spineIndex == i }
            val path = originalBook.spine.getOrNull(i).orEmpty()
            if (origCount > 0 && expCount == 0) {
                issues.add("Capítulo $i ($path) ficou inesperadamente vazio após exportação")
            } else if (origCount != expCount) {
                issues.add("Capítulo $i ($path) divergiu na contagem de blocos: original=$origCount, exportado=$expCount")
            }
        }

        val actualTranslated = if (translatedBlocks >= 0) translatedBlocks else exportedBlocks.size
        val actualUntranslated = if (untranslatedBlocks >= 0) untranslatedBlocks else (totalBlocks - actualTranslated)

        return ExportValidationReport(
            valid = issues.isEmpty(),
            spineCountOriginal = spineCountOriginal,
            spineCountExported = spineCountExported,
            totalBlocks = totalBlocks,
            translatedBlocks = actualTranslated,
            untranslatedBlocks = actualUntranslated,
            issues = issues,
        )
    }
}
