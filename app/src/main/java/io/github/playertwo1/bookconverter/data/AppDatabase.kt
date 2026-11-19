package io.github.playertwo1.bookconverter.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(indices = [Index(value = ["sha256"], unique = true)])
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String?,
    val language: String?,
    val format: String, // "EPUB" in the V1; MOBI/AZW3 arrive in D1
    val sha256: String,
    val privatePath: String, // app-private copy; the picked URI is not kept
    val sourceName: String,
    val sizeBytes: Long,
    val importedAt: Long,
)

@Dao
interface BookDao {
    @Query("SELECT * FROM Book ORDER BY importedAt DESC")
    fun all(): Flow<List<Book>>

    @Query("SELECT * FROM Book WHERE id = :id")
    suspend fun byId(id: Long): Book?

    @Query("SELECT * FROM Book WHERE sha256 = :sha256")
    suspend fun bySha(sha256: String): Book?

    @Insert
    suspend fun insert(book: Book): Long
}

// Every version bump needs a Migration plus a step in MigrationTest; there is no destructive fallback on purpose.
@Database(
    entities = [
        Book::class,
        TranslationJob::class,
        TranslationChunk::class,
    ],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun translations(): TranslationDao

    companion object {
        const val NAME = "bookconverter.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `TranslationJob` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `bookId` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `provider` TEXT NOT NULL,
                        `modelId` TEXT NOT NULL,
                        `effort` TEXT,
                        `tokenBudget` INTEGER,
                        `blockTotal` INTEGER NOT NULL,
                        `blockDone` INTEGER NOT NULL,
                        `inputTokens` INTEGER NOT NULL,
                        `outputTokens` INTEGER NOT NULL,
                        `failureReason` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        FOREIGN KEY(`bookId`) REFERENCES `Book`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_TranslationJob_bookId` ON `TranslationJob` (`bookId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_TranslationJob_status` ON `TranslationJob` (`status`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `TranslationChunk` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `jobId` INTEGER NOT NULL,
                        `chunkIndex` INTEGER NOT NULL,
                        `spineIndex` INTEGER NOT NULL,
                        `startBlockIndex` INTEGER NOT NULL,
                        `endBlockIndex` INTEGER NOT NULL,
                        `blockCount` INTEGER NOT NULL,
                        `sourceText` TEXT NOT NULL,
                        `translatedText` TEXT,
                        `status` TEXT NOT NULL,
                        `attemptCount` INTEGER NOT NULL,
                        `inputTokens` INTEGER NOT NULL,
                        `outputTokens` INTEGER NOT NULL,
                        `errorMessage` TEXT,
                        `updatedAt` INTEGER NOT NULL,
                        FOREIGN KEY(`jobId`) REFERENCES `TranslationJob`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_TranslationChunk_jobId_chunkIndex` ON `TranslationChunk` (`jobId`, `chunkIndex`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_TranslationChunk_jobId_status` ON `TranslationChunk` (`jobId`, `status`)")
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }
    }
}
