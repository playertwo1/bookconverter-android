package io.github.playertwo1.bookconverter.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.playertwo1.bookconverter.core.provider.DriveFolder
import io.github.playertwo1.bookconverter.core.provider.DriveUploadedFile
import io.github.playertwo1.bookconverter.core.provider.ErrorKind
import io.github.playertwo1.bookconverter.core.provider.GoogleDriveApi
import io.github.playertwo1.bookconverter.core.provider.GoogleDriveAuth
import io.github.playertwo1.bookconverter.core.provider.GoogleDriveTokens
import io.github.playertwo1.bookconverter.core.provider.ProviderError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Settings and credentials for Google Drive backup / sync.
 * Tokens and client secrets are AES-GCM encrypted in Keystore and stored in private SharedPreferences.
 * Zero tokens or secrets are ever logged.
 */
class GoogleDriveSettings(
    context: Context,
    private val auth: GoogleDriveAuth = GoogleDriveAuth(),
    private val api: GoogleDriveApi = GoogleDriveApi(),
) {
    private val prefs = context.applicationContext.getSharedPreferences("google_drive", Context.MODE_PRIVATE)
    private val keyLock = Any()

    var tokens: GoogleDriveTokens?
        get() = decrypt(KEY_TOKENS)?.let { runCatching { GoogleDriveTokens.fromJson(it) }.getOrNull() }
        set(v) {
            if (v == null) prefs.edit().remove(KEY_TOKENS).commit()
            else prefs.edit().putString(KEY_TOKENS, encrypt(v.toJson())).commit()
        }

    var clientId: String
        get() = prefs.getString(KEY_CLIENT_ID, "") ?: ""
        set(v) = prefs.edit().putString(KEY_CLIENT_ID, v.trim()).apply()

    var clientSecret: String
        get() = decrypt(KEY_CLIENT_SECRET) ?: prefs.getString(KEY_CLIENT_SECRET, "") ?: ""
        set(v) {
            val trimmed = v.trim()
            if (trimmed.isEmpty()) {
                prefs.edit().remove(KEY_CLIENT_SECRET).apply()
            } else {
                prefs.edit().putString(KEY_CLIENT_SECRET, encrypt(trimmed)).apply()
            }
        }

    var selectedFolderId: String
        get() = prefs.getString(KEY_FOLDER_ID, "root") ?: "root"
        set(v) = prefs.edit().putString(KEY_FOLDER_ID, v).apply()

    var selectedFolderName: String
        get() = prefs.getString(KEY_FOLDER_NAME, "Meu Drive (raiz)") ?: "Meu Drive (raiz)"
        set(v) = prefs.edit().putString(KEY_FOLDER_NAME, v).apply()

    var autoUploadOnComplete: Boolean
        get() = prefs.getBoolean(KEY_AUTO_UPLOAD, false)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_UPLOAD, v).apply()

    val isConnected: Boolean
        get() = tokens != null

    fun setFolder(id: String, name: String) {
        prefs.edit()
            .putString(KEY_FOLDER_ID, id)
            .putString(KEY_FOLDER_NAME, name)
            .apply()
    }

    fun clearAuth() {
        tokens = null
    }

    suspend fun validAccessToken(): String? = withContext(Dispatchers.IO) {
        synchronized(REFRESH_LOCK) {
            val t = tokens ?: return@withContext null
            if (!t.expiresSoon()) return@withContext t.accessToken
            try {
                val refreshed = auth.refresh(t)
                tokens = refreshed
                refreshed.accessToken
            } catch (e: ProviderError) {
                if (e.kind == ErrorKind.SIGNED_OUT || e.status == 400 || e.status == 401) {
                    clearAuth()
                }
                null
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun listFolders(parentId: String = "root"): List<DriveFolder> = withContext(Dispatchers.IO) {
        val token = validAccessToken() ?: throw ProviderError(401, "Google Drive não conectado", ErrorKind.SIGNED_OUT)
        api.listFolders(token, parentId)
    }

    suspend fun createFolder(name: String, parentId: String = "root"): DriveFolder = withContext(Dispatchers.IO) {
        val token = validAccessToken() ?: throw ProviderError(401, "Google Drive não conectado", ErrorKind.SIGNED_OUT)
        api.createFolder(token, name, parentId)
    }

    suspend fun uploadToConfiguredFolder(fileName: String, mimeType: String, bytes: ByteArray): DriveUploadedFile = withContext(Dispatchers.IO) {
        val token = validAccessToken() ?: throw ProviderError(401, "Google Drive não conectado", ErrorKind.SIGNED_OUT)
        val folderId = selectedFolderId.takeIf { it.isNotBlank() && it != "root" }
        api.uploadFile(token, folderId, fileName, mimeType, bytes)
    }

    private fun decrypt(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return try {
            val (iv, ct) = stored.split(':').map { Base64.decode(it, Base64.NO_WRAP) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) {
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

    private fun secretKey(): SecretKey = synchronized(keyLock) {
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
        const val ALIAS = "google-drive-tokens"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_TOKENS = "gdrive.tokens"
        const val KEY_CLIENT_ID = "gdrive.clientId"
        const val KEY_CLIENT_SECRET = "gdrive.clientSecret"
        const val KEY_FOLDER_ID = "gdrive.selectedFolderId"
        const val KEY_FOLDER_NAME = "gdrive.selectedFolderName"
        const val KEY_AUTO_UPLOAD = "gdrive.autoUploadOnComplete"
        val REFRESH_LOCK = Any()
    }
}
