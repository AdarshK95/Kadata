package com.kannadadoc.parser

import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hwpf.usermodel.Range
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * Legacy binary Word 97-2003 (.doc) parser powered by Apache POI HWPF (poi-scratchpad).
 */
class DocHwpfParser {

    companion object {
        private val CONTROL_CHAR_REGEX = Regex("[\\r\\u0007\\u000B\\u000C\\u0013\\u0014\\u0015]")
    }

    fun parse(inputStream: InputStream, sourceUri: String, fileName: String): ParsedDocument {
        return try {
            HWPFDocument(inputStream).use { doc ->
                val blocks = extractBlocks(doc.range)
                ParsedDocument(
                    sourceUri = sourceUri,
                    fileName = fileName,
                    sourceFormat = DocFormat.DOC,
                    blocks = blocks
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

    private fun extractBlocks(range: Range): List<DocBlock> {
        val blocks = mutableListOf<DocBlock>()
        val paraCount = range.numParagraphs()
        var idx = 0

        while (idx < paraCount) {
            val para = range.getParagraph(idx)

            if (para.isInTable && para.tableLevel == 1) {
                try {
                    val table = range.getTable(para)
                    val rows = mutableListOf<List<List<DocBlock.Paragraph>>>()
                    for (r in 0 until table.numRows()) {
                        val row = table.getRow(r)
                        val cells = mutableListOf<List<DocBlock.Paragraph>>()
                        for (c in 0 until row.numCells()) {
                            val cell = row.getCell(c)
                            val cellParas = mutableListOf<DocBlock.Paragraph>()
                            for (cp in 0 until cell.numParagraphs()) {
                                cellParas.add(convertParagraph(cell.getParagraph(cp)))
                            }
                            cells.add(cellParas)
                        }
                        rows.add(cells)
                    }
                    blocks.add(DocBlock.Table(rows = rows))
                    idx += table.numParagraphs()
                    continue
                } catch (_: Exception) {
                    // Fall back to regular paragraph extraction
                }
            }

            blocks.add(convertParagraph(para))
            idx++
        }

        return blocks
    }

    private fun convertParagraph(para: org.apache.poi.hwpf.usermodel.Paragraph): DocBlock.Paragraph {
        val runs = mutableListOf<TextRun>()
        for (j in 0 until para.numCharacterRuns()) {
            val cr = para.getCharacterRun(j)
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
