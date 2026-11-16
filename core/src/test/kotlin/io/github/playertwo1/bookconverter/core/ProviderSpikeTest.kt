package io.github.playertwo1.bookconverter.core

import io.github.playertwo1.bookconverter.core.epub.Epub
import io.github.playertwo1.bookconverter.core.provider.Provider
import io.github.playertwo1.bookconverter.core.provider.ProviderError
import io.github.playertwo1.bookconverter.core.provider.providerClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Real calls, so it costs a few cents and only runs with keys in the environment:
 * OPENAI_API_KEY / ANTHROPIC_API_KEY / GEMINI_API_KEY, optional <P>_MODEL (translates only when set),
 * <P>_BASE_URL (gateway) and SPIKE_EFFORT (default "low").
 */
class ProviderSpikeTest {
  private val system = "Translate the user's text from English to Brazilian Portuguese. " +
    "Keep every inline HTML tag exactly as it is. Reply with the translation only."

  private val sample by lazy {
    val book = File(System.getProperty("books.dir"), "animal_farm.epub")
    Epub.read(book.readBytes()).blocks().first { it.text.length in 200..800 }
  }

  @Test fun openai() = spike(Provider.OPENAI, "OPENAI")
  @Test fun anthropic() = spike(Provider.ANTHROPIC, "ANTHROPIC")
  @Test fun gemini() = spike(Provider.GEMINI, "GEMINI")

  private fun spike(provider: Provider, env: String) {
    val key = System.getenv("${env}_API_KEY")
    assumeTrue("${env}_API_KEY ausente", !key.isNullOrBlank())
    val client = providerClient(provider, key!!, System.getenv("${env}_BASE_URL"))
    val models = client.listModels()
    println("SPIKE $provider models=${models.size}: ${models.take(25)}")
    val model = System.getenv("${env}_MODEL") ?: return println("SPIKE $provider: defina ${env}_MODEL para testar a tradução")
    val effort = System.getenv("SPIKE_EFFORT") ?: "low"
    val result = try {
      client.translate(model, system, sample.html, effort).also { println("SPIKE $provider effort=$effort ACEITO") }
    } catch (e: ProviderError) {
      println("SPIKE $provider effort=$effort RECUSADO status=${e.status}: ${e.message}")
      client.translate(model, system, sample.html, null)
    }
    val tagsIn = Regex("<[^>]+>").findAll(sample.html).count()
    val tagsOut = Regex("<[^>]+>").findAll(result.text).count()
    println("SPIKE $provider model=${result.model} in=${result.inputTokens} out=${result.outputTokens} tags $tagsIn->$tagsOut")
    println("SPIKE $provider texto: ${result.text.take(300)}")
    assert(result.text.isNotBlank())
  }
}
