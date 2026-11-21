package com.kannadadoc.parser

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset

/**
 * Lightweight native Kotlin RTF parser tailored for legacy Kannada documents.
 * - Parses {\fonttbl ...} to resolve \fN font IDs (e.g. \f0 -> "Nudi 01 e").
 * - Decodes \'xx hex escapes strictly using Windows-1252 (Cp1252).
 */
class RtfParser {

    companion object {
        private val CP1252: Charset = Charset.forName("windows-1252")
        private val SKIP_DESTINATIONS = setOf(
            "fonttbl", "colortbl", "stylesheet", "info", "pict",
            "header", "footer", "headerl", "headerr", "headerf",
            "footerl", "footerr", "footerf", "footnote", "listtable",
            "listoverridetable", "revtbl", "rsidtbl", "generator", "themedata"
        )
    }

    private data class GroupState(
        var fontId: Int = 0,
        var bold: Boolean = false,
        var italic: Boolean = false,
        var underline: Boolean = false,
        var fontSizePt: Float? = null,
        var alignment: String = "left",
        var skipGroup: Boolean = false
    )

    fun parse(inputStream: InputStream, sourceUri: String, fileName: String): ParsedDocument {
        val rawBytes = inputStream.readBytes()
        val startOffset = if (
            rawBytes.size >= 3 &&
            rawBytes[0] == 0xEF.toByte() &&
            rawBytes[1] == 0xBB.toByte() &&
            rawBytes[2] == 0xBF.toByte()
        ) 3 else 0

        val rtfText = String(rawBytes, startOffset, rawBytes.size - startOffset, Charsets.ISO_8859_1)
        val fontTable = extractFontTable(rtfText)

        val paragraphs = mutableListOf<DocBlock.Paragraph>()
        val currentRuns = mutableListOf<TextRun>()
        val textBuffer = ByteArrayOutputStream()
        val unicodeBuffer = StringBuilder()

        val stack = ArrayDeque<GroupState>()
        var state = GroupState()
        var defaultFontId = 0

        fun flushBuffer() {
            if (textBuffer.size() > 0) {
                unicodeBuffer.append(String(textBuffer.toByteArray(), CP1252))
                textBuffer.reset()
            }
            if (unicodeBuffer.isNotEmpty()) {
                val text = unicodeBuffer.toString()
                unicodeBuffer.setLength(0)
                val fontName = fontTable[state.fontId] ?: fontTable[defaultFontId]
                val prev = currentRuns.lastOrNull()
                if (prev != null &&
                    prev.fontFamily == fontName &&
                    prev.bold == state.bold &&
                    prev.italic == state.italic &&
                    prev.underline == state.underline &&
                    prev.fontSizePt == state.fontSizePt
                ) {
                    currentRuns[currentRuns.lastIndex] = prev.copy(text = prev.text + text)
                } else {
                    currentRuns.add(
                        TextRun(
                            text = text,
                            fontFamily = fontName,
                            bold = state.bold,
                            italic = state.italic,
                            underline = state.underline,
                            fontSizePt = state.fontSizePt
                        )
                    )
                }
            }
        }

        fun finishParagraph() {
            flushBuffer()
            paragraphs.add(
                DocBlock.Paragraph(
                    runs = currentRuns.toList(),
                    alignment = state.alignment
                )
            )
            currentRuns.clear()
        }

        var i = 0
        val n = rtfText.length
        while (i < n) {
            val ch = rtfText[i]
            when (ch) {
                '{' -> {
                    flushBuffer()
                    stack.addLast(state.copy())
                    i++
                }
                '}' -> {
                    flushBuffer()
                    if (stack.isNotEmpty()) {
                        state = stack.removeLast()
                    }
                    i++
                }
                '\\' -> {
                    i++
                    if (i >= n) break
                    val next = rtfText[i]
                    when {
                        next == '\\' || next == '{' || next == '}' -> {
                            if (!state.skipGroup) textBuffer.write(next.code)
                            i++
                        }
                        next == '*' -> {
                            state.skipGroup = true
                            i++
                        }
                        next == '~' -> {
                            if (!state.skipGroup) textBuffer.write(' '.code)
                            i++
                        }
                        next == '\'' && i + 2 < n -> {
                            val hex = rtfText.substring(i + 1, i + 3)
                            val byteVal = hex.toIntOrNull(16)
                            if (byteVal != null && !state.skipGroup) {
                                textBuffer.write(byteVal)
                            }
                            i += 3
                        }
                        next == '\r' || next == '\n' -> {
                            if (!state.skipGroup) finishParagraph()
                            i++
                        }
                        next.isLetter() -> {
                            val wordStart = i
                            while (i < n && rtfText[i].isLetter()) i++
                            val word = rtfText.substring(wordStart, i)

                            val numStart = i
                            if (i < n && rtfText[i] == '-') i++
                            while (i < n && rtfText[i].isDigit()) i++
                            val param = if (i > numStart) rtfText.substring(numStart, i).toIntOrNull() else null

                            if (i < n && rtfText[i] == ' ') i++

                            if (word in SKIP_DESTINATIONS) {
                                state.skipGroup = true
                            } else if (!state.skipGroup) {
                                when (word) {
                                    "deff" -> if (param != null) {
                                        defaultFontId = param
                                        state.fontId = param
                                    }
                                    "f" -> if (param != null) {
                                        flushBuffer()
                                        state.fontId = param
                                    }
                                    "b" -> {
                                        flushBuffer()
                                        state.bold = param == null || param != 0
                                    }
                                    "i" -> {
                                        flushBuffer()
                                        state.italic = param == null || param != 0
                                    }
                                    "ul" -> {
                                        flushBuffer()
                                        state.underline = param == null || param != 0
                                    }
                                    "ulnone" -> {
                                        flushBuffer()
                                        state.underline = false
                                    }
                                    "fs" -> if (param != null && param > 0) {
                                        flushBuffer()
                                        state.fontSizePt = param / 2f
                                    }
                                    "plain" -> {
                                        flushBuffer()
                                        state.bold = false
                                        state.italic = false
                                        state.underline = false
                                    }
                                    "pard" -> {
                                        flushBuffer()
                                        state.alignment = "left"
                                    }
                                    "ql" -> state.alignment = "left"
                                    "qc" -> state.alignment = "center"
                                    "qr" -> state.alignment = "right"
                                    "qj" -> state.alignment = "justify"
                                    "par" -> finishParagraph()
                                    "line" -> {
                                        if (textBuffer.size() > 0) {
                                            unicodeBuffer.append(String(textBuffer.toByteArray(), CP1252))
                                            textBuffer.reset()
                                        }
                                        unicodeBuffer.append('\n')
                                    }
                                    "tab" -> textBuffer.write('\t'.code)
                                    "u" -> if (param != null) {
                                        if (textBuffer.size() > 0) {
                                            unicodeBuffer.append(String(textBuffer.toByteArray(), CP1252))
                                            textBuffer.reset()
                                        }
                                        val codePoint = if (param < 0) param + 65536 else param
                                        unicodeBuffer.append(codePoint.toChar())
                                        if (i < n && rtfText[i] != '\\' && rtfText[i] != '{' && rtfText[i] != '}') {
                                            i++
                                        }
                                    }
                                }
                            }
                        }
                        else -> i++
                    }
                }
                '\r', '\n' -> i++
                else -> {
                    if (!state.skipGroup) {
                        textBuffer.write(ch.code and 0xFF)
                    }
                    i++
                }
            }
        }

        flushBuffer()
        if (currentRuns.isNotEmpty()) {
            paragraphs.add(DocBlock.Paragraph(runs = currentRuns.toList(), alignment = state.alignment))
        }

        return ParsedDocument(
            sourceUri = sourceUri,
            fileName = fileName,
            sourceFormat = DocFormat.RTF,
            blocks = paragraphs
        )
    }

    internal fun extractFontTable(rtfText: String): Map<Int, String> {
        val start = rtfText.indexOf("{\\fonttbl")
        if (start == -1) return emptyMap()

        var depth = 0
        var end = start
        for (idx in start until rtfText.length) {
            when (rtfText[idx]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        end = idx
                        break
                    }
                }
            }
        }
        if (end <= start) return emptyMap()

        val tblBody = rtfText.substring(start + "{\\fonttbl".length, end)
        val result = mutableMapOf<Int, String>()
        val entryRegex = Regex("""\\f(\d+)([^;{}]*);""")
        for (match in entryRegex.findAll(tblBody)) {
            val fontId = match.groupValues[1].toIntOrNull() ?: continue
            val rawName = match.groupValues[2]
                .replace(Regex("""\\[a-zA-Z]+-?\d*\s?"""), "")
                .trim()
            if (rawName.isNotEmpty()) {
                result[fontId] = rawName
            }
        }
        return result
    }
}
