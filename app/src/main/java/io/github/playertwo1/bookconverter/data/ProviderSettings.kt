package io.github.playertwo1.bookconverter.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.playertwo1.bookconverter.core.provider.ChatGptAuth
import io.github.playertwo1.bookconverter.core.provider.ChatGptClient
import io.github.playertwo1.bookconverter.core.provider.ChatGptTokens
import io.github.playertwo1.bookconverter.core.provider.ErrorKind
import io.github.playertwo1.bookconverter.core.provider.Provider
import io.github.playertwo1.bookconverter.core.provider.ProviderClient
import io.github.playertwo1.bookconverter.core.provider.ProviderError
import io.github.playertwo1.bookconverter.core.provider.providerClient
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Per-provider settings. API keys are AES-GCM encrypted with a non-exportable Android Keystore key and kept
 * in private SharedPreferences, never in Room and never logged. Everything else here is not secret.
 */
class ProviderSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("providers", Context.MODE_PRIVATE)

    /** "Usable": an API key, or for CHATGPT a signed-in session. */
    fun hasKey(p: Provider) = prefs.contains(if (p == Provider.CHATGPT) OAUTH else "key.$p")

    /** Null when unset, or when the Keystore key is gone (e.g. data restored on another device): the user re-enters it. */
    fun key(p: Provider): String? = decrypt("key.$p")

    fun setKey(p: Provider, key: String?) {
        val k = key?.trim().orEmpty()
        if (k.isEmpty()) prefs.edit().remove("key.$p").remove("ok.$p").apply()
        else prefs.edit().putString("key.$p", encrypt(k)).remove("ok.$p").apply()
    }

    fun chatGptTokens(): ChatGptTokens? = decrypt(OAUTH)?.let { runCatching { ChatGptTokens.fromJson(it) }.getOrNull() }

    fun setChatGptTokens(t: ChatGptTokens?) {
        // commit(): a rotated refresh token must reach disk before the old one is forgotten.
        if (t == null) prefs.edit().remove(OAUTH).remove("ok.${Provider.CHATGPT}").commit()
        else prefs.edit().putString(OAUTH, encrypt(t.toJson())).commit()
    }

    /** Stable opaque id of this install for ext_agent_host_id (no user data in it). */
    val hostId: String
        get() = prefs.getString("chatgpt.host", null) ?: ("urn:uuid:" + UUID.randomUUID()).also { prefs.edit().putString("chatgpt.host", it).apply() }

    /** Issued client_id kept after sign-out, so the next sign-in reauthorizes instead of registering again. */
    var chatGptClientId: String?
        get() = prefs.getString("chatgpt.client", null)
        set(v) = prefs.edit().putString("chatgpt.client", v).apply()

    /** Access token for the next request, refreshed (and persisted) when about to expire. */
    fun chatGptAccessToken(): String = synchronized(REFRESH_LOCK) {
        val t = chatGptTokens() ?: throw ProviderError(401, "Sem sessão do ChatGPT", ErrorKind.SIGNED_OUT)
        if (!t.expiresSoon()) return t.accessToken
        try {
            ChatGptAuth().refresh(t).also(::setChatGptTokens).accessToken
        } catch (e: ProviderError) {
            if (e.kind == ErrorKind.SIGNED_OUT) setChatGptTokens(null) // terminal refresh error: sign in again
            throw e
        }
    }

    private fun decrypt(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return try {
            val (iv, ct) = stored.split(':').map { Base64.decode(it, Base64.NO_WRAP) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            prefs.edit().remove(name).apply()
            null
        }
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    /** Gateway base URL (e.g. Cloudflare AI Gateway); null = the provider's own endpoint. */
    fun baseUrl(p: Provider): String? = prefs.getString("base.$p", null)

    /** Returns false (and keeps the old value) when [url] is not https. */
    fun setBaseUrl(p: Provider, url: String?): Boolean {
        val u = url?.trim()?.trimEnd('/').orEmpty()
        if (u.isEmpty()) {
            prefs.edit().remove("base.$p").remove("ok.$p").apply()
            return true
        }
        if (!u.startsWith("https://") || u.length <= "https://".length) return false
        prefs.edit().putString("base.$p", u).remove("ok.$p").apply()
        return true
    }

    /** Model count from the last successful "verificar conexão"; null when never verified since the key/URL changed. */
    fun verifiedModels(p: Provider): Int? = prefs.getInt("ok.$p", -1).takeIf { it >= 0 }
    fun setVerified(p: Provider, models: Int) = prefs.edit().putInt("ok.$p", models).apply()

    fun consented(p: Provider) = prefs.getBoolean("consent.$p", false)
    fun setConsented(p: Provider) = prefs.edit().putBoolean("consent.$p", true).apply()

    /** Effort levels this model refused (AC06), so the screen shows them as unavailable instead of sending them again. */
    fun rejectedEfforts(p: Provider, model: String): Set<String> = prefs.getStringSet("noeffort.$p.$model", null).orEmpty()
    fun rejectEffort(p: Provider, model: String, effort: String) =
        prefs.edit().putStringSet("noeffort.$p.$model", rejectedEfforts(p, model) + effort).apply()

    var lastProvider: Provider?
        get() = prefs.getString("last.provider", null)?.let { runCatching { Provider.valueOf(it) }.getOrNull() }
        set(v) = prefs.edit().putString("last.provider", v?.name).apply()

    fun lastModel(p: Provider): String? = prefs.getString("last.model.$p", null)
    fun setLastModel(p: Provider, model: String) = prefs.edit().putString("last.model.$p", model).apply()

    fun client(p: Provider): ProviderClient? =
        if (p == Provider.CHATGPT) chatGptTokens()?.let { ChatGptClient(::chatGptAccessToken) }
        else key(p)?.let { providerClient(p, it, baseUrl(p)) }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val ALIAS = "provider-api-keys"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val OAUTH = "oauth.CHATGPT"
        /** One refresh at a time: the refresh token rotates, a parallel refresh would burn it. */
        val REFRESH_LOCK = Any()
    }
}

/** Shown in the UI and in the consent dialog. */
val Provider.label get() = when (this) {
    Provider.OPENAI -> "OpenAI (chave de API)"
    Provider.CHATGPT -> "ChatGPT (seu plano)"
    Provider.ANTHROPIC -> "Anthropic / Claude"
    Provider.GEMINI -> "Google Gemini"
}

val Provider.shortLabel get() = when (this) {
    Provider.OPENAI -> "OpenAI"
    Provider.CHATGPT -> "ChatGPT"
    Provider.ANTHROPIC -> "Claude"
    Provider.GEMINI -> "Gemini"
}

/** Default endpoint, shown as the hint of the base-URL field. */
val Provider.defaultBase get() = when (this) {
    Provider.OPENAI, Provider.CHATGPT -> "https://api.openai.com/v1"
    Provider.ANTHROPIC -> "https://api.anthropic.com"
    Provider.GEMINI -> "https://generativelanguage.googleapis.com/v1beta"
}

val Provider.privacyUrl get() = when (this) {
    Provider.OPENAI, Provider.CHATGPT -> "https://openai.com/policies/row-privacy-policy/"
    Provider.ANTHROPIC -> "https://www.anthropic.com/legal/privacy"
    Provider.GEMINI -> "https://ai.google.dev/gemini-api/terms"
}
