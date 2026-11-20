package io.github.playertwo1.bookconverter.core.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

typealias MobiKind = MobiSniffer.MobiKind
typealias MobiProbe = MobiSniffer.MobiProbe

object MobiSniffer {
  enum class MobiKind { MOBI, AZW3 }

  data class MobiProbe(
    val kind: MobiKind,
    val isDrm: Boolean,
    val headerVersion: Int,
    val encryptionType: Int,
  )

  fun probe(file: File): MobiProbe? {
    if (!file.exists() || !file.isFile || file.length() < 82) return null
    return try {
      RandomAccessFile(file, "r").use { raf ->
        if (raf.length() < 82) return null
        raf.seek(60)
        val magic = ByteArray(8)
        raf.readFully(magic)
        if (String(magic, Charsets.US_ASCII) != "BOOKMOBI") return null

        raf.seek(78)
        val record0Offset = raf.readInt().toLong() and 0xFFFFFFFFL
        if (record0Offset < 0 || record0Offset + 40 > raf.length()) return null

        raf.seek(record0Offset + 12)
        val encryptionType = raf.readUnsignedShort()

        raf.seek(record0Offset + 36)
        val headerVersion = raf.readInt()

        val isDrm = encryptionType != 0
        val kind = if (headerVersion >= 8) MobiKind.AZW3 else MobiKind.MOBI
        MobiProbe(kind, isDrm, headerVersion, encryptionType)
      }
    } catch (_: Exception) {
      null
    }
  }

  fun probe(bytes: ByteArray): MobiProbe? {
    if (bytes.size < 82) return null
    return try {
      val magic = bytes.sliceArray(60 until 68)
      if (String(magic, Charsets.US_ASCII) != "BOOKMOBI") return null
      val record0Offset = ((bytes[78].toLong() and 0xFF) shl 24) or
        ((bytes[79].toLong() and 0xFF) shl 16) or
        ((bytes[80].toLong() and 0xFF) shl 8) or
        (bytes[81].toLong() and 0xFF)
      if (record0Offset < 0 || record0Offset + 40 > bytes.size) return null
      val rec0 = record0Offset.toInt()
      val encryptionType = ((bytes[rec0 + 12].toInt() and 0xFF) shl 8) or (bytes[rec0 + 13].toInt() and 0xFF)
      val headerVersion = ((bytes[rec0 + 36].toInt() and 0xFF) shl 24) or
        ((bytes[rec0 + 37].toInt() and 0xFF) shl 16) or
        ((bytes[rec0 + 38].toInt() and 0xFF) shl 8) or
        (bytes[rec0 + 39].toInt() and 0xFF)
      val isDrm = encryptionType != 0
      val kind = if (headerVersion >= 8) MobiKind.AZW3 else MobiKind.MOBI
      MobiProbe(kind, isDrm, headerVersion, encryptionType)
    } catch (_: Exception) {
      null
    }
  }
}

object MobiEpubPackager {
  private data class ChapterInfo(val path: String, val title: String, val id: String)

  private val BLOCK_TAGS = setOf(
    "p", "div", "h1", "h2", "h3", "h4", "h5", "h6",
    "li", "blockquote", "dt", "dd", "figcaption", "td", "th",
    "table", "thead", "tbody", "tfoot", "tr", "ul", "ol", "dl",
    "figure", "section", "article", "header", "footer", "nav", "aside", "hr", "pre",
  )

  fun packageExtractedDir(
    extractedDir: File,
    outputEpub: File,
    title: String?,
    author: String?,
    language: String?,
  ) {
    if (!extractedDir.exists() || !extractedDir.isDirectory) {
      throw EpubException("Diretório de extração inexistente: ${extractedDir.path}")
    }

    val markupDir = File(extractedDir, "markup")
    val htmlExtensions = setOf("html", "xhtml", "htm", "xml")
    val rawMarkupFiles = if (markupDir.isDirectory) {
      markupDir.listFiles { f -> f.isFile && f.extension.lowercase() in htmlExtensions }?.sortedBy { it.name }
    } else null

    val markupFiles = if (!rawMarkupFiles.isNullOrEmpty()) {
      rawMarkupFiles
    } else {
      extractedDir.listFiles { f -> f.isFile && f.extension.lowercase() in htmlExtensions }?.sortedBy { it.name } ?: emptyList()
    }

    if (markupFiles.isEmpty()) {
      throw EpubException("Nenhum arquivo de markup HTML encontrado em ${extractedDir.path}")
    }

    val resourcesDir = File(extractedDir, "resources")
    val rawResourceFiles = if (resourcesDir.isDirectory) {
      resourcesDir.listFiles { f -> f.isFile }?.sortedBy { it.name } ?: emptyList()
    } else {
      emptyList()
    }

    val existingNcxFile = rawResourceFiles.firstOrNull { it.extension.lowercase() == "ncx" }
      ?: extractedDir.listFiles { f -> f.isFile && f.extension.lowercase() == "ncx" }?.firstOrNull()

    val resourceFiles = rawResourceFiles.filter { it.extension.lowercase() != "ncx" }
    val entries = LinkedHashMap<String, ByteArray>()
    val chapters = mutableListOf<ChapterInfo>()

    markupFiles.forEachIndexed { index, file ->
      val rawContent = file.readText(Charsets.UTF_8)
      val doc = Jsoup.parse(rawContent, "", Parser.htmlParser())
      doc.outputSettings().syntax(Document.OutputSettings.Syntax.xml).charset(Charsets.UTF_8).prettyPrint(false)

      val html = doc.selectFirst("html") ?: doc.appendElement("html")
      if (html.attr("xmlns").isBlank()) {
        html.attr("xmlns", "http://www.w3.org/1999/xhtml")
      }
      val head = doc.head() ?: html.prependElement("head")
      val body = doc.body() ?: html.appendElement("body")

      val chapterTitle = doc.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
        ?: doc.selectFirst("h2")?.text()?.takeIf { it.isNotBlank() }
        ?: doc.selectFirst("h3")?.text()?.takeIf { it.isNotBlank() }
        ?: doc.selectFirst("title")?.text()?.takeIf { it.isNotBlank() && it.lowercase() != "untitled" }
        ?: "Capítulo ${index + 1}"

      if (head.selectFirst("title") == null) {
        head.appendElement("title").text(chapterTitle)
      } else if (head.selectFirst("title")?.text().isNullOrBlank()) {
        head.selectFirst("title")?.text(chapterTitle)
      }

      val cssFiles = resourceFiles.filter { it.extension.lowercase() == "css" }
      for (css in cssFiles) {
        val cssHref = "../resources/${css.name}"
        if (head.select("link[href*='${css.name}']").isEmpty()) {
          head.appendElement("link")
            .attr("rel", "stylesheet")
            .attr("type", "text/css")
            .attr("href", cssHref)
        }
      }

      rewriteResources(doc, resourceFiles)
      wrapLooseContentInBody(body)

      val chapterId = "chapter_${String.format("%04d", index)}"
      val chapterZipPath = "OEBPS/chapters/part_${String.format("%04d", index)}.xhtml"
      val serialized = serializeDoc(doc)
      entries[chapterZipPath] = serialized
      chapters.add(ChapterInfo(path = chapterZipPath, title = chapterTitle, id = chapterId))
    }

    val bookTitle = title?.takeIf { it.isNotBlank() } ?: chapters.firstOrNull()?.title ?: "Livro"
    val bookAuthor = author?.takeIf { it.isNotBlank() } ?: "Desconhecido"
    val bookLang = language?.takeIf { it.isNotBlank() } ?: "pt"

    val tocXml = buildNcx(
      bookTitle = bookTitle,
      chapters = chapters,
      existingNcxFile = existingNcxFile,
      markupFiles = markupFiles,
    )
    entries["OEBPS/toc.ncx"] = tocXml.toByteArray(Charsets.UTF_8)

    val manifestResourceItems = mutableListOf<Pair<String, String>>()
    resourceFiles.forEachIndexed { index, file ->
      val resZipPath = "OEBPS/resources/${file.name}"
      entries[resZipPath] = file.readBytes()
      manifestResourceItems.add("res_${String.format("%04d", index)}" to resZipPath)
    }

    val opfXml = buildOpf(
      title = bookTitle,
      author = bookAuthor,
      language = bookLang,
      chapters = chapters,
      resources = manifestResourceItems,
    )
    entries["OEBPS/content.opf"] = opfXml.toByteArray(Charsets.UTF_8)

    val containerXml = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>""".trimIndent()
    entries["META-INF/container.xml"] = containerXml.toByteArray(Charsets.UTF_8)

    writeEpubZip(entries, outputEpub)
  }

  private fun serializeDoc(doc: Document): ByteArray {
    doc.outputSettings().syntax(Document.OutputSettings.Syntax.xml).charset(Charsets.UTF_8).prettyPrint(false)
    val xml = doc.outerHtml()
    val fullXml = if (xml.startsWith("<?xml")) xml else "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n$xml"
    return fullXml.toByteArray(Charsets.UTF_8)
  }

  private fun rewriteResources(doc: Document, resourceFiles: List<File>) {
    if (resourceFiles.isEmpty()) return

    for (img in doc.select("img")) {
      val recindexStr = img.attr("recindex").takeIf { it.isNotBlank() }
      val src = img.attr("src").takeIf { it.isNotBlank() }

      val matchedFile = findMatchingResource(recindexStr, src, resourceFiles)
      if (matchedFile != null) {
        img.attr("src", "../resources/${matchedFile.name}")
      } else if (src != null && !src.startsWith("http://") && !src.startsWith("https://") && !src.startsWith("../resources/")) {
        val baseName = src.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val fileByName = resourceFiles.firstOrNull { it.name.equals(baseName, ignoreCase = true) }
        if (fileByName != null) {
          img.attr("src", "../resources/${fileByName.name}")
        }
      }
    }

    for (img in doc.select("image")) {
      val href = img.attr("xlink:href").takeIf { it.isNotBlank() } ?: img.attr("href").takeIf { it.isNotBlank() }
      if (href != null && !href.startsWith("http://") && !href.startsWith("https://") && !href.startsWith("../resources/")) {
        val baseName = href.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val fileByName = resourceFiles.firstOrNull { it.name.equals(baseName, ignoreCase = true) }
        if (fileByName != null) {
          if (img.hasAttr("xlink:href")) img.attr("xlink:href", "../resources/${fileByName.name}")
          if (img.hasAttr("href")) img.attr("href", "../resources/${fileByName.name}")
        }
      }
    }
  }

  private fun findMatchingResource(recindexStr: String?, src: String?, resourceFiles: List<File>): File? {
    val recindex = recindexStr?.toIntOrNull()
      ?: if (src != null && src.startsWith("kindle:embed:")) {
        src.substringAfter("kindle:embed:").substringBefore('?').toIntOrNull()
      } else null

    if (recindex != null) {
      val candidate = resourceFiles.firstOrNull { f ->
        val base = f.nameWithoutExtension
        base.toIntOrNull() == recindex ||
          base.endsWith(String.format("%04d", recindex)) ||
          base.endsWith(String.format("%05d", recindex)) ||
          base.endsWith("_$recindex") ||
          base == "res_$recindex"
      }
      if (candidate != null) return candidate

      if (recindex in 1..resourceFiles.size) {
        return resourceFiles[recindex - 1]
      }
      if (recindex in resourceFiles.indices) {
        return resourceFiles[recindex]
      }
    }

    if (src != null) {
      val baseName = src.substringBefore('?').substringBefore('#').substringAfterLast('/')
      return resourceFiles.firstOrNull { it.name.equals(baseName, ignoreCase = true) }
    }

    return null
  }

  private fun wrapLooseContentInBody(body: Element) {
    wrapLooseNodes(body)
  }

  private fun wrapLooseNodes(container: Element) {
    val children = container.childNodes().toList()
    val currentRun = mutableListOf<Node>()

    fun flushRun() {
      if (currentRun.isEmpty()) return
      val hasContent = currentRun.any { node ->
        when (node) {
          is TextNode -> node.text().isNotBlank()
          is Element -> node.normalName() != "br"
          else -> false
        }
      }
      if (hasContent) {
        val p = container.ownerDocument()?.createElement("p") ?: Element("p")
        val first = currentRun.first()
        first.before(p)
        for (node in currentRun) {
          node.remove()
          p.appendChild(node)
        }
      }
      currentRun.clear()
    }

    for (node in children) {
      val isBlock = node is Element && isBlockTag(node.normalName())
      if (isBlock) {
        flushRun()
        if (hasBlockChildren(node as Element)) {
          wrapLooseNodes(node)
        }
      } else {
        currentRun.add(node)
      }
    }
    flushRun()
  }

  private fun isBlockTag(tag: String): Boolean = tag.lowercase() in BLOCK_TAGS

  private fun hasBlockChildren(element: Element): Boolean =
    element.children().any { isBlockTag(it.normalName()) }

  private fun buildNcx(
    bookTitle: String,
    chapters: List<ChapterInfo>,
    existingNcxFile: File?,
    markupFiles: List<File>,
  ): String {
    val uuid = UUID.randomUUID().toString()

    val existingNavPoints = if (existingNcxFile != null && existingNcxFile.exists()) {
      try {
        val doc = Jsoup.parse(existingNcxFile.readText(Charsets.UTF_8), "", Parser.xmlParser())
        doc.select("navPoint")
      } catch (_: Exception) {
        null
      }
    } else null

    val navMapEntries = mutableListOf<String>()

    if (!existingNavPoints.isNullOrEmpty()) {
      var mappedCount = 0
      for ((index, np) in existingNavPoints.withIndex()) {
        val text = np.select("navLabel > text").text().takeIf { it.isNotBlank() } ?: "Capítulo ${index + 1}"
        val src = np.select("content").attr("src")
        val targetFileName = src.substringBefore('#').substringAfterLast('/')
        val fragment = if ('#' in src) "#" + src.substringAfter('#') else ""

        val chapterIndex = markupFiles.indexOfFirst { it.name.equals(targetFileName, ignoreCase = true) }
        val targetChapter = if (chapterIndex >= 0) chapters.getOrNull(chapterIndex) else chapters.getOrNull(index)

        if (targetChapter != null) {
          val playOrder = index + 1
          val cleanSrc = "chapters/${targetChapter.path.substringAfterLast('/')}$fragment"
          navMapEntries.add(
            """    <navPoint id="navPoint-$playOrder" playOrder="$playOrder">
      <navLabel>
        <text>${escapeXml(text)}</text>
      </navLabel>
      <content src="$cleanSrc"/>
    </navPoint>"""
          )
          mappedCount++
        }
      }
      if (mappedCount == 0) navMapEntries.clear()
    }

    if (navMapEntries.isEmpty()) {
      for ((index, chapter) in chapters.withIndex()) {
        val playOrder = index + 1
        val cleanSrc = "chapters/${chapter.path.substringAfterLast('/')}"
        navMapEntries.add(
          """    <navPoint id="navPoint-$playOrder" playOrder="$playOrder">
      <navLabel>
        <text>${escapeXml(chapter.title)}</text>
      </navLabel>
      <content src="$cleanSrc"/>
    </navPoint>"""
        )
      }
    }

    return """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head>
    <meta name="dtb:uid" content="urn:uuid:$uuid"/>
    <meta name="dtb:depth" content="1"/>
    <meta name="dtb:totalPageCount" content="0"/>
    <meta name="dtb:maxPageNumber" content="0"/>
  </head>
  <docTitle>
    <text>${escapeXml(bookTitle)}</text>
  </docTitle>
  <navMap>
${navMapEntries.joinToString("\n")}
  </navMap>
</ncx>"""
  }

  private fun buildOpf(
    title: String,
    author: String,
    language: String,
    chapters: List<ChapterInfo>,
    resources: List<Pair<String, String>>,
  ): String {
    val uuid = UUID.randomUUID().toString()

    val manifestItems = buildList {
      add("""    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>""")
      for (ch in chapters) {
        val relHref = "chapters/${ch.path.substringAfterLast('/')}"
        add("""    <item id="${ch.id}" href="$relHref" media-type="application/xhtml+xml"/>""")
      }
      for ((id, path) in resources) {
        val relHref = "resources/${path.substringAfterLast('/')}"
        val mediaType = mimeTypeOf(path)
        add("""    <item id="$id" href="$relHref" media-type="$mediaType"/>""")
      }
    }

    val spineItems = chapters.map { ch ->
      """    <itemref idref="${ch.id}"/>"""
    }

    return """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="BookId">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
    <dc:title>${escapeXml(title)}</dc:title>
    <dc:creator opf:role="aut">${escapeXml(author)}</dc:creator>
    <dc:language>${escapeXml(language)}</dc:language>
    <dc:identifier id="BookId">urn:uuid:$uuid</dc:identifier>
  </metadata>
  <manifest>
${manifestItems.joinToString("\n")}
  </manifest>
  <spine toc="ncx">
${spineItems.joinToString("\n")}
  </spine>
</package>"""
  }

  private fun writeEpubZip(entries: Map<String, ByteArray>, outputEpub: File) {
    outputEpub.parentFile?.mkdirs()
    val mimetypeBytes = "application/epub+zip".toByteArray(Charsets.US_ASCII)

    FileOutputStream(outputEpub).use { fos ->
      ZipOutputStream(fos).use { zip ->
        val mimetypeEntry = ZipEntry("mimetype").apply {
          method = ZipEntry.STORED
          size = mimetypeBytes.size.toLong()
          compressedSize = mimetypeBytes.size.toLong()
          crc = CRC32().apply { update(mimetypeBytes) }.value
        }
        zip.putNextEntry(mimetypeEntry)
        zip.write(mimetypeBytes)
        zip.closeEntry()

        for ((name, data) in entries) {
          if (name == "mimetype") continue
          val entry = ZipEntry(name).apply {
            method = ZipEntry.DEFLATED
          }
          zip.putNextEntry(entry)
          zip.write(data)
          zip.closeEntry()
        }
      }
    }
  }

  private fun escapeXml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")

  private fun mimeTypeOf(path: String): String {
    return when (path.substringAfterLast('.', "").lowercase()) {
      "png" -> "image/png"
      "jpg", "jpeg" -> "image/jpeg"
      "gif" -> "image/gif"
      "svg" -> "image/svg+xml"
      "webp" -> "image/webp"
      "css" -> "text/css"
      "ttf" -> "font/ttf"
      "otf" -> "font/otf"
      "woff" -> "font/woff"
      "woff2" -> "font/woff2"
      "ncx" -> "application/x-dtbncx+xml"
      "xhtml", "html", "htm" -> "application/xhtml+xml"
      else -> "application/octet-stream"
    }
  }
}
