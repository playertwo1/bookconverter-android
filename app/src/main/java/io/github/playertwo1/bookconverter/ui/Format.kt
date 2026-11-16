package io.github.playertwo1.bookconverter.ui

import java.text.NumberFormat
import java.util.Locale

internal val PtBr: Locale = Locale.forLanguageTag("pt-BR")

internal fun sizeLabel(bytes: Long) = String.format(PtBr, "%.1f MB", bytes / 1_000_000.0)

internal fun count(n: Number) = NumberFormat.getIntegerInstance(PtBr).format(n)

// Shown in pt-BR, e.g. "en" -> "Inglês".
internal fun languageLabel(tag: String?): String? {
    if (tag.isNullOrBlank()) return null
    val pt = Locale.forLanguageTag("pt-BR")
    val name = Locale.forLanguageTag(tag).getDisplayLanguage(pt)
    if (name.isBlank()) return null
    return name.replaceFirstChar { it.titlecase(pt) }
}
