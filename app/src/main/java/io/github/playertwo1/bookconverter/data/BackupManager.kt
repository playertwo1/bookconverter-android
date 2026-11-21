package io.github.playertwo1.bookconverter.data

import android.content.Context
import android.net.Uri
import io.github.playertwo1.bookconverter.core.translate.Glossary
import io.github.playertwo1.bookconverter.core.translate.GlossaryTerm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupSummary(
    val booksCount: Int,
    val seriesCount: Int,
    val jobsCount: Int,
    val sizeBytes: Long,
)

data class RestoreResult(
    val restoredBooks: Int,
    val dbRestored: Boolean,
)

object BackupManager {

    private const val MAX_TOTAL_RESTORE_BYTES = 4L * 1024 * 1024 * 1024 // 4 GB safety limit
    private const val MAX_SINGLE_ENTRY_BYTES = 512L * 1024 * 1024 // 512 MB max single file size

    /**
     * Exports a manual backup of library books, SQLite database, and human-readable series glossaries.
     * Never exports ProviderSettings, API keys, or OAuth tokens.
     */
    suspend fun exportBackup(
        context: Context,
        targetUri: Uri,
    ): BackupSummary = withContext(Dispatchers.IO) {
        val db = AppDatabase.get(context)
        val sDb = db.openHelper.writableDatabase

        // Consolidate SQLite WAL into bookconverter.db
        try {
            sDb.query("PRAGMA wal_checkpoint(FULL)").use { cursor ->
                cursor.moveToFirst()
            }
        } catch (_: Throwable) {
            try {
                sDb.execSQL("PRAGMA wal_checkpoint(FULL)")
            } catch (_: Throwable) {}
        }

        fun countRows(tableName: String): Int = try {
            sDb.query("SELECT COUNT(*) FROM $tableName").use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
        } catch (_: Throwable) {
            0
        }

        val booksCount = countRows("Book")
        val seriesCount = countRows("Series")
        val jobsCount = countRows("TranslationJob")
        val dbVersion = sDb.version
        val createdAt = System.currentTimeMillis()

        // Load series and their glossaries for human-readable export
        val seriesGlossaries = mutableListOf<Pair<Long, String>>()
        try {
            sDb.query("SELECT id FROM Series").use { cursor ->
                while (cursor.moveToNext()) {
                    val seriesId = cursor.getLong(0)
                    val entries = db.series().glossaryForSeries(seriesId)
                    val terms = entries.map {
                        GlossaryTerm(source = it.sourceTerm, target = it.targetTerm, notes = it.notes)
                    }
                    val formatted = Glossary.formatDesktopTxt(terms)
                    seriesGlossaries.add(seriesId to formatted)
                }
            }
        } catch (_: Throwable) {}

        // Identify local books in app-private filesDir/books
        val booksDir = File(context.filesDir, "books")
        val bookFiles = if (booksDir.exists() && booksDir.isDirectory) {
            booksDir.listFiles()?.filter { it.isFile } ?: emptyList()
        } else {
            emptyList()
        }

        val dbFile = context.getDatabasePath(AppDatabase.NAME)
        if (!dbFile.exists()) {
            throw IOException("Arquivo do banco de dados não encontrado em ${dbFile.absolutePath}")
        }

        val targetStream = context.contentResolver.openOutputStream(targetUri)
            ?: throw IOException("Não foi possível abrir o destino para escrita: $targetUri")

        var totalBytesWritten = 0L
        val countingOut = object : OutputStream() {
            override fun write(b: Int) {
                targetStream.write(b)
                totalBytesWritten++
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                targetStream.write(b, off, len)
                totalBytesWritten += len
            }

            override fun flush() = targetStream.flush()
            override fun close() = targetStream.close()
        }

        ZipOutputStream(countingOut.buffered()).use { zipOut ->
            // 1. manifest.json (never include secrets/tokens)
            val manifestJson = """
                {
                  "version": 1,
                  "dbVersion": $dbVersion,
                  "createdAt": $createdAt
                }
            """.trimIndent()
            zipOut.putNextEntry(ZipEntry("manifest.json"))
            zipOut.write(manifestJson.toByteArray(Charsets.UTF_8))
            zipOut.closeEntry()

            // 2. database/bookconverter.db
            zipOut.putNextEntry(ZipEntry("database/${AppDatabase.NAME}"))
            dbFile.inputStream().buffered().use { inStream ->
                inStream.copyTo(zipOut)
            }
            zipOut.closeEntry()

            // 3. books/<fileName>
            for (file in bookFiles) {
                zipOut.putNextEntry(ZipEntry("books/${file.name}"))
                file.inputStream().buffered().use { inStream ->
                    inStream.copyTo(zipOut)
                }
                zipOut.closeEntry()
            }

            // 4. glossaries/series_<id>.txt
            for ((seriesId, formattedText) in seriesGlossaries) {
                zipOut.putNextEntry(ZipEntry("glossaries/series_$seriesId.txt"))
                zipOut.write(formattedText.toByteArray(Charsets.UTF_8))
                zipOut.closeEntry()
            }
        }

        BackupSummary(
            booksCount = booksCount,
            seriesCount = seriesCount,
            jobsCount = jobsCount,
            sizeBytes = totalBytesWritten,
        )
    }

    /**
     * Restores a manual backup from [sourceUri]. Validates Zip-Slip and size limits,
     * checks database validity and SQLite header, closes active database, replaces
     * database file and cleans WAL/SHM, restores book files, and reconciles pending chunks.
     */
    suspend fun restoreBackup(
        context: Context,
        sourceUri: Uri,
    ): RestoreResult = withContext(Dispatchers.IO) {
        val tempDir = File(
            context.cacheDir,
            "restore_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        )
        if (!tempDir.mkdirs()) {
            throw IOException("Não foi possível criar diretório temporário para restauração: ${tempDir.absolutePath}")
        }

        try {
            val inStream = context.contentResolver.openInputStream(sourceUri)
                ?: throw IOException("Não foi possível abrir o arquivo de backup: $sourceUri")

            var totalExtractedBytes = 0L

            ZipInputStream(inStream.buffered()).use { zipIn ->
                val tempCanonical = tempDir.canonicalPath
                while (true) {
                    val entry = zipIn.nextEntry ?: break
                    val name = entry.name

                    // Anti Zip-Slip validation: reject "..", leading "/", or "\\"
                    if (name.contains("..") || name.startsWith("/") || name.startsWith("\\") || name.contains("\\")) {
                        throw SecurityException("Caminho inseguro detectado no arquivo ZIP (Zip-Slip): $name")
                    }

                    val targetFile = File(tempDir, name)
                    val targetCanonical = targetFile.canonicalPath
                    if (!targetCanonical.startsWith(tempCanonical + File.separator)) {
                        throw SecurityException("Caminho fora do diretório temporário permitido (Zip-Slip): $name")
                    }

                    if (entry.isDirectory) {
                        targetFile.mkdirs()
                    } else {
                        targetFile.parentFile?.mkdirs()
                        targetFile.outputStream().use { out ->
                            val buffer = ByteArray(32 * 1024)
                            var entryBytes = 0L
                            while (true) {
                                val read = zipIn.read(buffer)
                                if (read == -1) break
                                entryBytes += read
                                totalExtractedBytes += read
                                if (entryBytes > MAX_SINGLE_ENTRY_BYTES || totalExtractedBytes > MAX_TOTAL_RESTORE_BYTES) {
                                    throw IOException("Limite de tamanho excedido durante descompressão (possível Zip-Bomb)")
                                }
                                out.write(buffer, 0, read)
                            }
                        }
                    }
                    zipIn.closeEntry()
                }
            }

            // Verify existence of database/bookconverter.db and SQLite format 3 header
            val extractedDbFile = File(tempDir, "database/${AppDatabase.NAME}")
            if (!extractedDbFile.exists() || !extractedDbFile.isFile) {
                throw IOException("Arquivo de banco de dados não encontrado no backup (esperado: database/${AppDatabase.NAME})")
            }

            val magicHeader = ByteArray(16)
            val bytesRead = extractedDbFile.inputStream().use { it.read(magicHeader) }
            val expectedHeader = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
            if (bytesRead < 16 || !magicHeader.contentEquals(expectedHeader)) {
                throw IOException("Arquivo de banco de dados corrompido ou com cabeçalho inválido (não é SQLite)")
            }

            // Close existing database instance before swapping files
            AppDatabase.closeInstance()

            // Replace context.getDatabasePath(AppDatabase.NAME) and clean old -wal and -shm files
            val liveDbFile = context.getDatabasePath(AppDatabase.NAME)
            val walFile = File(liveDbFile.parentFile, "${AppDatabase.NAME}-wal")
            val shmFile = File(liveDbFile.parentFile, "${AppDatabase.NAME}-shm")

            if (walFile.exists()) {
                walFile.delete()
            }
            if (shmFile.exists()) {
                shmFile.delete()
            }

            liveDbFile.parentFile?.mkdirs()
            extractedDbFile.copyTo(liveDbFile, overwrite = true)

            // Restore books/* to File(context.filesDir, "books")
            val extractedBooksDir = File(tempDir, "books")
            val liveBooksDir = File(context.filesDir, "books")
            liveBooksDir.mkdirs()

            var restoredBooksCount = 0
            if (extractedBooksDir.exists() && extractedBooksDir.isDirectory) {
                val extractedFiles = extractedBooksDir.listFiles()?.filter { it.isFile } ?: emptyList()
                for (bookFile in extractedFiles) {
                    val destFile = File(liveBooksDir, bookFile.name)
                    bookFile.copyTo(destFile, overwrite = true)
                    restoredBooksCount++
                }
            }

            // Reopen database and reconcile in-progress chunks
            val newDb = AppDatabase.get(context)
            newDb.translations().reconcileAllInProgress(System.currentTimeMillis())

            RestoreResult(
                restoredBooks = restoredBooksCount,
                dbRestored = true,
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }
}

// Top-level delegating functions for convenience and flexible imports
suspend fun exportBackup(context: Context, targetUri: Uri): BackupSummary =
    BackupManager.exportBackup(context, targetUri)

suspend fun restoreBackup(context: Context, sourceUri: Uri): RestoreResult =
    BackupManager.restoreBackup(context, sourceUri)
