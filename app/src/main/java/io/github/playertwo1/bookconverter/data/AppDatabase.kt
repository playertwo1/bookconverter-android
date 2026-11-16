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
@Database(entities = [Book::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun books(): BookDao

    companion object {
        const val NAME = "bookconverter.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .build()
                .also { instance = it }
        }
    }
}
