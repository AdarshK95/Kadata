package com.kannadadoc.parser

import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hwpf.usermodel.Range
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * Legacy binary Word 97-2003 (.doc) parser powered by Apache POI HWPF (poi-scratchpad).
 * Hardened against corrupt/nested government .doc tables and ALWAYS reports any partial
 * recovery to the user via [ParsedDocument.warningMessage] (zero silent degradation).
 */
class DocHwpfParser {

    companion object {
        private val CONTROL_CHAR_REGEX = Regex("[\\r\\u0007\\u000B\\u000C\\u0013\\u0014\\u0015]")
    }

    private class RecoveryTracker {
        var corruptTables: Int = 0
        var damagedCells: Int = 0
        var skippedParagraphs: Int = 0
        var skippedRuns: Int = 0

        fun buildWarning(): String? {
            val parts = mutableListOf<String>()
            if (corruptTables > 0) parts.add("$corruptTables damaged table(s) fell back to plain paragraphs")
            if (damagedCells > 0) parts.add("$damagedCells malformed table cell(s) were partially recovered")
            if (skippedParagraphs > 0) parts.add("$skippedParagraphs unreadable paragraph(s) were skipped")
            if (skippedRuns > 0) parts.add("$skippedRuns corrupt text run(s) were skipped")
            if (parts.isEmpty()) return null
            return "Legacy .doc compatibility notice: ${parts.joinToString("; ")}. Please review before saving."
        }
    }

    fun parse(inputStream: InputStream, sourceUri: String, fileName: String): ParsedDocument {
        return try {
            HWPFDocument(inputStream).use { doc ->
                val tracker = RecoveryTracker()
                val blocks = extractBlocks(doc.range, tracker)
                ParsedDocument(
                    sourceUri = sourceUri,
                    fileName = fileName,
                    sourceFormat = DocFormat.DOC,
                    blocks = blocks.ifEmpty {
                        listOf(DocBlock.Paragraph(runs = listOf(TextRun(text = "", fontFamily = "Noto Sans Kannada"))))
                    },
                    warningMessage = tracker.buildWarning()
                )
            }
        } catch (_: FileNotFoundException) {
            ParsedDocument(
                sourceUri = sourceUri,
                fileName = fileName,
                sourceFormat = DocFormat.DOC,
                blocks = listOf(
                    DocBlock.Paragraph(
                        runs = listOf(TextRun(text = "", fontFamily = "Noto Sans Kannada"))
                    )
                ),
                warningMessage = "This .doc file has an empty WordDocument stream (stub or empty Word 97 container)."
            )
        }
    }

    private fun extractBlocks(range: Range, tracker: RecoveryTracker): List<DocBlock> {
        val blocks = mutableListOf<DocBlock>()
        val paraCount = range.numParagraphs()
        var idx = 0

        while (idx < paraCount) {
            val para = runCatching { range.getParagraph(idx) }.getOrNull()
            if (para == null) {
                tracker.skippedParagraphs++
                idx++
                continue
            }

            if (para.isInTable && para.tableLevel == 1) {
                val parsedTable = runCatching {
                    val table = range.getTable(para)
                    val numRows = table.numRows()
                    val consumedParas = table.numParagraphs()
                    if (numRows <= 0 || consumedParas <= 0) {
                        tracker.corruptTables++
                        null
                    } else {
                        val rows = mutableListOf<List<List<DocBlock.Paragraph>>>()
                        for (r in 0 until numRows) {
                            val row = runCatching { table.getRow(r) }.getOrNull()
                            if (row == null) {
                                tracker.damagedCells++
                                continue
                            }
                            val numCells = row.numCells()
                            if (numCells <= 0) continue
                            val cells = mutableListOf<List<DocBlock.Paragraph>>()
                            for (c in 0 until numCells) {
                                val cellParas = mutableListOf<DocBlock.Paragraph>()
                                val cellOk = runCatching {
                                    val cell = row.getCell(c)
                                    val cellParaCount = cell.numParagraphs()
                                    for (cp in 0 until cellParaCount) {
                                        runCatching { convertParagraph(cell.getParagraph(cp), tracker) }
                                            .onSuccess { cellParas.add(it) }
                                            .onFailure { tracker.skippedParagraphs++ }
                                    }
                                }.isSuccess
                                if (!cellOk) tracker.damagedCells++
                                cells.add(cellParas.ifEmpty { listOf(DocBlock.Paragraph(emptyList())) })
                            }
                            if (cells.isNotEmpty()) rows.add(cells)
                        }
                        if (rows.isNotEmpty()) {
                            DocBlock.Table(rows = rows) to consumedParas
                        } else {
                            tracker.corruptTables++
                            null
                        }
                    }
                }.getOrElse {
                    tracker.corruptTables++
                    null
                }

                if (parsedTable != null) {
                    val (tableBlock, consumedParas) = parsedTable
                    blocks.add(tableBlock)
                    idx += maxOf(1, consumedParas)
                    continue
                }
            }

            runCatching { convertParagraph(para, tracker) }
                .onSuccess { blocks.add(it) }
                .onFailure { tracker.skippedParagraphs++ }
            idx++
        }

        return blocks
    }

    private fun convertParagraph(
        para: org.apache.poi.hwpf.usermodel.Paragraph,
        tracker: RecoveryTracker
    ): DocBlock.Paragraph {
        val runs = mutableListOf<TextRun>()
        val runCount = para.numCharacterRuns()
        for (j in 0 until runCount) {
            val cr = runCatching { para.getCharacterRun(j) }.getOrNull()
            if (cr == null) {
                tracker.skippedRuns++
                continue
            }
            val cleaned = cr.text().replace(CONTROL_CHAR_REGEX, "")
            if (cleaned.isEmpty()) continue

            runs.add(
                TextRun(
                    text = cleaned,
                    fontFamily = cr.fontName?.takeIf { it.isNotBlank() },
                    bold = cr.isBold,
                    italic = cr.isItalic,
                    underline = cr.underlineCode != 0,
                    fontSizePt = (cr.fontSize / 2f).takeIf { it > 0f }
                )
            )
        }

        val alignment = when (para.justification) {
            1 -> "center"
            2 -> "right"
            3 -> "justify"
            else -> "left"
        }

        return DocBlock.Paragraph(
            runs = runs,
            alignment = alignment
        )
    }
}
