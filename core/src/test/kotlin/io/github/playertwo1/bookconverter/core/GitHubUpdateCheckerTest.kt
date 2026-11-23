package io.github.playertwo1.bookconverter.core

import io.github.playertwo1.bookconverter.core.update.GitHubUpdateChecker
import io.github.playertwo1.bookconverter.core.update.UpdateCheckResult
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubUpdateCheckerTest {
  @Test
  fun detectsNewerReleaseAndExtractsApkUrl() {
    MockWebServer().use { server ->
      server.enqueue(
        MockResponse()
          .setResponseCode(200)
          .setBody(
            """
            {
              "tag_name": "v1.0.2",
              "name": "v1.0.2",
              "body": "Correções e melhorias.",
              "html_url": "https://github.com/playertwo1/bookconverter-android/releases/tag/v1.0.2",
              "assets": [
                {
                  "name": "checksums.txt",
                  "browser_download_url": "https://github.com/playertwo1/bookconverter-android/releases/download/v1.0.2/checksums.txt"
                },
                {
                  "name": "bookconverter-v1.0.2.apk",
                  "browser_download_url": "https://github.com/playertwo1/bookconverter-android/releases/download/v1.0.2/bookconverter-v1.0.2.apk"
                }
              ]
            }
            """.trimIndent()
          )
      )
      server.start()

      val checker = GitHubUpdateChecker(apiBaseUrl = server.url("/").toString())
      val result = checker.checkForUpdate("1.0.1")

      val req = server.takeRequest()
      assertEquals("/repos/playertwo1/bookconverter-android/releases/latest", req.path)
      assertTrue(result is UpdateCheckResult.UpdateAvailable)
      val available = result as UpdateCheckResult.UpdateAvailable
      assertEquals("v1.0.2", available.release.tagName)
      assertEquals("Correções e melhorias.", available.release.releaseNotes)
      assertEquals(
        "https://github.com/playertwo1/bookconverter-android/releases/download/v1.0.2/bookconverter-v1.0.2.apk",
        available.release.apkDownloadUrl,
      )
    }
  }

  @Test
  fun reportsUpToDateWhenVersionMatchesOrIsNewer() {
    MockWebServer().use { server ->
      repeat(2) {
        server.enqueue(
          MockResponse()
            .setResponseCode(200)
            .setBody(
              """
              {
                "tag_name": "v1.0.9",
                "name": "v1.0.9",
                "body": "",
                "html_url": "https://github.com/playertwo1/bookconverter-android/releases/tag/v1.0.9",
                "assets": []
              }
              """.trimIndent()
            )
        )
      }
      server.start()

      val checker = GitHubUpdateChecker(apiBaseUrl = server.url("/").toString())
      val same = checker.checkForUpdate("1.0.9")
      val ahead = checker.checkForUpdate("1.1.0")

      assertEquals(UpdateCheckResult.UpToDate("1.0.9", "v1.0.9"), same)
      assertEquals(UpdateCheckResult.UpToDate("1.1.0", "v1.0.9"), ahead)
    }
  }

  @Test
  fun semverComparisonHandlesMultiDigitAndPreRelease() {
    assertTrue(GitHubUpdateChecker.isNewerVersion("v1.0.10", "1.0.2"))
    assertFalse(GitHubUpdateChecker.isNewerVersion("v1.0.2", "1.0.10"))
    assertTrue(GitHubUpdateChecker.isNewerVersion("v1.0.2", "1.0.2-beta"))
    assertFalse(GitHubUpdateChecker.isNewerVersion("v1.0.2-beta", "1.0.2"))
    assertFalse(GitHubUpdateChecker.isNewerVersion("invalid", "1.0.1"))
  }

  @Test
  fun handlesHttp404RateLimitAndBadJson() {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setResponseCode(404))
      server.enqueue(MockResponse().setResponseCode(403))
      server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))
      server.start()

      val checker = GitHubUpdateChecker(apiBaseUrl = server.url("/").toString())

      val r404 = checker.checkForUpdate("1.0.1")
      val r403 = checker.checkForUpdate("1.0.1")
      val rBad = checker.checkForUpdate("1.0.1")

      assertTrue(r404 is UpdateCheckResult.Failed && r404.message.isNotBlank())
      assertTrue(r403 is UpdateCheckResult.Failed && r403.message.isNotBlank())
      assertTrue(rBad is UpdateCheckResult.Failed && rBad.message.isNotBlank())
    }
  }

  @Test
  fun rejectsNonHttpsApkDownloadUrl() {
    MockWebServer().use { server ->
      server.enqueue(
        MockResponse()
          .setResponseCode(200)
          .setBody(
            """
            {
              "tag_name": "v1.0.2",
              "name": "v1.0.2",
              "body": "Notas",
              "html_url": "http://example.com/insecure",
              "assets": [
                {
                  "name": "app.apk",
                  "browser_download_url": "http://example.com/app.apk"
                }
              ]
            }
            """.trimIndent()
          )
      )
      server.start()

      val checker = GitHubUpdateChecker(apiBaseUrl = server.url("/").toString())
      val result = checker.checkForUpdate("1.0.1") as UpdateCheckResult.UpdateAvailable

      assertNull(result.release.apkDownloadUrl)
      assertEquals("https://github.com/playertwo1/bookconverter-android/releases/latest", result.release.htmlUrl)
    }
  }
}
