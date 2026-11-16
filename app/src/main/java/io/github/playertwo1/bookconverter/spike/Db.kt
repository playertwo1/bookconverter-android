package io.github.playertwo1.bookconverter.spike

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity
data class FakeBlock(
    @PrimaryKey val id: Int,
    val state: String, // "PENDING" or "COMPLETED"
    val completedAt: Long?,
)

@Entity
data class JobEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val type: String,
    val detail: String,
)

@Dao
abstract class SpikeDao {
    @Query("DELETE FROM FakeBlock")
    abstract suspend fun clearBlocks()

    @Insert
    abstract suspend fun insertBlocks(blocks: List<FakeBlock>)

    @Transaction
    open suspend fun seed(n: Int) {
        clearBlocks()
        insertBlocks((1..n).map { FakeBlock(it, "PENDING", null) })
    }

    @Query("SELECT * FROM FakeBlock WHERE state = 'PENDING' ORDER BY id LIMIT 1")
    abstract suspend fun nextPending(): FakeBlock?

    @Query("UPDATE FakeBlock SET state = 'COMPLETED', completedAt = :at WHERE id = :id AND state = 'PENDING'")
    abstract suspend fun markCompleted(id: Int, at: Long)

    @Query("SELECT COUNT(*) FROM FakeBlock WHERE state = 'COMPLETED'")
    abstract fun completedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM FakeBlock")
    abstract fun total(): Flow<Int>

    @Insert
    abstract suspend fun log(event: JobEvent)

    @Query("SELECT * FROM JobEvent ORDER BY id DESC LIMIT 40")
    abstract fun lastEvents(): Flow<List<JobEvent>>

    @Query("DELETE FROM JobEvent")
    abstract suspend fun clearEvents()
}

@Database(entities = [FakeBlock::class, JobEvent::class], version = 1, exportSchema = false)
abstract class SpikeDatabase : RoomDatabase() {
    abstract fun dao(): SpikeDao

    companion object {
        @Volatile
        private var instance: SpikeDatabase? = null

        fun get(context: Context): SpikeDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, SpikeDatabase::class.java, "spike.db")
                .build()
                .also { instance = it }
        }
    }
}
