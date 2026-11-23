package io.github.playertwo1.bookconverter.core.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

data class GitHubRelease(
  val tagName: String,
  val name: String,
  val releaseNotes: String,
  val htmlUrl: String,
  val apkDownloadUrl: String?,
)

sealed interface UpdateCheckResult {
  data class UpdateAvailable(val currentVersion: String, val release: GitHubRelease) : UpdateCheckResult
  data class UpToDate(val currentVersion: String, val latestVersion: String) : UpdateCheckResult
  data class Failed(val message: String) : UpdateCheckResult
}

class GitHubUpdateChecker(
  private val repo: String = DEFAULT_REPO,
  private val apiBaseUrl: String = "https://api.github.com",
  private val client: OkHttpClient = defaultClient(),
) {
  fun checkForUpdate(currentVersion: String): UpdateCheckResult {
    val request = Request.Builder()
      .url("${apiBaseUrl.trimEnd('/')}/repos/$repo/releases/latest")
      .header("Accept", "application/vnd.github+json")
      .header("User-Agent", "Bookconverter-Android")
      .get()
      .build()

    return try {
      client.newCall(request).execute().use { resp ->
        when {
          resp.code == 404 -> UpdateCheckResult.Failed("Nenhuma release publicada encontrada no GitHub.")
          resp.code == 403 || resp.code == 429 ->
            UpdateCheckResult.Failed("Limite de requisições do GitHub atingido. Tente novamente mais tarde.")
          !resp.isSuccessful -> UpdateCheckResult.Failed("GitHub respondeu com erro HTTP ${resp.code}.")
          else -> {
            val body = resp.body?.string().orEmpty()
            val obj = Json.parseToJsonElement(body).jsonObject
            val tagName = obj["tag_name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (tagName.isEmpty()) {
              return UpdateCheckResult.Failed("Resposta inválida do GitHub: versão ausente.")
            }
            val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: tagName
            val releaseNotes = obj["body"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val htmlUrl = obj["html_url"]?.jsonPrimitive?.contentOrNull?.trim()
              ?.takeIf { it.startsWith("https://") }
              ?: "https://github.com/$repo/releases/latest"

            val apkDownloadUrl = obj["assets"]?.jsonArray
              ?.mapNotNull { element -> runCatching { element.jsonObject }.getOrNull() }
              ?.firstOrNull { asset ->
                val assetName = asset["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val downloadUrl = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                assetName.endsWith(".apk", ignoreCase = true) && downloadUrl.startsWith("https://")
              }
              ?.get("browser_download_url")?.jsonPrimitive?.contentOrNull?.trim()

            val release = GitHubRelease(
              tagName = tagName,
              name = name,
              releaseNotes = releaseNotes,
              htmlUrl = htmlUrl,
              apkDownloadUrl = apkDownloadUrl,
            )

            if (isNewerVersion(latestTag = tagName, currentVersion = currentVersion)) {
              UpdateCheckResult.UpdateAvailable(currentVersion = currentVersion, release = release)
            } else {
              UpdateCheckResult.UpToDate(currentVersion = currentVersion, latestVersion = tagName)
            }
          }
        }
      }
    } catch (_: IOException) {
      UpdateCheckResult.Failed("Sem conexão com o GitHub. Verifique sua internet.")
    } catch (_: Exception) {
      UpdateCheckResult.Failed("Falha ao ler dados da atualização.")
    }
  }

  companion object {
    const val DEFAULT_REPO = "playertwo1/bookconverter-android"

    private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(15, TimeUnit.SECONDS)
      .build()

    private data class ParsedVersion(val parts: List<Int>, val isPreRelease: Boolean)

    private fun parseVersion(raw: String): ParsedVersion? {
      val trimmed = raw.trim().removePrefix("v").removePrefix("V")
      if (trimmed.isEmpty()) return null
      val withoutBuild = trimmed.substringBefore('+')
      val hasPreRelease = '-' in withoutBuild
      val core = withoutBuild.substringBefore('-')
      if (core.isEmpty()) return null
      val segments = core.split('.')
      val parsed = segments.map { seg ->
        val n = seg.toIntOrNull() ?: return null
        if (n < 0) return null
        n
      }
      return ParsedVersion(parts = parsed, isPreRelease = hasPreRelease)
    }

    fun isNewerVersion(latestTag: String, currentVersion: String): Boolean {
      val latest = parseVersion(latestTag) ?: return false
      val current = parseVersion(currentVersion) ?: return false
      val maxLen = maxOf(latest.parts.size, current.parts.size)
      for (i in 0 until maxLen) {
        val l = latest.parts.getOrElse(i) { 0 }
        val c = current.parts.getOrElse(i) { 0 }
        if (l != c) return l > c
      }
      return !latest.isPreRelease && current.isPreRelease
    }
  }
}
