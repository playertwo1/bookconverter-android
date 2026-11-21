package io.github.playertwo1.bookconverter.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.playertwo1.bookconverter.data.BackupManager
import io.github.playertwo1.bookconverter.ui.theme.Accent
import io.github.playertwo1.bookconverter.ui.theme.Danger
import io.github.playertwo1.bookconverter.ui.theme.Field
import io.github.playertwo1.bookconverter.ui.theme.Positive
import io.github.playertwo1.bookconverter.ui.theme.Stroke
import io.github.playertwo1.bookconverter.ui.theme.Surface
import io.github.playertwo1.bookconverter.ui.theme.TextPrimary
import io.github.playertwo1.bookconverter.ui.theme.TextSecondary
import io.github.playertwo1.bookconverter.ui.theme.TextTertiary
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onOpenProviders: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    var isLoading by rememberSaveable { mutableStateOf(false) }
    var loadingMessage by rememberSaveable { mutableStateOf("") }
    var statusMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRestoreUriString by rememberSaveable { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                isLoading = true
                loadingMessage = "Exportando backup..."
                statusMessage = null
                errorMessage = null
                try {
                    val summary = BackupManager.exportBackup(context, uri)
                    statusMessage = "Backup salvo: ${summary.booksCount} livros, ${summary.seriesCount} séries, ${summary.jobsCount} traduções"
                } catch (e: Exception) {
                    errorMessage = e.message ?: "Falha ao exportar backup."
                } finally {
                    isLoading = false
                }
            }
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            pendingRestoreUriString = uri.toString()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text("Ajustes", style = MaterialTheme.typography.displaySmall, color = TextPrimary)

        Row(
            Modifier
                .padding(top = 24.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Surface)
                .border(1.dp, Stroke, RoundedCornerShape(16.dp))
                .clickable(role = Role.Button, onClick = onOpenProviders)
                .defaultMinSize(minHeight = 64.dp)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Provedores de IA", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("Chaves, URL base e conexão", color = TextSecondary, fontSize = 13.sp)
            }
            Text("›", color = TextSecondary, fontSize = 17.sp)
        }

        // Card Backup e restauração (AC16)
        Column(
            Modifier
                .padding(top = 16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Surface)
                .border(1.dp, Stroke, RoundedCornerShape(16.dp))
                .padding(16.dp),
        ) {
            Text(
                "Backup e restauração (AC16)",
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Exporta biblioteca, séries, glossários e checkpoints em .zip (chaves de API não são incluídas).",
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = {
                        statusMessage = null
                        errorMessage = null
                        exportLauncher.launch("bookconverter-backup.zip")
                    },
                    enabled = !isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Accent,
                        contentColor = TextPrimary,
                        disabledContainerColor = Field,
                        disabledContentColor = TextTertiary,
                    ),
                ) {
                    Text("Exportar backup (.zip)", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }

                Button(
                    onClick = {
                        statusMessage = null
                        errorMessage = null
                        restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    },
                    enabled = !isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Field,
                        contentColor = TextPrimary,
                        disabledContainerColor = Field,
                        disabledContentColor = TextTertiary,
                    ),
                ) {
                    Text("Restaurar backup (.zip)", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            if (isLoading) {
                Spacer(Modifier.height(14.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Accent,
                        strokeWidth = 2.dp,
                    )
                    Text(loadingMessage, color = TextSecondary, fontSize = 13.sp)
                }
            }

            statusMessage?.let { msg ->
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Positive.copy(alpha = 0.15f))
                        .border(1.dp, Positive.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(msg, color = Positive, fontSize = 13.sp)
                }
            }

            errorMessage?.let { err ->
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Danger.copy(alpha = 0.15f))
                        .border(1.dp, Danger.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(err, color = Danger, fontSize = 13.sp)
                }
            }
        }

        Text(
            "Bookconverter $version",
            color = TextTertiary,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 24.dp),
        )
    }

    pendingRestoreUriString?.let { uriStr ->
        val uri = Uri.parse(uriStr)
        AlertDialog(
            onDismissRequest = {
                if (!isLoading) pendingRestoreUriString = null
            },
            containerColor = Surface,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            shape = RoundedCornerShape(16.dp),
            title = {
                Text(
                    "Substituir dados atuais pelo backup?",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                )
            },
            text = {
                Column {
                    Text(
                        "Esta ação substituirá a biblioteca, séries, glossários e checkpoints atuais pelos dados do arquivo de backup.",
                        color = TextSecondary,
                        fontSize = 14.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "As chaves de API e configurações de provedores serão mantidas intactas neste aparelho. Jobs de tradução interrompidos serão reconciliados automaticamente.",
                        color = TextTertiary,
                        fontSize = 13.sp,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingRestoreUriString = null
                        scope.launch {
                            isLoading = true
                            loadingMessage = "Restaurando backup..."
                            statusMessage = null
                            errorMessage = null
                            try {
                                val result = BackupManager.restoreBackup(context, uri)
                                statusMessage = "Restaurado: ${result.restoredBooks} livros e banco de dados."
                            } catch (e: Exception) {
                                errorMessage = e.message ?: "Falha ao restaurar backup."
                            } finally {
                                isLoading = false
                            }
                        }
                    },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Danger,
                        contentColor = TextPrimary,
                    ),
                ) {
                    Text("Substituir e restaurar", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingRestoreUriString = null },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                    Text("Cancelar", color = TextSecondary)
                }
            },
        )
    }
}
