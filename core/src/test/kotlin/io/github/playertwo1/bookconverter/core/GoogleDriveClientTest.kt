package io.github.playertwo1.bookconverter.core

import io.github.playertwo1.bookconverter.core.provider.DriveFolder
import io.github.playertwo1.bookconverter.core.provider.DriveUploadedFile
import io.github.playertwo1.bookconverter.core.provider.ErrorKind
import io.github.playertwo1.bookconverter.core.provider.GoogleDriveApi
import io.github.playertwo1.bookconverter.core.provider.GoogleDriveAuth
import io.github.playertwo1.bookconverter.core.provider.GoogleDriveTokens
import io.github.playertwo1.bookconverter.core.provider.PendingGoogleSignIn
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
import java.security.MessageDigest
import java.util.Base64
import kotlin.concurrent.thread

class GoogleDriveClientTest {
  private val server = MockWebServer().apply { start() }
  private val baseUrl = server.url("/").toString().removeSuffix("/")
  private val now = 1_800_000_000L

  private val auth = GoogleDriveAuth(
    authEndpoint = "$baseUrl/oauth2/auth",
    tokenEndpoint = "$baseUrl/oauth2/token",
    userInfoEndpoint = "$baseUrl/oauth2/userinfo",
    now = { now },
  )

  private val api = GoogleDriveApi(baseUrl = baseUrl)

  @After
  fun tearDown() {
    server.shutdown()
  }

  private fun b64url(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

  private fun json(status: Int, body: String) {
    server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body))
  }

  private fun kindOf(block: () -> Unit): ErrorKind = try {
    block()
    fail("esperava ProviderError")
    error("")
  } catch (e: ProviderError) {
    e.kind
  }

  @Test
  fun startGeneratesPkceAndProperAuthorizeUrl() {
    val pending = auth.start(port = 1455, clientId = "google_client_id", clientSecret = "google_secret")
    val url = pending.url.toHttpUrl()

    assertEquals("/oauth2/auth", url.encodedPath)
    assertEquals("google_client_id", url.queryParameter("client_id"))
    assertEquals("http://127.0.0.1:1455/auth/callback", url.queryParameter("redirect_uri"))
    assertEquals("code", url.queryParameter("response_type"))
    assertEquals("https://www.googleapis.com/auth/drive", url.queryParameter("scope"))
    assertEquals("offline", url.queryParameter("access_type"))
    assertEquals("consent", url.queryParameter("prompt"))
    assertEquals("S256", url.queryParameter("code_challenge_method"))
    assertEquals(pending.state, url.queryParameter("state"))

    val expectedChallenge = b64url(MessageDigest.getInstance("SHA-256").digest(pending.verifier.toByteArray(Charsets.US_ASCII)))
    assertEquals(expectedChallenge, url.queryParameter("code_challenge"))

    assertEquals("google_client_id", pending.clientId)
    assertEquals("google_secret", pending.clientSecret)
    assertEquals("http://127.0.0.1:1455/auth/callback", pending.redirectUri)
  }

  @Test
  fun finishExchangesCodeAndFetchesEmail() {
    val pending = auth.start(1455, "client_id_1", "secret_1")

    json(200, """{"access_token":"acc_1","refresh_token":"ref_1","expires_in":3600,"token_type":"Bearer"}""")
    json(200, """{"email":"autor@example.com","verified_email":true}""")

    val tokens = auth.finish(pending, mapOf("code" to "code_1", "state" to pending.state))

    assertEquals("client_id_1", tokens.clientId)
    assertEquals("secret_1", tokens.clientSecret)
    assertEquals("acc_1", tokens.accessToken)
    assertEquals("ref_1", tokens.refreshToken)
    assertEquals(now + 3600, tokens.expiresAt)
    assertEquals("autor@example.com", tokens.email)

    // Verify token exchange request
    val tokenReq = server.takeRequest()
    assertEquals("/oauth2/token", tokenReq.path)
    val form = tokenReq.body.readUtf8()
    assertTrue(form.contains("grant_type=authorization_code"))
    assertTrue(form.contains("client_id=client_id_1"))
    assertTrue(form.contains("client_secret=secret_1"))
    assertTrue(form.contains("code=code_1"))
    assertTrue(form.contains("code_verifier=${pending.verifier}"))
    assertTrue(form.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A1455%2Fauth%2Fcallback"))

    // Verify userInfo request
    val userReq = server.takeRequest()
    assertEquals("/oauth2/userinfo", userReq.path)
    assertEquals("Bearer acc_1", userReq.getHeader("Authorization"))

    // Verify redacted toString
    assertFalse("toString não pode expor accessToken", tokens.toString().contains("acc_1"))
    assertFalse("toString não pode expor refreshToken", tokens.toString().contains("ref_1"))
    assertFalse("toString não pode expor clientSecret", tokens.toString().contains("secret_1"))

    // Verify serialization round-trip
    val serialized = tokens.toJson()
    val restored = GoogleDriveTokens.fromJson(serialized)
    assertEquals(tokens, restored)
  }

  @Test
  fun finishHandlesOptionalEmailFailureGracefully() {
    val pending = auth.start(1455, "client_id_1", null)

    json(200, """{"access_token":"acc_2","refresh_token":"ref_2","expires_in":1800}""")
    json(403, """{"error":"forbidden"}""")

    val tokens = auth.finish(pending, mapOf("code" to "code_2", "state" to pending.state))

    assertEquals("acc_2", tokens.accessToken)
    assertEquals("ref_2", tokens.refreshToken)
    assertEquals(now + 1800, tokens.expiresAt)
    assertNull(tokens.email)
    assertNull(tokens.clientSecret)
  }

  @Test
  fun finishRejectsInvalidStateOrCallbackError() {
    val pending = auth.start(1455, "cid", null)

    assertEquals(ErrorKind.SIGNED_OUT, kindOf {
      auth.finish(pending, mapOf("code" to "c", "state" to "wrong_state"))
    })
    assertEquals(ErrorKind.SIGNED_OUT, kindOf {
      auth.finish(pending, mapOf("error" to "access_denied", "state" to pending.state))
    })
    assertEquals(ErrorKind.SIGNED_OUT, kindOf {
      auth.finish(pending, mapOf("state" to pending.state)) // sem code
    })

    // Token endpoint returns 400
    json(400, """{"error":"invalid_grant"}""")
    assertEquals(ErrorKind.SIGNED_OUT, kindOf {
      auth.finish(pending, mapOf("code" to "c", "state" to pending.state))
    })

    // Token response missing refresh_token
    json(200, """{"access_token":"a"}""")
    assertEquals(ErrorKind.SIGNED_OUT, kindOf {
      auth.finish(pending, mapOf("code" to "c", "state" to pending.state))
    })
  }

  @Test
  fun refreshRotatesAccessTokenAndPreservesExistingRefreshTokenWhenOmitted() {
    val old = GoogleDriveTokens(
      clientId = "cid",
      clientSecret = "csec",
      accessToken = "old_acc",
      refreshToken = "old_ref",
      expiresAt = now - 10,
      email = "leitor@example.com",
    )
    assertTrue(old.expiresSoon(now))

    json(200, """{"access_token":"new_acc","expires_in":3600}""")

    val refreshed = auth.refresh(old)

    assertEquals("new_acc", refreshed.accessToken)
    assertEquals("old_ref", refreshed.refreshToken) // preservado
    assertEquals(now + 3600, refreshed.expiresAt)
    assertEquals("cid", refreshed.clientId)
    assertEquals("csec", refreshed.clientSecret)
    assertEquals("leitor@example.com", refreshed.email)
    assertFalse(refreshed.expiresSoon(now))

    val req = server.takeRequest()
    assertEquals("/oauth2/token", req.path)
    val body = req.body.readUtf8()
    assertTrue(body.contains("grant_type=refresh_token"))
    assertTrue(body.contains("refresh_token=old_ref"))
    assertTrue(body.contains("client_id=cid"))
    assertTrue(body.contains("client_secret=csec"))
  }

  @Test
  fun refreshUpdatesRefreshTokenWhenProvided() {
    val old = GoogleDriveTokens("cid", null, "acc", "ref_v1", now - 10)
    json(200, """{"access_token":"acc_v2","refresh_token":"ref_v2","expires_in":3600}""")

    val refreshed = auth.refresh(old)
    assertEquals("acc_v2", refreshed.accessToken)
    assertEquals("ref_v2", refreshed.refreshToken)
  }

  @Test
  fun refreshFailsOnInvalidGrant() {
    val old = GoogleDriveTokens("cid", null, "acc", "revoked_ref", now - 10)
    json(400, """{"error":"invalid_grant"}""")

    assertEquals(ErrorKind.SIGNED_OUT, kindOf { auth.refresh(old) })
  }

  @Test
  fun expiresSoonReturnsTrueWhenExpiredOrClose() {
    val tokensExpiringIn30s = GoogleDriveTokens("cid", null, "a", "r", now + 30)
    val tokensExpiringIn60s = GoogleDriveTokens("cid", null, "a", "r", now + 60)
    val tokensExpiringIn120s = GoogleDriveTokens("cid", null, "a", "r", now + 120)

    assertTrue(tokensExpiringIn30s.expiresSoon(now))
    assertTrue(tokensExpiringIn60s.expiresSoon(now))
    assertFalse(tokensExpiringIn120s.expiresSoon(now))
  }

  @Test
  fun listFoldersQueriesGoogleDriveWithCorrectFilter() {
    json(200, """{"files":[{"id":"fold_1","name":"Ebooks"},{"id":"fold_2","name":"Livros"}]}""")

    val folders = api.listFolders(accessToken = "token_test", parentId = "parent_abc")

    assertEquals(2, folders.size)
    assertEquals(DriveFolder("fold_1", "Ebooks"), folders[0])
    assertEquals(DriveFolder("fold_2", "Livros"), folders[1])

    val req = server.takeRequest()
    assertEquals("GET", req.method)
    assertEquals("Bearer token_test", req.getHeader("Authorization"))
    val url = req.requestUrl!!
    assertEquals("/drive/v3/files", url.encodedPath)
    assertEquals("mimeType='application/vnd.google-apps.folder' and trashed=false and 'parent_abc' in parents", url.queryParameter("q"))
    assertEquals("files(id, name)", url.queryParameter("fields"))
  }

  @Test
  fun createFolderPostsToDriveV3WithCorrectBody() {
    json(200, """{"id":"new_id","name":"Minhas Traduções","mimeType":"application/vnd.google-apps.folder"}""")

    val folder = api.createFolder(accessToken = "token_test", name = "Minhas Traduções", parentId = "root")

    assertEquals(DriveFolder("new_id", "Minhas Traduções"), folder)

    val req = server.takeRequest()
    assertEquals("POST", req.method)
    assertEquals("/drive/v3/files", req.path)
    assertEquals("Bearer token_test", req.getHeader("Authorization"))
    val body = req.body.readUtf8()
    assertTrue(body.contains(""""name":"Minhas Traduções""""))
    assertTrue(body.contains(""""mimeType":"application/vnd.google-apps.folder""""))
    assertTrue(body.contains(""""parents":["root"]"""))
  }

  @Test
  fun uploadFilePostsMultipartRelatedWithMetadataAndBinary() {
    json(200, """{"id":"file_100","name":"Dom Casmurro.epub","webViewLink":"https://drive.google.com/file/d/file_100/view"}""")

    val content = "epub-content-sample".toByteArray(Charsets.UTF_8)
    val uploaded = api.uploadFile(
      accessToken = "token_test",
      folderId = "folder_books",
      fileName = "Dom Casmurro.epub",
      mimeType = "application/epub+zip",
      bytes = content,
    )

    assertEquals(DriveUploadedFile("file_100", "Dom Casmurro.epub", "https://drive.google.com/file/d/file_100/view"), uploaded)

    val req = server.takeRequest()
    assertEquals("POST", req.method)
    val url = req.requestUrl!!
    assertEquals("/upload/drive/v3/files", url.encodedPath)
    assertEquals("multipart", url.queryParameter("uploadType"))
    assertEquals("id,name,webViewLink", url.queryParameter("fields"))
    assertEquals("Bearer token_test", req.getHeader("Authorization"))

    val contentType = req.getHeader("Content-Type").orEmpty()
    assertTrue(contentType, contentType.startsWith("multipart/related; boundary="))

    val body = req.body.readUtf8()
    assertTrue(body.contains(""""name":"Dom Casmurro.epub""""))
    assertTrue(body.contains(""""parents":["folder_books"]"""))
    assertTrue(body.contains("Content-Type: application/epub+zip"))
    assertTrue(body.contains("epub-content-sample"))
  }

  @Test
  fun uploadFileToRootWhenFolderIdNull() {
    json(200, """{"id":"file_root_1","name":"Relatorio.pdf"}""")

    val uploaded = api.uploadFile(
      accessToken = "token_test",
      folderId = null,
      fileName = "Relatorio.pdf",
      mimeType = "application/pdf",
      bytes = "pdf-content".toByteArray(Charsets.UTF_8),
    )

    assertEquals(DriveUploadedFile("file_root_1", "Relatorio.pdf", null), uploaded)

    val req = server.takeRequest()
    val body = req.body.readUtf8()
    assertTrue(body.contains(""""name":"Relatorio.pdf""""))
    assertFalse(body.contains("parents"))
  }

  @Test
  fun apiMethodsThrowProviderErrorOnUnauthorized() {
    json(401, """{"error":{"code":401,"message":"Invalid Credentials"}}""")
    assertEquals(ErrorKind.SIGNED_OUT, kindOf {
      api.listFolders("invalid_token")
    })
  }

  @Test
  fun loopbackReceivesRedirectCallback() {
    val listener = GoogleDriveAuth.listen()
    val port = listener.localPort
    fun get(path: String) = Socket("127.0.0.1", port).use { s ->
      s.getOutputStream().write("GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
      s.getInputStream().bufferedReader().readLine()
    }
    var favicon: String? = null
    var callback: String? = null
    val idle = Socket("127.0.0.1", port)
    thread {
      favicon = get("/favicon.ico")
      callback = get("/auth/callback?code=g_code%2B1&state=g_state")
    }
    val q = listener.use { GoogleDriveAuth.awaitCallback(it, 15_000) }
    idle.close()
    assertEquals(mapOf("code" to "g_code+1", "state" to "g_state"), q)
    Thread.sleep(100)
    assertEquals("HTTP/1.1 404 Not Found", favicon)
    assertEquals("HTTP/1.1 200 OK", callback)
  }
}
