package io.github.playertwo1.bookconverter.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playertwo1.bookconverter.core.provider.ErrorKind
import io.github.playertwo1.bookconverter.core.provider.ProviderClient
import io.github.playertwo1.bookconverter.core.provider.ProviderError
import io.github.playertwo1.bookconverter.core.provider.Translation
import io.github.playertwo1.bookconverter.core.translate.Effort
import io.github.playertwo1.bookconverter.core.translate.JobOutcome
import io.github.playertwo1.bookconverter.core.translate.JobProgress
import io.github.playertwo1.bookconverter.core.translate.JobStatus as CoreJobStatus
import io.github.playertwo1.bookconverter.core.translate.StoredChunk
import io.github.playertwo1.bookconverter.core.translate.TranslationEngine
import io.github.playertwo1.bookconverter.core.translate.TranslationStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class TranslationChaosTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun openDb(name: String): AppDatabase {
        return Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()
    }

    private fun deleteDb(name: String) {
        context.deleteDatabase(name)
    }

    private class RoomTranslationStore(
        private val dao: TranslationDao,
        private val onEffortDowngradedCb: (Long, Effort?) -> Unit = { _, _ -> },
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
            onEffortDowngradedCb(jobId, newEffort)
        }
    }

    private class FakeClient(
        private val handler: (model: String, system: String, text: String, effort: String?) -> Translation = { m, _, _, _ ->
            Translation("⟦1⟧\nTraduzido", m, 10L, 20L)
        },
    ) : ProviderClient {
        val callCount = AtomicInteger(0)

        override fun listModels(): List<String> = listOf("gpt-4o")

        override fun translate(model: String, system: String, text: String, effort: String?): Translation {
            callCount.incrementAndGet()
            return handler(model, system, text, effort)
        }
    }

    private suspend fun insertBook(
        db: AppDatabase,
        title: String = "Livro de Teste",
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

    private fun makeChunks(count: Int, blockCount: Int = 1): List<TranslationChunk> {
        return (0 until count).map { i ->
            TranslationChunk(
                chunkIndex = i,
                spineIndex = 0,
                startBlockIndex = i * blockCount,
                endBlockIndex = (i + 1) * blockCount - 1,
                blockCount = blockCount,
                sourceText = (1..blockCount).joinToString("\n\n") { b -> "⟦$b⟧\nOriginal chunk $i block $b" },
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    /**
     * AC07: Pausa no 73 de 200 chunks reais no Room.
     * Retoma até 200, verifica job.blockDone == 200, dao.countCompletedChunks(jobId) == 200,
     * e zero duplicações.
     */
    @Test
    fun ac07PauseAt73Of200ChunksAndResumeToCompletion() = runBlocking {
        val dbName = "ac07-pause-test.db"
        deleteDb(dbName)
        val db = openDb(dbName)
        try {
            val bookId = insertBook(db)
            val chunks = makeChunks(200, blockCount = 1)
            val job = TranslationJob(
                bookId = bookId,
                status = JobStatus.RUNNING,
                provider = TranslationProvider.OPENAI,
                modelId = "gpt-4o",
                blockTotal = 200,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            val jobId = db.translations().createJobWithChunks(job, chunks)

            val store = RoomTranslationStore(db.translations())
            val engine = TranslationEngine(delayFn = {})
            val client = FakeClient { m, _, _, _ ->
                Translation("⟦1⟧\nTraduzido", m, 10L, 10L)
            }

            // Executa e pausa cooperativamente exatamente quando o chunk 73 completa
            val firstOutcome = engine.runJob(
                jobId = jobId,
                client = client,
                model = "gpt-4o",
                store = store,
                onProgress = { progress ->
                    if (progress.done == 73) {
                        db.translations().updateJobStatus(jobId, JobStatus.PAUSED, null, System.currentTimeMillis())
                    }
                }
            )

            assertEquals(JobOutcome.Paused, firstOutcome)

            // Valida checkpoint no Room: exatamente 73 concluídos
            val pausedJob = db.translations().jobById(jobId)
            assertNotNull(pausedJob)
            assertEquals(73, pausedJob?.blockDone)
            assertEquals(73, db.translations().countCompletedChunks(jobId))
            assertEquals(JobStatus.PAUSED, pausedJob?.status)

            val chunksMidway = db.translations().chunksForJob(jobId)
            assertEquals(200, chunksMidway.size)
            for (i in 0 until 73) {
                assertEquals("Chunk $i deve estar COMPLETED", ChunkStatus.COMPLETED, chunksMidway[i].status)
            }
            for (i in 73 until 200) {
                assertEquals("Chunk $i deve estar PENDING", ChunkStatus.PENDING, chunksMidway[i].status)
            }

            // Retoma: altera status para RUNNING e roda até o fim
            db.translations().updateJobStatus(jobId, JobStatus.RUNNING, null, System.currentTimeMillis())

            val secondOutcome = engine.runJob(
                jobId = jobId,
                client = client,
                model = "gpt-4o",
                store = store
            )

            assertEquals(JobOutcome.Completed, secondOutcome)

            // Validação final da invariante e integridade
            val finalJob = db.translations().jobById(jobId)
            assertNotNull(finalJob)
            assertEquals(200, finalJob?.blockDone)
            assertEquals(200, db.translations().countCompletedChunks(jobId))
            assertEquals(2000L, finalJob?.inputTokens)
            assertEquals(2000L, finalJob?.outputTokens)

            val finalChunks = db.translations().chunksForJob(jobId)
            assertEquals(200, finalChunks.size)
            val indices = finalChunks.map { it.chunkIndex }
            assertEquals((0 until 200).toList(), indices)
            assertTrue("Todos os 200 chunks devem estar COMPLETED", finalChunks.all { it.status == ChunkStatus.COMPLETED })
        } finally {
            db.close()
            deleteDb(dbName)
        }
    }

    /**
     * AC08: Crash + Reboot real do banco de dados SQLite.
     * Fecha e reabre o AppDatabase com chunk em IN_PROGRESS (simulando morte súbita do processo
     * durante requisição de rede pendente). Ao religar, TranslationEngine reconcilia para PENDING
     * e conclui todos os chunks sem duplicação.
     */
    @Test
    fun ac08CrashAndRebootRealDatabaseWithChunkInProgress() = runBlocking {
        val dbName = "ac08-crash-reboot.db"
        deleteDb(dbName)
        var db = openDb(dbName)
        try {
            val bookId = insertBook(db)
            val chunks = makeChunks(5, blockCount = 1)
            val job = TranslationJob(
                bookId = bookId,
                status = JobStatus.RUNNING,
                provider = TranslationProvider.OPENAI,
                modelId = "gpt-4o",
                blockTotal = 5,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            val jobId = db.translations().createJobWithChunks(job, chunks)

            val initialChunks = db.translations().chunksForJob(jobId)
            // Chunks 0 e 1 concluídos
            db.translations().markChunkCompletedAndSyncJob(
                jobId = jobId,
                chunkId = initialChunks[0].id,
                translatedText = "⟦1⟧\nTraduzido 0",
                inputTokens = 10L,
                outputTokens = 10L,
                updatedAt = System.currentTimeMillis()
            )
            db.translations().markChunkCompletedAndSyncJob(
                jobId = jobId,
                chunkId = initialChunks[1].id,
                translatedText = "⟦1⟧\nTraduzido 1",
                inputTokens = 10L,
                outputTokens = 10L,
                updatedAt = System.currentTimeMillis()
            )

            // Chunk 2 fica travado em IN_PROGRESS (simulando crash do processo no meio do voo da rede)
            db.translations().markChunkInProgress(initialChunks[2].id, System.currentTimeMillis())
            assertEquals(ChunkStatus.IN_PROGRESS, db.translations().chunkById(initialChunks[2].id)?.status)

            // SIMULAÇÃO DE MORTE DO PROCESSO / REBOOT DO SISTEMA
            db.close()

            // REBOOT: Reabre a instância do Room a partir do mesmo arquivo físico no disco
            db = openDb(dbName)

            // Confirma que o chunk 2 permaneceu IN_PROGRESS no disco após a reabertura
            val rebootedChunk2 = db.translations().chunkById(initialChunks[2].id)
            assertEquals(ChunkStatus.IN_PROGRESS, rebootedChunk2?.status)

            // TranslationEngine inicia runJob: executa reconcileInProgress no início (AC08)
            val store = RoomTranslationStore(db.translations())
            val engine = TranslationEngine(delayFn = {})
            val client = FakeClient { m, _, _, _ ->
                Translation("⟦1⟧\nTraduzido", m, 10L, 10L)
            }

            val outcome = engine.runJob(
                jobId = jobId,
                client = client,
                model = "gpt-4o",
                store = store
            )

            assertEquals(JobOutcome.Completed, outcome)

            // Verifica que o chunk 2 foi recuperado e completado sem duplicar registros
            val completedCount = db.translations().countCompletedChunks(jobId)
            val finalJob = db.translations().jobById(jobId)
            assertEquals(5, completedCount)
            assertEquals(5, finalJob?.blockDone)

            val finalChunks = db.translations().chunksForJob(jobId)
            assertEquals(5, finalChunks.size)
            assertTrue(finalChunks.all { it.status == ChunkStatus.COMPLETED })
            assertEquals((0 until 5).toList(), finalChunks.map { it.chunkIndex })
        } finally {
            db.close()
            deleteDb(dbName)
        }
    }

    /**
     * AC10 (Sem rede) + AC11 (429 Rate Limit e Teto de Orçamento)
     * Valida WaitingNetwork, WaitingRateLimit e PausedBudget persistidos corretamente no Room,
     * e retomada bem sucedida após updateJobBudget(jobId, null).
     */
    @Test
    fun ac10WaitingNetworkAc11WaitingRateLimitAndTokenBudgetWithResumption() = runBlocking {
        val dbName = "ac10-ac11-test.db"
        deleteDb(dbName)
        val db = openDb(dbName)
        try {
            val bookId = insertBook(db)
            val store = RoomTranslationStore(db.translations())
            val engine = TranslationEngine(delayFn = {})

            // 1. AC10: Falha de rede -> WaitingNetwork com reset para PENDING
            val chunksNetwork = makeChunks(2, blockCount = 1)
            val jobNetwork = TranslationJob(
                bookId = bookId,
                status = JobStatus.RUNNING,
                provider = TranslationProvider.OPENAI,
                modelId = "gpt-4o",
                blockTotal = 2,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            val jobIdNetwork = db.translations().createJobWithChunks(jobNetwork, chunksNetwork)

            val networkFailClient = FakeClient { _, _, _, _ ->
                throw IOException("Falha de conexão com o servidor")
            }

            val netOutcome = engine.runJob(
                jobId = jobIdNetwork,
                client = networkFailClient,
                model = "gpt-4o",
                store = store
            )

            assertTrue(netOutcome is JobOutcome.WaitingNetwork)
            val netMsg = (netOutcome as JobOutcome.WaitingNetwork).message
            db.translations().updateJobStatus(jobIdNetwork, JobStatus.WAITING_NETWORK, netMsg, System.currentTimeMillis())

            val dbJobNetwork = db.translations().jobById(jobIdNetwork)
            assertEquals(JobStatus.WAITING_NETWORK, dbJobNetwork?.status)
            val netChunks = db.translations().chunksForJob(jobIdNetwork)
            assertEquals("Chunk deve ser resetado para PENDING", ChunkStatus.PENDING, netChunks[0].status)

            // 2. AC11: 429 Rate Limit após retries -> WaitingRateLimit com reset para PENDING
            val chunksRate = makeChunks(2, blockCount = 1)
            val jobRate = TranslationJob(
                bookId = bookId,
                status = JobStatus.RUNNING,
                provider = TranslationProvider.OPENAI,
                modelId = "gpt-4o",
                blockTotal = 2,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            val jobIdRate = db.translations().createJobWithChunks(jobRate, chunksRate)

            val rateLimitClient = FakeClient { _, _, _, _ ->
                throw ProviderError(429, "Rate limit atingido (429)", ErrorKind.RATE_LIMIT)
            }

            val rateOutcome = engine.runJob(
                jobId = jobIdRate,
                client = rateLimitClient,
                model = "gpt-4o",
                store = store
            )

            assertTrue(rateOutcome is JobOutcome.WaitingRateLimit)
            val rateMsg = (rateOutcome as JobOutcome.WaitingRateLimit).message
            db.translations().updateJobStatus(jobIdRate, JobStatus.WAITING_RATE_LIMIT, rateMsg, System.currentTimeMillis())

            val dbJobRate = db.translations().jobById(jobIdRate)
            assertEquals(JobStatus.WAITING_RATE_LIMIT, dbJobRate?.status)
            val rateChunks = db.translations().chunksForJob(jobIdRate)
            assertEquals("Chunk deve ser resetado para PENDING", ChunkStatus.PENDING, rateChunks[0].status)

            // 3. AC11: Teto de orçamento -> PausedBudget e Retomada
            val chunksBudget = makeChunks(3, blockCount = 1)
            val jobBudget = TranslationJob(
                bookId = bookId,
                status = JobStatus.RUNNING,
                provider = TranslationProvider.OPENAI,
                modelId = "gpt-4o",
                tokenBudget = 100L,
                blockTotal = 3,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            val jobIdBudget = db.translations().createJobWithChunks(jobBudget, chunksBudget)

            val budgetClient = FakeClient { m, _, _, _ ->
                Translation("⟦1⟧\nTraduzido", m, 30L, 30L) // 60 tokens por chunk
            }

            val budgetOutcome1 = engine.runJob(
                jobId = jobIdBudget,
                client = budgetClient,
                model = "gpt-4o",
                tokenBudget = 100L,
                store = store
            )

            // Chunks 0 e 1 consumiram 120 tokens >= 100 limit -> PausedBudget antes do chunk 2
            assertTrue(budgetOutcome1 is JobOutcome.PausedBudget)
            val pausedBudgetOutcome = budgetOutcome1 as JobOutcome.PausedBudget
            assertEquals(120L, pausedBudgetOutcome.tokensUsed)
            assertEquals(100L, pausedBudgetOutcome.budget)

            db.translations().updateJobStatus(
                jobIdBudget,
                JobStatus.PAUSED_BUDGET,
                "Teto de tokens atingido (${pausedBudgetOutcome.tokensUsed} / ${pausedBudgetOutcome.budget})",
                System.currentTimeMillis()
            )

            val dbJobBudget = db.translations().jobById(jobIdBudget)
            assertEquals(JobStatus.PAUSED_BUDGET, dbJobBudget?.status)
            assertEquals(2, dbJobBudget?.blockDone)
            assertEquals(2, db.translations().countCompletedChunks(jobIdBudget))
            assertEquals(ChunkStatus.PENDING, db.translations().chunksForJob(jobIdBudget)[2].status)

            // RETOMADA: Usuário remove o orçamento e retoma o job
            db.translations().updateJobBudget(jobIdBudget, null, System.currentTimeMillis())
            db.translations().updateJobStatus(jobIdBudget, JobStatus.RUNNING, null, System.currentTimeMillis())

            val refreshedJob = db.translations().jobById(jobIdBudget)
            assertNull(refreshedJob?.tokenBudget)

            val budgetOutcome2 = engine.runJob(
                jobId = jobIdBudget,
                client = budgetClient,
                model = "gpt-4o",
                tokenBudget = null,
                store = store
            )

            assertEquals(JobOutcome.Completed, budgetOutcome2)
            assertEquals(3, db.translations().countCompletedChunks(jobIdBudget))
            assertEquals(3, db.translations().jobById(jobIdBudget)?.blockDone)
        } finally {
            db.close()
            deleteDb(dbName)
        }
    }

    /**
     * D11: Fila serial de múltiplos livros (FIFO).
     * 2 livros enfileirados em QUEUED: nextRunnableJob() entrega livro 1 primeiro e só depois livro 2.
     */
    @Test
    fun serialQueueMultipleBooksFifo() = runBlocking {
        val dbName = "serial-queue-test.db"
        deleteDb(dbName)
        val db = openDb(dbName)
        try {
            val book1 = insertBook(db, title = "Livro Um", author = "Autor A")
            val book2 = insertBook(db, title = "Livro Dois", author = "Autor B")

            val job1 = TranslationJob(
                bookId = book1,
                status = JobStatus.QUEUED,
                provider = TranslationProvider.OPENAI,
                modelId = "gpt-4o",
                blockTotal = 10,
                createdAt = 1000L,
                updatedAt = 1000L
            )
            val job2 = TranslationJob(
                bookId = book2,
                status = JobStatus.QUEUED,
                provider = TranslationProvider.OPENAI,
                modelId = "gpt-4o",
                blockTotal = 10,
                createdAt = 2000L,
                updatedAt = 2000L
            )

            val id1 = db.translations().insertJob(job1)
            val id2 = db.translations().insertJob(job2)

            // 1. Ambos QUEUED: entrega id1 por ser mais antigo
            val first = db.translations().nextRunnableJob()
            assertNotNull(first)
            assertEquals(id1, first?.id)
            assertEquals(book1, first?.bookId)

            // 2. id1 em RUNNING: continua entregando id1 prioritariamente
            db.translations().updateJobStatus(id1, JobStatus.RUNNING, null, 1500L)
            val running = db.translations().nextRunnableJob()
            assertEquals(id1, running?.id)

            // 3. id1 COMPLETED: agora entrega id2
            db.translations().updateJobStatus(id1, JobStatus.COMPLETED, null, 2500L)
            val second = db.translations().nextRunnableJob()
            assertNotNull(second)
            assertEquals(id2, second?.id)
            assertEquals(book2, second?.bookId)

            // 4. id2 COMPLETED: não há mais jobs elegíveis
            db.translations().updateJobStatus(id2, JobStatus.COMPLETED, null, 3500L)
            val third = db.translations().nextRunnableJob()
            assertNull(third)
        } finally {
            db.close()
            deleteDb(dbName)
        }
    }

    /**
     * AC14: Excluir job remove chunks mas preserva o Book intacto.
     */
    @Test
    fun ac14DeleteJobPreservesBook() = runBlocking {
        val dbName = "ac14-delete-test.db"
        deleteDb(dbName)
        val db = openDb(dbName)
        try {
            val bookId = insertBook(db, title = "Memórias Póstumas", author = "Machado de Assis")
            val chunks = makeChunks(10, blockCount = 1)
            val job = TranslationJob(
                bookId = bookId,
                status = JobStatus.RUNNING,
                provider = TranslationProvider.OPENAI,
                modelId = "gpt-4o",
                blockTotal = 10,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            val jobId = db.translations().createJobWithChunks(job, chunks)

            // Completa 3 chunks
            val initialChunks = db.translations().chunksForJob(jobId)
            for (i in 0 until 3) {
                db.translations().markChunkCompletedAndSyncJob(
                    jobId = jobId,
                    chunkId = initialChunks[i].id,
                    translatedText = "⟦1⟧\nTraduzido $i",
                    inputTokens = 10L,
                    outputTokens = 10L,
                    updatedAt = System.currentTimeMillis()
                )
            }

            assertEquals(10, db.translations().chunksForJob(jobId).size)
            assertEquals(3, db.translations().countCompletedChunks(jobId))

            // Exclui o Job via DAO
            db.translations().deleteJob(jobId)

            // Job e Chunks devem ter sido removidos
            assertNull(db.translations().jobById(jobId))
            assertTrue(db.translations().chunksForJob(jobId).isEmpty())

            // Livro deve permanecer 100% íntegro no banco
            val preservedBook = db.books().byId(bookId)
            assertNotNull(preservedBook)
            assertEquals("Memórias Póstumas", preservedBook?.title)
            assertEquals("Machado de Assis", preservedBook?.author)
            assertEquals("EPUB", preservedBook?.format)
        } finally {
            db.close()
            deleteDb(dbName)
        }
    }

    /**
     * Teste de Caos Completo no Room Real (SQLite em disco):
     * - 200 chunks reais no SQLite
     * - 50 pausas/retomadas aleatórias
     * - 20 crashes simulados (chunks em IN_PROGRESS órfãos)
     * - 10 reaberturas completas do AppDatabase (10 reboots reais de DB!)
     * - Falhas transientes de rede e 429
     * - Após CADA etapa e no fim: assert job.blockDone == dao.countCompletedChunks(jobId)
     * - Ao final: 200 chunks COMPLETED, zero duplicações.
     */
    @Test
    fun fullChaosTest200ChunksRealRoom() = runBlocking {
        val dbName = "full-chaos-real.db"
        deleteDb(dbName)
        var db = openDb(dbName)
        try {
            val bookId = insertBook(db, title = "Guerra e Paz", author = "Liev Tolstói")
            val chunks = makeChunks(200, blockCount = 1)
            val job = TranslationJob(
                bookId = bookId,
                status = JobStatus.RUNNING,
                provider = TranslationProvider.OPENAI,
                modelId = "gpt-4o",
                blockTotal = 200,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            val jobId = db.translations().createJobWithChunks(job, chunks)

            var pauseCount = 0
            var crashCount = 0
            var rebootCount = 0
            val engine = TranslationEngine(delayFn = {})

            var transientNetworkRemaining = 5
            var transient429Remaining = 5

            val client = FakeClient { m, _, text, _ ->
                // Injeta falhas transientes de rede
                if (transientNetworkRemaining > 0 && (1..10).random() == 1) {
                    transientNetworkRemaining--
                    throw IOException("Caos de rede transiente")
                }
                // Injeta falhas transientes de 429
                if (transient429Remaining > 0 && (1..10).random() == 1) {
                    transient429Remaining--
                    throw ProviderError(429, "Caos 429 transiente", ErrorKind.RATE_LIMIT)
                }
                Translation("⟦1⟧\nTraduzido", m, 10L, 10L)
            }

            while (true) {
                val currentCompleted = db.translations().countCompletedChunks(jobId)
                val currentJob = db.translations().jobById(jobId)
                assertNotNull(currentJob)
                // Invariante sagrada após cada iteração
                assertEquals(
                    "Invariante violada: blockDone != countCompletedChunks",
                    currentCompleted,
                    currentJob?.blockDone
                )

                if (currentCompleted >= 200) {
                    break
                }

                // Injeção de Crash (marca próximo chunk como IN_PROGRESS órfão)
                if (crashCount < 20) {
                    val next = db.translations().nextPendingChunk(jobId)
                    if (next != null) {
                        db.translations().markChunkInProgress(next.id, System.currentTimeMillis())
                        crashCount++
                    }
                }

                // Injeção de Reboot (fecha e reabre o AppDatabase do disco)
                if (rebootCount < 10 && (1..2).random() == 1) {
                    db.close()
                    db = openDb(dbName)
                    rebootCount++
                }

                // Executa engine com pausa periódica para atingir >= 50 pausas
                val store = RoomTranslationStore(db.translations())
                val outcome = engine.runJob(
                    jobId = jobId,
                    client = client,
                    model = "gpt-4o",
                    store = store,
                    onProgress = { _ ->
                        if (pauseCount < 50) {
                            db.translations().updateJobStatus(
                                jobId,
                                JobStatus.PAUSED,
                                null,
                                System.currentTimeMillis()
                            )
                        }
                    }
                )

                when (outcome) {
                    is JobOutcome.Paused -> {
                        pauseCount++
                        val pausedJob = db.translations().jobById(jobId)
                        assertEquals(
                            "Invariante em pausa violada",
                            db.translations().countCompletedChunks(jobId),
                            pausedJob?.blockDone
                        )
                        // Retoma
                        db.translations().updateJobStatus(
                            jobId,
                            JobStatus.RUNNING,
                            null,
                            System.currentTimeMillis()
                        )
                    }
                    is JobOutcome.WaitingNetwork -> {
                        db.translations().updateJobStatus(
                            jobId,
                            JobStatus.RUNNING,
                            null,
                            System.currentTimeMillis()
                        )
                    }
                    is JobOutcome.WaitingRateLimit -> {
                        db.translations().updateJobStatus(
                            jobId,
                            JobStatus.RUNNING,
                            null,
                            System.currentTimeMillis()
                        )
                    }
                    is JobOutcome.Completed -> {
                        // Concluído
                    }
                    else -> {
                        throw IllegalStateException("Resultado inesperado no teste de caos: $outcome")
                    }
                }
            }

            // Afirmações finais de estresse e resiliência
            assertTrue("Deve ter executado pelo menos 50 pausas (executou $pauseCount)", pauseCount >= 50)
            assertTrue("Deve ter executado pelo menos 20 crashes simulados (executou $crashCount)", crashCount >= 20)
            assertTrue("Deve ter executado pelo menos 10 reboots de DB (executou $rebootCount)", rebootCount >= 10)

            val finalJob = db.translations().jobById(jobId)
            assertNotNull(finalJob)
            assertEquals(200, finalJob?.blockDone)
            assertEquals(200, db.translations().countCompletedChunks(jobId))
            assertEquals(2000L, finalJob?.inputTokens)
            assertEquals(2000L, finalJob?.outputTokens)

            val finalChunks = db.translations().chunksForJob(jobId)
            assertEquals(200, finalChunks.size)
            assertTrue("Todos os 200 chunks devem estar COMPLETED", finalChunks.all { it.status == ChunkStatus.COMPLETED })
            assertEquals((0 until 200).toList(), finalChunks.map { it.chunkIndex })
        } finally {
            db.close()
            deleteDb(dbName)
        }
    }
}
