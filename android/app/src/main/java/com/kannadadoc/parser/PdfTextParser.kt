package com.kannadadoc.parser

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.InputStream
import java.io.StringWriter

/**
 * Font-aware PDF text extractor powered by PDFBox-Android.
 */
class PdfTextParser {

    companion object {
        private val SUBSET_PREFIX_REGEX = Regex("^[A-Z]{6}\\+")
    }

    fun parse(inputStream: InputStream, sourceUri: String, fileName: String): ParsedDocument {
        val extractedBlocks = mutableListOf<DocBlock.Paragraph>()

        PDDocument.load(inputStream).use { pdDoc ->
            val stripper = object : PDFTextStripper() {
                private val currentLineRuns = mutableListOf<TextRun>()

                override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                    if (textPositions.isEmpty()) return

                    var activeFont: String? = null
                    var activeSize: Float? = null
                    var activeBold = false
                    val buffer = StringBuilder()

                    fun flushRun() {
                        if (buffer.isNotEmpty()) {
                            currentLineRuns.add(
                                TextRun(
                                    text = buffer.toString(),
                                    fontFamily = activeFont,
                                    bold = activeBold,
                                    fontSizePt = activeSize
                                )
                            )
                            buffer.setLength(0)
                        }
                    }

                    for (pos in textPositions) {
                        val rawFont = pos.font?.name ?: ""
                        val cleanFont = rawFont.replace(SUBSET_PREFIX_REGEX, "").ifBlank { null }
                        val isBold = rawFont.contains("Bold", ignoreCase = true)
                        val sizePt = pos.fontSizeInPt.takeIf { it > 0f }

                        if (buffer.isNotEmpty() && (cleanFont != activeFont || isBold != activeBold)) {
                            flushRun()
                        }
                        activeFont = cleanFont
                        activeBold = isBold
                        activeSize = sizePt
                        buffer.append(pos.unicode ?: "")
                    }
                    flushRun()
                }

                override fun writeLineSeparator() {
                    if (currentLineRuns.isNotEmpty()) {
                        extractedBlocks.add(DocBlock.Paragraph(runs = currentLineRuns.toList()))
                        currentLineRuns.clear()
                    }
                }

                override fun writeParagraphEnd() {
                    writeLineSeparator()
                }

                override fun endPage(page: PDPage?) {
                    writeLineSeparator()
                }
            }

            stripper.sortByPosition = true
            stripper.writeText(pdDoc, StringWriter())
        }

        val warning = if (extractedBlocks.isEmpty()) {
            "No selectable text found in this PDF (it may be a scanned image)."
        } else null

        return ParsedDocument(
            sourceUri = sourceUri,
            fileName = fileName.substringBeforeLast('.') + ".docx",
            sourceFormat = DocFormat.PDF_EXTRACTED,
            blocks = extractedBlocks.ifEmpty {
                listOf(DocBlock.Paragraph(runs = listOf(TextRun(text = ""))))
            },
            warningMessage = warning
        )
    }
}
