package io.github.playertwo1.bookconverter.core.provider

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal fun defaultHttp(): OkHttpClient = http
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

/** Kept after Google Drive OAuth2 login. Secret: stored encrypted, never logged. */
data class GoogleDriveTokens(
  val clientId: String,
  val clientSecret: String?,
  val accessToken: String,
  val refreshToken: String,
  val expiresAt: Long,
  val email: String? = null,
) {
  /** Refresh early so a request never leaves with a token about to expire. */
  fun expiresSoon(now: Long = System.currentTimeMillis() / 1000): Boolean = now >= expiresAt - 60

  fun toJson(): String = buildJsonObject {
    put("clientId", clientId)
    clientSecret?.let { put("clientSecret", it) }
    put("accessToken", accessToken)
    put("refreshToken", refreshToken)
    put("expiresAt", expiresAt)
    email?.let { put("email", it) }
  }.toString()

  /** Redacted on purpose: data class toString must never expose tokens. */
  override fun toString(): String = "GoogleDriveTokens(email=$email, expiresAt=$expiresAt)"

  companion object {
    fun fromJson(s: String): GoogleDriveTokens {
      val o = json.parseToJsonElement(s).jsonObject
      fun f(k: String) = o[k]!!.jsonPrimitive.content
      return GoogleDriveTokens(
        clientId = f("clientId"),
        clientSecret = o["clientSecret"]?.jsonPrimitive?.content,
        accessToken = f("accessToken"),
        refreshToken = f("refreshToken"),
        expiresAt = o["expiresAt"]!!.jsonPrimitive.content.toLong(),
        email = o["email"]?.jsonPrimitive?.content,
      )
    }
  }
}

/** One sign-in attempt; lives only in memory between opening the browser and the loopback callback. */
class PendingGoogleSignIn(
  val url: String,
  val redirectUri: String,
  val clientId: String,
  val clientSecret: String?,
  val state: String,
  val verifier: String,
)

/**
 * Google Drive OAuth2 with PKCE and loopback callback on 127.0.0.1.
 */
class GoogleDriveAuth(
  private val authEndpoint: String = "https://accounts.google.com/o/oauth2/v2/auth",
  private val tokenEndpoint: String = "https://oauth2.googleapis.com/token",
  private val userInfoEndpoint: String = "https://www.googleapis.com/oauth2/v2/userinfo",
  private val http: OkHttpClient = defaultHttp(),
  private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {

  fun start(port: Int, clientId: String, clientSecret: String? = null): PendingGoogleSignIn {
    val redirectUri = "http://127.0.0.1:$port/auth/callback"
    val state = random()
    val verifier = random(48)
    val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    val params = listOf(
      "client_id" to clientId,
      "redirect_uri" to redirectUri,
      "response_type" to "code",
      "scope" to SCOPE_DRIVE,
      "access_type" to "offline",
      "prompt" to "consent",
      "state" to state,
      "code_challenge" to challenge,
      "code_challenge_method" to "S256",
    )
    val query = params.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8").replace("+", "%20") }
    val separator = if (authEndpoint.contains("?")) "&" else "?"
    val url = "$authEndpoint$separator$query"
    return PendingGoogleSignIn(url, redirectUri, clientId, clientSecret, state, verifier)
  }

  fun finish(pending: PendingGoogleSignIn, callback: Map<String, String>): GoogleDriveTokens {
    if (callback["state"] != pending.state) {
      throw ProviderError(null, "Resposta de login com state inválido", ErrorKind.SIGNED_OUT)
    }
    callback["error"]?.let {
      throw ProviderError(null, "Login não concluído: $it", ErrorKind.SIGNED_OUT)
    }
    val code = callback["code"] ?: throw ProviderError(null, "Login sem código de autorização", ErrorKind.SIGNED_OUT)

    val formBuilder = FormBody.Builder()
      .add("grant_type", "authorization_code")
      .add("client_id", pending.clientId)
      .add("code", code)
      .add("code_verifier", pending.verifier)
      .add("redirect_uri", pending.redirectUri)
    if (!pending.clientSecret.isNullOrBlank()) {
      formBuilder.add("client_secret", pending.clientSecret)
    }

    val tokenReq = Request.Builder().url(tokenEndpoint).post(formBuilder.build()).build()
    val tokenRes = executeCall(tokenReq)

    val accessToken = tokenRes["access_token"]?.jsonPrimitive?.content
      ?: throw ProviderError(null, "Resposta sem access_token", ErrorKind.SIGNED_OUT)
    val refreshToken = tokenRes["refresh_token"]?.jsonPrimitive?.content
      ?: throw ProviderError(null, "Resposta sem refresh_token", ErrorKind.SIGNED_OUT)
    val expiresIn = tokenRes["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600L
    val expiresAt = now() + expiresIn

    val email = if (userInfoEndpoint.isNotBlank()) {
      try {
        val userReq = Request.Builder().url(userInfoEndpoint).header("Authorization", "Bearer $accessToken").get().build()
        val userRes = executeCall(userReq)
        userRes["email"]?.jsonPrimitive?.content
      } catch (_: Exception) {
        null
      }
    } else null

    return GoogleDriveTokens(
      clientId = pending.clientId,
      clientSecret = pending.clientSecret,
      accessToken = accessToken,
      refreshToken = refreshToken,
      expiresAt = expiresAt,
      email = email,
    )
  }

  fun refresh(tokens: GoogleDriveTokens): GoogleDriveTokens {
    val formBuilder = FormBody.Builder()
      .add("grant_type", "refresh_token")
      .add("client_id", tokens.clientId)
      .add("refresh_token", tokens.refreshToken)
    if (!tokens.clientSecret.isNullOrBlank()) {
      formBuilder.add("client_secret", tokens.clientSecret)
    }

    val req = Request.Builder().url(tokenEndpoint).post(formBuilder.build()).build()
    val res = executeCall(req)

    val accessToken = res["access_token"]?.jsonPrimitive?.content
      ?: throw ProviderError(null, "Resposta sem access_token", ErrorKind.SIGNED_OUT)
    val newRefreshToken = res["refresh_token"]?.jsonPrimitive?.content ?: tokens.refreshToken
    val expiresIn = res["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600L

    return tokens.copy(
      accessToken = accessToken,
      refreshToken = newRefreshToken,
      expiresAt = now() + expiresIn,
    )
  }

  fun revoke(tokens: GoogleDriveTokens) {
    val form = FormBody.Builder().add("token", tokens.refreshToken).build()
    val req = Request.Builder().url("https://oauth2.googleapis.com/revoke").post(form).build()
    try { http.newCall(req).execute().close() } catch (_: Exception) {}
  }

  private fun executeCall(request: Request): JsonObject {
    val response = try {
      http.newCall(request).execute()
    } catch (e: java.io.IOException) {
      throw ProviderError(null, "Falha de rede: ${e.message}", ErrorKind.NETWORK)
    }
    response.use {
      val body = it.body?.string().orEmpty()
      if (!it.isSuccessful) {
        val kind = if (it.code in 400..499) ErrorKind.SIGNED_OUT else classify(it.code, body)
        throw ProviderError(it.code, body.take(500), kind)
      }
      return if (body.isNotBlank()) json.parseToJsonElement(body).jsonObject else JsonObject(emptyMap())
    }
  }

  companion object {
    const val SCOPE_DRIVE = "https://www.googleapis.com/auth/drive"
    const val PREFERRED_PORT = 1455

    private val rng = SecureRandom()
    private fun random(bytes: Int = 32) = b64url(ByteArray(bytes).also(rng::nextBytes))
    private fun b64url(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    /** Binds the loopback listener; 1455 when free, otherwise any port. */
    fun listen(): ServerSocket = runCatching { ServerSocket(PREFERRED_PORT, 8, InetAddress.getByName("127.0.0.1")) }
      .getOrElse { ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")) }

    /**
     * Serves the loopback until /auth/callback arrives and returns its query.
     * Throws [SocketTimeoutException] after [timeoutMs].
     */
    fun awaitCallback(server: ServerSocket, timeoutMs: Int = 10 * 60_000): Map<String, String> {
      val deadline = System.currentTimeMillis() + timeoutMs
      while (true) {
        server.soTimeout = (deadline - System.currentTimeMillis()).coerceAtLeast(1).toInt()
        server.accept().use { s ->
          s.soTimeout = 2_000
          val target = try {
            val reader = s.getInputStream().bufferedReader(Charsets.US_ASCII)
            reader.readLine()?.split(' ')?.getOrNull(1).orEmpty().also { while (!reader.readLine().isNullOrEmpty()) Unit }
          } catch (e: java.io.IOException) {
            null
          } ?: return@use
          val (path, query) = target.substringBefore('?') to target.substringAfter('?', "")
          val ok = path == "/auth/callback"
          val html = if (ok) PAGE else "Not found"
          val body = html.toByteArray(Charsets.UTF_8)
          s.getOutputStream().apply {
            write(("HTTP/1.1 ${if (ok) "200 OK" else "404 Not Found"}\r\nContent-Type: text/html; charset=utf-8\r\n" +
              "Content-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n").toByteArray(Charsets.US_ASCII))
            write(body); flush()
          }
          if (ok) return query.split('&').filter { '=' in it }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
          }
        }
      }
    }

    private const val PAGE = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>" +
      "<body style='background:#000;color:#f5f5f7;font-family:sans-serif;padding:32px'>" +
      "<h2>Login do Google Drive recebido</h2><p>Pode voltar ao Bookconverter.</p></body>"
  }
}

data class DriveFolder(val id: String, val name: String)
data class DriveUploadedFile(val id: String, val name: String, val webViewLink: String?)

/**
 * Google Drive REST API v3 client for folder operations and multipart file uploads.
 */
class GoogleDriveApi(
  private val baseUrl: String = "https://www.googleapis.com",
  private val http: OkHttpClient = defaultHttp(),
) {

  fun listFolders(accessToken: String, parentId: String = "root"): List<DriveFolder> {
    val q = "mimeType='application/vnd.google-apps.folder' and trashed=false and '$parentId' in parents"
    val base = baseUrl.trimEnd('/')
    val url = "$base/drive/v3/files".toHttpUrl().newBuilder()
      .addQueryParameter("q", q)
      .addQueryParameter("fields", "files(id, name)")
      .build()
    val req = Request.Builder()
      .url(url)
      .header("Authorization", "Bearer $accessToken")
      .get()
      .build()
    val res = executeCall(req)
    val files = res["files"]?.jsonArray.orEmpty()
    return files.map {
      val o = it.jsonObject
      DriveFolder(
        id = o["id"]!!.jsonPrimitive.content,
        name = o["name"]?.jsonPrimitive?.content.orEmpty(),
      )
    }
  }

  fun createFolder(accessToken: String, name: String, parentId: String = "root"): DriveFolder {
    val body = buildJsonObject {
      put("name", name)
      put("mimeType", "application/vnd.google-apps.folder")
      putJsonArray("parents") { add(parentId) }
    }
    val base = baseUrl.trimEnd('/')
    val req = Request.Builder()
      .url("$base/drive/v3/files")
      .header("Authorization", "Bearer $accessToken")
      .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
      .build()
    val res = executeCall(req)
    return DriveFolder(
      id = res["id"]!!.jsonPrimitive.content,
      name = res["name"]?.jsonPrimitive?.content ?: name,
    )
  }

  fun uploadFile(
    accessToken: String,
    folderId: String?,
    fileName: String,
    mimeType: String,
    bytes: ByteArray,
  ): DriveUploadedFile {
    val metadata = buildJsonObject {
      put("name", fileName)
      if (!folderId.isNullOrBlank()) {
        putJsonArray("parents") { add(folderId) }
      }
    }

    val metadataBody = metadata.toString().toRequestBody("application/json; charset=UTF-8".toMediaType())
    val fileBody = bytes.toRequestBody(mimeType.toMediaTypeOrNull() ?: "application/octet-stream".toMediaType())

    val multipart = MultipartBody.Builder()
      .setType("multipart/related".toMediaType())
      .addPart(metadataBody)
      .addPart(fileBody)
      .build()

    val base = baseUrl.trimEnd('/')
    val url = "$base/upload/drive/v3/files".toHttpUrl().newBuilder()
      .addQueryParameter("uploadType", "multipart")
      .addQueryParameter("fields", "id,name,webViewLink")
      .build()

    val req = Request.Builder()
      .url(url)
      .header("Authorization", "Bearer $accessToken")
      .post(multipart)
      .build()

    val res = executeCall(req)
    return DriveUploadedFile(
      id = res["id"]?.jsonPrimitive?.content ?: throw ProviderError(null, "Resposta sem id", ErrorKind.BAD_OUTPUT),
      name = res["name"]?.jsonPrimitive?.content ?: fileName,
      webViewLink = res["webViewLink"]?.jsonPrimitive?.content,
    )
  }

  private fun executeCall(request: Request): JsonObject {
    val response = try {
      http.newCall(request).execute()
    } catch (e: java.io.IOException) {
      throw ProviderError(null, "Falha de rede: ${e.message}", ErrorKind.NETWORK)
    }
    response.use {
      val body = it.body?.string().orEmpty()
      if (!it.isSuccessful) {
        val kind = if (it.code == 401) ErrorKind.SIGNED_OUT else classify(it.code, body)
        throw ProviderError(it.code, body.take(500), kind)
      }
      return if (body.isNotBlank()) json.parseToJsonElement(body).jsonObject else JsonObject(emptyMap())
    }
  }
}
