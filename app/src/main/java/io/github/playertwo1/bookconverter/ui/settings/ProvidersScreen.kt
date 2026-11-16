package io.github.playertwo1.bookconverter.ui.settings

import android.app.Application
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface as M3Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.playertwo1.bookconverter.core.provider.Provider
import io.github.playertwo1.bookconverter.data.defaultBase
import io.github.playertwo1.bookconverter.data.label
import io.github.playertwo1.bookconverter.ui.count
import io.github.playertwo1.bookconverter.ui.theme.Accent
import io.github.playertwo1.bookconverter.ui.theme.Background
import io.github.playertwo1.bookconverter.ui.theme.Danger
import io.github.playertwo1.bookconverter.ui.theme.Field
import io.github.playertwo1.bookconverter.ui.theme.Positive
import io.github.playertwo1.bookconverter.ui.theme.Stroke
import io.github.playertwo1.bookconverter.ui.theme.Surface
import io.github.playertwo1.bookconverter.ui.theme.TextPrimary
import io.github.playertwo1.bookconverter.ui.theme.TextSecondary
import io.github.playertwo1.bookconverter.ui.theme.TextTertiary

@Composable
fun ProvidersScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val vm: ProvidersViewModel = viewModel { ProvidersViewModel(app) }
    val rows by vm.rows.collectAsStateWithLifecycle()
    // Stores the provider name only, so the open card survives recreation without holding any key.
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }

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
            Text("Provedores de IA", fontSize = 17.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(16.dp))
        rows.forEach { row ->
            val open = expanded == row.provider.name
            ProviderCard(
                row = row,
                open = open,
                onToggle = { expanded = if (open) null else row.provider.name },
                vm = vm,
            )
        }
        Card(Modifier.padding(bottom = 8.dp)) {
            Column {
                Text("Seleção de esforço", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Aparece na nova tradução e só fica disponível quando o modelo aceita o controle. " +
                        "Se o modelo recusar, o app traduz sem ele e marca o nível como indisponível.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Card(Modifier.padding(bottom = 8.dp)) {
            Column {
                Text(
                    "Credenciais no armazenamento seguro",
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "As chaves ficam cifradas pelo Android Keystore neste aparelho. " +
                        "Não vão para o banco de dados, backups em texto nem logs.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ProviderCard(row: ProviderRow, open: Boolean, onToggle: () -> Unit, vm: ProvidersViewModel) {
    val p = row.provider
    Card(Modifier.padding(bottom = 8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .clickable(
                    role = Role.Button,
                    onClickLabel = if (open) "Recolher" else "Expandir",
                    onClick = onToggle,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (row.check is Check.Ok) Positive else Accent))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(p.label, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                val (line, color) = statusLine(row)
                Text(line, color = color, fontSize = 13.sp)
                row.baseUrl?.let { Text("Gateway: $it", color = TextTertiary, fontSize = 12.sp) }
            }
        }
        if (open) {
            if (p == Provider.CHATGPT) ChatGptFields(row, vm) else ProviderFields(row, vm)
        }
    }
}

@Composable
private fun ChatGptFields(row: ProviderRow, vm: ProvidersViewModel) {
    Spacer(Modifier.height(12.dp))
    Text(
        "Entre com a sua conta do ChatGPT (Go, Plus, Pro ou Business). As traduções usam a cota de Codex/trabalho " +
            "do seu plano, sem chave de API. O login abre no navegador; depois volte ao app.",
        color = TextSecondary,
        fontSize = 13.sp,
    )
    Spacer(Modifier.height(12.dp))
    when {
        row.signingIn -> {
            Text("Aguardando o login no navegador…", color = TextPrimary, fontSize = 15.sp)
            TextButton(onClick = vm::cancelSignIn) { Text("Cancelar", color = TextSecondary, fontSize = 15.sp) }
        }
        !row.hasKey -> AppButton("Entrar com ChatGPT", modifier = Modifier.fillMaxWidth()) { vm.signInChatGpt() }
        else -> {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton(
                    "Verificar conexão",
                    modifier = Modifier.weight(1f),
                    enabled = row.check !is Check.Running,
                    secondary = true,
                ) { vm.verify(Provider.CHATGPT) }
                AppButton("Entrar de novo", modifier = Modifier.weight(1f), secondary = true) { vm.signInChatGpt() }
            }
            TextButton(
                onClick = vm::signOutChatGpt,
                colors = ButtonDefaults.textButtonColors(contentColor = Danger),
            ) { Text("Sair da conta", fontSize = 15.sp) }
        }
    }
}

private fun statusLine(row: ProviderRow): Pair<String, Color> = when {
    row.signingIn -> "Aguardando o login no navegador…" to TextSecondary
    // A failed sign-in leaves no session; its message must win over the plain "não conectado".
    row.provider == Provider.CHATGPT && row.check is Check.Failed -> (row.check as Check.Failed).message to Danger
    row.provider == Provider.CHATGPT && !row.hasKey -> "Não conectado · entrar com a conta" to TextSecondary
    row.provider == Provider.CHATGPT -> "Conectado como ${row.account}" +
        ((row.check as? Check.Ok)?.let { " · ${count(it.models)} modelos" } ?: "") to TextSecondary
    !row.hasKey -> "Sem chave" to TextSecondary
    else -> when (val c = row.check) {
        Check.Idle -> "Chave salva · verificar conexão" to TextSecondary
        Check.Running -> "Verificando…" to TextSecondary
        is Check.Ok -> "Conectado · ${count(c.models)} modelos" to TextSecondary
        is Check.Failed -> c.message to Danger
    }
}

@Composable
private fun ProviderFields(row: ProviderRow, vm: ProvidersViewModel) {
    val p = row.provider
    // Typed key lives only here: plain remember, never rememberSaveable, so it stays out of saved instance state.
    var key by remember { mutableStateOf("") }
    var url by remember { mutableStateOf(row.baseUrl.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }

    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = key,
        onValueChange = { key = it },
        label = { Text("Chave de API") },
        placeholder = { Text(if (row.hasKey) "Salva — deixe em branco para manter" else "Cole sua chave") },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        singleLine = true,
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text("URL base (opcional)") },
        placeholder = { Text(p.defaultBase) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        singleLine = true,
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "Para Cloudflare AI Gateway. OpenAI e Gemini incluem a versão (/v1, /v1beta); Anthropic não.",
        color = TextTertiary,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 4.dp),
    )
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppButton("Salvar", modifier = Modifier.weight(1f)) {
            val err = vm.save(p, key, url)
            error = err
            if (err == null) key = ""
        }
        AppButton(
            "Verificar conexão",
            modifier = Modifier.weight(1f),
            enabled = row.hasKey && row.check !is Check.Running,
            secondary = true,
        ) { vm.verify(p) }
    }
    error?.let { Text(it, color = Danger, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
    if (row.hasKey) {
        TextButton(
            onClick = { vm.removeKey(p) },
            colors = ButtonDefaults.textButtonColors(contentColor = Danger),
        ) { Text("Remover chave", fontSize = 15.sp) }
    }
}

@Composable
private fun AppButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    secondary: Boolean = false,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (secondary) Field else Accent,
            contentColor = TextPrimary,
            disabledContainerColor = Field,
            disabledContentColor = TextTertiary,
        ),
    ) {
        // 15sp so both buttons share one row on a phone without wrapping.
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
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

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    M3Surface(
        modifier = modifier.fillMaxWidth(),
        color = Surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Stroke),
    ) {
        Column(Modifier.defaultMinSize(minHeight = 64.dp).padding(16.dp)) { content() }
    }
}
