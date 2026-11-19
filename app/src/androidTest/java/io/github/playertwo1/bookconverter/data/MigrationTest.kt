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
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
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

        // Continue migration to 3 so Room database can open with current schema
        helper.runMigrationsAndValidate(dbName, 3, true, AppDatabase.MIGRATION_2_3)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
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

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
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

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .build()

        runBlocking {
            val books = db.books().all().first()
            assertEquals(1, books.size)
            assertEquals("Quincas Borba", books[0].title)
            assertEquals(null, books[0].seriesId)
        }
        db.close()
    }
}
