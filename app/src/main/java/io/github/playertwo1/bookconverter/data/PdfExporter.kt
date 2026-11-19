package io.github.playertwo1.bookconverter.data

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.ByteArrayOutputStream

/**
 * Native Android PDF exporter for translated text and quick translations.
 * Uses [PdfDocument] and [StaticLayout] for clean multi-page pagination
 * with zero external dependencies.
 */
object PdfExporter {

    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 48f
    private const val TITLE_SIZE_PT = 16f
    private const val BODY_SIZE_PT = 11f
    private const val TITLE_BOTTOM_SPACING = 16f

    /**
     * Converts the given [text] into PDF bytes formatted as A4 pages (595 x 842 pt).
     *
     * @param text The body text to format and export into PDF.
     * @param title Optional title rendered in bold 16pt at the top of the first page.
     * @return The raw PDF bytes.
     */
    fun toPdfBytes(text: String, title: String? = null): ByteArray {
        val document = PdfDocument()
        val outputStream = ByteArrayOutputStream()

        try {
            val contentWidth = (PAGE_WIDTH - 2 * MARGIN).toInt().coerceAtLeast(1)

            val titlePaint = TextPaint().apply {
                isAntiAlias = true
                color = Color.BLACK
                textSize = TITLE_SIZE_PT
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            }

            val bodyPaint = TextPaint().apply {
                isAntiAlias = true
                color = Color.BLACK
                textSize = BODY_SIZE_PT
                typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            }

            val titleLayout = if (!title.isNullOrBlank()) {
                StaticLayout.Builder.obtain(title, 0, title.length, titlePaint, contentWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false)
                    .build()
            } else {
                null
            }

            // Handle empty text: produce a single valid page (with title if present).
            if (text.isEmpty()) {
                val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
                val page = document.startPage(pageInfo)
                val canvas = page.canvas
                canvas.drawColor(Color.WHITE)

                if (titleLayout != null) {
                    canvas.save()
                    canvas.translate(MARGIN, MARGIN)
                    titleLayout.draw(canvas)
                    canvas.restore()
                }

                document.finishPage(page)
                document.writeTo(outputStream)
                return outputStream.toByteArray()
            }

            val bodyLayout = StaticLayout.Builder.obtain(text, 0, text.length, bodyPaint, contentWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.15f)
                .setIncludePad(false)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .build()

            val totalLines = bodyLayout.lineCount
            var currentLine = 0
            var pageNumber = 1

            while (currentLine < totalLines) {
                val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
                val page = document.startPage(pageInfo)
                val canvas = page.canvas
                canvas.drawColor(Color.WHITE)

                val startY: Float
                val usableHeight: Float

                if (pageNumber == 1 && titleLayout != null) {
                    canvas.save()
                    canvas.translate(MARGIN, MARGIN)
                    titleLayout.draw(canvas)
                    canvas.restore()

                    startY = MARGIN + titleLayout.height + TITLE_BOTTOM_SPACING
                    usableHeight = (PAGE_HEIGHT - MARGIN) - startY
                } else {
                    startY = MARGIN
                    usableHeight = PAGE_HEIGHT - 2 * MARGIN
                }

                val pageStartLine = currentLine
                val firstLineTop = bodyLayout.getLineTop(pageStartLine)
                var pageEndLine = pageStartLine

                while (pageEndLine < totalLines) {
                    val lineBottom = bodyLayout.getLineBottom(pageEndLine)
                    val heightSoFar = lineBottom - firstLineTop
                    if (heightSoFar > usableHeight && pageEndLine > pageStartLine) {
                        break
                    }
                    pageEndLine++
                }

                if (pageEndLine == pageStartLine) {
                    // Fallback to guarantee progress even if usableHeight is very small
                    pageEndLine = pageStartLine + 1
                }

                val clipTop = firstLineTop
                val clipBottom = bodyLayout.getLineBottom(pageEndLine - 1)

                canvas.save()
                canvas.clipRect(MARGIN, startY, MARGIN + contentWidth, startY + (clipBottom - clipTop))
                canvas.translate(MARGIN, startY - clipTop)
                bodyLayout.draw(canvas)
                canvas.restore()

                document.finishPage(page)

                currentLine = pageEndLine
                pageNumber++
            }

            document.writeTo(outputStream)
            return outputStream.toByteArray()
        } finally {
            document.close()
        }
    }
}
