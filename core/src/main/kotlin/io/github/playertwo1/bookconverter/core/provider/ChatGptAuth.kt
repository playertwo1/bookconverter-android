package io.github.playertwo1.bookconverter.core.provider

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.Request
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/** Everything kept after "Sign in with ChatGPT". Secret: the app stores it encrypted, never logs it. */
data class ChatGptTokens(
  val clientId: String,
  val subject: String,
  val email: String?,
  val idToken: String,
  val accessToken: String,
  val refreshToken: String,
  /** Epoch seconds. */
  val expiresAt: Long,
  val scopes: String,
) {
  /** Refresh a minute early so a request never leaves with a token about to expire. */
  fun expiresSoon(now: Long = System.currentTimeMillis() / 1000) = now >= expiresAt - 60

  fun toJson(): String = buildJsonObject {
    put("clientId", clientId); put("subject", subject); email?.let { put("email", it) }
    put("idToken", idToken); put("accessToken", accessToken); put("refreshToken", refreshToken)
    put("expiresAt", expiresAt); put("scopes", scopes)
  }.toString()

  /** Redacted on purpose: data class toString would print the tokens. */
  override fun toString() = "ChatGptTokens(subject=$subject, expiresAt=$expiresAt)"

  companion object {
    fun fromJson(s: String): ChatGptTokens {
      val o = json.parseToJsonElement(s).jsonObject
      fun f(k: String) = o[k]!!.jsonPrimitive.content
      return ChatGptTokens(f("clientId"), f("subject"), o["email"]?.jsonPrimitive?.content, f("idToken"), f("accessToken"),
        f("refreshToken"), f("expiresAt").toLong(), f("scopes"))
    }
  }
}

/** One sign-in attempt; lives only in memory between opening the browser and the loopback callback. */
class PendingSignIn(val url: String, val redirectUri: String, val clientId: String, val state: String, val nonce: String, val verifier: String)

/**
 * "Sign in with ChatGPT" for open-source / locally hosted apps (developers.openai.com/siwc): dynamic client
 * registration, PKCE, loopback callback on 127.0.0.1. The plan then pays Responses API calls ([ChatGptClient]).
 */
class ChatGptAuth(private val issuer: String = "https://auth.openai.com", private val now: () -> Long = { System.currentTimeMillis() / 1000 }) {
  private val tokenUrl = "$issuer/api/accounts/oauth/token"

  /**
   * [issuedClientId] null = first registration (dynamic client); otherwise reauthorization of the saved client.
   * [hostId] is a stable opaque `urn:uuid:` per install.
   */
  fun start(port: Int, hostId: String, appName: String, issuedClientId: String?, idTokenHint: String? = null, loginHint: String? = null): PendingSignIn {
    val redirect = "http://127.0.0.1:$port/auth/callback"
    val clientId = issuedClientId ?: DYNAMIC_CLIENT
    val state = random()
    val nonce = random()
    val verifier = random(48)
    val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    val params = buildList {
      add("client_id" to clientId)
      if (issuedClientId == null) add("agent_name_hint" to appName)
      add("ext_agent_host_id" to hostId)
      if (issuedClientId != null && idTokenHint != null) add("id_token_hint" to idTokenHint)
      if (issuedClientId != null && loginHint != null) add("login_hint" to loginHint)
      add("response_type" to "code")
      add("redirect_uri" to redirect)
      add("scope" to SCOPES)
      add("resource" to RESOURCE)
      add("state" to state)
      add("nonce" to nonce)
      add("code_challenge_method" to "S256")
      add("code_challenge" to challenge)
    }
    val query = params.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8").replace("+", "%20") }
    return PendingSignIn("$issuer/api/accounts/authorize?$query", redirect, clientId, state, nonce, verifier)
  }

  /** Validates the callback, exchanges the code and checks the ID token. Throws [ProviderError] with a pt-BR-ready kind. */
  fun finish(p: PendingSignIn, callback: Map<String, String>): ChatGptTokens {
    if (callback["state"] != p.state) throw ProviderError(null, "Resposta de login com state inválido", ErrorKind.SIGNED_OUT)
    callback["error"]?.let { throw ProviderError(null, "Login não concluído: $it", ErrorKind.SIGNED_OUT) }
    val code = callback["code"] ?: throw ProviderError(null, "Login sem código de autorização", ErrorKind.SIGNED_OUT)
    val clientId = if (p.clientId == DYNAMIC_CLIENT) {
      callback["client_id"]?.takeIf { it.isNotBlank() && it != DYNAMIC_CLIENT }
        ?: throw ProviderError(null, "Registro do app incompleto (sem client_id)", ErrorKind.SIGNED_OUT)
    } else {
      callback["client_id"]?.let { if (it != p.clientId) throw ProviderError(null, "client_id diferente do salvo", ErrorKind.SIGNED_OUT) }
      p.clientId
    }
    val r = post(FormBody.Builder()
      .add("grant_type", "authorization_code").add("client_id", clientId).add("code", code)
      .add("code_verifier", p.verifier).add("redirect_uri", p.redirectUri).add("resource", RESOURCE).build())
    return tokensFrom(r, clientId, p.nonce, previous = null)
  }

  /** Rotating refresh: the returned set replaces the old one entirely. */
  fun refresh(t: ChatGptTokens): ChatGptTokens {
    val r = post(FormBody.Builder()
      .add("grant_type", "refresh_token").add("client_id", t.clientId)
      .add("refresh_token", t.refreshToken).add("resource", RESOURCE).build())
    return tokensFrom(r, t.clientId, nonce = null, previous = t)
  }

  /** Best effort; the caller clears local tokens either way. */
  fun revoke(t: ChatGptTokens) {
    post(FormBody.Builder().add("token", t.refreshToken).add("token_type_hint", "refresh_token").add("client_id", t.clientId).build(),
      url = "$issuer/api/accounts/oauth/revoke", expectJson = false)
  }

  private fun tokensFrom(r: JsonObject, clientId: String, nonce: String?, previous: ChatGptTokens?): ChatGptTokens {
    fun s(k: String) = r[k]?.jsonPrimitive?.content
    val scopes = s("scope") ?: previous?.scopes.orEmpty()
    if ("chatgpt.tokens.use.direct" !in scopes.split(' ')) throw ProviderError(403, "Permissão de uso do plano não concedida", ErrorKind.PLAN_UNAVAILABLE)
    val idToken = s("id_token") ?: previous?.idToken ?: throw ProviderError(null, "Resposta sem id_token", ErrorKind.SIGNED_OUT)
    val claims = if (s("id_token") != null) verifyIdToken(idToken, clientId, nonce) else null
    val subject = claims?.get("sub")?.jsonPrimitive?.content ?: previous!!.subject
    if (previous != null && subject != previous.subject) throw ProviderError(null, "A conta do token renovado é outra", ErrorKind.SIGNED_OUT)
    return ChatGptTokens(
      clientId = clientId,
      subject = subject,
      email = claims?.get("email")?.jsonPrimitive?.content ?: previous?.email,
      idToken = idToken,
      accessToken = s("access_token") ?: throw ProviderError(null, "Resposta sem access_token", ErrorKind.SIGNED_OUT),
      refreshToken = s("refresh_token") ?: previous?.refreshToken ?: throw ProviderError(null, "Resposta sem refresh_token", ErrorKind.SIGNED_OUT),
      expiresAt = now() + (r["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600),
      scopes = scopes,
    )
  }

  /** RS256 signature against the issuer's JWKS, then iss/aud/exp/nonce. Returns the claims. */
  internal fun verifyIdToken(jwt: String, audience: String, nonce: String?): JsonObject {
    val parts = jwt.split('.')
    if (parts.size != 3) throw bad("formato")
    val header = json.parseToJsonElement(String(b64dec(parts[0]))).jsonObject
    if (header["alg"]?.jsonPrimitive?.content != "RS256") throw bad("algoritmo")
    val kid = header["kid"]?.jsonPrimitive?.content
    val keys = call(Request.Builder().url("$issuer/.well-known/jwks.json").get().build())["keys"]!!.jsonArray.map { it.jsonObject }
    val jwk = keys.firstOrNull { it["kid"]?.jsonPrimitive?.content == kid } ?: throw bad("chave desconhecida")
    val pub = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(
      BigInteger(1, b64dec(jwk["n"]!!.jsonPrimitive.content)), BigInteger(1, b64dec(jwk["e"]!!.jsonPrimitive.content))))
    val ok = Signature.getInstance("SHA256withRSA").run {
      initVerify(pub); update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII)); verify(b64dec(parts[2]))
    }
    if (!ok) throw bad("assinatura")
    val c = json.parseToJsonElement(String(b64dec(parts[1]))).jsonObject
    if (c["iss"]?.jsonPrimitive?.content != issuer) throw bad("emissor")
    val aud = c["aud"]?.let { a -> runCatching { a.jsonArray.map { it.jsonPrimitive.content } }.getOrElse { listOf(a.jsonPrimitive.content) } }.orEmpty()
    if (audience !in aud) throw bad("audiência")
    if ((c["exp"]?.jsonPrimitive?.longOrNull ?: 0) < now() - 60) throw bad("expirado")
    if (nonce != null && c["nonce"]?.jsonPrimitive?.content != nonce) throw bad("nonce")
    if (c["sub"]?.jsonPrimitive?.content.isNullOrBlank()) throw bad("sem sub")
    return c
  }

  private fun bad(what: String) = ProviderError(null, "ID token inválido ($what)", ErrorKind.SIGNED_OUT)

  private fun post(body: FormBody, url: String = tokenUrl, expectJson: Boolean = true): JsonObject {
    val r = try {
      call(Request.Builder().url(url).post(body).build(), expectJson)
    } catch (e: ProviderError) {
      // 4xx from the token endpoint (invalid_grant, refresh_token_reused...) means the session is gone: sign in again.
      if (e.status != null && e.status in 400..499) throw ProviderError(e.status, e.message ?: "", ErrorKind.SIGNED_OUT) else throw e
    }
    return r
  }

  companion object {
    const val DYNAMIC_CLIENT = "dynamic_agent_client"
    const val RESOURCE = "https://api.openai.com/v1"
    const val SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
    const val PREFERRED_PORT = 1455

    private val rng = SecureRandom()
    private fun random(bytes: Int = 32) = b64url(ByteArray(bytes).also(rng::nextBytes))
    private fun b64url(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    private fun b64dec(s: String) = Base64.getUrlDecoder().decode(s)

    /** Binds the loopback listener; 1455 when free, otherwise any port (only the port may vary). Start it before the browser. */
    fun listen(): ServerSocket = runCatching { ServerSocket(PREFERRED_PORT, 8, InetAddress.getByName("127.0.0.1")) }
      .getOrElse { ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")) }

    /**
     * Serves the loopback until /auth/callback arrives (other paths, e.g. favicon, get 404) and returns its query.
     * Throws [SocketTimeoutException] after [timeoutMs]. The page shown never echoes the code.
     */
    fun awaitCallback(server: ServerSocket, timeoutMs: Int = 10 * 60_000): Map<String, String> {
      val deadline = System.currentTimeMillis() + timeoutMs
      while (true) {
        server.soTimeout = (deadline - System.currentTimeMillis()).coerceAtLeast(1).toInt()
        server.accept().use { s ->
          // Browsers open speculative connections that never send a request: a short read timeout, and a
          // failure on one connection only skips it instead of aborting the sign-in.
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
      "<h2>Login recebido</h2><p>Pode voltar ao Bookconverter.</p></body>"
  }
}
