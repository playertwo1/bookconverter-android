package io.github.playertwo1.bookconverter.spike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val CHANNEL_ID = "jobs"
private const val NOTIF_ID = 1

class FakeTranslateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val dao = SpikeDatabase.get(context).dao()
    private val nm = context.getSystemService(NotificationManager::class.java)

    override suspend fun doWork(): Result {
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Jobs", NotificationManager.IMPORTANCE_LOW))
        event("START", "attempt=$runAttemptCount")

        try {
            setForeground(
                ForegroundInfo(NOTIF_ID, notification("Traduzindo..."), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            )
            event("FGS_OK", "")
        } catch (t: Throwable) {
            event("FGS_FAIL", "${t::class.simpleName}: ${t.message}")
        }

        val blockSeconds = inputData.getInt("blockSeconds", 20)
        try {
            while (true) {
                val b = dao.nextPending() ?: break
                delay(blockSeconds * 1000L)
                dao.markCompleted(b.id, System.currentTimeMillis())
                nm.notify(NOTIF_ID, notification("Bloco ${b.id} salvo"))
                if (b.id % 30 == 0) event("SAVED", "${b.id}")
            }
        } catch (e: CancellationException) {
            // NonCancellable: the coroutine is already cancelled, so a plain Room call would not write this row.
            withContext(NonCancellable) { event("STOP", "reason=$stopReason") }
            throw e
        }

        event("DONE", "")
        return Result.success()
    }

    private suspend fun event(type: String, detail: String) =
        dao.log(JobEvent(at = System.currentTimeMillis(), type = type, detail = detail))

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Tradução de livro (spike)")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
}
