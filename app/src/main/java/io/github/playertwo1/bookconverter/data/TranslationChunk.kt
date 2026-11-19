package io.github.playertwo1.bookconverter.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object ChunkStatus {
    const val PENDING = "PENDING"
    const val IN_PROGRESS = "IN_PROGRESS"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
}

@Entity(
    tableName = "TranslationChunk",
    foreignKeys = [
        ForeignKey(
            entity = TranslationJob::class,
            parentColumns = ["id"],
            childColumns = ["jobId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["jobId", "chunkIndex"], unique = true),
        Index(value = ["jobId", "status"])
    ]
)
data class TranslationChunk(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val jobId: Long = 0,
    val chunkIndex: Int,
    val spineIndex: Int,
    val startBlockIndex: Int,
    val endBlockIndex: Int,
    val blockCount: Int,
    val sourceText: String,
    val translatedText: String? = null,
    val status: String = ChunkStatus.PENDING,
    val attemptCount: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val errorMessage: String? = null,
    val updatedAt: Long,
)
