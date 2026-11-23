package io.github.playertwo1.bookconverter.ui.verify

import android.app.Application
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface as M3Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.playertwo1.bookconverter.core.epub.Verification
import io.github.playertwo1.bookconverter.data.Book
import io.github.playertwo1.bookconverter.data.Series
import io.github.playertwo1.bookconverter.data.suggestSeriesName
import io.github.playertwo1.bookconverter.ui.PtBr
import io.github.playertwo1.bookconverter.ui.count
import io.github.playertwo1.bookconverter.ui.languageLabel
import io.github.playertwo1.bookconverter.ui.sizeLabel
import io.github.playertwo1.bookconverter.ui.theme.Accent
import io.github.playertwo1.bookconverter.ui.theme.Background
import io.github.playertwo1.bookconverter.ui.theme.Danger
import io.github.playertwo1.bookconverter.ui.theme.Field
import io.github.playertwo1.bookconverter.ui.theme.Positive
import io.github.playertwo1.bookconverter.ui.theme.Serif
import io.github.playertwo1.bookconverter.ui.theme.Stroke
import io.github.playertwo1.bookconverter.ui.theme.Surface
import io.github.playertwo1.bookconverter.ui.theme.TextPrimary
import io.github.playertwo1.bookconverter.ui.theme.TextSecondary

@Composable
fun VerificationScreen(bookId: Long, onBack: () -> Unit, onStartTranslation: (Long) -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val vm: VerificationViewModel = viewModel(key = "verify-$bookId") { VerificationViewModel(app, bookId) }
    val state by vm.state.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(Background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(48.dp)
                    .clickable(role = Role.Button, onClickLabel = "Voltar", onClick = onBack)
                    .semantics { contentDescription = "Voltar" },
                contentAlignment = Alignment.Center,
            ) {
                Text("‹", fontSize = 28.sp, color = TextPrimary)
            }
            Text("Verificação do arquivo", fontSize = 17.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold)
        }
        when (val s = state) {
            is VerifyState.Loading -> Column(
                Modifier.fillMaxWidth().padding(top = 96.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = Accent)
                Spacer(Modifier.height(12.dp))
                Text("Verificando…", color = TextSecondary)
            }
            is VerifyState.Failed -> Text(s.message, color = Danger, modifier = Modifier.padding(top = 24.dp))
            is VerifyState.Ready -> ReadyContent(
                book = s.book,
                v = s.v,
                series = s.series,
                onStartTranslation = onStartTranslation,
                onCreateSeries = vm::createSeriesForBook,
            )
        }
    }
}

@Composable
private fun ReadyContent(
    book: Book,
    v: Verification,
    series: Series?,
    onStartTranslation: (Long) -> Unit,
    onCreateSeries: (String, String?) -> Unit,
) {
    var showCreateSeriesDialog by rememberSaveable(book.id) { mutableStateOf(false) }
    Spacer(Modifier.height(16.dp))
    Card {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Accent))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                book.title,
                color = TextPrimary,
                fontSize = 17.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(book.author, book.format, sizeLabel(book.sizeBytes), languageLabel(book.language))
                    .joinToString(" · "),
                color = TextSecondary,
                fontSize = 13.sp,
            )
        }
    }
    Text(
        "Verificações",
        color = TextPrimary,
        fontFamily = Serif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        modifier = Modifier.padding(top = 24.dp, bottom = 12.dp),
    )
    CheckCard(
        ok = true,
        title = "Formato reconhecido",
        detail = "EPUB ${v.version.orEmpty()} sem proteção".replace("  ", " "),
    )
    CheckCard(
        ok = v.tocEntries > 0 && v.tocBroken == 0,
        title = "Estrutura detectada",
        detail = "${count(v.spineDocs)} documentos na ordem de leitura · " +
            (if (v.tocEntries > 0) "índice com ${count(v.tocEntries)} entradas" else "sem índice") +
            (if (v.tocBroken > 0) " · ${v.tocBroken} link(s) quebrado(s)" else ""),
    )
    CheckCard(
        ok = v.coverage >= Verification.MIN_COVERAGE,
        title = "Texto extraível",
        detail = "${count(v.blocks)} blocos · ${count(v.chars)} caracteres · cobertura " +
            String.format(PtBr, "%.1f%%", v.coverage * 100),
    )
    CheckCard(
        ok = true,
        title = "Direitos de acesso",
        detail = if (v.obfuscatedFonts) "Sem DRM · fontes ofuscadas (permitido)" else "Sem DRM detectado",
    )
    if (series != null) {
        CheckCard(
            ok = true,
            title = "Série vinculada",
            detail = listOfNotNull(series.name, series.author?.takeIf { it.isNotBlank() }).joinToString(" · "),
        )
    }
    Button(
        onClick = { onStartTranslation(book.id) },
        modifier = Modifier.padding(top = 16.dp).fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = TextPrimary),
    ) {
        Text("Nova tradução", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
    if (series == null) {
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { showCreateSeriesDialog = true },
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Stroke),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
        ) {
            Text("Criar série com este livro", fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
    Spacer(Modifier.height(24.dp))

    if (showCreateSeriesDialog) {
        CreateBookSeriesDialog(
            initialName = suggestSeriesName(book.title),
            initialAuthor = book.author.orEmpty(),
            onConfirm = { name, author ->
                showCreateSeriesDialog = false
                onCreateSeries(name, author)
            },
            onDismiss = { showCreateSeriesDialog = false },
        )
    }
}

@Composable
private fun CreateBookSeriesDialog(
    initialName: String,
    initialAuthor: String,
    onConfirm: (String, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    var author by rememberSaveable(initialAuthor) { mutableStateOf(initialAuthor) }
    var showError by rememberSaveable { mutableStateOf(false) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Accent,
        unfocusedBorderColor = Stroke,
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        focusedLabelColor = TextSecondary,
        unfocusedLabelColor = TextSecondary,
        focusedContainerColor = Field,
        unfocusedContainerColor = Field,
        cursorColor = Accent,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = {
            Text(
                text = "Nova série",
                fontFamily = Serif,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        showError = false
                    },
                    label = { Text("Nome da série") },
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                    isError = showError && name.isBlank(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text("Autor (opcional)") },
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (showError) {
                    Text(
                        text = "O nome da série é obrigatório.",
                        color = Danger,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        onConfirm(name.trim(), author.trim().ifEmpty { null })
                    } else {
                        showError = true
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) {
                Text("Criar")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary),
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) {
                Text("Cancelar")
            }
        },
    )
}

@Composable
private fun CheckCard(ok: Boolean, title: String, detail: String) {
    Card(Modifier.padding(bottom = 8.dp).semantics(mergeDescendants = true) {}) {
        // The symbol differs too, so the state does not rely on colour alone.
        Text(
            if (ok) "✓" else "!",
            color = if (ok) Positive else Danger,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = TextSecondary, fontSize = 13.sp)
        }
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    M3Surface(
        modifier = modifier.fillMaxWidth(),
        color = Surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Stroke),
    ) {
        Row(
            Modifier.defaultMinSize(minHeight = 64.dp).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}
