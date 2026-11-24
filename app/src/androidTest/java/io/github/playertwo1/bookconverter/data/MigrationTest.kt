// Each schema bump adds a test that runs helper.runMigrationsAndValidate(name, N, true, MIGRATION_X_N)
package io.github.playertwo1.bookconverter.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun version1CreatesAndOpens() {
        helper.createDatabase("migration-test-v1", 1).apply {
            execSQL(
                "INSERT INTO Book (title, author, language, format, sha256, privatePath, sourceName, sizeBytes, importedAt) " +
                    "VALUES ('t', NULL, 'en', 'EPUB', 'abc', '/x', 'x.epub', 1, 0)"
            )
            close()
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, AppDatabase::class.java, "migration-test-v1")
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
            )
            .build()
        runBlocking {
            assertEquals(1, db.books().all().first().size)
        }
        db.close()
    }

    @Test
    fun migration1To2PreservesBooksAndEnablesTranslations() {
        val dbName = "migration-test-1-2"
        helper.createDatabase(dbName, 1).apply {
            execSQL(
                "INSERT INTO Book (id, title, author, language, format, sha256, privatePath, sourceName, sizeBytes, importedAt) " +
                    "VALUES (1, 'Dom Casmurro', 'Machado de Assis', 'pt', 'EPUB', 'sha_casmurro', '/data/casmurro.epub', 'casmurro.epub', 1024, 1000)"
            )
            close()
        }

        // Run migration and validate schema against 2.json
        helper.runMigrationsAndValidate(dbName, 2, true, AppDatabase.MIGRATION_1_2)

        // Continue migration to 3, 4, 5 and 6 so Room database can open with current schema
        helper.runMigrationsAndValidate(dbName, 3, true, AppDatabase.MIGRATION_2_3)
        helper.runMigrationsAndValidate(dbName, 4, true, AppDatabase.MIGRATION_3_4)
        helper.runMigrationsAndValidate(dbName, 5, true, AppDatabase.MIGRATION_4_5)
        helper.runMigrationsAndValidate(dbName, 6, true, AppDatabase.MIGRATION_5_6)
        helper.runMigrationsAndValidate(dbName, 7, true, AppDatabase.MIGRATION_6_7)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
            )
            .build()

        runBlocking {
            // Verify existing book is preserved
            val books = db.books().all().first()
            assertEquals(1, books.size)
            assertEquals("Dom Casmurro", books[0].title)

            // Test TranslationDao operations
            val job = TranslationJob(
                bookId = 1,
                status = JobStatus.QUEUED,
                provider = TranslationProvider.OPENAI,
                modelId = "gpt-4o",
                blockTotal = 2,
                createdAt = 1000L,
                updatedAt = 1000L,
            )
            val chunks = listOf(
                TranslationChunk(
                    chunkIndex = 0,
                    spineIndex = 0,
                    startBlockIndex = 0,
                    endBlockIndex = 1,
                    blockCount = 2,
                    sourceText = "⟦1⟧Capítulo 1⟦2⟧Texto",
                    updatedAt = 1000L,
                ),
                TranslationChunk(
                    chunkIndex = 1,
                    spineIndex = 0,
                    startBlockIndex = 2,
                    endBlockIndex = 3,
                    blockCount = 2,
                    sourceText = "⟦1⟧Capítulo 2⟦2⟧Texto",
                    updatedAt = 1000L,
                )
            )

            val jobId = db.translations().createJobWithChunks(job, chunks)
            assertEquals(1L, jobId)

            val loadedJob = db.translations().jobById(jobId)
            assertNotNull(loadedJob)
            assertEquals(JobStatus.QUEUED, loadedJob?.status)
            assertEquals(2, loadedJob?.blockTotal)
            assertEquals(0, loadedJob?.blockDone)

            val loadedChunks = db.translations().chunksForJob(jobId)
            assertEquals(2, loadedChunks.size)
            assertEquals(jobId, loadedChunks[0].jobId)
            assertEquals(jobId, loadedChunks[1].jobId)

            // Next pending chunk
            val nextChunk = db.translations().nextPendingChunk(jobId)
            assertEquals(0, nextChunk?.chunkIndex)

            // Mark chunk 0 in progress, then completed and sync job
            db.translations().markChunkInProgress(loadedChunks[0].id, 2000L)
            db.translations().markChunkCompletedAndSyncJob(
                jobId = jobId,
                chunkId = loadedChunks[0].id,
                translatedText = "⟦1⟧Chapter 1⟦2⟧Text",
                inputTokens = 150L,
                outputTokens = 200L,
                updatedAt = 2000L,
            )

            // Check invariant: blockDone == countCompletedChunks
            val completedCount = db.translations().countCompletedChunks(jobId)
            val updatedJob = db.translations().jobById(jobId)
            assertEquals(1, completedCount)
            assertEquals(completedCount, updatedJob?.blockDone)
            assertEquals(150L, updatedJob?.inputTokens)
            assertEquals(200L, updatedJob?.outputTokens)

            // Verify next pending chunk is chunk 1
            val nextPending = db.translations().nextPendingChunk(jobId)
            assertEquals(1, nextPending?.chunkIndex)

            // Verify observeAllJobsWithBook returns JobWithBook correctly
            val jobsWithBook = db.translations().observeAllJobsWithBook().first()
            assertEquals(1, jobsWithBook.size)
            assertEquals("Dom Casmurro", jobsWithBook[0].bookTitle)
            assertEquals("Machado de Assis", jobsWithBook[0].bookAuthor)
            assertEquals(1, jobsWithBook[0].blockDone)
        }
        db.close()
    }

    @Test
    fun migration2To3PreservesBooksAndJobs() {
        val dbName = "migration-test-2-3"
        helper.createDatabase(dbName, 2).apply {
            execSQL(
                "INSERT INTO Book (id, title, author, language, format, sha256, privatePath, sourceName, sizeBytes, importedAt) " +
                    "VALUES (1, 'Memórias Póstumas', 'Machado de Assis', 'pt', 'EPUB', 'sha_memorias', '/data/memorias.epub', 'memorias.epub', 2048, 1000)"
            )
            execSQL(
                "INSERT INTO TranslationJob (id, bookId, status, provider, modelId, effort, tokenBudget, blockTotal, blockDone, inputTokens, outputTokens, failureReason, createdAt, updatedAt) " +
                    "VALUES (1, 1, 'QUEUED', 'OPENAI', 'gpt-4o', NULL, NULL, 5, 0, 0, 0, NULL, 1000, 1000)"
            )
            close()
        }

        // Validate migration to version 3 against 3.json
        helper.runMigrationsAndValidate(dbName, 3, true, AppDatabase.MIGRATION_2_3)

        // Continue migration to 4, 5 and 6 so Room database can open with current schema
        helper.runMigrationsAndValidate(dbName, 4, true, AppDatabase.MIGRATION_3_4)
        helper.runMigrationsAndValidate(dbName, 5, true, AppDatabase.MIGRATION_4_5)
        helper.runMigrationsAndValidate(dbName, 6, true, AppDatabase.MIGRATION_5_6)
        helper.runMigrationsAndValidate(dbName, 7, true, AppDatabase.MIGRATION_6_7)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
            )
            .build()

        runBlocking {
            val books = db.books().all().first()
            assertEquals(1, books.size)
            assertEquals("Memórias Póstumas", books[0].title)
            assertEquals(null, books[0].seriesId)

            val job = db.translations().jobById(1)
            assertNotNull(job)
            assertEquals("QUEUED", job?.status)
            assertEquals(null, job?.seriesId)
            assertEquals(null, job?.glossaryVersion)
            assertEquals(null, job?.glossarySnapshot)
            assertEquals(null, job?.styleNotesSnapshot)

            // Test SeriesDao and Book series association
            val seriesId = db.series().insertSeries(
                Series(
                    name = "Trilogia Realista",
                    styleNotes = "Tom irônico e formal",
                    glossaryVersion = 1,
                    createdAt = 1000L,
                    updatedAt = 1000L,
                )
            )
            assertEquals(1L, seriesId)

            // Associate book with series
            db.books().setSeries(1, seriesId)
            val updatedBook = db.books().byId(1)
            assertEquals(seriesId, updatedBook?.seriesId)

            val booksInSeries = db.books().booksInSeries(seriesId)
            assertEquals(1, booksInSeries.size)
            assertEquals(1L, booksInSeries[0].id)

            // Insert glossary entry
            val termId = db.series().saveGlossaryEntryAndBump(
                GlossaryEntry(
                    seriesId = seriesId,
                    sourceTerm = "humanitas",
                    targetTerm = "Humanitas",
                    notes = "Conceito filosófico de Quincas Borba",
                    updatedAt = 2000L,
                ),
                now = 2000L
            )
            val glossary = db.series().glossaryForSeries(seriesId)
            assertEquals(1, glossary.size)
            assertEquals("humanitas", glossary[0].sourceTerm)

            val updatedSeries = db.series().byId(seriesId)
            assertEquals(2, updatedSeries?.glossaryVersion)
            assertEquals(2000L, updatedSeries?.updatedAt)

            // Verify SeriesSummary
            val summaries = db.series().observeAllSummaries().first()
            assertEquals(1, summaries.size)
            assertEquals("Trilogia Realista", summaries[0].series.name)
            assertEquals(1, summaries[0].bookCount)
            assertEquals(1, summaries[0].termCount)

            // Delete series preserves book (seriesId set to null)
            db.series().deleteSeries(seriesId)
            val bookAfterSeriesDelete = db.books().byId(1)
            assertNotNull(bookAfterSeriesDelete)
            assertEquals(null, bookAfterSeriesDelete?.seriesId)

            val seriesAfterDelete = db.series().byId(seriesId)
            assertEquals(null, seriesAfterDelete)
            val glossaryAfterDelete = db.series().glossaryForSeries(seriesId)
            assertEquals(0, glossaryAfterDelete.size)
        }
        db.close()
    }

    @Test
    fun migration1To3PreservesData() {
        val dbName = "migration-test-1-3"
        helper.createDatabase(dbName, 1).apply {
            execSQL(
                "INSERT INTO Book (id, title, author, language, format, sha256, privatePath, sourceName, sizeBytes, importedAt) " +
                    "VALUES (1, 'Quincas Borba', 'Machado de Assis', 'pt', 'EPUB', 'sha_borba', '/data/borba.epub', 'borba.epub', 1024, 1000)"
            )
            close()
        }

        helper.runMigrationsAndValidate(dbName, 3, true, AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
        helper.runMigrationsAndValidate(dbName, 4, true, AppDatabase.MIGRATION_3_4)
        helper.runMigrationsAndValidate(dbName, 5, true, AppDatabase.MIGRATION_4_5)
        helper.runMigrationsAndValidate(dbName, 6, true, AppDatabase.MIGRATION_5_6)
        helper.runMigrationsAndValidate(dbName, 7, true, AppDatabase.MIGRATION_6_7)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
            )
            .build()

        runBlocking {
            val books = db.books().all().first()
            assertEquals(1, books.size)
            assertEquals("Quincas Borba", books[0].title)
            assertEquals(null, books[0].seriesId)
        }
        db.close()
    }

    @Test
    fun migration3To4PreservesDataAndEnablesMemory() {
        val dbName = "migration-test-3-4"
        helper.createDatabase(dbName, 3).apply {
            execSQL(
                "INSERT INTO Book (id, title, author, language, format, sha256, privatePath, sourceName, sizeBytes, importedAt, seriesId) " +
                    "VALUES (1, 'Esaú e Jacó', 'Machado de Assis', 'pt', 'EPUB', 'sha_esau', '/data/esau.epub', 'esau.epub', 1024, 1000, 1)"
            )
            execSQL(
                "INSERT INTO Series (id, name, styleNotes, glossaryVersion, createdAt, updatedAt) " +
                    "VALUES (1, 'Fase Madura', 'Estilo realista', 1, 1000, 1000)"
            )
            execSQL(
                "INSERT INTO GlossaryEntry (id, seriesId, sourceTerm, targetTerm, notes, updatedAt) " +
                    "VALUES (1, 1, 'Natividade', 'Natividade', 'Mãe dos gêmeos', 1000)"
            )
            execSQL(
                "INSERT INTO TranslationJob (id, bookId, status, provider, modelId, effort, tokenBudget, blockTotal, blockDone, inputTokens, outputTokens, failureReason, createdAt, updatedAt, seriesId, glossaryVersion, glossarySnapshot, styleNotesSnapshot) " +
                    "VALUES (1, 1, 'QUEUED', 'OPENAI', 'gpt-4o', NULL, NULL, 5, 0, 0, 0, NULL, 1000, 1000, 1, 1, NULL, NULL)"
            )
            close()
        }

        // Validate migration to version 4 against 4.json
        helper.runMigrationsAndValidate(dbName, 4, true, AppDatabase.MIGRATION_3_4)

        // Continue migration to 5 and 6 so Room database can open with current schema
        helper.runMigrationsAndValidate(dbName, 5, true, AppDatabase.MIGRATION_4_5)
        helper.runMigrationsAndValidate(dbName, 6, true, AppDatabase.MIGRATION_5_6)
        helper.runMigrationsAndValidate(dbName, 7, true, AppDatabase.MIGRATION_6_7)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
            )
            .build()

        runBlocking {
            // Verify existing v3 data preserved
            val books = db.books().all().first()
            assertEquals(1, books.size)
            assertEquals("Esaú e Jacó", books[0].title)
            assertEquals(1L, books[0].seriesId)

            val series = db.series().byId(1)
            assertNotNull(series)
            assertEquals("Fase Madura", series?.name)

            val glossary = db.series().glossaryForSeries(1)
            assertEquals(1, glossary.size)
            assertEquals("Natividade", glossary[0].sourceTerm)

            val job = db.translations().jobById(1)
            assertNotNull(job)
            assertEquals(1L, job?.seriesId)

            // Insert memory proposals
            val entries = listOf(
                SeriesMemoryEntry(
                    seriesId = 1,
                    category = MemoryCategory.TERM,
                    sourceTerm = "Pedro",
                    targetTerm = "Peter",
                    notes = "Irmão gêmeo",
                    excerpt = "Pedro e Paulo brigavam desde o ventre.",
                    sourceBookTitle = "Esaú e Jacó",
                    status = MemoryStatus.PENDING,
                    createdAt = 2000L,
                    updatedAt = 2000L,
                ),
                SeriesMemoryEntry(
                    seriesId = 1,
                    category = MemoryCategory.CHARACTER,
                    sourceTerm = "Paulo",
                    targetTerm = "Paul",
                    notes = "Irmão gêmeo",
                    excerpt = "Paulo pensava o oposto de Pedro.",
                    sourceBookTitle = "Esaú e Jacó",
                    status = MemoryStatus.PENDING,
                    createdAt = 2000L,
                    updatedAt = 2000L,
                ),
                SeriesMemoryEntry(
                    seriesId = 1,
                    category = MemoryCategory.PAIR,
                    sourceTerm = "gêmeos",
                    targetTerm = "twins",
                    notes = null,
                    excerpt = "Eram os dois gêmeos.",
                    sourceBookTitle = "Esaú e Jacó",
                    status = MemoryStatus.PENDING,
                    createdAt = 2000L,
                    updatedAt = 2000L,
                )
            )
            db.series().insertMemoryEntries(entries)

            // Test observeMemoryEntries
            val memoryList = db.series().observeMemoryEntries(1).first()
            assertEquals(3, memoryList.size)

            val termEntry = memoryList.first { it.category == MemoryCategory.TERM }
            val charEntry = memoryList.first { it.category == MemoryCategory.CHARACTER }
            val pairEntry = memoryList.first { it.category == MemoryCategory.PAIR }

            // Approve TERM proposal with editedTarget -> should update memory entry and sync with GlossaryEntry + bump glossaryVersion!
            val approveTime = 3000L
            db.series().approveMemoryProposal(termEntry.id, "Peter (Twin)", approveTime)

            val updatedTermMemory = db.series().memoryEntryById(termEntry.id)
            assertNotNull(updatedTermMemory)
            assertEquals(MemoryStatus.APPROVED, updatedTermMemory?.status)
            assertEquals("Peter (Twin)", updatedTermMemory?.targetTerm)
            assertEquals(approveTime, updatedTermMemory?.updatedAt)

            // Verify GlossaryEntry was created/upserted and glossaryVersion bumped
            val updatedGlossary = db.series().glossaryForSeries(1)
            assertEquals(2, updatedGlossary.size)
            val syncedGlossaryTerm = updatedGlossary.find { it.sourceTerm == "Pedro" }
            assertNotNull(syncedGlossaryTerm)
            assertEquals("Peter (Twin)", syncedGlossaryTerm?.targetTerm)
            assertEquals("Irmão gêmeo", syncedGlossaryTerm?.notes)

            val updatedSeries = db.series().byId(1)
            assertEquals(2, updatedSeries?.glossaryVersion)
            assertEquals(approveTime, updatedSeries?.updatedAt)

            // Approve CHARACTER proposal without editedTarget -> should use entry.targetTerm, but NOT insert into GlossaryEntry
            val charApproveTime = 3500L
            db.series().approveMemoryProposal(charEntry.id, null, charApproveTime)
            val updatedCharMemory = db.series().memoryEntryById(charEntry.id)
            assertEquals(MemoryStatus.APPROVED, updatedCharMemory?.status)
            assertEquals("Paul", updatedCharMemory?.targetTerm)

            val glossaryAfterChar = db.series().glossaryForSeries(1)
            assertEquals(2, glossaryAfterChar.size) // unchanged term count

            // Reject PAIR proposal
            db.series().updateMemoryStatus(pairEntry.id, MemoryStatus.REJECTED, pairEntry.targetTerm, 4000L)
            val updatedPair = db.series().memoryEntryById(pairEntry.id)
            assertEquals(MemoryStatus.REJECTED, updatedPair?.status)

            // Test approvedMemoryForSeries
            val approvedOnly = db.series().approvedMemoryForSeries(1)
            assertEquals(2, approvedOnly.size)

            // Test deleteMemoryEntry
            db.series().deleteMemoryEntry(pairEntry.id)
            val memoryAfterDelete = db.series().observeMemoryEntries(1).first()
            assertEquals(2, memoryAfterDelete.size)

            // Test CASCADE on Series delete
            db.series().deleteSeries(1)
            val memoryAfterSeriesDelete = db.series().observeMemoryEntries(1).first()
            assertEquals(0, memoryAfterSeriesDelete.size)
        }
        db.close()
    }

    @Test
    fun migration1To4PreservesData() {
        val dbName = "migration-test-1-4"
        helper.createDatabase(dbName, 1).apply {
            execSQL(
                "INSERT INTO Book (id, title, author, language, format, sha256, privatePath, sourceName, sizeBytes, importedAt) " +
                    "VALUES (1, 'Memorial de Aires', 'Machado de Assis', 'pt', 'EPUB', 'sha_aires', '/data/aires.epub', 'aires.epub', 1024, 1000)"
            )
            close()
        }

        helper.runMigrationsAndValidate(dbName, 4, true, AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
        helper.runMigrationsAndValidate(dbName, 5, true, AppDatabase.MIGRATION_4_5)
        helper.runMigrationsAndValidate(dbName, 6, true, AppDatabase.MIGRATION_5_6)
        helper.runMigrationsAndValidate(dbName, 7, true, AppDatabase.MIGRATION_6_7)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
            )
            .build()

        runBlocking {
            val books = db.books().all().first()
            assertEquals(1, books.size)
            assertEquals("Memorial de Aires", books[0].title)
            assertEquals(null, books[0].seriesId)

            val seriesId = db.series().insertSeries(
                Series(
                    name = "Última Fase",
                    styleNotes = "Diário e memórias",
                    glossaryVersion = 1,
                    createdAt = 1000L,
                    updatedAt = 1000L,
                )
            )
            db.series().insertMemoryEntries(
                listOf(
                    SeriesMemoryEntry(
                        seriesId = seriesId,
                        category = MemoryCategory.CHARACTER,
                        sourceTerm = "Conselheiro Aires",
                        targetTerm = "Counselor Aires",
                        notes = "Diplomata aposentado",
                        excerpt = "O Conselheiro Aires escreveu em seu diário.",
                        sourceBookTitle = "Memorial de Aires",
                        status = MemoryStatus.PENDING,
                        createdAt = 1000L,
                        updatedAt = 1000L,
                    )
                )
            )
            val entries = db.series().observeMemoryEntries(seriesId).first()
            assertEquals(1, entries.size)
            assertEquals("Conselheiro Aires", entries[0].sourceTerm)
        }
        db.close()
    }

    @Test
    fun migration4To5PreservesDataAndAddsBatchFields() {
        val dbName = "migration-test-4-5"
        helper.createDatabase(dbName, 4).apply {
            execSQL(
                "INSERT INTO Book (id, title, author, language, format, sha256, privatePath, sourceName, sizeBytes, importedAt, seriesId) " +
                    "VALUES (1, 'Dom Casmurro', 'Machado de Assis', 'pt', 'EPUB', 'sha_casmurro', '/data/casmurro.epub', 'casmurro.epub', 1024, 1000, NULL)"
            )
            execSQL(
                "INSERT INTO TranslationJob (id, bookId, status, provider, modelId, effort, tokenBudget, blockTotal, blockDone, inputTokens, outputTokens, failureReason, createdAt, updatedAt, seriesId, glossaryVersion, glossarySnapshot, styleNotesSnapshot) " +
                    "VALUES (1, 1, 'QUEUED', 'OPENAI', 'gpt-4o', NULL, NULL, 2, 0, 0, 0, NULL, 1000, 1000, NULL, NULL, NULL, NULL)"
            )
            execSQL(
                "INSERT INTO TranslationChunk (id, jobId, chunkIndex, spineIndex, startBlockIndex, endBlockIndex, blockCount, sourceText, translatedText, status, attemptCount, inputTokens, outputTokens, errorMessage, updatedAt) " +
                    "VALUES (1, 1, 0, 0, 0, 1, 2, '⟦1⟧Capítulo 1⟦2⟧Texto', NULL, 'PENDING', 0, 0, 0, NULL, 1000)"
            )
            close()
        }

        // Validate migration to version 5 against 5.json, then 6 against 6.json
        helper.runMigrationsAndValidate(dbName, 5, true, AppDatabase.MIGRATION_4_5)
        helper.runMigrationsAndValidate(dbName, 6, true, AppDatabase.MIGRATION_5_6)
        helper.runMigrationsAndValidate(dbName, 7, true, AppDatabase.MIGRATION_6_7)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
            )
            .build()

        runBlocking {
            // Verify existing job preserved with default executionMode="SYNC" and remoteBatchId=null
            val job = db.translations().jobById(1)
            assertNotNull(job)
            assertEquals("SYNC", job?.executionMode)
            assertEquals(null, job?.remoteBatchId)

            // Verify updateJobBatchId updates batch ID and transitions to WAITING_BATCH
            val updateTime = 2000L
            db.translations().updateJobBatchId(1, "batch_remote_999", JobStatus.WAITING_BATCH, updateTime)
            val updatedJob = db.translations().jobById(1)
            assertEquals("batch_remote_999", updatedJob?.remoteBatchId)
            assertEquals(JobStatus.WAITING_BATCH, updatedJob?.status)
            assertEquals(updateTime, updatedJob?.updatedAt)

            // Verify nextRunnableJob picks up WAITING_BATCH job
            val runnableJob = db.translations().nextRunnableJob()
            assertNotNull(runnableJob)
            assertEquals(1L, runnableJob?.id)
            assertEquals(JobStatus.WAITING_BATCH, runnableJob?.status)

            // Verify pendingChunksForJob
            val pending = db.translations().pendingChunksForJob(1)
            assertEquals(1, pending.size)
            assertEquals(0, pending[0].chunkIndex)

            // Verify markBatchChunkCompleted delegates and syncs job progress
            db.translations().markBatchChunkCompleted(
                jobId = 1,
                chunkId = 1,
                translatedText = "⟦1⟧Chapter 1⟦2⟧Text",
                inputTokens = 120L,
                outputTokens = 250L,
                updatedAt = 3000L
            )
            val chunk = db.translations().chunkById(1)
            assertNotNull(chunk)
            assertEquals("COMPLETED", chunk?.status)
            assertEquals("⟦1⟧Chapter 1⟦2⟧Text", chunk?.translatedText)

            val syncedJob = db.translations().jobById(1)
            assertNotNull(syncedJob)
            assertEquals(1, syncedJob?.blockDone)
            assertEquals(120L, syncedJob?.inputTokens)
            assertEquals(250L, syncedJob?.outputTokens)
        }
        db.close()
    }

    @Test
    fun migration1To5PreservesData() {
        val dbName = "migration-test-1-5"
        helper.createDatabase(dbName, 1).apply {
            execSQL(
                "INSERT INTO Book (id, title, author, language, format, sha256, privatePath, sourceName, sizeBytes, importedAt) " +
                    "VALUES (1, 'Helena', 'Machado de Assis', 'pt', 'EPUB', 'sha_helena', '/data/helena.epub', 'helena.epub', 1024, 1000)"
            )
            close()
        }

        helper.runMigrationsAndValidate(
            dbName,
            7,
            true,
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
            AppDatabase.MIGRATION_5_6,
            AppDatabase.MIGRATION_6_7,
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
            )
            .build()

        runBlocking {
            val books = db.books().all().first()
            assertEquals(1, books.size)
            assertEquals("Helena", books[0].title)
            assertEquals(null, books[0].seriesId)

            val job = TranslationJob(
                bookId = 1,
                status = JobStatus.WAITING_BATCH,
                provider = TranslationProvider.OPENAI,
                modelId = "gpt-4o",
                blockTotal = 1,
                createdAt = 1000L,
                updatedAt = 1000L,
                executionMode = "BATCH",
                remoteBatchId = "batch_abc",
            )
            val jobId = db.translations().insertJob(job)
            val loadedJob = db.translations().jobById(jobId)
            assertNotNull(loadedJob)
            assertEquals("BATCH", loadedJob?.executionMode)
            assertEquals("batch_abc", loadedJob?.remoteBatchId)
            assertEquals(JobStatus.WAITING_BATCH, loadedJob?.status)
        }
        db.close()
    }

    @Test
    fun closeInstanceClosesAndResetsSingleton() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db1 = AppDatabase.get(context)
        val db2 = AppDatabase.get(context)
        assertSame(db1, db2)

        AppDatabase.closeInstance()
        val db3 = AppDatabase.get(context)
        assertNotNull(db3)
        assertNotSame(db1, db3)
        AppDatabase.closeInstance()
    }

    @Test
    fun migration5To6PreservesSeriesAndAddsAuthor() {
        val dbName = "migration-test-5-6"
        helper.createDatabase(dbName, 5).apply {
            execSQL(
                "INSERT INTO Series (id, name, styleNotes, glossaryVersion, createdAt, updatedAt) " +
                    "VALUES (1, 'Ciclo de Fundação', 'Tom clássico', 2, 1000, 1000)"
            )
            close()
        }

        helper.runMigrationsAndValidate(dbName, 6, true, AppDatabase.MIGRATION_5_6)
        helper.runMigrationsAndValidate(dbName, 7, true, AppDatabase.MIGRATION_6_7)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
            )
            .build()

        runBlocking {
            val existing = db.series().byId(1)
            assertNotNull(existing)
            assertEquals("Ciclo de Fundação", existing?.name)
            assertEquals(null, existing?.author)

            val newId = db.series().insertSeries(
                Series(
                    name = "Mistborn",
                    author = "Brandon Sanderson",
                    createdAt = 2000L,
                    updatedAt = 2000L,
                )
            )
            val created = db.series().byId(newId)
            assertEquals("Brandon Sanderson", created?.author)

            val found = db.series().findByNameIgnoreCase("  mistborn ")
            assertNotNull(found)
            assertEquals(newId, found?.id)
        }
        db.close()
    }

    @Test
    fun migration6To7PreservesExistingDataAndAddsMetadataColumns() {
        val dbName = "migration-test-6-7"
        helper.createDatabase(dbName, 6).apply {
            execSQL(
                "INSERT INTO Series (id, name, author, styleNotes, glossaryVersion, createdAt, updatedAt) " +
                    "VALUES (1, 'Duna', 'Frank Herbert', 'Tom solene', 3, 1000, 1000)"
            )
            execSQL(
                "INSERT INTO Book (id, title, author, language, format, sha256, privatePath, sourceName, sizeBytes, importedAt, seriesId) " +
                    "VALUES (1, 'Dune', 'Frank Herbert', 'en', 'EPUB', 'sha_dune_v6', '/data/dune.epub', 'dune.epub', 4096, 1000, 1)"
            )
            execSQL(
                "INSERT INTO GlossaryEntry (id, seriesId, sourceTerm, targetTerm, notes, updatedAt) " +
                    "VALUES (1, 1, 'Spice', 'Especiaria', 'Melange', 1000)"
            )
            execSQL(
                "INSERT INTO TranslationJob (id, bookId, status, provider, modelId, blockTotal, blockDone, inputTokens, outputTokens, createdAt, updatedAt, seriesId, glossaryVersion, glossarySnapshot, styleNotesSnapshot, executionMode) " +
                    "VALUES (1, 1, 'QUEUED', 'OPENAI', 'gpt-4o', 10, 0, 0, 0, 1000, 1000, 1, 3, '[]', 'Tom solene', 'SYNC')"
            )
            close()
        }

        helper.runMigrationsAndValidate(dbName, 7, true, AppDatabase.MIGRATION_6_7)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
            )
            .build()

        runBlocking {
            // Existing records preserved untouched, new metadata columns default to null
            val existingSeries = db.series().byId(1)
            assertNotNull(existingSeries)
            assertEquals("Duna", existingSeries?.name)
            assertEquals("Frank Herbert", existingSeries?.author)
            assertEquals("Tom solene", existingSeries?.styleNotes)
            assertEquals(3, existingSeries?.glossaryVersion)
            assertEquals(null, existingSeries?.summary)
            assertEquals(null, existingSeries?.wikidataQid)
            assertEquals(null, existingSeries?.charactersJson)
            assertEquals(null, existingSeries?.locationsJson)

            val existingBook = db.books().byId(1)
            assertNotNull(existingBook)
            assertEquals("Dune", existingBook?.title)
            assertEquals(1L, existingBook?.seriesId)
            assertEquals(null, existingBook?.isbn)
            assertEquals(null, existingBook?.seriesPosition)
            assertEquals(null, existingBook?.metadataSourcesJson)
            assertEquals(null, existingBook?.charactersJson)
            assertEquals(null, existingBook?.locationsJson)

            val glossary = db.series().glossaryForSeries(1)
            assertEquals(1, glossary.size)
            assertEquals("Especiaria", glossary[0].targetTerm)

            val job = db.translations().jobById(1)
            assertNotNull(job)
            assertEquals(1L, job?.seriesId)
            assertEquals("Tom solene", job?.styleNotesSnapshot)

            val sampleCharsJson = """[{"name":"Paul Atreides","description":"Protagonista","source":"WIKIDATA","fromFreeText":false}]"""
            val sampleLocsJson = """[{"name":"Arrakis","aliases":["Duna"],"source":"WIKIDATA","fromFreeText":false}]"""

            // Updating book & series metadata persists all new fields
            db.books().updateBook(
                existingBook!!.copy(
                    isbn = "9780441172719",
                    publisher = "Ace Books",
                    publishedDate = "1965",
                    summary = "Em Arrakis, Paul Atreides enfrenta intrigas politicas.",
                    seriesPosition = "1",
                    openLibraryKey = "/works/OL893415W",
                    wikidataQid = "Q190192",
                    hardcoverId = "42",
                    metadataSourcesJson = """{"isbn":"OPEN_LIBRARY","seriesPosition":"WIKIDATA"}""",
                    charactersJson = sampleCharsJson,
                    locationsJson = sampleLocsJson,
                )
            )
            db.series().updateSeries(
                existingSeries!!.copy(
                    summary = "Saga de ficcao cientifica ambientada no planeta deserto Arrakis.",
                    wikidataQid = "Q837705",
                    hardcoverId = "7",
                    metadataSourcesJson = """{"summary":"WIKIDATA"}""",
                    charactersJson = sampleCharsJson,
                    locationsJson = sampleLocsJson,
                )
            )

            val updatedBook = db.books().byId(1)
            assertEquals("9780441172719", updatedBook?.isbn)
            assertEquals("Ace Books", updatedBook?.publisher)
            assertEquals("1965", updatedBook?.publishedDate)
            assertEquals("1", updatedBook?.seriesPosition)
            assertEquals("/works/OL893415W", updatedBook?.openLibraryKey)
            assertEquals("Q190192", updatedBook?.wikidataQid)
            assertEquals("42", updatedBook?.hardcoverId)
            assertEquals(sampleCharsJson, updatedBook?.charactersJson)
            assertEquals(sampleLocsJson, updatedBook?.locationsJson)

            val updatedSeries = db.series().byId(1)
            assertEquals("Q837705", updatedSeries?.wikidataQid)
            assertEquals("7", updatedSeries?.hardcoverId)
            assertEquals("""{"summary":"WIKIDATA"}""", updatedSeries?.metadataSourcesJson)
            assertEquals(sampleCharsJson, updatedSeries?.charactersJson)
            assertEquals(sampleLocsJson, updatedSeries?.locationsJson)
        }
        db.close()
    }
}
