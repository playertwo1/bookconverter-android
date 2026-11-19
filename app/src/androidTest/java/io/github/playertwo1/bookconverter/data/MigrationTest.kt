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
            .addMigrations(AppDatabase.MIGRATION_1_2)
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

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_1_2)
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
}
