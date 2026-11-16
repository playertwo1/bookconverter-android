package io.github.playertwo1.bookconverter.data

import io.github.playertwo1.bookconverter.core.epub.Epub
import io.github.playertwo1.bookconverter.core.epub.EpubException
import io.github.playertwo1.bookconverter.core.epub.Verification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

class ImportResult(val book: Book, val alreadyInLibrary: Boolean, val verification: Verification)

private const val MAX_FILE_BYTES = 512L * 1024 * 1024

/** Decide by the file's bytes, not its extension or MIME type (the picker lets anything through). */
private fun sniff(head: ByteArray) {
    fun at(offset: Int, magic: String) =
        head.size >= offset + magic.length && String(head, offset, magic.length, Charsets.ISO_8859_1) == magic
    when {
        at(0, "%PDF") -> throw EpubException("PDF não é suportado: o app lê EPUB")
        at(60, "BOOKMOBI") -> throw EpubException("MOBI/AZW3 ainda não é suportado: chega numa fase própria")
        !at(0, "PK\u0003\u0004") -> throw EpubException("Não é um EPUB: o arquivo não é um ZIP")
    }
}

/**
 * Copies [input] into [booksDir] as `<sha256>.epub` and records it, but only after [Epub.read]
 * accepts it (readable, DRM-free, safe ZIP). Throws [EpubException] with a pt-BR message otherwise.
 * The same file imported twice returns the existing book.
 */
suspend fun importEpub(
    input: InputStream,
    sourceName: String,
    booksDir: File,
    dao: BookDao,
    now: Long = System.currentTimeMillis(),
): ImportResult = withContext(Dispatchers.IO) {
    booksDir.mkdirs()
    val tmp = File.createTempFile("import-", ".tmp", booksDir)
    try {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        tmp.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                size += n
                if (size > MAX_FILE_BYTES) throw EpubException("Arquivo grande demais (mais de 512 MB)")
                digest.update(buf, 0, n)
                out.write(buf, 0, n)
            }
        }
        sniff(tmp.inputStream().use { it.readNBytes(68) })
        val epub = tmp.inputStream().buffered().use { Epub.read(it) }
        val verification = epub.verify()
        if (verification.blocks == 0) throw EpubException("Nenhum texto extraível: o livro parece ter só imagens")
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        dao.bySha(sha)?.let { return@withContext ImportResult(it, alreadyInLibrary = true, verification) }

        val target = File(booksDir, "$sha.epub")
        // A leftover from an import that died before the insert has the same hash, so replacing it is safe.
        if (!tmp.renameTo(target) && !(target.delete() && tmp.renameTo(target))) {
            throw IOException("Não consegui gravar a cópia privada do livro")
        }
        val book = Book(
            title = epub.title?.trim()?.takeIf { it.isNotEmpty() } ?: sourceName.substringBeforeLast('.'),
            author = epub.author?.trim()?.takeIf { it.isNotEmpty() },
            language = epub.language?.trim()?.takeIf { it.isNotEmpty() },
            format = "EPUB",
            sha256 = sha,
            privatePath = target.absolutePath,
            sourceName = sourceName,
            sizeBytes = size,
            importedAt = now,
        )
        ImportResult(book.copy(id = dao.insert(book)), alreadyInLibrary = false, verification)
    } finally {
        tmp.delete() // no-op once renamed
    }
}
