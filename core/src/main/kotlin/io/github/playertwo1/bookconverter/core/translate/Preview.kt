package io.github.playertwo1.bookconverter.core.translate

import io.github.playertwo1.bookconverter.core.provider.ErrorKind
import io.github.playertwo1.bookconverter.core.provider.ProviderClient
import io.github.playertwo1.bookconverter.core.provider.ProviderError

class PreviewResult(
  val sample: Chunk,
  /** Inner HTML per block, same order as [sample]'s blocks. */
  val translated: List<String>,
  /** Model ID the provider reports having used (may be a dated snapshot of the requested one). */
  val model: String,
  /** The effort actually applied; null when none was sent or it was refused. */
  val effortApplied: String?,
  /** True when the requested effort was refused (AC06) and the call ran without it. */
  val effortRejected: Boolean,
  val inputTokens: Long?,
  val outputTokens: Long?,
)

/** Translates the sample once. An effort the model refuses is dropped, never forced (AC06); other errors propagate. */
fun preview(client: ProviderClient, model: String, effort: String?, sample: Chunk): PreviewResult {
  val request = Prompt.encode(sample)
  var rejected = false
  val t = try {
    client.translate(model, Prompt.SYSTEM, request, effort)
  } catch (e: ProviderError) {
    if (effort == null || e.kind != ErrorKind.EFFORT_REJECTED) throw e
    rejected = true
    client.translate(model, Prompt.SYSTEM, request, null)
  }
  return PreviewResult(sample, Prompt.decode(t.text, sample.blocks.size), t.model, effort.takeUnless { rejected }, rejected, t.inputTokens, t.outputTokens)
}

/** Point estimate for the whole book (D12: tokens only). Shown with [MARGIN] and labelled "estimado". */
data class Estimate(val calls: Int, val inputTokens: Long, val outputTokens: Long) {
  val low get() = Estimate(calls, (inputTokens * (1 - MARGIN)).toLong(), (outputTokens * (1 - MARGIN)).toLong())
  val high get() = Estimate(calls, (inputTokens * (1 + MARGIN)).toLong(), (outputTokens * (1 + MARGIN)).toLong())
  val total get() = inputTokens + outputTokens

  companion object { const val MARGIN = 0.3 }
}

/**
 * Scales the sample's measured usage to the whole book. Input = a fixed prompt cost per call plus text that scales
 * with size; output scales with size (reasoning tokens included, since providers bill them as output).
 * Null when the provider did not report usage.
 */
fun estimate(result: PreviewResult, chunks: List<Chunk>): Estimate? {
  val input = result.inputTokens ?: return null
  val output = result.outputTokens ?: return null
  val ratio = chunks.sumOf { it.htmlChars }.toDouble() / result.sample.htmlChars
  val fixed = minOf(input, (Prompt.SYSTEM.length / CHARS_PER_TOKEN).toLong())
  return Estimate(
    calls = chunks.size,
    inputTokens = (chunks.size * fixed + ratio * (input - fixed)).toLong(),
    outputTokens = (ratio * output).toLong(),
  )
}

private const val CHARS_PER_TOKEN = 4
