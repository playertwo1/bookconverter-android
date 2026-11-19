package io.github.playertwo1.bookconverter.ui.activity

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface as M3Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.playertwo1.bookconverter.data.JobStatus
import io.github.playertwo1.bookconverter.data.JobWithBook
import io.github.playertwo1.bookconverter.ui.count
import io.github.playertwo1.bookconverter.ui.theme.Accent
import io.github.playertwo1.bookconverter.ui.theme.AccentSoft
import io.github.playertwo1.bookconverter.ui.theme.Background
import io.github.playertwo1.bookconverter.ui.theme.Danger
import io.github.playertwo1.bookconverter.ui.theme.Field
import io.github.playertwo1.bookconverter.ui.theme.Positive
import io.github.playertwo1.bookconverter.ui.theme.Serif
import io.github.playertwo1.bookconverter.ui.theme.Stroke
import io.github.playertwo1.bookconverter.ui.theme.Surface
import io.github.playertwo1.bookconverter.ui.theme.TextPrimary
import io.github.playertwo1.bookconverter.ui.theme.TextSecondary
import io.github.playertwo1.bookconverter.ui.theme.TextTertiary

private val WarningSoft = Color(0x2EF59E0B)
private val WarningText = Color(0xFFFBBF24)
private val PositiveSoft = Color(0x2E35B77B)
private val DangerSoft = Color(0x2EF36D7A)

private data class PendingConfirmAction(
    val job: JobWithBook,
    val isDelete: Boolean,
)

@Composable
fun ActivityScreen(
    modifier: Modifier = Modifier,
    vm: ActivityViewModel = viewModel(),
) {
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    var pendingAction by remember { mutableStateOf<PendingConfirmAction?>(null) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(Background)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 96.dp),
    ) {
        item {
            Text("Atividade", style = MaterialTheme.typography.displaySmall, color = TextPrimary)
            Spacer(Modifier.height(20.dp))
        }

        if (uiState.isEmpty) {
            item {
                EmptyActivityView()
            }
        } else {
            // 1. Active / In Progress Section
            if (uiState.activeJobs.isNotEmpty()) {
                item {
                    SectionHeader("Em andamento")
                }
                items(uiState.activeJobs, key = { it.id }) { job ->
                    ActiveJobCard(
                        job = job,
                        onPause = { vm.pauseJob(job.id) },
                        onResume = { clearBudget -> vm.resumeJob(job.id, clearBudget) },
                        onCancel = { pendingAction = PendingConfirmAction(job, isDelete = false) },
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }

            // 2. Queue Section
            if (uiState.queuedJobs.isNotEmpty()) {
                item {
                    SectionHeader("Fila de espera (${uiState.queuedJobs.size})")
                }
                itemsIndexed(uiState.queuedJobs, key = { _, job -> job.id }) { index, job ->
                    QueuedJobCard(
                        position = index + 1,
                        job = job,
                        onCancel = { pendingAction = PendingConfirmAction(job, isDelete = false) },
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }

            // 3. History Section
            if (uiState.historyJobs.isNotEmpty()) {
                item {
                    SectionHeader("Histórico")
                }
                items(uiState.historyJobs, key = { it.id }) { job ->
                    HistoryJobCard(
                        job = job,
                        onResume = { vm.resumeJob(job.id) },
                        onDelete = { pendingAction = PendingConfirmAction(job, isDelete = true) },
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }

    pendingAction?.let { action ->
        ConfirmActionDialog(
            action = action,
            onConfirm = {
                if (action.isDelete) {
                    vm.deleteJob(action.job.id)
                } else {
                    vm.cancelJob(action.job.id)
                }
                pendingAction = null
            },
            onDismiss = { pendingAction = null },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        color = TextPrimary,
        fontFamily = Serif,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
    )
}

@Composable
private fun ActiveJobCard(
    job: JobWithBook,
    onPause: () -> Unit,
    onResume: (clearBudget: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    val percent = if (job.blockTotal > 0) (job.blockDone * 100 / job.blockTotal) else 0
    val progress = if (job.blockTotal > 0) job.blockDone.toFloat() / job.blockTotal.toFloat() else 0f
    val totalTokens = job.inputTokens + job.outputTokens

    M3Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Stroke),
    ) {
        Column(Modifier.padding(16.dp)) {
            // Header: Title, author & Status Badge
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = job.bookTitle,
                        color = TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    job.bookAuthor?.takeIf { it.isNotBlank() }?.let { author ->
                        Text(author, color = TextSecondary, fontSize = 13.sp)
                    }
                    Text(
                        "${job.provider} · ${job.modelId}",
                        color = TextTertiary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                StatusBadge(status = job.status)
            }

            Spacer(Modifier.height(16.dp))

            // Progress bar
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = Accent,
                trackColor = Field,
            )

            Spacer(Modifier.height(8.dp))

            // Progress text: Blocks and Tokens
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${count(job.blockDone)} / ${count(job.blockTotal)} blocos · $percent%",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                val budget = job.tokenBudget
                val tokenText = if (budget != null) {
                    "${count(totalTokens)} / ${count(budget)} teto"
                } else {
                    "${count(totalTokens)} tokens"
                }
                Text(tokenText, color = TextSecondary, fontSize = 12.sp)
            }

            // Alert banner if waiting or paused budget
            when (job.status) {
                JobStatus.WAITING_NETWORK -> {
                    Spacer(Modifier.height(12.dp))
                    AlertBanner(
                        message = "Sem conexão com a internet. A tradução será retomada automaticamente assim que a conexão voltar.",
                        isWarning = true,
                    )
                }
                JobStatus.WAITING_RATE_LIMIT -> {
                    Spacer(Modifier.height(12.dp))
                    val reason = job.failureReason?.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: ""
                    AlertBanner(
                        message = "Limite de requisições do provedor atingido. Aguardando intervalo para retomar.$reason",
                        isWarning = true,
                    )
                }
                JobStatus.PAUSED_BUDGET -> {
                    Spacer(Modifier.height(12.dp))
                    val budget = job.tokenBudget ?: 0L
                    AlertBanner(
                        message = "O teto de ${count(budget)} tokens foi atingido. Seu progresso foi salvo com segurança. Você pode continuar sem limite de orçamento ou cancelar.",
                        isWarning = false,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Action Buttons
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (job.status) {
                    JobStatus.RUNNING,
                    JobStatus.WAITING_NETWORK,
                    JobStatus.WAITING_RATE_LIMIT -> {
                        Button(
                            onClick = onPause,
                            modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Field,
                                contentColor = TextPrimary,
                            ),
                        ) {
                            Text("Pausar", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedButton(
                            onClick = onCancel,
                            modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Danger),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger),
                        ) {
                            Text("Cancelar", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    JobStatus.PAUSED -> {
                        Button(
                            onClick = { onResume(false) },
                            modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Accent,
                                contentColor = TextPrimary,
                            ),
                        ) {
                            Text("Retomar", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedButton(
                            onClick = onCancel,
                            modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Danger),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger),
                        ) {
                            Text("Cancelar", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    JobStatus.PAUSED_BUDGET -> {
                        Button(
                            onClick = { onResume(true) },
                            modifier = Modifier.weight(1.3f).defaultMinSize(minHeight = 48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Accent,
                                contentColor = TextPrimary,
                            ),
                        ) {
                            Text("Continuar sem teto", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedButton(
                            onClick = onCancel,
                            modifier = Modifier.weight(0.9f).defaultMinSize(minHeight = 48.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Danger),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger),
                        ) {
                            Text("Cancelar", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueuedJobCard(
    position: Int,
    job: JobWithBook,
    onCancel: () -> Unit,
) {
    M3Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Stroke),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Field),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "#$position",
                    color = Accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    job.bookTitle,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${job.provider} · ${job.modelId} · ${count(job.blockTotal)} blocos",
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
            }

            Spacer(Modifier.width(8.dp))

            TextButton(
                onClick = onCancel,
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .semantics { contentDescription = "Cancelar tradução de ${job.bookTitle}" },
            ) {
                Text("Cancelar", color = Danger, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun HistoryJobCard(
    job: JobWithBook,
    onResume: () -> Unit,
    onDelete: () -> Unit,
) {
    val totalTokens = job.inputTokens + job.outputTokens

    M3Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Stroke),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        job.bookTitle,
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    job.bookAuthor?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = TextSecondary, fontSize = 12.sp)
                    }
                }
                StatusBadge(status = job.status)
            }

            Spacer(Modifier.height(8.dp))

            when (job.status) {
                JobStatus.COMPLETED -> {
                    Text(
                        "${count(job.blockTotal)} blocos concluídos · ${count(totalTokens)} tokens",
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                }
                JobStatus.FAILED -> {
                    Text(
                        "${count(job.blockDone)} / ${count(job.blockTotal)} blocos processados",
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                    job.failureReason?.takeIf { it.isNotBlank() }?.let { reason ->
                        Text(
                            "Motivo: $reason",
                            color = Danger,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                else -> {
                    Text(
                        "${count(job.blockDone)} / ${count(job.blockTotal)} blocos processados · ${count(totalTokens)} tokens",
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (job.status == JobStatus.FAILED || job.status == JobStatus.CANCELLED) {
                    TextButton(
                        onClick = onResume,
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    ) {
                        Text("Retomar", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                    Text("Excluir", color = TextSecondary, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: String) {
    val (label, bg, fg) = when (status) {
        JobStatus.RUNNING -> Triple("Traduzindo…", AccentSoft, Accent)
        JobStatus.WAITING_NETWORK -> Triple("Sem conexão", WarningSoft, WarningText)
        JobStatus.WAITING_RATE_LIMIT -> Triple("Aguardando API", WarningSoft, WarningText)
        JobStatus.PAUSED -> Triple("Pausado", Field, TextSecondary)
        JobStatus.PAUSED_BUDGET -> Triple("Teto atingido", DangerSoft, Danger)
        JobStatus.QUEUED -> Triple("Na fila", Field, TextSecondary)
        JobStatus.COMPLETED -> Triple("Concluído", PositiveSoft, Positive)
        JobStatus.FAILED -> Triple("Falhou", DangerSoft, Danger)
        JobStatus.CANCELLED -> Triple("Cancelado", Field, TextTertiary)
        else -> Triple(status, Field, TextSecondary)
    }

    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun AlertBanner(message: String, isWarning: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isWarning) WarningSoft else DangerSoft)
            .padding(12.dp),
    ) {
        Text(
            text = message,
            color = if (isWarning) WarningText else Danger,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun EmptyActivityView() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 72.dp, bottom = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(Field),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(16.dp).clip(CircleShape).background(Accent))
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Nenhuma tradução ativa",
            color = TextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Inicie a tradução de um livro na Biblioteca para acompanhar a fila, progresso e histórico aqui.",
            color = TextSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}

@Composable
private fun ConfirmActionDialog(
    action: PendingConfirmAction,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = {
            Text(
                if (action.isDelete) "Excluir registro de tradução?"
                else "Cancelar tradução?"
            )
        },
        text = {
            Column {
                Text(
                    if (action.isDelete)
                        "Deseja realmente excluir o registro da tradução de \"${action.job.bookTitle}\"?"
                    else
                        "Deseja realmente cancelar a tradução de \"${action.job.bookTitle}\"?",
                    fontSize = 14.sp,
                    color = TextPrimary,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "O livro original na Biblioteca será mantido intacto.",
                    fontSize = 13.sp,
                    color = TextSecondary,
                    fontWeight = FontWeight.Medium,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Danger,
                    contentColor = TextPrimary,
                ),
            ) {
                Text(if (action.isDelete) "Excluir" else "Cancelar tradução")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) {
                Text("Voltar", color = TextSecondary)
            }
        },
    )
}
