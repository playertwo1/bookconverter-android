package io.github.playertwo1.bookconverter.core.translate

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object TextExporter {

  /**
   * Exports text as clean UTF-8 bytes.
   */
  fun toTxt(text: String): ByteArray {
    return text.toByteArray(Charsets.UTF_8)
  }

  /**
   * Exports text as Markdown bytes with optional `# $title\n\n` header.
   */
  fun toMarkdown(text: String, title: String? = null): ByteArray {
    val content = if (!title.isNullOrBlank()) {
      "# ${title.trim()}\n\n$text"
    } else {
      text
    }
    return content.toByteArray(Charsets.UTF_8)
  }

  /**
   * Generates a 100% valid Office Open XML (.docx) file containing:
   * - `[Content_Types].xml`
   * - `_rels/.rels`
   * - `word/_rels/document.xml.rels`
   * - `word/document.xml`
   * with proper XML escaping and paragraphs wrapped in `<w:p><w:r><w:t xml:space="preserve">...</w:t></w:r></w:p>`.
   */
  fun toDocx(text: String, title: String? = null): ByteArray {
    val baos = ByteArrayOutputStream()
    ZipOutputStream(baos).use { zip ->
      // 1. [Content_Types].xml
      zip.putNextEntry(ZipEntry("[Content_Types].xml"))
      zip.write(CONTENT_TYPES_XML.toByteArray(Charsets.UTF_8))
      zip.closeEntry()

      // 2. _rels/.rels
      zip.putNextEntry(ZipEntry("_rels/.rels"))
      zip.write(PACKAGE_RELS_XML.toByteArray(Charsets.UTF_8))
      zip.closeEntry()

      // 3. word/_rels/document.xml.rels
      zip.putNextEntry(ZipEntry("word/_rels/document.xml.rels"))
      zip.write(DOCUMENT_RELS_XML.toByteArray(Charsets.UTF_8))
      zip.closeEntry()

      // 4. word/document.xml
      zip.putNextEntry(ZipEntry("word/document.xml"))
      zip.write(buildDocumentXml(text, title).toByteArray(Charsets.UTF_8))
      zip.closeEntry()
    }
    return baos.toByteArray()
  }

  private fun buildDocumentXml(text: String, title: String?): String {
    val sb = StringBuilder()
    sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
    sb.append("\n")
    sb.append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""")
    sb.append("\n  <w:body>\n")

    if (!title.isNullOrBlank()) {
      sb.append("    <w:p><w:r><w:t xml:space=\"preserve\">")
      sb.append(escapeXml(title.trim()))
      sb.append("</w:t></w:r></w:p>\n")
    }

    val lines = if (text.isEmpty()) {
      if (title.isNullOrBlank()) listOf("") else emptyList()
    } else {
      text.replace("\r\n", "\n").split("\n")
    }

    for (line in lines) {
      sb.append("    <w:p><w:r><w:t xml:space=\"preserve\">")
      sb.append(escapeXml(line))
      sb.append("</w:t></w:r></w:p>\n")
    }

    sb.append("  </w:body>\n")
    sb.append("</w:document>")
    return sb.toString()
  }

  internal fun escapeXml(input: String): String {
    val sb = StringBuilder(input.length)
    for (ch in input) {
      when (ch) {
        '&' -> sb.append("&amp;")
        '<' -> sb.append("&lt;")
        '>' -> sb.append("&gt;")
        '"' -> sb.append("&quot;")
        '\'' -> sb.append("&apos;")
        else -> sb.append(ch)
      }
    }
    return sb.toString()
  }

  private const val CONTENT_TYPES_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>"""

  private const val PACKAGE_RELS_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

  private const val DOCUMENT_RELS_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
</Relationships>"""
}
