package com.kannadadoc.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import com.kannadadoc.parser.DocBlock
import com.kannadadoc.parser.ParsedDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Generates crisp, vector-text A4 PDFs with HarfBuzz Kannada shaping using Android's
 * public PdfDocument + StaticLayout APIs (100% safe on Android 9 through Android 16+).
 *
 * Paginates long paragraphs and tall table rows line-by-line across pages via
 * [PdfPaginationPolicy] so zero lines are ever clipped off the bottom of a page.
 */
class PdfExporter(private val context: Context) {

    companion object {
        // A4 at 72 DPI
        private const val PAGE_WIDTH = 595
        private const val PAGE_HEIGHT = 842
        private const val MARGIN_X = 48f
        private const val MARGIN_TOP = 54f
        private const val MARGIN_BOTTOM = 54f
        private const val MAX_PAGE_Y = PAGE_HEIGHT - MARGIN_BOTTOM
        private const val CONTENT_WIDTH = (PAGE_WIDTH - MARGIN_X * 2).toInt()
    }

    suspend fun exportToPdf(document: ParsedDocument, baseFileName: String): File =
        withContext(Dispatchers.IO) {
            val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val safeName = baseFileName.substringBeforeLast('.')
                .replace(Regex("[^a-zA-Z0-9._\\-\\u0C80-\\u0CFF ]"), "_")
                .ifBlank { "KannadaDocument" }
            val outFile = File(exportDir, "$safeName.pdf")

            val baseTypeface = loadKannadaTypeface()
            val pdfDoc = PdfDocument()

            try {
                var pageNumber = 1
                var currentPage = pdfDoc.startPage(
                    PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
                )
                var canvas = currentPage.canvas
                var cursorY = MARGIN_TOP

                val borderPaint = Paint().apply {
                    color = Color.parseColor("#B0B8B0")
                    style = Paint.Style.STROKE
                    strokeWidth = 0.75f
                }
                val headerBgPaint = Paint().apply {
                    color = Color.parseColor("#F1F5F1")
                    style = Paint.Style.FILL
                }
                val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#757575")
                    textSize = 9f
                    typeface = baseTypeface
                }

                fun finishCurrentPage() {
                    val footerText = "Page $pageNumber"
                    val w = footerPaint.measureText(footerText)
                    canvas.drawText(footerText, PAGE_WIDTH - MARGIN_X - w, PAGE_HEIGHT - 24f, footerPaint)
                    pdfDoc.finishPage(currentPage)
                }

                fun startNextPage() {
                    finishCurrentPage()
                    pageNumber++
                    currentPage = pdfDoc.startPage(
                        PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
                    )
                    canvas = currentPage.canvas
                    cursorY = MARGIN_TOP
                }

                var orderedIndex = 0

                for (block in document.blocks) {
                    when (block) {
                        is DocBlock.Paragraph -> {
                            if (block.listType == "ordered") orderedIndex++ else orderedIndex = 0
                            val prefix = when (block.listType) {
                                "bullet" -> "•  "
                                "ordered" -> "$orderedIndex.  "
                                else -> ""
                            }

                            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                                color = Color.parseColor("#1A1C1A")
                                typeface = baseTypeface
                                textSize = when (block.headingLevel) {
                                    1 -> 18f
                                    2 -> 15f
                                    3 -> 13.5f
                                    else -> 12f
                                }
                            }

                            val spannable = buildSpannable(block, prefix)
                            val layout = buildStaticLayout(spannable, paint, CONTENT_WIDTH, block.alignment)
                            val (lineTops, lineBottoms) = extractLineBounds(layout)
                            val keepWholeLimit = if (block.headingLevel > 0) Int.MAX_VALUE else 3

                            val (slices, _) = PdfPaginationPolicy.paginateSingleLayout(
                                lineTops = lineTops,
                                lineBottoms = lineBottoms,
                                startCursorY = cursorY,
                                marginTop = MARGIN_TOP,
                                maxPageY = MAX_PAGE_Y,
                                keepWholeMaxLines = keepWholeLimit
                            )

                            for (slice in slices) {
                                if (slice.pageBreakBefore) {
                                    startNextPage()
                                }
                                canvas.save()
                                canvas.clipRect(
                                    MARGIN_X - 2f,
                                    cursorY,
                                    PAGE_WIDTH - MARGIN_X + 2f,
                                    cursorY + slice.sliceHeight + 1f
                                )
                                canvas.translate(MARGIN_X, cursorY - slice.sliceTop)
                                layout.draw(canvas)
                                canvas.restore()
                                cursorY += slice.sliceHeight
                            }

                            val trailingGap = if (block.headingLevel > 0) 10f else 6f
                            cursorY = (cursorY + trailingGap).coerceAtMost(MAX_PAGE_Y)
                        }

                        is DocBlock.Table -> {
                            orderedIndex = 0
                            drawTable(
                                table = block,
                                baseTypeface = baseTypeface,
                                borderPaint = borderPaint,
                                headerBgPaint = headerBgPaint,
                                getCanvas = { canvas },
                                getCursorY = { cursorY },
                                advanceY = { dy -> cursorY += dy },
                                startNextPage = { startNextPage() }
                            )
                            cursorY = (cursorY + 10f).coerceAtMost(MAX_PAGE_Y)
                        }
                    }
                }

                finishCurrentPage()

                FileOutputStream(outFile).use { fos ->
                    pdfDoc.writeTo(fos)
                }
                outFile
            } finally {
                runCatching { pdfDoc.close() }
            }
        }

    private fun extractLineBounds(layout: StaticLayout): Pair<IntArray, IntArray> {
        val count = layout.lineCount
        val tops = IntArray(count) { layout.getLineTop(it) }
        val bottoms = IntArray(count) { layout.getLineBottom(it) }
        return tops to bottoms
    }

    private fun drawTable(
        table: DocBlock.Table,
        baseTypeface: Typeface,
        borderPaint: Paint,
        headerBgPaint: Paint,
        getCanvas: () -> Canvas,
        getCursorY: () -> Float,
        advanceY: (Float) -> Unit,
        startNextPage: () -> Unit
    ) {
        val cellPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1A1C1A")
            typeface = baseTypeface
            textSize = 11f
        }
        val cellPad = 6f

        table.rows.forEachIndexed { rowIndex, row ->
            if (row.isEmpty()) return@forEachIndexed
            val colCount = row.size
            val colWidth = CONTENT_WIDTH.toFloat() / colCount
            val innerWidth = (colWidth - cellPad * 2).toInt().coerceAtLeast(24)

            val cellLayouts = row.map { cellParas ->
                val sb = SpannableStringBuilder()
                cellParas.forEachIndexed { pIdx, p ->
                    if (pIdx > 0) sb.append("\n")
                    sb.append(buildSpannable(p, ""))
                }
                buildStaticLayout(sb, cellPaint, innerWidth, "left")
            }

            val boundsList = cellLayouts.map { extractLineBounds(it) }
            val (rowSlices, _) = PdfPaginationPolicy.paginateTableRow(
                colLineTops = boundsList.map { it.first },
                colLineBottoms = boundsList.map { it.second },
                cellPad = cellPad,
                startCursorY = getCursorY(),
                marginTop = MARGIN_TOP,
                maxPageY = MAX_PAGE_Y
            )

            rowSlices.forEachIndexed { sliceIndex, slice ->
                if (slice.pageBreakBefore) {
                    startNextPage()
                }
                val topY = getCursorY()
                val c = getCanvas()
                val sliceHeight = slice.sliceRowHeight

                cellLayouts.forEachIndexed { colIndex, layout ->
                    val leftX = MARGIN_X + colIndex * colWidth
                    if (rowIndex == 0 && sliceIndex == 0) {
                        c.drawRect(leftX, topY, leftX + colWidth, topY + sliceHeight, headerBgPaint)
                    }
                    c.drawRect(leftX, topY, leftX + colWidth, topY + sliceHeight, borderPaint)

                    val startL = slice.colStartLines[colIndex]
                    val endL = slice.colEndLines[colIndex]
                    if (startL <= endL && startL >= 0 && endL < layout.lineCount) {
                        val sliceTop = layout.getLineTop(startL).toFloat()
                        val sliceTextHeight = (layout.getLineBottom(endL) - layout.getLineTop(startL)).toFloat()
                        c.save()
                        c.clipRect(
                            leftX + cellPad - 1f,
                            topY + cellPad,
                            leftX + colWidth - cellPad + 1f,
                            topY + cellPad + sliceTextHeight + 1f
                        )
                        c.translate(leftX + cellPad, topY + cellPad - sliceTop)
                        layout.draw(c)
                        c.restore()
                    }
                }
                advanceY(sliceHeight)
            }
        }
    }

    private fun buildSpannable(para: DocBlock.Paragraph, prefix: String): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        if (prefix.isNotEmpty()) sb.append(prefix)
        for (run in para.runs) {
            val start = sb.length
            sb.append(run.text)
            val end = sb.length
            if (end > start) {
                val style = when {
                    (run.bold || para.headingLevel > 0) && run.italic -> Typeface.BOLD_ITALIC
                    run.bold || para.headingLevel > 0 -> Typeface.BOLD
                    run.italic -> Typeface.ITALIC
                    else -> Typeface.NORMAL
                }
                if (style != Typeface.NORMAL) {
                    sb.setSpan(StyleSpan(style), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (run.underline) {
                    sb.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        return sb
    }

    private fun buildStaticLayout(
        text: CharSequence,
        paint: TextPaint,
        width: Int,
        alignment: String
    ): StaticLayout {
        val align = when (alignment) {
            "center" -> Layout.Alignment.ALIGN_CENTER
            "right" -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(align)
            .setLineSpacing(4f, 1.35f) // Extra line spacing for Kannada subscripts
            .setIncludePad(true)
            .build()
    }

    private fun loadKannadaTypeface(): Typeface {
        return try {
            Typeface.createFromAsset(context.assets, "fonts/unicode/NotoSansKannada-Regular.ttf")
        } catch (_: Exception) {
            Typeface.SANS_SERIF
        }
    }
}
