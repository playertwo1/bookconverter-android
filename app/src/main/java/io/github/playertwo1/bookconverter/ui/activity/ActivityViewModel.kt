package io.github.playertwo1.bookconverter.ui.activity

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.playertwo1.bookconverter.data.AppDatabase
import io.github.playertwo1.bookconverter.data.JobStatus
import io.github.playertwo1.bookconverter.data.JobWithBook
import io.github.playertwo1.bookconverter.worker.QueueManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ActivityUiState(
    val activeJobs: List<JobWithBook> = emptyList(),
    val queuedJobs: List<JobWithBook> = emptyList(),
    val historyJobs: List<JobWithBook> = emptyList(),
    val isEmpty: Boolean = true,
)

class ActivityViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.get(app).translations()

    val uiState: StateFlow<ActivityUiState> = dao.observeAllJobsWithBook()
        .map { jobs ->
            val active = mutableListOf<JobWithBook>()
            val queued = mutableListOf<JobWithBook>()
            val history = mutableListOf<JobWithBook>()

            for (job in jobs) {
                when (job.status) {
                    JobStatus.RUNNING,
                    JobStatus.WAITING_NETWORK,
                    JobStatus.WAITING_RATE_LIMIT,
                    JobStatus.PAUSED,
                    JobStatus.PAUSED_BUDGET -> active.add(job)

                    JobStatus.QUEUED -> queued.add(job)

                    JobStatus.COMPLETED,
                    JobStatus.FAILED,
                    JobStatus.CANCELLED -> history.add(job)

                    else -> history.add(job)
                }
            }

            queued.sortBy { it.createdAt }
            history.sortByDescending { it.updatedAt }

            ActivityUiState(
                activeJobs = active,
                queuedJobs = queued,
                historyJobs = history,
                isEmpty = jobs.isEmpty(),
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ActivityUiState()
        )

    fun pauseJob(jobId: Long) {
        viewModelScope.launch {
            QueueManager.pauseJob(getApplication(), jobId)
        }
    }

    fun resumeJob(jobId: Long, clearBudget: Boolean = false) {
        viewModelScope.launch {
            QueueManager.resumeJob(getApplication(), jobId, clearBudget)
        }
    }

    fun cancelJob(jobId: Long) {
        viewModelScope.launch {
            QueueManager.cancelJob(getApplication(), jobId)
        }
    }

    fun deleteJob(jobId: Long) {
        viewModelScope.launch {
            QueueManager.deleteJob(getApplication(), jobId)
        }
    }
}
