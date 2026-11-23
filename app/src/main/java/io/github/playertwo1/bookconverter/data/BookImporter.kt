package io.github.playertwo1.bookconverter.data

import io.github.playertwo1.bookconverter.core.epub.DrmProtected
import io.github.playertwo1.bookconverter.core.epub.Epub
import io.github.playertwo1.bookconverter.core.epub.EpubException
import io.github.playertwo1.bookconverter.core.epub.MobiEpubPackager
import io.github.playertwo1.bookconverter.core.epub.MobiSniffer
import io.github.playertwo1.bookconverter.core.epub.Verification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

class ImportResult(
    val book: Book,
    val alreadyInLibrary: Boolean,
    val verification: Verification,
    val linkedSeriesName: String? = null,
)

private const val MAX_FILE_BYTES = 512L * 1024 * 1024

private enum class FileFormat { EPUB, MOBI }

/** Decide by the file's bytes, not its extension or MIME type (the picker lets anything through). */
private fun sniff(head: ByteArray): FileFormat {
    fun at(offset: Int, magic: String) =
        head.size >= offset + magic.length && String(head, offset, magic.length, Charsets.ISO_8859_1) == magic
    return when {
        at(0, "%PDF") -> throw EpubException("PDF não é suportado: o app lê EPUB, MOBI e AZW3")
        at(60, "BOOKMOBI") -> FileFormat.MOBI
        at(0, "PK\u0003\u0004") -> FileFormat.EPUB
        else -> throw EpubException("Não é um EPUB: o arquivo não é um ZIP")
    }
}

data class MobiConvertedMeta(
    val format: String,
    val title: String?,
    val author: String?,
    val language: String?,
)

internal fun defaultConvertMobi(source: File, targetEpub: File): MobiConvertedMeta {
    val extractDir = File.createTempFile("mobi-extract-", ".tmp", targetEpub.parentFile ?: source.parentFile)
    extractDir.delete()
    extractDir.mkdirs()
    try {
        val res = try {
            NativeMobiBridge.extract(source, extractDir)
        } catch (e: Throwable) {
            if (e is DrmProtected || e is EpubException) throw e
            if (e is UnsatisfiedLinkError || e is NoClassDefFoundError) {
                throw EpubException("Arquivo MOBI/AZW3 inválido ou não suportado neste ambiente")
            }
            throw e
        }
        if (res.status == "DRM") {
            throw DrmProtected("Livro protegido por DRM (Amazon Kindle)")
        }
        if (res.status != "OK") {
            throw EpubException(res.errorMessage ?: "Arquivo MOBI/AZW3 inválido ou corrompido")
        }
        MobiEpubPackager.packageExtractedDir(
            extractedDir = extractDir,
            outputEpub = targetEpub,
            title = res.title,
            author = res.author,
            language = res.language,
        )
        return MobiConvertedMeta(
            format = res.format.ifEmpty { "MOBI" },
            title = res.title,
            author = res.author,
            language = res.language,
        )
    } finally {
        extractDir.deleteRecursively()
    }
}

private val WHITESPACE_RUN = Regex("\\s+")

private fun normalizeAuthor(author: String?): String? =
    author?.trim()?.replace(WHITESPACE_RUN, " ")?.takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT)

/**
 * Picks an existing series from homonymous candidates only when the author match is unambiguous.
 * Leaves conflicting or ambiguous cases unlinked (null) so the user can choose before translating.
 */
internal suspend fun matchExistingSeriesForAuthor(
    matches: List<Series>,
    cleanAuthor: String?,
    seriesDao: SeriesDao,
    now: Long,
): Series? {
    if (matches.isEmpty()) return null
    val bookAuthorNorm = normalizeAuthor(cleanAuthor)
    if (bookAuthorNorm != null) {
        val exactAuthorMatches = matches.filter { normalizeAuthor(it.author) == bookAuthorNorm }
        if (exactAuthorMatches.size == 1) return exactAuthorMatches.single()
        if (exactAuthorMatches.size > 1) return null
        if (matches.size == 1 && normalizeAuthor(matches.single().author) == null) {
            return matches.single().copy(author = cleanAuthor, updatedAt = now).also { seriesDao.updateSeries(it) }
        }
        return null
    }
    return matches.filter { normalizeAuthor(it.author) == null }.singleOrNull()
}

private suspend fun resolveOrCreateSeries(
    seriesName: String?,
    author: String?,
    seriesDao: SeriesDao?,
    now: Long,
): Series? {
    if (seriesDao == null || seriesName.isNullOrBlank()) return null
    val cleanName = seriesName.trim()
    val cleanAuthor = author?.trim()?.takeIf { it.isNotEmpty() }
    val matches = seriesDao.findAllByNameIgnoreCase(cleanName)
    if (matches.isNotEmpty()) {
        return matchExistingSeriesForAuthor(matches, cleanAuthor, seriesDao, now)
    }
    val created = Series(
        name = cleanName,
        author = cleanAuthor,
        styleNotes = "",
        glossaryVersion = 1,
        createdAt = now,
        updatedAt = now,
    )
    return created.copy(id = seriesDao.insertSeries(created))
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
    mobiToEpub: (source: File, targetEpub: File) -> MobiConvertedMeta = ::defaultConvertMobi,
    seriesDao: SeriesDao? = null,
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
        val format = sniff(tmp.inputStream().use { it.readNBytes(68) })
        when (format) {
            FileFormat.EPUB -> {
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
                val bookAuthor = epub.author?.trim()?.takeIf { it.isNotEmpty() }
                val linkedSeries = resolveOrCreateSeries(epub.series, bookAuthor, seriesDao, now)
                val book = Book(
                    title = epub.title?.trim()?.takeIf { it.isNotEmpty() } ?: sourceName.substringBeforeLast('.'),
                    author = bookAuthor,
                    language = epub.language?.trim()?.takeIf { it.isNotEmpty() },
                    format = "EPUB",
                    sha256 = sha,
                    privatePath = target.absolutePath,
                    sourceName = sourceName,
                    sizeBytes = size,
                    importedAt = now,
                    seriesId = linkedSeries?.id,
                )
                ImportResult(
                    book.copy(id = dao.insert(book)),
                    alreadyInLibrary = false,
                    verification,
                    linkedSeriesName = linkedSeries?.name,
                )
            }
            FileFormat.MOBI -> {
                val probe = MobiSniffer.probe(tmp)
                if (probe?.isDrm == true) {
                    throw DrmProtected("Livro protegido por DRM (Amazon Kindle)")
                }
                val sha = digest.digest().joinToString("") { "%02x".format(it) }
                dao.bySha(sha)?.let { existing ->
                    val verification = File(existing.privatePath).inputStream().buffered().use { Epub.read(it).verify() }
                    return@withContext ImportResult(existing, alreadyInLibrary = true, verification)
                }

                val target = File(booksDir, "$sha.epub")
                try {
                    val meta = mobiToEpub(tmp, target)
                    val epub = target.inputStream().buffered().use { Epub.read(it) }
                    val verification = epub.verify()
                    if (verification.blocks == 0) {
                        target.delete()
                        throw EpubException("Livro MOBI/AZW3 sem texto extraível")
                    }
                    val bookAuthor = meta.author?.trim()?.takeIf { it.isNotEmpty() }
                        ?: epub.author?.trim()?.takeIf { it.isNotEmpty() }
                    val linkedSeries = resolveOrCreateSeries(epub.series, bookAuthor, seriesDao, now)
                    val book = Book(
                        title = meta.title?.trim()?.takeIf { it.isNotEmpty() }
                            ?: epub.title?.trim()?.takeIf { it.isNotEmpty() }
                            ?: sourceName.substringBeforeLast('.'),
                        author = bookAuthor,
                        language = meta.language?.trim()?.takeIf { it.isNotEmpty() }
                            ?: epub.language?.trim()?.takeIf { it.isNotEmpty() },
                        format = meta.format.ifBlank { probe?.kind?.name ?: "MOBI" },
                        sha256 = sha,
                        privatePath = target.absolutePath,
                        sourceName = sourceName,
                        sizeBytes = size,
                        importedAt = now,
                        seriesId = linkedSeries?.id,
                    )
                    ImportResult(
                        book.copy(id = dao.insert(book)),
                        alreadyInLibrary = false,
                        verification,
                        linkedSeriesName = linkedSeries?.name,
                    )
                } catch (e: Throwable) {
                    target.delete()
                    throw e
                }
            }
        }
    } finally {
        tmp.delete() // no-op once renamed
    }
}

suspend fun importBook(
    input: InputStream,
    sourceName: String,
    booksDir: File,
    dao: BookDao,
    now: Long = System.currentTimeMillis(),
    mobiToEpub: (source: File, targetEpub: File) -> MobiConvertedMeta = ::defaultConvertMobi,
    seriesDao: SeriesDao? = null,
): ImportResult = importEpub(input, sourceName, booksDir, dao, now, mobiToEpub, seriesDao)
