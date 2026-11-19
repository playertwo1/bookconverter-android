package io.github.playertwo1.bookconverter.data

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "Series")
data class Series(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val styleNotes: String = "",
    val glossaryVersion: Int = 1,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "GlossaryEntry",
    foreignKeys = [
        ForeignKey(
            entity = Series::class,
            parentColumns = ["id"],
            childColumns = ["seriesId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("seriesId"),
        Index(value = ["seriesId", "sourceTerm"], unique = true)
    ]
)
data class GlossaryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val seriesId: Long,
    val sourceTerm: String,
    val targetTerm: String,
    val notes: String? = null,
    val updatedAt: Long,
)

data class SeriesSummary(
    @Embedded val series: Series,
    val bookCount: Int,
    val termCount: Int,
)

@Dao
interface SeriesDao {

    @Query("""
        SELECT 
            s.*, 
            (SELECT COUNT(*) FROM Book b WHERE b.seriesId = s.id) AS bookCount, 
            (SELECT COUNT(*) FROM GlossaryEntry g WHERE g.seriesId = s.id) AS termCount 
        FROM Series s 
        ORDER BY s.name COLLATE NOCASE ASC
    """)
    fun observeAllSummaries(): Flow<List<SeriesSummary>>

    @Query("SELECT * FROM Series ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<Series>>

    @Query("SELECT * FROM Series WHERE id = :seriesId")
    fun observeById(seriesId: Long): Flow<Series?>

    @Query("SELECT * FROM Series WHERE id = :seriesId")
    suspend fun byId(seriesId: Long): Series?

    @Insert
    suspend fun insertSeries(series: Series): Long

    @Update
    suspend fun updateSeries(series: Series)

    @Query("UPDATE Series SET glossaryVersion = glossaryVersion + 1, updatedAt = :updatedAt WHERE id = :seriesId")
    suspend fun bumpGlossaryVersion(seriesId: Long, updatedAt: Long)

    @Query("UPDATE Book SET seriesId = NULL WHERE seriesId = :seriesId")
    suspend fun unlinkBooksFromSeries(seriesId: Long)

    @Query("DELETE FROM Series WHERE id = :seriesId")
    suspend fun deleteSeriesById(seriesId: Long)

    @Transaction
    suspend fun deleteSeries(seriesId: Long) {
        unlinkBooksFromSeries(seriesId)
        deleteSeriesById(seriesId)
    }

    @Query("SELECT * FROM GlossaryEntry WHERE seriesId = :seriesId ORDER BY sourceTerm COLLATE NOCASE ASC")
    fun observeGlossary(seriesId: Long): Flow<List<GlossaryEntry>>

    @Query("SELECT * FROM GlossaryEntry WHERE seriesId = :seriesId ORDER BY sourceTerm COLLATE NOCASE ASC")
    suspend fun glossaryForSeries(seriesId: Long): List<GlossaryEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGlossaryEntry(entry: GlossaryEntry): Long

    @Transaction
    suspend fun saveGlossaryEntryAndBump(entry: GlossaryEntry, now: Long): Long {
        val id = upsertGlossaryEntry(entry.copy(updatedAt = now))
        bumpGlossaryVersion(entry.seriesId, now)
        return id
    }

    @Query("DELETE FROM GlossaryEntry WHERE id = :entryId")
    suspend fun deleteGlossaryEntryById(entryId: Long)

    @Transaction
    suspend fun deleteGlossaryEntryAndBump(entryId: Long, seriesId: Long, now: Long) {
        deleteGlossaryEntryById(entryId)
        bumpGlossaryVersion(seriesId, now)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGlossaryEntries(entries: List<GlossaryEntry>): List<Long>

    @Transaction
    suspend fun importGlossaryEntriesAndBump(seriesId: Long, entries: List<GlossaryEntry>, now: Long) {
        if (entries.isNotEmpty()) {
            val mapped = entries.map { it.copy(seriesId = seriesId, updatedAt = now) }
            upsertGlossaryEntries(mapped)
        }
        bumpGlossaryVersion(seriesId, now)
    }
}
