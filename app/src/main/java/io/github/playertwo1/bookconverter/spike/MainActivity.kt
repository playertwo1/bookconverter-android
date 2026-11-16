package io.github.playertwo1.bookconverter.spike

import android.Manifest
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BLACK = Color(0xFF000000)
private val ACCENT = Color(0xFF6366F1)
private val TEXT = Color(0xFFF5F5F7)

class MainActivity : ComponentActivity() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = SpikeDatabase.get(this).dao()
        appScope.launch { dao.log(JobEvent(at = System.currentTimeMillis(), type = "APP_START", detail = "")) }
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent { Screen() }
    }
}

private fun enqueue(context: Context) {
    WorkManager.getInstance(context).enqueueUniqueWork(
        "fake-job",
        ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<FakeTranslateWorker>().setInputData(workDataOf("blockSeconds" to 20)).build(),
    )
}

private fun time(at: Long): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(at))

@Composable
private fun Screen() {
    val ctx = LocalContext.current
    val dao = remember { SpikeDatabase.get(ctx).dao() }
    val scope = rememberCoroutineScope()
    val completed by remember { dao.completedCount() }.collectAsState(initial = 0)
    val total by remember { dao.total() }.collectAsState(initial = 0)
    val events by remember { dao.lastEvents() }.collectAsState(initial = emptyList())

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = ACCENT,
            onPrimary = TEXT,
            background = BLACK,
            onBackground = TEXT,
            surface = BLACK,
            onSurface = TEXT,
        )
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(BLACK)
                .safeDrawingPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Salvos: $completed / $total", fontSize = 24.sp, color = TEXT)
            Button(onClick = {
                scope.launch {
                    // Stop a running job first, or a second tap reseeds blocks under a live worker.
                    WorkManager.getInstance(ctx).cancelUniqueWork("fake-job").await()
                    dao.seed(360)
                    dao.clearEvents()
                    enqueue(ctx)
                }
            }) { Text("Iniciar novo (360 × 20s)") }
            Button(onClick = { WorkManager.getInstance(ctx).cancelUniqueWork("fake-job") }) { Text("Pausar") }
            Button(onClick = { enqueue(ctx) }) { Text("Retomar") }
            LazyColumn(Modifier.weight(1f)) {
                items(events, key = { it.id }) { e ->
                    Text("${time(e.at)} ${e.type} ${e.detail}", fontSize = 13.sp, color = TEXT)
                }
            }
        }
    }
}
