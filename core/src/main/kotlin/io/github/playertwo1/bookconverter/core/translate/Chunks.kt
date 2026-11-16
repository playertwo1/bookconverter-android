package io.github.playertwo1.bookconverter.core.translate

import io.github.playertwo1.bookconverter.core.epub.Block

/** Consecutive blocks of one spine document, sent to the model in a single call (D13). */
data class Chunk(val blocks: List<Block>) {
  init { require(blocks.isNotEmpty()) }

  /** Stable: derived from the first and last block IDs, which come only from the file's bytes. */
  val id: String get() = "${blocks.first().spineIndex}:${blocks.first().index}-${blocks.last().index}"
  val htmlChars: Int get() = blocks.sumOf { it.html.length }
}

const val MAX_CHUNK_CHARS = 3000

/** Groups blocks in order, never across spine documents; a block longer than [maxChars] travels alone. */
fun chunk(blocks: List<Block>, maxChars: Int = MAX_CHUNK_CHARS): List<Chunk> {
  val out = ArrayList<Chunk>()
  var current = ArrayList<Block>()
  var size = 0
  for (b in blocks) {
    val sameDoc = current.isEmpty() || current.last().spineIndex == b.spineIndex
    if (current.isNotEmpty() && (!sameDoc || size + b.html.length > maxChars)) {
      out += Chunk(current)
      current = ArrayList()
      size = 0
    }
    current += b
    size += b.html.length
  }
  if (current.isNotEmpty()) out += Chunk(current)
  return out
}

/**
 * The preview sample: a reasonably full chunk about a tenth into the book, past the front matter
 * (title page, copyright, contents) whose text says nothing about how the prose will translate.
 */
fun sampleChunk(chunks: List<Chunk>, maxChars: Int = MAX_CHUNK_CHARS): Chunk {
  require(chunks.isNotEmpty())
  return chunks.drop(chunks.size / 10).firstOrNull { it.htmlChars >= maxChars * 6 / 10 && it.blocks.size >= 2 }
    ?: chunks.maxBy { it.htmlChars }
}
