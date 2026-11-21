package com.kannadadoc.export

import com.kannadadoc.parser.DocBlock
import com.kannadadoc.parser.ParsedDocument
import com.kannadadoc.parser.TextRun
import java.io.OutputStream
import java.nio.charset.Charset

/**
 * Generates Microsoft Word-compatible .doc files (Rich Text Format container accepted natively
 * by Word 97 through Word 365 on Windows) with explicit \fonttbl entries for Nudi 01 e,
 * BRH Kannada, Noto Sans Kannada, and Calibri.
 */
class RtfDocWriter {

    companion object {
        private val CP1252: Charset = Charset.forName("windows-1252")
    }

    fun write(document: ParsedDocument, outputStream: OutputStream, defaultFont: String = "Calibri") {
        // Collect unique font families in document order
        val fontList = linkedSetOf(defaultFont, "Calibri", "Noto Sans Kannada", "Nudi 01 e", "BRH Kannada")
        for (block in document.blocks) {
            when (block) {
                is DocBlock.Paragraph -> block.runs.forEach { r ->
                    r.fontFamily?.takeIf { it.isNotBlank() }?.let { fontList.add(it) }
                }
                is DocBlock.Table -> block.rows.forEach { row ->
                    row.forEach { cell ->
                        cell.forEach { p ->
                            p.runs.forEach { r ->
                                r.fontFamily?.takeIf { it.isNotBlank() }?.let { fontList.add(it) }
                            }
                        }
                    }
                }
            }
        }
        val fontIndexMap = fontList.withIndex().associate { (idx, name) -> name to idx }

        val sb = StringBuilder()
        sb.append("{\\rtf1\\ansi\\ansicpg1252\\deff0\n")
        sb.append("{\\fonttbl\n")
        fontIndexMap.forEach { (name, idx) ->
            sb.append("{\\f$idx\\fnil\\fcharset0 ${escapePlainAscii(name)};}\n")
        }
        sb.append("}\n")
        sb.append("\\paperw11906\\paperh16838\\margl1440\\margr1440\\margt1440\\margb1440\n")

        var orderedIndex = 0
        for (block in document.blocks) {
            when (block) {
                is DocBlock.Paragraph -> {
                    if (block.listType == "ordered") orderedIndex++ else orderedIndex = 0
                    val prefix = when (block.listType) {
                        "bullet" -> "•  "
                        "ordered" -> "$orderedIndex.  "
                        else -> null
                    }
                    appendParagraph(sb, block, fontIndexMap, defaultFont, prefix)
                }
                is DocBlock.Table -> {
                    orderedIndex = 0
                    for (row in block.rows) {
                        val rowText = row.joinToString("\t") { cellParas ->
                            cellParas.joinToString(" ") { p -> p.runs.joinToString("") { it.text } }
                        }
                        appendParagraph(
                            sb,
                            DocBlock.Paragraph(runs = listOf(TextRun(text = rowText, fontFamily = defaultFont))),
                            fontIndexMap,
                            defaultFont,
                            null
                        )
                    }
                }
            }
        }

        sb.append("}\n")
        outputStream.write(sb.toString().toByteArray(Charsets.US_ASCII))
    }

    private fun appendParagraph(
        sb: StringBuilder,
        para: DocBlock.Paragraph,
        fontIndexMap: Map<String, Int>,
        defaultFont: String,
        prefix: String?
    ) {
        val alignCmd = when (para.alignment) {
            "center" -> "\\qc"
            "right" -> "\\qr"
            "justify" -> "\\qj"
            else -> "\\ql"
        }
        val baseSizeHalfPts = when (para.headingLevel) {
            1 -> 36
            2 -> 30
            3 -> 26
            else -> 24
        }

        sb.append("{\\pard$alignCmd\\sa160\\sl276\\slmult1 ")
        if (prefix != null) {
            val fIdx = fontIndexMap[defaultFont] ?: 0
            sb.append("{\\f$fIdx\\fs$baseSizeHalfPts ")
            appendEncodedText(sb, prefix)
            sb.append("}")
        }

        for (run in para.runs) {
            val font = run.fontFamily?.takeIf { it.isNotBlank() } ?: defaultFont
            val fIdx = fontIndexMap[font] ?: 0
            val sizeHalfPts = run.fontSizePt?.let { (it * 2).toInt() } ?: baseSizeHalfPts
            val isBold = run.bold || para.headingLevel > 0

            sb.append("{\\f$fIdx\\fs$sizeHalfPts")
            if (isBold) sb.append("\\b")
            if (run.italic) sb.append("\\i")
            if (run.underline) sb.append("\\ul")
            sb.append(" ")
            appendEncodedText(sb, run.text)
            sb.append("}")
        }
        sb.append("\\par}\n")
    }

    private fun appendEncodedText(sb: StringBuilder, text: String) {
        for (ch in text) {
            val code = ch.code
            when {
                ch == '\\' || ch == '{' || ch == '}' -> sb.append('\\').append(ch)
                ch == '\n' -> sb.append("\\line ")
                ch == '\t' -> sb.append("\\tab ")
                code in 0x20..0x7E -> sb.append(ch)
                code in 0x80..0xFF -> {
                    // Encode legacy Nudi/Baraha Latin-1 / Cp1252 characters as \'xx hex escapes
                    val cpBytes = ch.toString().toByteArray(CP1252)
                    for (b in cpBytes) {
                        sb.append(String.format("\\'%02x", b.toInt() and 0xFF))
                    }
                }
                else -> {
                    // Encode Unicode Kannada (U+0C80..U+0CFF) & symbols via RTF \uN? escape
                    val signed = if (code > 32767) code - 65536 else code
                    sb.append("\\u").append(signed).append('?')
                }
            }
        }
    }

    private fun escapePlainAscii(name: String): String =
        name.replace("\\", "\\\\").replace("{", "\\{").replace("}", "\\}")
}
