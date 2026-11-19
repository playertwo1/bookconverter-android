package io.github.playertwo1.bookconverter.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PauseActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val jobId = intent.getLongExtra(EXTRA_JOB_ID, -1L)
        if (jobId <= 0L) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                QueueManager.pauseJob(context, jobId)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_JOB_ID = "extra_job_id"
    }
}
