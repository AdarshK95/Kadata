package com.kannadadoc.export

/**
 * Pure Kotlin geometric pagination planner for Android `StaticLayout` paragraphs and table rows.
 *
 * Guarantees:
 * - Zero lines are ever drawn below `maxPageY` (no bottom clipping on long Kannada paragraphs or tall cells).
 * - Every line index `0 until lineCount` is emitted exactly once in order.
 * - Strictly terminates even if a single line exceeds full printable page height.
 */
object PdfPaginationPolicy {

    data class PageLineSlice(
        val startLine: Int,
        val endLine: Int,
        val pageBreakBefore: Boolean,
        val sliceTop: Float,
        val sliceHeight: Float
    )

    data class TableRowPageSlice(
        val colStartLines: IntArray,
        val colEndLines: IntArray,
        val pageBreakBefore: Boolean,
        val sliceRowHeight: Float
    )

    /**
     * Plans page slices for a single paragraph `StaticLayout`.
     * Returns `(slices, finalCursorY)`.
     */
    fun paginateSingleLayout(
        lineTops: IntArray,
        lineBottoms: IntArray,
        startCursorY: Float,
        marginTop: Float,
        maxPageY: Float,
        keepWholeMaxLines: Int = 3
    ): Pair<List<PageLineSlice>, Float> {
        val lineCount = minOf(lineTops.size, lineBottoms.size)
        if (lineCount <= 0) {
            return emptyList<PageLineSlice>() to startCursorY
        }

        val fullPageHeight = (maxPageY - marginTop).coerceAtLeast(1f)
        val totalHeight = (lineBottoms[lineCount - 1] - lineTops[0]).toFloat().coerceAtLeast(0f)

        // Fast path 1: fits cleanly on the current page
        if (startCursorY + totalHeight <= maxPageY) {
            return listOf(
                PageLineSlice(
                    startLine = 0,
                    endLine = lineCount - 1,
                    pageBreakBefore = false,
                    sliceTop = lineTops[0].toFloat(),
                    sliceHeight = totalHeight
                )
            ) to (startCursorY + totalHeight)
        }

        // Fast path 2: keep headings / short paragraphs intact on a fresh page when they fit in 1 page
        if (totalHeight <= fullPageHeight && lineCount <= keepWholeMaxLines && startCursorY > marginTop + 1f) {
            return listOf(
                PageLineSlice(
                    startLine = 0,
                    endLine = lineCount - 1,
                    pageBreakBefore = true,
                    sliceTop = lineTops[0].toFloat(),
                    sliceHeight = totalHeight
                )
            ) to (marginTop + totalHeight)
        }

        val slices = mutableListOf<PageLineSlice>()
        var cursorY = startCursorY
        var lineIdx = 0
        var pendingPageBreak = false

        while (lineIdx < lineCount) {
            var available = maxPageY - cursorY
            val firstLineHeight = (lineBottoms[lineIdx] - lineTops[lineIdx]).toFloat().coerceAtLeast(0f)

            if (firstLineHeight > available && cursorY > marginTop + 1f) {
                pendingPageBreak = true
                cursorY = marginTop
                available = fullPageHeight
            }

            val sliceTop = lineTops[lineIdx]
            var endLine = lineIdx
            while (endLine + 1 < lineCount) {
                val candidateHeight = (lineBottoms[endLine + 1] - sliceTop).toFloat()
                if (candidateHeight <= available) {
                    endLine++
                } else {
                    break
                }
            }

            val sliceHeight = (lineBottoms[endLine] - sliceTop).toFloat().coerceAtLeast(0f)
            slices.add(
                PageLineSlice(
                    startLine = lineIdx,
                    endLine = endLine,
                    pageBreakBefore = pendingPageBreak,
                    sliceTop = sliceTop.toFloat(),
                    sliceHeight = sliceHeight
                )
            )
            cursorY += sliceHeight
            lineIdx = endLine + 1
            pendingPageBreak = lineIdx < lineCount
            if (pendingPageBreak) {
                cursorY = marginTop
            }
        }

        return slices to cursorY
    }

    /**
     * Plans page slices for a multi-column table row whose cell layouts may collectively exceed
     * a single A4 page. Keeps all columns synchronized per page slice with clean cell padding.
     *
     * For a column with no remaining lines on a slice, `colStartLines[c] > colEndLines[c]`.
     */
    fun paginateTableRow(
        colLineTops: List<IntArray>,
        colLineBottoms: List<IntArray>,
        cellPad: Float,
        startCursorY: Float,
        marginTop: Float,
        maxPageY: Float
    ): Pair<List<TableRowPageSlice>, Float> {
        val colCount = colLineTops.size
        if (colCount == 0) return emptyList<TableRowPageSlice>() to startCursorY

        val lineCounts = IntArray(colCount) { c -> minOf(colLineTops[c].size, colLineBottoms[c].size) }
        val maxContentHeight = (0 until colCount).maxOfOrNull { c ->
            val cnt = lineCounts[c]
            if (cnt > 0) (colLineBottoms[c][cnt - 1] - colLineTops[c][0]).toFloat() else 0f
        } ?: 0f
        val wholeRowHeight = maxOf(16f, maxContentHeight) + cellPad * 2f
        val fullPageHeight = (maxPageY - marginTop).coerceAtLeast(1f)

        // Fast path 1: entire row fits on current page
        if (startCursorY + wholeRowHeight <= maxPageY) {
            return listOf(
                TableRowPageSlice(
                    colStartLines = IntArray(colCount) { 0 },
                    colEndLines = IntArray(colCount) { c -> lineCounts[c] - 1 },
                    pageBreakBefore = false,
                    sliceRowHeight = wholeRowHeight
                )
            ) to (startCursorY + wholeRowHeight)
        }

        // Fast path 2: entire row fits cleanly on a single fresh page
        if (wholeRowHeight <= fullPageHeight && startCursorY > marginTop + 1f) {
            return listOf(
                TableRowPageSlice(
                    colStartLines = IntArray(colCount) { 0 },
                    colEndLines = IntArray(colCount) { c -> lineCounts[c] - 1 },
                    pageBreakBefore = true,
                    sliceRowHeight = wholeRowHeight
                )
            ) to (marginTop + wholeRowHeight)
        }

        // Multi-page tall table row: slice all columns in lockstep across pages
        val slices = mutableListOf<TableRowPageSlice>()
        val nextStart = IntArray(colCount) { 0 }
        var cursorY = startCursorY
        var pendingPageBreak = false

        fun hasRemainingLines(): Boolean = (0 until colCount).any { c -> nextStart[c] < lineCounts[c] }

        // Handle completely empty rows cleanly
        if (!hasRemainingLines()) {
            val needBreak = cursorY + wholeRowHeight > maxPageY && cursorY > marginTop + 1f
            val finalY = (if (needBreak) marginTop else cursorY) + wholeRowHeight
            return listOf(
                TableRowPageSlice(
                    colStartLines = IntArray(colCount) { 0 },
                    colEndLines = IntArray(colCount) { -1 },
                    pageBreakBefore = needBreak,
                    sliceRowHeight = wholeRowHeight
                )
            ) to finalY
        }

        while (hasRemainingLines()) {
            var availTextHeight = maxPageY - cursorY - cellPad * 2f

            // If no active column can fit even its next single line, move to a fresh page first
            val minNeededLine = (0 until colCount)
                .filter { c -> nextStart[c] < lineCounts[c] }
                .minOf { c ->
                    val s = nextStart[c]
                    (colLineBottoms[c][s] - colLineTops[c][s]).toFloat().coerceAtLeast(0f)
                }

            if (availTextHeight < minNeededLine && cursorY > marginTop + 1f) {
                pendingPageBreak = true
                cursorY = marginTop
                availTextHeight = fullPageHeight - cellPad * 2f
            }

            val sliceStarts = IntArray(colCount) { c -> nextStart[c] }
            val sliceEnds = IntArray(colCount) { -1 }
            var maxSliceTextHeight = 0f

            for (c in 0 until colCount) {
                val startL = nextStart[c]
                val totalL = lineCounts[c]
                if (startL >= totalL) {
                    sliceEnds[c] = startL - 1 // Empty slice for this column
                    continue
                }
                val topPx = colLineTops[c][startL]
                var endL = startL - 1
                var k = startL
                while (k < totalL) {
                    val h = (colLineBottoms[c][k] - topPx).toFloat()
                    if (h <= availTextHeight) {
                        endL = k
                        k++
                    } else {
                        break
                    }
                }
                // Guarantee forward progress at top of page even for an ultra-tall single line
                if (endL < startL && cursorY <= marginTop + 1f) {
                    endL = startL
                }
                sliceEnds[c] = endL
                if (endL >= startL) {
                    val h = (colLineBottoms[c][endL] - topPx).toFloat().coerceAtLeast(0f)
                    if (h > maxSliceTextHeight) maxSliceTextHeight = h
                    nextStart[c] = endL + 1
                }
            }

            // Safety valve: if we somehow made zero progress while not at top of page, advance page
            val madeProgress = (0 until colCount).any { c -> sliceEnds[c] >= sliceStarts[c] }
            if (!madeProgress) {
                if (cursorY > marginTop + 1f) {
                    pendingPageBreak = true
                    cursorY = marginTop
                    continue
                }
                // Force 1 line from every remaining column to guarantee termination
                for (c in 0 until colCount) {
                    if (nextStart[c] < lineCounts[c]) {
                        val s = nextStart[c]
                        sliceStarts[c] = s
                        sliceEnds[c] = s
                        val h = (colLineBottoms[c][s] - colLineTops[c][s]).toFloat().coerceAtLeast(0f)
                        if (h > maxSliceTextHeight) maxSliceTextHeight = h
                        nextStart[c] = s + 1
                    }
                }
            }

            val sliceRowHeight = maxOf(16f, maxSliceTextHeight) + cellPad * 2f
            slices.add(
                TableRowPageSlice(
                    colStartLines = sliceStarts,
                    colEndLines = sliceEnds,
                    pageBreakBefore = pendingPageBreak,
                    sliceRowHeight = sliceRowHeight
                )
            )
            cursorY += sliceRowHeight
            pendingPageBreak = hasRemainingLines()
            if (pendingPageBreak) {
                cursorY = marginTop
            }
        }

        return slices to cursorY
    }
}
