package io.github.playertwo1.bookconverter.core

import io.github.playertwo1.bookconverter.core.provider.ChatGptAuth
import io.github.playertwo1.bookconverter.core.provider.ChatGptClient
import io.github.playertwo1.bookconverter.core.provider.ChatGptTokens
import io.github.playertwo1.bookconverter.core.provider.ErrorKind
import io.github.playertwo1.bookconverter.core.provider.ProviderError
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import kotlin.concurrent.thread

/** Offline: the issuer and the Responses API are a local MockWebServer; the ID token is signed with a test RSA key. */
class ChatGptTest {
  private val server = MockWebServer().apply { start() }
  private val issuer = server.url("/").toString().removeSuffix("/")
  private val now = 1_800_000_000L
  private val auth = ChatGptAuth(issuer) { now }
  private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

  @After fun stop() = server.shutdown()

  private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
  private fun jwks(): String {
    val pub = keys.public as RSAPublicKey
    fun unsigned(x: java.math.BigInteger) = x.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
    return """{"keys":[{"kty":"RSA","kid":"k1","alg":"RS256","n":"${b64(unsigned(pub.modulus))}","e":"${b64(unsigned(pub.publicExponent))}"}]}"""
  }
  private fun idToken(aud: String, nonce: String, iss: String = issuer, sub: String = "user-1"): String {
    val h = b64("""{"alg":"RS256","kid":"k1"}""".toByteArray())
    val p = b64("""{"iss":"$iss","aud":"$aud","exp":${now + 3600},"nonce":"$nonce","sub":"$sub","email":"leitor@example.com"}""".toByteArray())
    val sig = Signature.getInstance("SHA256withRSA").run { initSign(keys.private); update("$h.$p".toByteArray()); sign() }
    return "$h.$p.${b64(sig)}"
  }
  private fun json(status: Int, body: String) = server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body))
  private fun tokenResponse(id: String?, scope: String = ChatGptAuth.SCOPES, refresh: String = "r1") =
    """{"access_token":"a1","refresh_token":"$refresh",${id?.let { "\"id_token\":\"$it\"," } ?: ""}"token_type":"Bearer","expires_in":3600,"scope":"$scope"}"""
  private fun kindOf(block: () -> Unit): ErrorKind = try { block(); fail("esperava ProviderError"); error("") } catch (e: ProviderError) { e.kind }

  @Test fun authorizeUrlFollowsTheRegistrationContract() {
    val p = auth.start(1455, "urn:uuid:0000", "Bookconverter", issuedClientId = null)
    val u = p.url.toHttpUrl()
    assertEquals("/api/accounts/authorize", u.encodedPath)
    assertEquals("dynamic_agent_client", u.queryParameter("client_id"))
    assertEquals("Bookconverter", u.queryParameter("agent_name_hint"))
    assertEquals("http://127.0.0.1:1455/auth/callback", u.queryParameter("redirect_uri"))
    assertEquals(ChatGptAuth.SCOPES, u.queryParameter("scope"))
    assertEquals("https://api.openai.com/v1", u.queryParameter("resource"))
    assertEquals("S256", u.queryParameter("code_challenge_method"))
    assertEquals(b64(MessageDigest.getInstance("SHA-256").digest(p.verifier.toByteArray())), u.queryParameter("code_challenge"))
    // Reauthorization reuses the issued client and drops the registration hint.
    val again = auth.start(5000, "urn:uuid:0000", "Bookconverter", issuedClientId = "oaiapp_1", idTokenHint = "old.id.token").url.toHttpUrl()
    assertEquals("oaiapp_1", again.queryParameter("client_id"))
    assertNull(again.queryParameter("agent_name_hint"))
    assertEquals("old.id.token", again.queryParameter("id_token_hint"))
  }

  @Test fun finishExchangesCodeAndVerifiesTheIdToken() {
    val p = auth.start(1455, "urn:uuid:0000", "Bookconverter", null)
    json(200, tokenResponse(idToken("oaiapp_1", p.nonce)))
    json(200, jwks())
    val t = auth.finish(p, mapOf("code" to "c1", "state" to p.state, "client_id" to "oaiapp_1"))
    assertEquals("oaiapp_1", t.clientId)
    assertEquals("user-1", t.subject)
    assertEquals("leitor@example.com", t.email)
    assertEquals(now + 3600, t.expiresAt)
    val form = server.takeRequest().body.readUtf8()
    for (f in listOf("grant_type=authorization_code", "client_id=oaiapp_1", "code=c1", "code_verifier=${p.verifier}", "redirect_uri=http%3A%2F%2F127.0.0.1%3A1455%2Fauth%2Fcallback"))
      assertTrue(form, form.contains(f))
    assertFalse("toString não pode expor tokens", t.toString().contains("a1"))
    assertEquals(t, ChatGptTokens.fromJson(t.toJson()))
  }

  @Test fun finishRejectsForgedOrIncompleteCallbacks() {
    val p = auth.start(1455, "urn:uuid:0000", "Bookconverter", null)
    assertEquals(ErrorKind.SIGNED_OUT, kindOf { auth.finish(p, mapOf("code" to "c", "state" to "outro", "client_id" to "oaiapp_1")) })
    assertEquals(ErrorKind.SIGNED_OUT, kindOf { auth.finish(p, mapOf("error" to "access_denied", "state" to p.state)) })
    assertEquals(ErrorKind.SIGNED_OUT, kindOf { auth.finish(p, mapOf("code" to "c", "state" to p.state)) }) // registration without client_id
    // Wrong nonce in the ID token.
    json(200, tokenResponse(idToken("oaiapp_1", "nonce-errado"))); json(200, jwks())
    assertEquals(ErrorKind.SIGNED_OUT, kindOf { auth.finish(p, mapOf("code" to "c", "state" to p.state, "client_id" to "oaiapp_1")) })
    // Token from another issuer.
    json(200, tokenResponse(idToken("oaiapp_1", p.nonce, iss = "https://evil.example"))); json(200, jwks())
    assertEquals(ErrorKind.SIGNED_OUT, kindOf { auth.finish(p, mapOf("code" to "c", "state" to p.state, "client_id" to "oaiapp_1")) })
    // Signed in, but the plan permission was not granted.
    json(200, tokenResponse(idToken("oaiapp_1", p.nonce), scope = "openid profile email offline_access"))
    assertEquals(ErrorKind.PLAN_UNAVAILABLE, kindOf { auth.finish(p, mapOf("code" to "c", "state" to p.state, "client_id" to "oaiapp_1")) })
    // Expired code.
    json(400, """{"error":"invalid_grant"}""")
    assertEquals(ErrorKind.SIGNED_OUT, kindOf { auth.finish(p, mapOf("code" to "c", "state" to p.state, "client_id" to "oaiapp_1")) })
  }

  @Test fun refreshRotatesTokensAndKeepsTheAccount() {
    val old = ChatGptTokens("oaiapp_1", "user-1", "leitor@example.com", "id0", "a0", "r0", now - 10, ChatGptAuth.SCOPES)
    assertTrue(old.expiresSoon(now))
    json(200, """{"access_token":"a2","refresh_token":"r2","token_type":"Bearer","expires_in":3600}""")
    val t = auth.refresh(old)
    assertEquals("a2", t.accessToken)
    assertEquals("r2", t.refreshToken)
    assertEquals("user-1", t.subject)
    assertFalse(t.expiresSoon(now))
    val form = server.takeRequest().body.readUtf8()
    assertTrue(form.contains("grant_type=refresh_token") && form.contains("refresh_token=r0") && !form.contains("scope="))
    json(400, """{"error":"refresh_token_reused"}""")
    assertEquals(ErrorKind.SIGNED_OUT, kindOf { auth.refresh(old) })
  }

  @Test fun loopbackIgnoresOtherPathsAndReturnsTheCallbackQuery() {
    val listener = ChatGptAuth.listen()
    val port = listener.localPort
    fun get(path: String) = Socket("127.0.0.1", port).use { s ->
      s.getOutputStream().write("GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray()); s.getInputStream().bufferedReader().readLine()
    }
    var favicon: String? = null
    var callback: String? = null
    // Like Chrome's speculative preconnect: an open socket that never sends a request must not abort the wait.
    val idle = Socket("127.0.0.1", port)
    thread { favicon = get("/favicon.ico"); callback = get("/auth/callback?code=c%2B1&state=s1") }
    val q = listener.use { ChatGptAuth.awaitCallback(it, 15_000) }
    idle.close()
    assertEquals(mapOf("code" to "c+1", "state" to "s1"), q)
    Thread.sleep(200)
    assertEquals("HTTP/1.1 404 Not Found", favicon)
    assertEquals("HTTP/1.1 200 OK", callback)
  }

  private fun sse(vararg events: String) = server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
    .setBody(events.joinToString("") { "event: x\ndata: $it\n\n" }))

  @Test fun streamsTextAndUsageWithThePlanRules() {
    sse("""{"type":"response.created"}""",
      """{"type":"response.output_text.delta","delta":"⟦1⟧ Ol"}""",
      """{"type":"response.output_text.delta","delta":"á"}""",
      """{"type":"response.completed","response":{"model":"gpt-x-2026","usage":{"input_tokens":50,"output_tokens":9}}}""")
    val t = ChatGptClient({ "tok" }, issuer).translate("gpt-x", "sys", "⟦1⟧ Hi", "low")
    assertEquals("⟦1⟧ Olá", t.text)
    assertEquals("gpt-x-2026", t.model)
    assertEquals(9L, t.outputTokens)
    val sent = server.takeRequest()
    assertEquals("Bearer tok", sent.getHeader("Authorization"))
    val body = sent.body.readUtf8()
    for (f in listOf(""""store":false""", """"stream":true""", """"instructions":"sys"""", """"effort":"low"""")) assertTrue(body, body.contains(f))
    for (f in listOf("max_output_tokens", "temperature", """"role":"system"""")) assertFalse(body, body.contains(f))
  }

  @Test fun planErrorsAreClassified() {
    val c = ChatGptClient({ "tok" }, issuer)
    sse("""{"type":"response.failed","response":{"error":{"code":"subscription_sharing_usage_limit_exceeded","message":"limit"}}}""")
    assertEquals(ErrorKind.RATE_LIMIT, kindOf { c.translate("m", "s", "t", null) })
    json(403, """{"error":{"code":"subscription_sharing_user_not_eligible","message":"no"}}""")
    assertEquals(ErrorKind.PLAN_UNAVAILABLE, kindOf { c.translate("m", "s", "t", null) })
    json(401, """{"detail":"Unauthorized"}""")
    assertEquals(ErrorKind.SIGNED_OUT, kindOf { c.listModels() })
    json(400, """{"error":{"code":"subscription_sharing_unsupported_capability","param":"reasoning.effort","message":"Unsupported value for reasoning.effort"}}""")
    assertEquals(ErrorKind.EFFORT_REJECTED, kindOf { c.translate("m", "s", "t", "high") })
    sse("""{"type":"response.output_text.delta","delta":"meio"}""") // stream dies without response.completed
    assertEquals(ErrorKind.NETWORK, kindOf { c.translate("m", "s", "t", null) })
  }

  @Test fun listsOnlyVisibleModelsInServerOrder() {
    json(200, """{"models":[{"slug":"gpt-b","visibility":"list"},{"slug":"gpt-hidden","visibility":"hide"},{"slug":"gpt-a","visibility":"list"}]}""")
    assertEquals(listOf("gpt-b", "gpt-a"), ChatGptClient({ "tok" }, issuer).listModels())
  }
}
