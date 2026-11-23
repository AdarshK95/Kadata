package com.kannadadoc.parser

import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.charset.Charset

class HtmlParser {

    companion object {
        private val BODY_OPEN_REGEX = Regex("<body\\b[^>]*>", RegexOption.IGNORE_CASE)
        private val BODY_CLOSE_REGEX = Regex("</body\\s*>", RegexOption.IGNORE_CASE)
        private val TOKEN_REGEX = Regex("(<[^>]+>|[^<]+)")
        private val TAG_NAME_REGEX = Regex("^</?\\s*([A-Za-z0-9:_-]+)")
        private val STYLE_ATTR_REGEX = Regex("style\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
        private val ALIGN_ATTR_REGEX = Regex("align\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
        private val NUM_ENTITY_HEX_REGEX = Regex("&#x([0-9a-fA-F]+);")
        private val NUM_ENTITY_DEC_REGEX = Regex("&#([0-9]+);")
        private val IGNORED_BLOCK_TAGS = listOf("script", "style", "head", "noscript")
    }

    private data class InlineState(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val fontFamily: String? = null,
        val colorHex: String? = null
    )

    private class HtmlTableContext(
        val rows: MutableList<List<List<DocBlock.Paragraph>>> = mutableListOf(),
        var currentRow: MutableList<List<DocBlock.Paragraph>> = mutableListOf(),
        var currentCellParas: MutableList<DocBlock.Paragraph> = mutableListOf(),
        var inRow: Boolean = false,
        var inCell: Boolean = false
    )

    fun parse(
        inputStream: InputStream,
        sourceUri: String,
        fileName: String,
        maxHtmlBytes: Int = DocumentSizePolicy.MAX_HTML_BYTES
    ): ParsedDocument {
        val buffered = if (inputStream is BufferedInputStream) {
            inputStream
        } else {
            BufferedInputStream(inputStream, 16 * 1024)
        }

        val header = BoundedStreamReader.peekHeader(buffered, 4)

        val htmlBytes = if (isZipHeader(header)) {
            // Stream ZIP directly through strict SafeZipReader (skips images; REJECTS oversized HTML)
            val htmlEntries = SafeZipReader.readMatchingEntries(
                inputStream = buffered,
                maxEntryBytes = maxHtmlBytes,
                maxTotalBytes = maxHtmlBytes,
                fileName = fileName,
                formatLabel = "HTML"
            ) { norm ->
                norm.endsWith(".html") || norm.endsWith(".htm") || norm.endsWith(".xhtml")
            }
            htmlEntries.entries
                .firstOrNull { it.key == "index.html" || it.key.endsWith("/index.html") }?.value
                ?: htmlEntries.values.maxByOrNull { it.size }
                ?: error("No HTML document found inside zipped web page: $fileName")
        } else {
            // Strictly reject raw HTML exceeding maxHtmlBytes (never silently truncate)
            BoundedStreamReader.readStrict(
                inputStream = buffered,
                maxBytes = maxHtmlBytes,
                fileName = fileName,
                formatLabel = "HTML"
            )
        }

        val htmlText = decodeSmartCharset(htmlBytes)
        val blocks = parseHtmlToBlocks(htmlText)
        val finalBlocks = if (blocks.isEmpty()) {
            listOf(DocBlock.Paragraph(runs = listOf(TextRun(text = ""))))
        } else {
            blocks
        }

        return ParsedDocument(
            sourceUri = sourceUri,
            fileName = fileName,
            sourceFormat = DocFormat.HTML,
            blocks = finalBlocks
        )
    }

    fun parseHtmlToBlocks(rawHtml: String): List<DocBlock> {
        val noComments = stripCommentsLinear(rawHtml)
        val cleaned = stripIgnoredElementsLinear(noComments)
        val bodyHtml = extractBodySlice(cleaned)

        val blocks = mutableListOf<DocBlock>()
        val inlineStack = ArrayDeque<InlineState>()
        inlineStack.addLast(InlineState())

        var currentRuns = mutableListOf<TextRun>()
        var currentHeading = 0
        var currentAlignment = "left"
        val listTypeStack = ArrayDeque<String>()

        val tableStack = ArrayDeque<HtmlTableContext>()
        val maxTableDepth = 32

        fun activeTable(): HtmlTableContext? = tableStack.lastOrNull()

        fun isInsideCell(): Boolean = activeTable()?.inCell == true

        fun effectiveInline(): InlineState {
            var b = false
            var it = false
            var u = false
            var ff: String? = null
            var col: String? = null
            for (s in inlineStack) {
                if (s.bold) b = true
                if (s.italic) it = true
                if (s.underline) u = true
                s.fontFamily?.let { ff = it }
                s.colorHex?.let { col = it }
            }
            return InlineState(b, it, u, ff, col)
        }

        fun flushParagraph(forceEmpty: Boolean = false) {
            val nonEmpty = currentRuns.any { it.text.isNotBlank() }
            val inActiveCell = isInsideCell()
            if (nonEmpty || forceEmpty) {
                val para = DocBlock.Paragraph(
                    runs = currentRuns.toList(),
                    headingLevel = currentHeading,
                    alignment = currentAlignment,
                    listType = if (inActiveCell) null else listTypeStack.lastOrNull()
                )
                val tbl = activeTable()
                if (tbl != null && tbl.inCell) {
                    tbl.currentCellParas.add(para)
                } else if (tbl != null && tbl.inRow) {
                    // Text directly inside <tr> without <td>: preserve in synthetic cell
                    tbl.currentCellParas.add(para)
                    tbl.inCell = true
                } else {
                    blocks.add(para)
                }
            }
            currentRuns = mutableListOf()
            currentHeading = 0
            currentAlignment = "left"
        }

        fun closeCurrentCell(tbl: HtmlTableContext) {
            if (!tbl.inCell) return
            flushParagraph(false)
            if (!tbl.inRow) tbl.inRow = true
            tbl.currentRow.add(tbl.currentCellParas.ifEmpty { listOf(DocBlock.Paragraph(emptyList())) })
            tbl.currentCellParas = mutableListOf()
            tbl.inCell = false
        }

        fun closeCurrentRow(tbl: HtmlTableContext) {
            closeCurrentCell(tbl)
            if (tbl.currentRow.isNotEmpty()) {
                tbl.rows.add(tbl.currentRow.toList())
                tbl.currentRow = mutableListOf()
            }
            tbl.inRow = false
        }

        fun popAndFinalizeTopTable() {
            if (tableStack.isEmpty()) return
            val finished = tableStack.removeLast()
            closeCurrentRow(finished)
            if (finished.rows.isEmpty()) return

            val parent = tableStack.lastOrNull()
            if (parent == null) {
                blocks.add(DocBlock.Table(rows = finished.rows.toList()))
            } else {
                // Nested table: flatten losslessly into enclosing cell paragraphs
                val flattened = TableFlattenHelper.flattenNestedTableToParagraphs(finished.rows)
                if (flattened.isNotEmpty()) {
                    if (!parent.inRow) parent.inRow = true
                    if (!parent.inCell) parent.inCell = true
                    parent.currentCellParas.addAll(flattened)
                }
            }
        }

        fun parseAlignmentFromTag(tag: String): String {
            val style = STYLE_ATTR_REGEX.find(tag)?.groupValues?.getOrNull(1).orEmpty()
            val cssAlign = Regex("text-align\\s*:\\s*([a-zA-Z]+)", RegexOption.IGNORE_CASE)
                .find(style)?.groupValues?.getOrNull(1)?.lowercase()
            val attrAlign = ALIGN_ATTR_REGEX.find(tag)?.groupValues?.getOrNull(1)?.lowercase()
            return when (cssAlign ?: attrAlign) {
                "center" -> "center"
                "right", "end" -> "right"
                "justify" -> "justify"
                else -> "left"
            }
        }

        fun parseInlineStyleFromTag(tagName: String, rawTag: String): InlineState {
            val style = STYLE_ATTR_REGEX.find(rawTag)?.groupValues?.getOrNull(1).orEmpty()
            val bold = tagName == "b" || tagName == "strong" ||
                Regex("font-weight\\s*:\\s*(bold|[6-9]00)", RegexOption.IGNORE_CASE).containsMatchIn(style)
            val italic = tagName == "i" || tagName == "em" ||
                Regex("font-style\\s*:\\s*(italic|oblique)", RegexOption.IGNORE_CASE).containsMatchIn(style)
            val underline = tagName == "u" ||
                Regex("text-decoration[^;]*underline", RegexOption.IGNORE_CASE).containsMatchIn(style)
            val font = Regex("font-family\\s*:\\s*([^;]+)", RegexOption.IGNORE_CASE)
                .find(style)?.groupValues?.getOrNull(1)
                ?.split(',')?.firstOrNull()?.trim('\'', '"', ' ')
                ?.takeIf { it.isNotBlank() }
            val color = Regex("(?<!-)color\\s*:\\s*(#[0-9a-fA-F]{3,6})", RegexOption.IGNORE_CASE)
                .find(style)?.groupValues?.getOrNull(1)
            return InlineState(bold = bold, italic = italic, underline = underline, fontFamily = font, colorHex = color)
        }

        for (match in TOKEN_REGEX.findAll(bodyHtml)) {
            val token = match.value
            if (token.startsWith("<")) {
                val isClosing = token.startsWith("</")
                val isSelfClosing = token.endsWith("/>")
                val tagName = TAG_NAME_REGEX.find(token)?.groupValues?.getOrNull(1)?.lowercase() ?: continue

                when (tagName) {
                    "br" -> {
                        if (currentRuns.isNotEmpty()) {
                            currentRuns.add(TextRun(text = "\n"))
                        }
                    }
                    "h1", "h2", "h3", "h4", "p", "div", "blockquote", "pre" -> {
                        if (!isClosing) {
                            flushParagraph(false)
                            currentHeading = when (tagName) {
                                "h1" -> 1
                                "h2" -> 2
                                "h3", "h4" -> 3
                                else -> 0
                            }
                            currentAlignment = parseAlignmentFromTag(token)
                        } else {
                            flushParagraph(false)
                        }
                    }
                    "ul" -> {
                        if (!isClosing) {
                            flushParagraph(false)
                            listTypeStack.addLast("bullet")
                        } else {
                            flushParagraph(false)
                            if (listTypeStack.isNotEmpty()) listTypeStack.removeLast()
                        }
                    }
                    "ol" -> {
                        if (!isClosing) {
                            flushParagraph(false)
                            listTypeStack.addLast("ordered")
                        } else {
                            flushParagraph(false)
                            if (listTypeStack.isNotEmpty()) listTypeStack.removeLast()
                        }
                    }
                    "li" -> {
                        flushParagraph(false)
                    }
                    "table" -> {
                        if (!isClosing && !isSelfClosing) {
                            flushParagraph(false)
                            if (tableStack.size < maxTableDepth) {
                                tableStack.addLast(HtmlTableContext())
                            }
                        } else if (isClosing) {
                            flushParagraph(false)
                            popAndFinalizeTopTable()
                        }
                    }
                    "tr" -> activeTable()?.let { tbl ->
                        if (!isClosing && !isSelfClosing) {
                            // HTML5 implicit close of prior unclosed <tr> / <td>
                            if (tbl.inRow || tbl.inCell) {
                                closeCurrentRow(tbl)
                            }
                            tbl.currentRow = mutableListOf()
                            tbl.inRow = true
                        } else if (isClosing) {
                            closeCurrentRow(tbl)
                        }
                    }
                    "td", "th" -> activeTable()?.let { tbl ->
                        if (!isClosing && !isSelfClosing) {
                            // HTML5 implicit close of prior unclosed <td>
                            if (tbl.inCell) {
                                closeCurrentCell(tbl)
                            }
                            if (!tbl.inRow) {
                                tbl.currentRow = mutableListOf()
                                tbl.inRow = true
                            }
                            tbl.currentCellParas = mutableListOf()
                            tbl.inCell = true
                        } else if (isClosing) {
                            closeCurrentCell(tbl)
                        }
                    }
                    "b", "strong", "i", "em", "u", "span", "font", "a" -> {
                        if (!isClosing && !isSelfClosing) {
                            inlineStack.addLast(parseInlineStyleFromTag(tagName, token))
                        } else if (isClosing && inlineStack.size > 1) {
                            inlineStack.removeLast()
                        }
                    }
                }
            } else {
                val decoded = decodeHtmlEntities(token).replace(Regex("[\\r\\n\\t]+"), " ")
                val collapsed = decoded.replace(Regex(" {2,}"), " ")
                if (collapsed.isNotEmpty() && (collapsed.isNotBlank() || currentRuns.isNotEmpty())) {
                    val eff = effectiveInline()
                    currentRuns.add(
                        TextRun(
                            text = collapsed,
                            fontFamily = eff.fontFamily,
                            bold = eff.bold,
                            italic = eff.italic,
                            underline = eff.underline,
                            colorHex = eff.colorHex
                        )
                    )
                }
            }
        }
        flushParagraph(false)
        while (tableStack.isNotEmpty()) {
            popAndFinalizeTopTable()
        }
        return blocks
    }

    /**
     * Linear O(N) HTML comment stripper (avoids regex backtracking / StackOverflowError on huge files).
     */
    private fun stripCommentsLinear(html: String): String {
        if (!html.contains("<!--")) return html
        val sb = StringBuilder(html.length)
        var i = 0
        while (i < html.length) {
            val start = html.indexOf("<!--", i)
            if (start == -1) {
                sb.append(html, i, html.length)
                break
            }
            sb.append(html, i, start)
            val end = html.indexOf("-->", start + 4)
            if (end == -1) break
            i = end + 3
        }
        return sb.toString()
    }

    /**
     * Linear O(N) stripper for <script>, <style>, <head>, and <noscript> blocks.
     */
    private fun stripIgnoredElementsLinear(html: String): String {
        val lower = html.lowercase()
        val sb = StringBuilder(html.length)
        var i = 0
        val n = html.length

        while (i < n) {
            val lt = lower.indexOf('<', i)
            if (lt == -1) {
                sb.append(html, i, n)
                break
            }
            var matchedTag: String? = null
            for (tag in IGNORED_BLOCK_TAGS) {
                val afterTag = lt + 1 + tag.length
                if (afterTag <= n &&
                    lower.regionMatches(lt + 1, tag, 0, tag.length) &&
                    (afterTag == n || lower[afterTag] == '>' || lower[afterTag].isWhitespace() || lower[afterTag] == '/')
                ) {
                    matchedTag = tag
                    break
                }
            }
            if (matchedTag == null) {
                sb.append(html, i, lt + 1)
                i = lt + 1
                continue
            }

            sb.append(html, i, lt)
            val openEnd = lower.indexOf('>', lt + 1 + matchedTag.length)
            if (openEnd == -1) break
            // Self-closing tag like <script src="..."/>
            if (openEnd > lt && lower[openEnd - 1] == '/') {
                i = openEnd + 1
                continue
            }
            val closeNeedle = "</$matchedTag"
            val closeStart = lower.indexOf(closeNeedle, openEnd + 1)
            if (closeStart == -1) break
            val closeEnd = lower.indexOf('>', closeStart + closeNeedle.length)
            if (closeEnd == -1) break
            i = closeEnd + 1
        }
        return sb.toString()
    }

    private fun extractBodySlice(html: String): String {
        val open = BODY_OPEN_REGEX.find(html) ?: return html
        val bodyStart = open.range.last + 1
        val close = BODY_CLOSE_REGEX.find(html, bodyStart)
        return if (close != null) html.substring(bodyStart, close.range.first) else html.substring(bodyStart)
    }

    private fun isZipHeader(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() &&
            bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() &&
            bytes[3] == 0x04.toByte()

    private fun decodeSmartCharset(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        return if (utf8.contains('\uFFFD')) {
            String(bytes, Charset.forName("windows-1252"))
        } else {
            utf8
        }
    }

    private fun decodeHtmlEntities(input: String): String {
        var s = input
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&hellip;", "…")
        s = NUM_ENTITY_HEX_REGEX.replace(s) { m ->
            val cp = m.groupValues[1].toIntOrNull(16)
            if (cp != null && cp > 0) String(Character.toChars(cp)) else m.value
        }
        s = NUM_ENTITY_DEC_REGEX.replace(s) { m ->
            val cp = m.groupValues[1].toIntOrNull(10)
            if (cp != null && cp > 0) String(Character.toChars(cp)) else m.value
        }
        return s
    }
}
