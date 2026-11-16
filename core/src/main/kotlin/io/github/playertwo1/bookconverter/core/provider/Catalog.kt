package io.github.playertwo1.bookconverter.core.provider

/** Effort values sent as-is to every API, fast → thorough. A model may still refuse one: preview() then drops it (AC06). */
val EFFORT_LEVELS = listOf("low", "medium", "high")

// The model lists from the APIs are the authority; this only hides families that cannot translate text.
private val NOT_FOR_TEXT = Regex(
  """embed|tts|whisper|transcri|dall-e|gpt-image|imagen|image-generation|veo|audio|realtime|moderation|search|aqa|computer-use|robotics|-live""",
  RegexOption.IGNORE_CASE,
)

fun translationModels(ids: List<String>): List<String> = ids.filterNot { NOT_FOR_TEXT.containsMatchIn(it) }
