package io.github.playertwo1.bookconverter.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playertwo1.bookconverter.core.provider.ProviderClient
import io.github.playertwo1.bookconverter.core.provider.Translation
import io.github.playertwo1.bookconverter.core.translate.Effort
import io.github.playertwo1.bookconverter.core.translate.Glossary
import io.github.playertwo1.bookconverter.core.translate.GlossaryTerm
import io.github.playertwo1.bookconverter.core.translate.JobOutcome
import io.github.playertwo1.bookconverter.core.translate.JobProgress
import io.github.playertwo1.bookconverter.core.translate.JobStatus as CoreJobStatus
import io.github.playertwo1.bookconverter.core.translate.StoredChunk
import io.github.playertwo1.bookconverter.core.translate.TextExporter
import io.github.playertwo1.bookconverter.core.translate.TranslationEngine
import io.github.playertwo1.bookconverter.core.translate.TranslationStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class SeriesAndExportTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun openDb(name: String): AppDatabase {
        return Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .build()
    }

    private fun deleteDb(name: String) {
        context.deleteDatabase(name)
    }

    private class RoomTranslationStore(
        private val dao: TranslationDao,
    ) : TranslationStore {
        override suspend fun reconcileInProgress(jobId: Long) {
            dao.reconcileInProgress(jobId, System.currentTimeMillis())
        }

        override suspend fun nextPendingChunk(jobId: Long): StoredChunk? {
            val chunk = dao.nextPendingChunk(jobId) ?: return null
            return StoredChunk(
                id = chunk.id,
                chunkIndex = chunk.chunkIndex,
                blockCount = chunk.blockCount,
                sourceText = chunk.sourceText,
                attemptCount = chunk.attemptCount
            )
        }

        override suspend fun markChunkInProgress(chunkId: Long) {
            dao.markChunkInProgress(chunkId, System.currentTimeMillis())
        }

        override suspend fun resetChunkToPending(chunkId: Long) {
            dao.resetChunkToPending(chunkId, System.currentTimeMillis())
        }

        override suspend fun markChunkCompleted(
            jobId: Long,
            chunkId: Long,
            translatedText: String,
            inputTokens: Long,
            outputTokens: Long,
        ) {
            dao.markChunkCompletedAndSyncJob(
                jobId = jobId,
                chunkId = chunkId,
                translatedText = translatedText,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                updatedAt = System.currentTimeMillis()
            )
        }

        override suspend fun markChunkFailed(
            chunkId: Long,
            attemptCount: Int,
            errorMessage: String,
        ) {
            dao.markChunkFailed(
                chunkId = chunkId,
                attemptCount = attemptCount,
                errorMessage = errorMessage,
                updatedAt = System.currentTimeMillis()
            )
        }

        override suspend fun currentProgress(jobId: Long): JobProgress {
            val currentJob = dao.jobById(jobId)
            return if (currentJob != null) {
                JobProgress(
                    done = currentJob.blockDone,
                    total = currentJob.blockTotal,
                    tokensUsed = currentJob.inputTokens + currentJob.outputTokens
                )
            } else {
                JobProgress(0, 0, 0)
            }
        }

        override suspend fun stopSignal(jobId: Long): CoreJobStatus? {
            val currentJob = dao.jobById(jobId) ?: return null
            return when (currentJob.status) {
                JobStatus.PAUSED -> CoreJobStatus.PAUSED
                JobStatus.CANCELLED -> CoreJobStatus.CANCELLED
                else -> null
            }
        }

        override suspend fun onEffortDowngraded(jobId: Long, newEffort: Effort?) {
            dao.updateJobEffort(jobId, newEffort, System.currentTimeMillis())
        }
    }

    private suspend fun insertBook(
        db: AppDatabase,
        title: String = "Livro da Série",
        author: String = "Autor de Teste",
    ): Long {
        return db.books().insert(
            Book(
                title = title,
                author = author,
                language = "en",
                format = "EPUB",
                sha256 = "sha256_${System.nanoTime()}_${(1..100000).random()}",
                privatePath = "/data/book_${System.nanoTime()}.epub",
                sourceName = "book.epub",
                sizeBytes = 2048L,
                importedAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * AC12: 20 termos sintéticos + Room v3 + TranslationEngine.
     * - Cria Series com 20 termos e styleNotes.
     * - Valida round-trip lossless formatDesktopTxt -> parseDesktopTxt com '→' e '->'.
     * - Grava snapshot versionado no TranslationJob.
     * - TranslationEngine divide a tradução em múltiplos chunks e filtra no system prompt
     *   SOMENTE os termos presentes em cada chunk específico.
     * - Verifica que todos os 20 termos foram aplicados nas traduções finais salvas no Room.
     */
    @Test
    fun ac12TwentySyntheticTermsRoundTripPromptFilteringAndApplication() = runBlocking {
        val dbName = "ac12-series-glossary.db"
        deleteDb(dbName)
        val db = openDb(dbName)
        try {
            // 1. Criar os 20 termos sintéticos
            val syntheticTerms = listOf(
                GlossaryTerm("Psychohistory", "Psico-história"),
                GlossaryTerm("Trantor", "Trântor"),
                GlossaryTerm("Seldon Plan", "Plano Seldon"),
                GlossaryTerm("Galactic Empire", "Império Galáctico"),
                GlossaryTerm("Encyclopedia Galactica", "Enciclopédia Galáctica"),
                GlossaryTerm("Hari Seldon", "Hari Seldon"),
                GlossaryTerm("Terminus", "Términus"),
                GlossaryTerm("Time Vault", "Câmara Temporal"),
                GlossaryTerm("Seldon Crisis", "Crise Seldon"),
                GlossaryTerm("Foundation", "Fundação"),
                GlossaryTerm("Kalgan", "Kalgan"),
                GlossaryTerm("The Mule", "O Mulo"),
                GlossaryTerm("Anacreon", "Anacreonte"),
                GlossaryTerm("Korell", "Corel"),
                GlossaryTerm("Merchant Princes", "Príncipes Mercadores"),
                GlossaryTerm("Spaceship", "Nave Estelar"),
                GlossaryTerm("Hyperdrive", "Hiperpropulsão"),
                GlossaryTerm("Star's End", "Fim das Estrelas"),
                GlossaryTerm("Prime Radiant", "Prime Radiante"),
                GlossaryTerm("First Speaker", "Primeiro Orador")
            )
            assertEquals(20, syntheticTerms.size)

            // 2. Round-trip lossless formatDesktopTxt -> parseDesktopTxt com '→'
            val formattedArrow = Glossary.formatDesktopTxt(syntheticTerms)
            val parsedArrow = Glossary.parseDesktopTxt(formattedArrow)
            assertEquals(20, parsedArrow.terms.size)
            assertEquals(0, parsedArrow.conflicts.size)
            assertEquals(0, parsedArrow.skippedLines)
            assertEquals(syntheticTerms, parsedArrow.terms)

            // Round-trip lossless com '->'
            val formattedAscii = formattedArrow.replace("→", "->")
            val parsedAscii = Glossary.parseDesktopTxt(formattedAscii)
            assertEquals(20, parsedAscii.terms.size)
            assertEquals(0, parsedAscii.conflicts.size)
            assertEquals(0, parsedAscii.skippedLines)
            assertEquals(syntheticTerms, parsedAscii.terms)

            // 3. Inserir Series e Glossário no Room v3
            val styleNotes = "Tom solene e épico. Manter terminologia galáctica formal."
            val now = System.currentTimeMillis()
            val seriesId = db.series().insertSeries(
                Series(
                    name = "Ciclo de Fundação",
                    styleNotes = styleNotes,
                    glossaryVersion = 1,
                    createdAt = now,
                    updatedAt = now
                )
            )

            val glossaryEntries = syntheticTerms.map {
                GlossaryEntry(
                    seriesId = seriesId,
                    sourceTerm = it.source,
                    targetTerm = it.target,
                    updatedAt = now
                )
            }
            db.series().importGlossaryEntriesAndBump(seriesId, glossaryEntries, now)

            val seriesFromDb = db.series().byId(seriesId)
            assertNotNull(seriesFromDb)
            assertEquals(2, seriesFromDb?.glossaryVersion)
            val entriesFromDb = db.series().glossaryForSeries(seriesId)
            assertEquals(20, entriesFromDb.size)

            // 4. Associar livro e criar TranslationJob com snapshot versionado
            val bookId = insertBook(db, title = "Fundação e Império", author = "Isaac Asimov")
            db.books().setSeries(bookId, seriesId)

            val snapshot = Glossary.serializeSnapshot(syntheticTerms)
            val chunksData = listOf(
                "⟦1⟧\nPsychohistory guided the Galactic Empire on Trantor through the Seldon Plan recorded in the Encyclopedia Galactica.",
                "⟦1⟧\nHari Seldon traveled to Terminus and built the Time Vault to resolve each Seldon Crisis for the Foundation.",
                "⟦1⟧\nKalgan opposed The Mule across Anacreon and Korell, supported by Merchant Princes.",
                "⟦1⟧\nA Spaceship powered by Hyperdrive reached Star's End, deciphered the Prime Radiant by the First Speaker."
            )

            val chunks = chunksData.mapIndexed { idx, text ->
                TranslationChunk(
                    chunkIndex = idx,
                    spineIndex = 0,
                    startBlockIndex = idx,
                    endBlockIndex = idx,
                    blockCount = 1,
                    sourceText = text,
                    updatedAt = now
                )
            }

            val job = TranslationJob(
                bookId = bookId,
                status = JobStatus.RUNNING,
                provider = TranslationProvider.OPENAI,
                modelId = "gpt-4o",
                blockTotal = 4,
                createdAt = now,
                updatedAt = now,
                seriesId = seriesId,
                glossaryVersion = seriesFromDb?.glossaryVersion,
                glossarySnapshot = snapshot,
                styleNotesSnapshot = seriesFromDb?.styleNotes
            )
            val jobId = db.translations().createJobWithChunks(job, chunks)

            // 5. Executar TranslationEngine com verificação do prompt do sistema
            val receivedSystemPrompts = mutableListOf<String>()
            val fakeClient = object : ProviderClient {
                override fun listModels(): List<String> = listOf("gpt-4o")
                override fun translate(model: String, system: String, text: String, effort: String?): Translation {
                    receivedSystemPrompts.add(system)
                    val translated = when {
                        text.contains("Psychohistory") ->
                            "⟦1⟧\nA Psico-história guiou o Império Galáctico em Trântor através do Plano Seldon registrado na Enciclopédia Galáctica."
                        text.contains("Hari Seldon") ->
                            "⟦1⟧\nHari Seldon viajou para Términus e construiu a Câmara Temporal para resolver cada Crise Seldon para a Fundação."
                        text.contains("Kalgan") ->
                            "⟦1⟧\nKalgan se opôs a O Mulo através de Anacreonte e Corel, apoiada pelos Príncipes Mercadores."
                        text.contains("Spaceship") ->
                            "⟦1⟧\nUma Nave Estelar movida por Hiperpropulsão alcançou o Fim das Estrelas, decifrado no Prime Radiante pelo Primeiro Orador."
                        else -> "⟦1⟧\nTexto traduzido."
                    }
                    return Translation(translated, model, 20L, 30L)
                }
            }

            val store = RoomTranslationStore(db.translations())
            val engine = TranslationEngine(delayFn = {})
            val glossaryTerms = Glossary.parseSnapshot(job.glossarySnapshot)
            assertEquals(20, glossaryTerms.size)

            val outcome = engine.runJob(
                jobId = jobId,
                client = fakeClient,
                model = "gpt-4o",
                store = store,
                glossary = glossaryTerms,
                styleNotes = job.styleNotesSnapshot
            )

            assertEquals(JobOutcome.Completed, outcome)
            assertEquals(4, receivedSystemPrompts.size)

            // 6. Verificar que SOMENTE os termos de cada chunk foram enviados no system prompt
            // Chunk 0: termos 0..4
            val prompt0 = receivedSystemPrompts[0]
            assertTrue(prompt0.contains(styleNotes))
            for (i in 0..4) {
                assertTrue("Prompt 0 deve conter '${syntheticTerms[i].source}'", prompt0.contains(syntheticTerms[i].source))
            }
            for (i in 5..19) {
                assertTrue("Prompt 0 NÃO deve conter '${syntheticTerms[i].source}'", !prompt0.contains(syntheticTerms[i].source))
            }

            // Chunk 1: termos 5..9
            val prompt1 = receivedSystemPrompts[1]
            for (i in 5..9) {
                assertTrue("Prompt 1 deve conter '${syntheticTerms[i].source}'", prompt1.contains(syntheticTerms[i].source))
            }
            for (i in 0..4) {
                assertTrue("Prompt 1 NÃO deve conter '${syntheticTerms[i].source}'", !prompt1.contains(syntheticTerms[i].source))
            }
            for (i in 10..19) {
                assertTrue("Prompt 1 NÃO deve conter '${syntheticTerms[i].source}'", !prompt1.contains(syntheticTerms[i].source))
            }

            // Chunk 2: termos 10..14
            val prompt2 = receivedSystemPrompts[2]
            for (i in 10..14) {
                assertTrue("Prompt 2 deve conter '${syntheticTerms[i].source}'", prompt2.contains(syntheticTerms[i].source))
            }
            for (i in 0..9) {
                assertTrue("Prompt 2 NÃO deve conter '${syntheticTerms[i].source}'", !prompt2.contains(syntheticTerms[i].source))
            }
            for (i in 15..19) {
                assertTrue("Prompt 2 NÃO deve conter '${syntheticTerms[i].source}'", !prompt2.contains(syntheticTerms[i].source))
            }

            // Chunk 3: termos 15..19
            val prompt3 = receivedSystemPrompts[3]
            for (i in 15..19) {
                assertTrue("Prompt 3 deve conter '${syntheticTerms[i].source}'", prompt3.contains(syntheticTerms[i].source))
            }
            for (i in 0..14) {
                assertTrue("Prompt 3 NÃO deve conter '${syntheticTerms[i].source}'", !prompt3.contains(syntheticTerms[i].source))
            }

            // 7. Verificar que todos os 20 termos foram aplicados nas traduções salvas no Room
            val completedChunks = db.translations().chunksForJob(jobId)
            assertEquals(4, completedChunks.size)
            assertTrue("Todos os 4 chunks devem estar COMPLETED", completedChunks.all { it.status == ChunkStatus.COMPLETED })

            val fullTranslatedText = completedChunks.joinToString("\n") { it.translatedText ?: "" }
            for (term in syntheticTerms) {
                assertTrue(
                    "Termo traduzido '${term.target}' deve estar no texto completo",
                    fullTranslatedText.contains(term.target)
                )
            }
        } finally {
            db.close()
            deleteDb(dbName)
        }
    }

    /**
     * Valida que excluir uma série remove série e termos em cascata,
     * mas preserva os livros da biblioteca intactos com seriesId == null.
     */
    @Test
    fun deleteSeriesPreservesBooksAndRemovesGlossaryCascade() = runBlocking {
        val dbName = "delete-series-test.db"
        deleteDb(dbName)
        val db = openDb(dbName)
        try {
            val seriesId = db.series().insertSeries(
                Series(
                    name = "Trilogia Cósmica",
                    styleNotes = "Estilo clássico",
                    glossaryVersion = 1,
                    createdAt = 1000L,
                    updatedAt = 1000L
                )
            )

            val book1 = insertBook(db, title = "Além do Planeta Silencioso", author = "C.S. Lewis")
            val book2 = insertBook(db, title = "Perelandra", author = "C.S. Lewis")

            db.books().setSeries(book1, seriesId)
            db.books().setSeries(book2, seriesId)

            assertEquals(seriesId, db.books().byId(book1)?.seriesId)
            assertEquals(seriesId, db.books().byId(book2)?.seriesId)

            db.series().saveGlossaryEntryAndBump(
                GlossaryEntry(
                    seriesId = seriesId,
                    sourceTerm = "Oyarsa",
                    targetTerm = "Oyarsa",
                    updatedAt = 1000L
                ),
                now = 1000L
            )
            assertEquals(1, db.series().glossaryForSeries(seriesId).size)

            // Exclui série através do método com transação do DAO
            db.series().deleteSeries(seriesId)

            // Série e termos foram removidos
            assertNull(db.series().byId(seriesId))
            assertTrue(db.series().glossaryForSeries(seriesId).isEmpty())

            // Livros continuam na biblioteca intactos com seriesId == null
            val preservedBook1 = db.books().byId(book1)
            val preservedBook2 = db.books().byId(book2)

            assertNotNull(preservedBook1)
            assertNotNull(preservedBook2)
            assertNull(preservedBook1?.seriesId)
            assertNull(preservedBook2?.seriesId)
            assertEquals("Além do Planeta Silencioso", preservedBook1?.title)
            assertEquals("Perelandra", preservedBook2?.title)
        } finally {
            db.close()
            deleteDb(dbName)
        }
    }

    /**
     * Valida exportadores nos 4 formatos:
     * - TextExporter.toTxt: UTF-8 pt-BR preservado
     * - TextExporter.toMarkdown: UTF-8 pt-BR e título preservados
     * - TextExporter.toDocx: ZIP válido com XMLs bem formatados e caracteres escapados
     * - PdfExporter.toPdfBytes: PDF válido (%PDF-) com android.graphics.pdf.PdfDocument real
     *   para texto curto, texto vazio e texto longo multi-página (>120 linhas).
     */
    @Test
    fun exportInFourFormatsTxtDocxPdfMarkdown() {
        val ptBrSample = "Atenção: áéíóú, ç, ã õ, pontuação « » — e símbolos & < > \" '."
        val title = "Tradução de Amostra"

        // 1. TextExporter.toTxt
        val txtBytes = TextExporter.toTxt(ptBrSample)
        assertEquals(ptBrSample, String(txtBytes, Charsets.UTF_8))

        // 2. TextExporter.toMarkdown
        val mdBytesWithTitle = TextExporter.toMarkdown(ptBrSample, title)
        assertEquals("# $title\n\n$ptBrSample", String(mdBytesWithTitle, Charsets.UTF_8))

        val mdBytesNoTitle = TextExporter.toMarkdown(ptBrSample, null)
        assertEquals(ptBrSample, String(mdBytesNoTitle, Charsets.UTF_8))

        // 3. TextExporter.toDocx
        val docxBytes = TextExporter.toDocx(ptBrSample, title)
        assertTrue("DOCX bytes devem ser gerados", docxBytes.isNotEmpty())

        val zipEntries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(docxBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val content = zis.bufferedReader(Charsets.UTF_8).readText()
                zipEntries[entry.name] = content
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        assertTrue("Deve conter [Content_Types].xml", zipEntries.containsKey("[Content_Types].xml"))
        assertTrue("Deve conter _rels/.rels", zipEntries.containsKey("_rels/.rels"))
        assertTrue("Deve conter word/_rels/document.xml.rels", zipEntries.containsKey("word/_rels/document.xml.rels"))
        assertTrue("Deve conter word/document.xml", zipEntries.containsKey("word/document.xml"))

        val docXml = zipEntries["word/document.xml"]!!
        assertTrue("Deve conter título com XML preservado", docXml.contains("Tradução de Amostra"))
        assertTrue("Deve conter &amp;", docXml.contains("&amp;"))
        assertTrue("Deve conter &lt;", docXml.contains("&lt;"))
        assertTrue("Deve conter &gt;", docXml.contains("&gt;"))
        assertTrue("Deve conter caracteres acentuados pt-BR intactos", docXml.contains("áéíóú"))

        // 4. PdfExporter.toPdfBytes (no Android runtime real)
        // a) Texto curto
        val shortPdf = PdfExporter.toPdfBytes(ptBrSample, title)
        assertTrue("PDF curto deve ser gerado", shortPdf.isNotEmpty())
        val shortHeader = String(shortPdf.copyOfRange(0, 5), Charsets.US_ASCII)
        assertEquals("%PDF-", shortHeader)

        // b) Texto vazio
        val emptyPdf = PdfExporter.toPdfBytes("", null)
        assertTrue("PDF vazio deve ser gerado", emptyPdf.isNotEmpty())
        val emptyHeader = String(emptyPdf.copyOfRange(0, 5), Charsets.US_ASCII)
        assertEquals("%PDF-", emptyHeader)

        // c) Texto longo multi-página (>120 linhas)
        val longText = (1..150).joinToString("\n") { lineNum ->
            "Capítulo 1, Linha $lineNum: Texto descritivo para forçar quebra de página automática no gerador nativo de PDF."
        }
        val longPdf = PdfExporter.toPdfBytes(longText, "Documento de Grande Volume")
        assertTrue("PDF longo deve ter tamanho substancial", longPdf.size > 3000)
        val longHeader = String(longPdf.copyOfRange(0, 5), Charsets.US_ASCII)
        assertEquals("%PDF-", longHeader)
    }
}
