package io.github.playertwo1.bookconverter.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object JobStatus {
    const val QUEUED = "QUEUED"
    const val RUNNING = "RUNNING"
    const val PAUSED = "PAUSED"
    const val WAITING_NETWORK = "WAITING_NETWORK"
    const val WAITING_RATE_LIMIT = "WAITING_RATE_LIMIT"
    const val PAUSED_BUDGET = "PAUSED_BUDGET"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
}

object TranslationProvider {
    const val OPENAI = "OPENAI"
    const val CHATGPT = "CHATGPT"
    const val ANTHROPIC = "ANTHROPIC"
    const val GEMINI = "GEMINI"
}

@Entity(
    tableName = "TranslationJob",
    foreignKeys = [
        ForeignKey(
            entity = Book::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("bookId"),
        Index("status")
    ]
)
data class TranslationJob(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val status: String,
    val provider: String,
    val modelId: String,
    val effort: String? = null,
    val tokenBudget: Long? = null,
    val blockTotal: Int,
    val blockDone: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val failureReason: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
