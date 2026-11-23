package io.github.playertwo1.bookconverter.ui

import android.content.Intent
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.withStateAtLeast
import io.github.playertwo1.bookconverter.core.provider.DriveFolder
import io.github.playertwo1.bookconverter.core.provider.GoogleDriveAuth
import io.github.playertwo1.bookconverter.core.provider.ProviderError
import io.github.playertwo1.bookconverter.core.update.GitHubUpdateChecker
import io.github.playertwo1.bookconverter.core.update.UpdateCheckResult
import io.github.playertwo1.bookconverter.data.BackupManager
import io.github.playertwo1.bookconverter.data.GoogleDriveSettings
import io.github.playertwo1.bookconverter.ui.theme.Accent
import io.github.playertwo1.bookconverter.ui.theme.AccentSoft
import io.github.playertwo1.bookconverter.ui.theme.AppThemeMode
import io.github.playertwo1.bookconverter.ui.theme.Danger
import io.github.playertwo1.bookconverter.ui.theme.Field
import io.github.playertwo1.bookconverter.ui.theme.Positive
import io.github.playertwo1.bookconverter.ui.theme.Stroke
import io.github.playertwo1.bookconverter.ui.theme.Surface
import io.github.playertwo1.bookconverter.ui.theme.TextPrimary
import io.github.playertwo1.bookconverter.ui.theme.TextSecondary
import io.github.playertwo1.bookconverter.ui.theme.TextTertiary
import io.github.playertwo1.bookconverter.ui.theme.ThemePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.ServerSocket
import java.net.SocketTimeoutException

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

    val driveSettings = remember { GoogleDriveSettings(context) }
    var isDriveConnected by rememberSaveable { mutableStateOf(driveSettings.isConnected) }
    var driveEmail by rememberSaveable { mutableStateOf(driveSettings.tokens?.email ?: "Conectado") }
    var driveFolderName by rememberSaveable { mutableStateOf(driveSettings.selectedFolderName) }
    var driveFolderId by rememberSaveable { mutableStateOf(driveSettings.selectedFolderId) }
    var driveAutoUpload by rememberSaveable { mutableStateOf(driveSettings.autoUploadOnComplete) }
    var driveClientId by rememberSaveable { mutableStateOf(driveSettings.clientId) }
    var driveClientSecret by rememberSaveable { mutableStateOf(driveSettings.clientSecret) }
    var isDriveLoading by rememberSaveable { mutableStateOf(false) }
    var driveStatusMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var driveErrorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var driveSignInJob by remember { mutableStateOf<Job?>(null) }
    var driveServerListener by remember { mutableStateOf<ServerSocket?>(null) }

    var showFolderDialog by rememberSaveable { mutableStateOf(false) }
    var currentBrowseId by rememberSaveable { mutableStateOf("root") }
    var currentBrowseName by rememberSaveable { mutableStateOf("Meu Drive (raiz)") }
    var navStack by rememberSaveable { mutableStateOf(listOf<String>()) }
    var newFolderNameInput by rememberSaveable { mutableStateOf("") }
    var isCreatingFolder by rememberSaveable { mutableStateOf(false) }
    var isFoldersLoading by rememberSaveable { mutableStateOf(false) }
    var foldersError by rememberSaveable { mutableStateOf<String?>(null) }
    var foldersList by remember { mutableStateOf<List<DriveFolder>>(emptyList()) }

    val updateChecker = remember { GitHubUpdateChecker() }
    var isCheckingUpdate by rememberSaveable { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<UpdateCheckResult?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            try { driveServerListener?.close() } catch (_: Exception) {}
            driveSignInJob?.cancel()
        }
    }

    LaunchedEffect(showFolderDialog, currentBrowseId) {
        if (showFolderDialog) {
            isFoldersLoading = true
            foldersError = null
            try {
                foldersList = driveSettings.listFolders(currentBrowseId)
            } catch (e: Exception) {
                foldersError = e.message ?: "Erro ao carregar pastas"
            } finally {
                isFoldersLoading = false
            }
        }
    }

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

        // Card Aparência (D5)
        val currentThemeMode by ThemePreferences.themeMode.collectAsState()
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
                "Aparência (D5)",
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Escolha o tema visual do aplicativo.",
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppThemeMode.entries.forEach { mode ->
                    val isSelected = currentThemeMode == mode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) AccentSoft else Field)
                            .border(
                                1.dp,
                                if (isSelected) Accent else Stroke,
                                RoundedCornerShape(12.dp),
                            )
                            .clickable(role = Role.RadioButton) {
                                ThemePreferences.setMode(context, mode)
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = mode.label,
                            color = if (isSelected) TextPrimary else TextSecondary,
                            fontSize = 15.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        if (isSelected) {
                            Text(
                                text = "✓",
                                color = Accent,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }

        // Card Google Drive (Upload automático)
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
                "Google Drive (Upload automático)",
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Envie livros traduzidos automaticamente para a nuvem.",
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )

            if (!isDriveConnected) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = driveClientId,
                        onValueChange = {
                            driveClientId = it
                            driveSettings.clientId = it.trim()
                        },
                        label = { Text("Client ID") },
                        placeholder = { Text("xxxxxx.apps.googleusercontent.com") },
                        singleLine = true,
                        colors = fieldColors(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp),
                    )

                    OutlinedTextField(
                        value = driveClientSecret,
                        onValueChange = {
                            driveClientSecret = it
                            driveSettings.clientSecret = it.trim()
                        },
                        label = { Text("Client Secret (opcional)") },
                        placeholder = { Text("Opcional") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        singleLine = true,
                        colors = fieldColors(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp),
                    )

                    Text(
                        "Configure o URI de redirecionamento http://127.0.0.1:1455/auth/callback no Google Cloud Console.",
                        color = TextTertiary,
                        fontSize = 12.sp,
                    )

                    if (isDriveLoading) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Field)
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = Accent,
                                    strokeWidth = 2.dp,
                                )
                                Text("Aguardando login no navegador...", color = TextSecondary, fontSize = 13.sp)
                            }
                            TextButton(
                                onClick = {
                                    try { driveServerListener?.close() } catch (_: Exception) {}
                                    driveSignInJob?.cancel()
                                    isDriveLoading = false
                                },
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                            ) {
                                Text("Cancelar", color = Danger, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    } else {
                        Button(
                            onClick = {
                                val trimmedId = driveClientId.trim()
                                if (trimmedId.isEmpty()) {
                                    driveErrorMessage = "Informe o Client ID do Google Cloud Console."
                                    return@Button
                                }
                                driveErrorMessage = null
                                driveStatusMessage = null
                                isDriveLoading = true
                                driveSignInJob = scope.launch {
                                    var server: ServerSocket? = null
                                    try {
                                        server = withContext(Dispatchers.IO) { GoogleDriveAuth.listen() }
                                        driveServerListener = server
                                        val auth = GoogleDriveAuth()
                                        val secret = driveClientSecret.trim().ifEmpty { null }
                                        driveSettings.clientId = trimmedId
                                        driveSettings.clientSecret = driveClientSecret.trim()

                                        val pending = auth.start(server.localPort, trimmedId, secret)
                                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(pending.url))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        context.startActivity(browserIntent)

                                        val callback = withContext(Dispatchers.IO) {
                                            server.use { GoogleDriveAuth.awaitCallback(it) }
                                        }
                                        withContext(Dispatchers.Main) {
                                            ProcessLifecycleOwner.get().lifecycle.withStateAtLeast(Lifecycle.State.STARTED) {}
                                        }
                                        val tokens = withContext(Dispatchers.IO) {
                                            auth.finish(pending, callback)
                                        }
                                        driveSettings.tokens = tokens
                                        isDriveConnected = true
                                        driveEmail = tokens.email ?: "Conectado"
                                        driveFolderName = driveSettings.selectedFolderName
                                        driveFolderId = driveSettings.selectedFolderId
                                        driveStatusMessage = "Google Drive conectado com sucesso!"
                                    } catch (e: SocketTimeoutException) {
                                        driveErrorMessage = "Tempo limite esgotado para o login. Tente novamente."
                                    } catch (e: ProviderError) {
                                        driveErrorMessage = e.userMessage
                                    } catch (e: java.net.SocketException) {
                                        // Login cancelado pelo usuário
                                    } catch (e: Exception) {
                                        driveErrorMessage = "Falha ao conectar: " + (e.message ?: e.javaClass.simpleName)
                                    } finally {
                                        try { server?.close() } catch (_: Exception) {}
                                        driveServerListener = null
                                        isDriveLoading = false
                                    }
                                }
                            },
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
                            Text("Conectar Google Drive", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Field)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Conta conectada", color = TextTertiary, fontSize = 12.sp)
                            Text(
                                driveEmail.ifBlank { "Conectado" },
                                color = TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Text("✓", color = Positive, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Field)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Pasta selecionada", color = TextTertiary, fontSize = 12.sp)
                            Text(
                                driveFolderName,
                                color = TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                currentBrowseId = "root"
                                currentBrowseName = "Meu Drive (raiz)"
                                navStack = emptyList()
                                newFolderNameInput = ""
                                showFolderDialog = true
                            },
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Accent,
                                contentColor = TextPrimary,
                            ),
                        ) {
                            Text("Escolher pasta", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }

                        Button(
                            onClick = {
                                driveSettings.clearAuth()
                                isDriveConnected = false
                                driveEmail = ""
                                driveStatusMessage = "Google Drive desconectado."
                                driveErrorMessage = null
                            },
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Field,
                                contentColor = Danger,
                            ),
                        ) {
                            Text("Desconectar", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Field)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                "Enviar automaticamente ao concluir tradução",
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                "Upload direto para o Drive assim que o arquivo finalizar.",
                                color = TextSecondary,
                                fontSize = 12.sp,
                            )
                        }
                        Switch(
                            checked = driveAutoUpload,
                            onCheckedChange = { checked ->
                                driveSettings.autoUploadOnComplete = checked
                                driveAutoUpload = checked
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = TextPrimary,
                                checkedTrackColor = Accent,
                                uncheckedThumbColor = TextSecondary,
                                uncheckedTrackColor = Stroke,
                            ),
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .semantics {
                                    contentDescription = "Enviar automaticamente ao concluir tradução"
                                },
                        )
                    }
                }
            }

            driveStatusMessage?.let { msg ->
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

            driveErrorMessage?.let { err ->
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

        // Card Atualizações (GitHub Releases)
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
                "Atualizações",
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Versão instalada: ${version.ifBlank { "desconhecida" }} · GitHub",
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )

            Button(
                onClick = {
                    scope.launch {
                        isCheckingUpdate = true
                        updateResult = null
                        try {
                            updateResult = withContext(Dispatchers.IO) {
                                updateChecker.checkForUpdate(version)
                            }
                        } finally {
                            isCheckingUpdate = false
                        }
                    }
                },
                enabled = !isCheckingUpdate,
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
                if (isCheckingUpdate) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = TextSecondary,
                            strokeWidth = 2.dp,
                        )
                        Text("Verificando no GitHub...", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Text("Procurar atualização no GitHub", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            fun openExternalUrl(url: String) {
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Exception) {
                    updateResult = UpdateCheckResult.Failed("Não foi possível abrir o navegador.")
                }
            }

            when (val r = updateResult) {
                is UpdateCheckResult.UpToDate -> {
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
                        Text(
                            "Você já está na versão mais recente (${r.latestVersion}).",
                            color = Positive,
                            fontSize = 13.sp,
                        )
                    }
                }
                is UpdateCheckResult.UpdateAvailable -> {
                    Spacer(Modifier.height(14.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(AccentSoft)
                            .border(1.dp, Accent, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "Nova versão disponível: ${r.release.tagName}",
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (r.release.releaseNotes.isNotBlank()) {
                            Text(
                                r.release.releaseNotes,
                                color = TextSecondary,
                                fontSize = 12.sp,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (r.release.apkDownloadUrl != null) {
                            val apkUrl = r.release.apkDownloadUrl!!
                            Button(
                                onClick = { openExternalUrl(apkUrl) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 48.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Accent,
                                    contentColor = TextPrimary,
                                ),
                            ) {
                                Text("Baixar APK (${r.release.tagName})", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Button(
                                onClick = { openExternalUrl(r.release.htmlUrl) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 48.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Field,
                                    contentColor = TextPrimary,
                                ),
                            ) {
                                Text("Ver no GitHub", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            }
                        } else {
                            Button(
                                onClick = { openExternalUrl(r.release.htmlUrl) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 48.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Accent,
                                    contentColor = TextPrimary,
                                ),
                            ) {
                                Text("Abrir release no GitHub", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                is UpdateCheckResult.Failed -> {
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
                        Text(r.message, color = Danger, fontSize = 13.sp)
                    }
                }
                null -> Unit
            }
        }

        Text(
            "Bookconverter $version",
            color = TextTertiary,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 24.dp),
        )
        Spacer(Modifier.navigationBarsPadding().height(80.dp))
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

    if (showFolderDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!isCreatingFolder) showFolderDialog = false
            },
            containerColor = Surface,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            shape = RoundedCornerShape(16.dp),
            title = {
                Column {
                    Text(
                        "Escolher pasta no Drive",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                        color = TextPrimary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Pasta atual: $currentBrowseName",
                        color = Accent,
                        fontSize = 13.sp,
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (currentBrowseId != "root") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Field)
                                .clickable {
                                    if (navStack.isNotEmpty()) {
                                        val prev = navStack.last()
                                        navStack = navStack.dropLast(1)
                                        val parts = prev.split(":::", limit = 2)
                                        currentBrowseId = parts[0]
                                        currentBrowseName = parts.getOrElse(1) { "Meu Drive (raiz)" }
                                    } else {
                                        currentBrowseId = "root"
                                        currentBrowseName = "Meu Drive (raiz)"
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("← Voltar", color = Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            TextButton(
                                onClick = {
                                    navStack = emptyList()
                                    currentBrowseId = "root"
                                    currentBrowseName = "Meu Drive (raiz)"
                                },
                                modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                            ) {
                                Text("Ir para raiz", color = TextTertiary, fontSize = 12.sp)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }

                    if (isFoldersLoading) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Accent,
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("Carregando pastas...", color = TextSecondary, fontSize = 13.sp)
                        }
                    } else if (foldersError != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Danger.copy(alpha = 0.15f))
                                .padding(12.dp),
                        ) {
                            Text(foldersError ?: "", color = Danger, fontSize = 13.sp)
                            Spacer(Modifier.height(8.dp))
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        isFoldersLoading = true
                                        foldersError = null
                                        try {
                                            foldersList = driveSettings.listFolders(currentBrowseId)
                                        } catch (e: Exception) {
                                            foldersError = e.message ?: "Erro ao carregar pastas"
                                        } finally {
                                            isFoldersLoading = false
                                        }
                                    }
                                },
                                modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                            ) {
                                Text("Tentar novamente", color = Accent, fontSize = 13.sp)
                            }
                        }
                    } else {
                        if (foldersList.isEmpty()) {
                            Text(
                                "Nenhuma subpasta encontrada.",
                                color = TextTertiary,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        } else {
                            Text(
                                "Subpastas (toque para abrir):",
                                color = TextSecondary,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                foldersList.forEach { folder ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .defaultMinSize(minHeight = 48.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Field)
                                            .clickable {
                                                navStack = navStack + "$currentBrowseId:::$currentBrowseName"
                                                currentBrowseId = folder.id
                                                currentBrowseName = folder.name
                                            }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text("📁", fontSize = 16.sp)
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            folder.name,
                                            color = TextPrimary,
                                            fontSize = 14.sp,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text("›", color = TextSecondary, fontSize = 16.sp)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Text("Criar nova subpasta:", color = TextSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = newFolderNameInput,
                            onValueChange = { newFolderNameInput = it },
                            placeholder = { Text("Nome da pasta", fontSize = 13.sp) },
                            singleLine = true,
                            colors = fieldColors(),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp),
                        )
                        Button(
                            onClick = {
                                val name = newFolderNameInput.trim()
                                if (name.isNotEmpty()) {
                                    scope.launch {
                                        isCreatingFolder = true
                                        foldersError = null
                                        try {
                                            val created = driveSettings.createFolder(name, currentBrowseId)
                                            driveSettings.setFolder(created.id, created.name)
                                            driveFolderName = created.name
                                            driveFolderId = created.id
                                            newFolderNameInput = ""
                                            showFolderDialog = false
                                        } catch (e: Exception) {
                                            foldersError = e.message ?: "Falha ao criar pasta"
                                        } finally {
                                            isCreatingFolder = false
                                        }
                                    }
                                }
                            },
                            enabled = newFolderNameInput.isNotBlank() && !isCreatingFolder,
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Accent,
                                contentColor = TextPrimary,
                                disabledContainerColor = Field,
                                disabledContentColor = TextTertiary,
                            ),
                        ) {
                            if (isCreatingFolder) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = TextPrimary,
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Text("+ Criar", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        driveSettings.setFolder(currentBrowseId, currentBrowseName)
                        driveFolderName = currentBrowseName
                        driveFolderId = currentBrowseId
                        showFolderDialog = false
                    },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Accent,
                        contentColor = TextPrimary,
                    ),
                ) {
                    Text("Selecionar esta pasta", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showFolderDialog = false },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                    Text("Cancelar", color = TextSecondary)
                }
            },
        )
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Accent,
    unfocusedBorderColor = Stroke,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedLabelColor = TextSecondary,
    unfocusedLabelColor = TextSecondary,
    focusedPlaceholderColor = TextSecondary,
    unfocusedPlaceholderColor = TextSecondary,
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
)
