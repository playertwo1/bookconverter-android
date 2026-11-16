package io.github.playertwo1.bookconverter.ui.settings

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.withStateAtLeast
import io.github.playertwo1.bookconverter.core.provider.ChatGptAuth
import io.github.playertwo1.bookconverter.core.provider.Provider
import io.github.playertwo1.bookconverter.core.provider.ProviderError
import io.github.playertwo1.bookconverter.data.ProviderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.ServerSocket
import java.net.SocketTimeoutException

sealed interface Check {
    data object Idle : Check
    data object Running : Check
    data class Ok(val models: Int) : Check
    data class Failed(val message: String) : Check
}

/** What the screen may show about one provider. Keys and tokens never leave [ProviderSettings]. */
data class ProviderRow(
    val provider: Provider,
    val hasKey: Boolean,
    val baseUrl: String?,
    val check: Check,
    /** CHATGPT only: the signed-in account's email. */
    val account: String? = null,
    /** CHATGPT only: waiting for the browser to come back to the loopback. */
    val signingIn: Boolean = false,
)

class ProvidersViewModel(private val app: Application) : AndroidViewModel(app) {
    private val settings = ProviderSettings(app)
    private val _rows = MutableStateFlow(Provider.entries.map { row(it) })
    val rows: StateFlow<List<ProviderRow>> = _rows.asStateFlow()
    private var signIn: Job? = null
    private var listener: ServerSocket? = null

    private fun row(p: Provider, check: Check? = null, signingIn: Boolean = false) = ProviderRow(
        p, settings.hasKey(p), settings.baseUrl(p),
        check ?: settings.verifiedModels(p)?.let { Check.Ok(it) } ?: Check.Idle,
        account = if (p == Provider.CHATGPT) settings.chatGptTokens()?.let { it.email ?: "conta conectada" } else null,
        signingIn = signingIn,
    )

    private fun refresh(p: Provider, check: Check? = null, signingIn: Boolean = false) =
        _rows.update { rows -> rows.map { if (it.provider == p) row(p, check, signingIn) else it } }

    /** Blank [key] keeps the saved key (so the user can change only the URL). Returns an error message or null. */
    fun save(p: Provider, key: String, baseUrl: String): String? {
        if (!settings.setBaseUrl(p, baseUrl)) return "A URL base precisa começar com https://"
        if (key.isNotBlank()) settings.setKey(p, key)
        refresh(p)
        return null
    }

    fun removeKey(p: Provider) {
        settings.setKey(p, null)
        refresh(p)
    }

    /** "Verificar conexão": lists models; a cheap call that sends no book text. */
    fun verify(p: Provider) {
        refresh(p, Check.Running)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val client = settings.client(p) ?: return@withContext Check.Failed("Nada salvo para este provedor.")
                    Check.Ok(client.listModels().size).also { settings.setVerified(p, it.models) }
                } catch (e: ProviderError) {
                    Check.Failed(e.userMessage)
                } catch (e: Exception) {
                    Check.Failed("Falha: " + (e.message ?: e.javaClass.simpleName))
                }
            }
            refresh(p, result)
        }
    }

    /**
     * "Sign in with ChatGPT": loopback listener first, then the browser. If Android kills the app while the
     * browser is open the listener dies with it; the user just taps "Entrar" again.
     */
    fun signInChatGpt() {
        if (signIn?.isActive == true) return
        val p = Provider.CHATGPT
        refresh(p, signingIn = true)
        signIn = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val server = try { ChatGptAuth.listen() } catch (e: Exception) {
                    return@withContext Check.Failed("Não foi possível abrir a porta local do login: ${e.message}")
                }
                listener = server
                try {
                    val auth = ChatGptAuth()
                    val old = settings.chatGptTokens()
                    val pending = auth.start(server.localPort, settings.hostId, "Bookconverter", settings.chatGptClientId, old?.idToken, old?.email)
                    app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pending.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    val callback = server.use { ChatGptAuth.awaitCallback(it) }
                    // The callback lands while the browser is in front, and Android 15+ blocks network for
                    // background apps: hold the code until the user is back in the app, then exchange it.
                    withContext(Dispatchers.Main) {
                        ProcessLifecycleOwner.get().lifecycle.withStateAtLeast(Lifecycle.State.STARTED) {}
                    }
                    val tokens = auth.finish(pending, callback)
                    settings.setChatGptTokens(tokens)
                    settings.chatGptClientId = tokens.clientId
                    // Prove the plan route works right away (lists models, sends no book text).
                    val models = settings.client(p)!!.listModels().size
                    settings.setVerified(p, models)
                    Check.Ok(models)
                } catch (e: SocketTimeoutException) {
                    Check.Failed("O login não voltou a tempo. Tente de novo.")
                } catch (e: ProviderError) {
                    Check.Failed(e.userMessage)
                } catch (e: java.net.SocketException) {
                    Check.Idle // cancelled: the listener was closed
                } catch (e: Exception) {
                    Check.Failed("Falha no login: " + (e.message ?: e.javaClass.simpleName))
                } finally {
                    server.close()
                    listener = null
                }
            }
            refresh(p, result)
        }
    }

    fun cancelSignIn() {
        listener?.close()
        signIn?.cancel()
        refresh(Provider.CHATGPT)
    }

    /** Revokes the session at OpenAI (best effort) and forgets the tokens. */
    fun signOutChatGpt() {
        val tokens = settings.chatGptTokens()
        settings.setChatGptTokens(null)
        refresh(Provider.CHATGPT)
        if (tokens != null) viewModelScope.launch(Dispatchers.IO) { runCatching { ChatGptAuth().revoke(tokens) } }
    }

    override fun onCleared() {
        listener?.close()
    }
}
