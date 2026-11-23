package io.github.playertwo1.bookconverter.core.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

open class EpubException(message: String) : Exception(message)
class DrmProtected(message: String) : EpubException(message)

/** One translatable unit: a leaf block element of a spine document. */
data class Block(val spineIndex: Int, val index: Int, val html: String, val text: String, val sha256: String) {
  val id: String get() = "$spineIndex:$index"
}

/** One table-of-contents link; [path] is the ZIP entry it points to, without the #fragment. */
data class TocEntry(val title: String, val path: String)

/** What the Verification screen shows; computed in one pass over the spine. */
data class Verification(
  /** OPF package version, e.g. "2.0" or "3.0". */
  val version: String?,
  val spineDocs: Int,
  val tocEntries: Int,
  /** TOC links whose target file is not in the ZIP. */
  val tocBroken: Int,
  val blocks: Int,
  val chars: Long,
  /** Share of the body text that falls inside blocks; low values mean text the chunker would skip. */
  val coverage: Double,
  val obfuscatedFonts: Boolean,
) {
  /** Non-fatal issues, in pt-BR. A book with no blocks never gets this far: the importer refuses it. */
  val warnings: List<String>
    get() = buildList {
      if (coverage < MIN_COVERAGE) add("Parte do texto fica fora dos blocos (cobertura de ${"%.1f".format(coverage * 100)}%)")
      if (tocEntries == 0) add("O livro não tem índice (nav ou NCX)")
      if (tocBroken > 0) add("$tocBroken link(s) do índice apontam para arquivos ausentes")
    }

  companion object {
    const val MIN_COVERAGE = 0.98
  }
}

class Epub private constructor(
  /** Every ZIP entry, in original order, as raw bytes. */
  val entries: LinkedHashMap<String, ByteArray>,
  val title: String?,
  val author: String?,
  val language: String?,
  val series: String? = null,
  val version: String?,
  /** Entry paths of the spine XHTML documents, in reading order. */
  val spine: List<String>,
  val toc: List<TocEntry>,
  val obfuscatedFonts: Boolean,
) {
  /** Block IDs are stable: "spine:index" plus the text hash, derived only from the file's bytes. */
  fun blocks(): List<Block> = spine.indices.flatMap { i -> blocksOf(i, parse(spine[i])).map { it.first } }

  fun coverage(): Double = verify().coverage

  fun verify(): Verification {
    var body = 0L
    var inBlocks = 0L
    var count = 0
    spine.forEachIndexed { i, path ->
      val doc = parse(path)
      body += bodyOf(doc)?.text()?.length ?: 0
      val blocks = blocksOf(i, doc)
      count += blocks.size
      inBlocks += blocks.sumOf { it.first.text.length }
    }
    return Verification(
      version = version,
      spineDocs = spine.size,
      tocEntries = toc.size,
      tocBroken = toc.count { it.path !in entries },
      blocks = count,
      chars = inBlocks,
      coverage = if (body == 0L) 1.0 else inBlocks.toDouble() / body,
      obfuscatedFonts = obfuscatedFonts,
    )
  }

  /**
   * Returns a new EPUB. [replace] gets each block and returns the new inner HTML, or null to keep it.
   * Documents with no replaced block, and every non-spine entry, keep their original bytes.
   */
  fun rewrite(replace: (Block) -> String?): ByteArray {
    val out = LinkedHashMap(entries)
    spine.forEachIndexed { i, path ->
      val doc = parse(path)
      var changed = false
      for ((block, element) in blocksOf(i, doc)) {
        replace(block)?.let { element.html(it); changed = true }
      }
      if (changed) out[path] = serialize(doc)
    }
    return write(out)
  }

  // shortcut: assumes UTF-8 XHTML (EPUB allows UTF-16 too); detect the BOM/declaration if a real book needs it.
  private fun parse(path: String): Document =
    Jsoup.parse(String(entries.getValue(path), Charsets.UTF_8), "", Parser.xmlParser())

  companion object {
    private const val MAX_ENTRIES = 10_000
    private const val MAX_TOTAL_BYTES = 512L * 1024 * 1024
    // "div" counts only as a leaf: some books (Gutenberg boilerplate, cheap conversions) put prose in bare divs.
    private val BLOCK_TAGS = setOf("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "dt", "dd", "figcaption", "td", "th")
    private val FONT_OBFUSCATION = setOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC")
    private val XHTML_TYPES = setOf("application/xhtml+xml", "text/html")

    fun read(bytes: ByteArray): Epub = read(ByteArrayInputStream(bytes))

    fun read(input: InputStream): Epub {
      val entries = readZip(input)
      checkDrm(entries)
      val container = xml(entries["META-INF/container.xml"] ?: throw EpubException("Sem META-INF/container.xml: não é um EPUB"))
      val opfPath = byLocal(container, "rootfile").firstOrNull()?.attr("full-path")?.takeIf { it.isNotBlank() }
        ?: throw EpubException("container.xml sem rootfile")
      val opf = xml(entries[opfPath] ?: throw EpubException("OPF ausente: $opfPath"))
      val opfDir = opfPath.substringBeforeLast('/', "")
      val manifest = byLocal(opf, "item").associate { it.attr("id") to it }
      val spine = byLocal(opf, "itemref").mapNotNull { ref ->
        val item = manifest[ref.attr("idref")] ?: return@mapNotNull null
        if (item.attr("media-type") !in XHTML_TYPES) return@mapNotNull null
        resolve(opfDir, item.attr("href")).takeIf { it in entries }
      }
      if (spine.isEmpty()) throw EpubException("Spine vazia: nenhum capítulo legível")
      return Epub(
        entries,
        title = byLocal(opf, "title").firstOrNull()?.text(),
        author = byLocal(opf, "creator").firstOrNull()?.text(),
        language = byLocal(opf, "language").firstOrNull()?.text(),
        series = extractSeries(opf),
        version = byLocal(opf, "package").firstOrNull()?.attr("version")?.takeIf { it.isNotBlank() },
        spine = spine,
        toc = navToc(entries, opfDir, manifest.values).ifEmpty { ncxToc(entries, opf, opfDir, manifest) },
        obfuscatedFonts = "META-INF/encryption.xml" in entries, // checkDrm already let only font obfuscation through
      )
    }

    private fun extractSeries(opf: Document): String? {
      val metas = byLocal(opf, "meta")
      val collectionTypes = metas
        .filter { it.attr("property").trim() == "collection-type" && it.attr("refines").trim().startsWith("#") }
        .associate { it.attr("refines").trim().removePrefix("#") to it.text().trim().lowercase() }

      val collections = metas.filter { it.attr("property").trim() == "belongs-to-collection" && it.text().isNotBlank() }
      val epub3Series = collections.firstOrNull { collectionTypes[it.attr("id").trim()] == "series" }
        ?: collections.firstOrNull {
          val id = it.attr("id").trim()
          id.isBlank() || id !in collectionTypes
        }
      if (epub3Series != null) return epub3Series.text().trim()

      return metas
        .firstOrNull { it.attr("name").trim().equals("calibre:series", ignoreCase = true) && it.attr("content").isNotBlank() }
        ?.attr("content")?.trim()
    }

    /** EPUB 3: the manifest item with properties="nav", then its <nav epub:type="toc">. */
    private fun navToc(entries: Map<String, ByteArray>, opfDir: String, items: Collection<Element>): List<TocEntry> {
      val item = items.firstOrNull { "nav" in it.attr("properties").split(' ') } ?: return emptyList()
      val navPath = resolve(opfDir, item.attr("href"))
      val doc = xml(entries[navPath] ?: return emptyList())
      val navs = byLocal(doc, "nav")
      val toc = navs.firstOrNull { "toc" in it.attr("epub:type").split(' ') } ?: navs.firstOrNull() ?: return emptyList()
      return byLocal(toc, "a").filter { it.hasAttr("href") }.map { a -> TocEntry(a.text(), target(navPath, a.attr("href"))) }
    }

    /** EPUB 2 (or EPUB 3 without nav): the NCX named by <spine toc="…">, else any NCX in the manifest. */
    private fun ncxToc(entries: Map<String, ByteArray>, opf: Document, opfDir: String, manifest: Map<String, Element>): List<TocEntry> {
      val item = byLocal(opf, "spine").firstOrNull()?.attr("toc")?.let { manifest[it] }
        ?: manifest.values.firstOrNull { it.attr("media-type") == "application/x-dtbncx+xml" }
        ?: return emptyList()
      val ncxPath = resolve(opfDir, item.attr("href"))
      val doc = xml(entries[ncxPath] ?: return emptyList())
      return byLocal(doc, "navPoint").mapNotNull { point ->
        val src = point.children().firstOrNull { it.tagName().substringAfter(':') == "content" }?.attr("src") ?: return@mapNotNull null
        TocEntry(byLocal(point, "text").firstOrNull()?.text().orEmpty(), target(ncxPath, src))
      }
    }

    /** A TOC href is relative to the file that holds it; "#frag" alone points back at that file. */
    private fun target(fromPath: String, href: String): String =
      if (href.startsWith("#")) fromPath else resolve(fromPath.substringBeforeLast('/', ""), href)

    private fun readZip(input: InputStream): LinkedHashMap<String, ByteArray> {
      val entries = LinkedHashMap<String, ByteArray>()
      var total = 0L
      // shortcut: ZipInputStream rejects STORED entries with a data descriptor; switch to ZipFile on the app's private copy if a real book hits it.
      ZipInputStream(input).use { zip ->
        while (true) {
          val e = zip.nextEntry ?: break
          if (e.isDirectory) continue
          val name = e.name
          if (name.startsWith("/") || '\\' in name || name.split('/').any { it == ".." }) throw EpubException("Caminho inseguro no ZIP: $name")
          if (entries.size >= MAX_ENTRIES) throw EpubException("ZIP com entradas demais")
          val buf = ByteArrayOutputStream()
          val chunk = ByteArray(64 * 1024)
          while (true) {
            val n = zip.read(chunk)
            if (n < 0) break
            total += n
            if (total > MAX_TOTAL_BYTES) throw EpubException("Conteúdo descompactado grande demais (possível ZIP bomb)")
            buf.write(chunk, 0, n)
          }
          entries[name] = buf.toByteArray()
        }
      }
      return entries
    }

    private fun checkDrm(entries: Map<String, ByteArray>) {
      if ("META-INF/rights.xml" in entries) throw DrmProtected("Livro protegido por DRM (Adobe ADEPT)")
      if ("META-INF/sinf.xml" in entries) throw DrmProtected("Livro protegido por DRM (Apple FairPlay)")
      val enc = entries["META-INF/encryption.xml"] ?: return
      val algorithms = byLocal(xml(enc), "EncryptionMethod").map { it.attr("Algorithm") }
      if (algorithms.any { it !in FONT_OBFUSCATION }) throw DrmProtected("Livro com conteúdo criptografado (DRM)")
    }

    private fun xml(bytes: ByteArray) = Jsoup.parse(String(bytes, Charsets.UTF_8), "", Parser.xmlParser())

    // OPF/container tags may carry a namespace prefix (opf:item, dc:title), so match on the local name.
    private fun byLocal(doc: Element, local: String) = doc.allElements.filter { it.tagName().substringAfter(':') == local }

    private fun bodyOf(doc: Document) = doc.allElements.firstOrNull { it.normalName().substringAfter(':') == "body" }

    private fun resolve(dir: String, href: String): String {
      val path = URLDecoder.decode(href.substringBefore('#'), "UTF-8")
      val parts = ArrayList<String>()
      for (p in (if (dir.isEmpty()) path else "$dir/$path").split('/')) when (p) {
        "", "." -> {}
        ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
        else -> parts.add(p)
      }
      return parts.joinToString("/")
    }

    private fun blocksOf(spineIndex: Int, doc: Document): List<Pair<Block, Element>> {
      val body = bodyOf(doc) ?: return emptyList()
      return body.allElements
        .filter { el ->
          el.normalName() in BLOCK_TAGS &&
            el.allElements.drop(1).none { it.normalName() in BLOCK_TAGS } &&
            el.text().isNotBlank()
        }
        .mapIndexed { i, el -> Block(spineIndex, i, el.html(), el.text(), sha256(el.text())) to el }
    }

    private fun serialize(doc: Document): ByteArray {
      doc.outputSettings().prettyPrint(false).syntax(Document.OutputSettings.Syntax.xml).charset(Charsets.UTF_8)
      return doc.outerHtml().toByteArray(Charsets.UTF_8)
    }

    private fun write(entries: Map<String, ByteArray>): ByteArray {
      val bos = ByteArrayOutputStream()
      ZipOutputStream(bos).use { zip ->
        // OCF: "mimetype" must be the first entry and stored uncompressed.
        entries["mimetype"]?.let { data ->
          val e = ZipEntry("mimetype").apply {
            method = ZipEntry.STORED
            size = data.size.toLong()
            compressedSize = data.size.toLong()
            crc = CRC32().apply { update(data) }.value
          }
          zip.putNextEntry(e); zip.write(data); zip.closeEntry()
        }
        for ((name, data) in entries) if (name != "mimetype") {
          zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
        }
      }
      return bos.toByteArray()
    }

    fun sha256(text: String): String =
      MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
  }
}
