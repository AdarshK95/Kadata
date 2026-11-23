package com.kannadadoc.parser

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream

class OdtParser internal constructor(
    private val pullParserFactory: () -> XmlPullParser
) {
    constructor() : this({ Xml.newPullParser() })

    private data class OdtStyle(
        val fontFamily: String? = null,
        val bold: Boolean? = null,
        val italic: Boolean? = null,
        val underline: Boolean? = null,
        val colorHex: String? = null,
        val alignment: String? = null,
        val parentName: String? = null
    )

    fun parse(inputStream: InputStream, sourceUri: String, fileName: String): ParsedDocument {
        val entries = SafeZipReader.readMatchingEntries(
            inputStream = inputStream,
            fileName = fileName,
            formatLabel = "ODT"
        ) { norm ->
            norm == "styles.xml" || norm == "content.xml"
        }
        val stylesXmlBytes = entries["styles.xml"]
        val contentXmlBytes = entries["content.xml"]

        val contentBytes = contentXmlBytes
            ?: error("Invalid ODT document: missing content.xml")

        val rawStyles = mutableMapOf<String, OdtStyle>()
        val fontDecls = mutableMapOf<String, String>()

        val warnings = mutableListOf<String>()
        if (stylesXmlBytes != null && !parseStyleDefinitions(stylesXmlBytes, rawStyles, fontDecls)) {
            warnings.add("Document styles (styles.xml) were malformed; opened with default formatting.")
        }
        if (!parseStyleDefinitions(contentBytes, rawStyles, fontDecls)) {
            warnings.add("Some inline formatting definitions in content.xml could not be read.")
        }

        fun resolveStyle(name: String?, depth: Int = 0): OdtStyle {
            if (name.isNullOrBlank() || depth > 8) return OdtStyle()
            val cur = rawStyles[name] ?: return OdtStyle()
            val parent = if (!cur.parentName.isNullOrBlank() && cur.parentName != name) {
                resolveStyle(cur.parentName, depth + 1)
            } else {
                OdtStyle()
            }
            val rawFont = cur.fontFamily ?: parent.fontFamily
            val mappedFont = rawFont?.let { fontDecls[it] ?: it }
            return OdtStyle(
                fontFamily = mappedFont,
                bold = cur.bold ?: parent.bold,
                italic = cur.italic ?: parent.italic,
                underline = cur.underline ?: parent.underline,
                colorHex = cur.colorHex ?: parent.colorHex,
                alignment = cur.alignment ?: parent.alignment
            )
        }

        val bodyResult = parseBodyBlocks(contentBytes, ::resolveStyle)
        val finalBlocks = if (bodyResult.blocks.isEmpty()) {
            listOf(DocBlock.Paragraph(runs = listOf(TextRun(text = ""))))
        } else {
            bodyResult.blocks
        }

        bodyResult.warningMessage?.let { warnings.add(it) }
        return ParsedDocument(
            sourceUri = sourceUri,
            fileName = fileName,
            sourceFormat = DocFormat.ODT,
            blocks = finalBlocks,
            warningMessage = warnings.takeIf { it.isNotEmpty() }?.joinToString(" ")
        )
    }

    private fun parseStyleDefinitions(
        xmlBytes: ByteArray,
        stylesOut: MutableMap<String, OdtStyle>,
        fontDeclsOut: MutableMap<String, String>
    ): Boolean {
        try {
            val parser = pullParserFactory()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(ByteArrayInputStream(xmlBytes), "UTF-8")

            var currentStyleName: String? = null
            var curFont: String? = null
            var curBold: Boolean? = null
            var curItalic: Boolean? = null
            var curUnderline: Boolean? = null
            var curColor: String? = null
            var curAlign: String? = null
            var curParent: String? = null

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "font-face" -> {
                                val name = attr(parser, "name")
                                val family = attr(parser, "font-family")?.trim('\'', '"', ' ')
                                if (!name.isNullOrBlank() && !family.isNullOrBlank()) {
                                    fontDeclsOut[name] = family
                                }
                            }
                            "style" -> {
                                currentStyleName = attr(parser, "name")
                                curParent = attr(parser, "parent-style-name")
                                curFont = null
                                curBold = null
                                curItalic = null
                                curUnderline = null
                                curColor = null
                                curAlign = null
                            }
                            "text-properties" -> if (currentStyleName != null) {
                                attr(parser, "font-name")?.let { curFont = it }
                                if (curFont == null) {
                                    attr(parser, "font-family")?.trim('\'', '"', ' ')?.let { curFont = it }
                                }
                                attr(parser, "font-weight")?.let { fw ->
                                    if (fw.equals("bold", true) || (fw.toIntOrNull() ?: 0) >= 600) {
                                        curBold = true
                                    }
                                }
                                attr(parser, "font-style")?.let { fs ->
                                    if (fs.equals("italic", true) || fs.equals("oblique", true)) {
                                        curItalic = true
                                    }
                                }
                                attr(parser, "text-underline-style")?.let { ul ->
                                    if (!ul.equals("none", true) && ul.isNotBlank()) {
                                        curUnderline = true
                                    }
                                }
                                attr(parser, "color")?.let { col ->
                                    if (col.startsWith("#") && (col.length == 7 || col.length == 4)) {
                                        curColor = col
                                    }
                                }
                            }
                            "paragraph-properties" -> if (currentStyleName != null) {
                                attr(parser, "text-align")?.lowercase()?.let { align ->
                                    curAlign = when (align) {
                                        "center" -> "center"
                                        "end", "right" -> "right"
                                        "justify" -> "justify"
                                        else -> "left"
                                    }
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "style" && !currentStyleName.isNullOrBlank()) {
                            stylesOut[currentStyleName!!] = OdtStyle(
                                fontFamily = curFont,
                                bold = curBold,
                                italic = curItalic,
                                underline = curUnderline,
                                colorHex = curColor,
                                alignment = curAlign,
                                parentName = curParent
                            )
                            currentStyleName = null
                        }
                    }
                }
                event = parser.next()
            }
            return true
        } catch (_: Exception) {
            return false
        }
    }

    private data class BodyParseResult(
        val blocks: List<DocBlock>,
        val warningMessage: String? = null
    )

    private fun parseBodyBlocks(
        xmlBytes: ByteArray,
        resolveStyle: (String?) -> OdtStyle
    ): BodyParseResult {
        val blocks = mutableListOf<DocBlock>()
        val bodyWarnings = mutableListOf<String>()
        var damagedTableCount = 0

        try {
            val parser = pullParserFactory()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(ByteArrayInputStream(xmlBytes), "UTF-8")

            var inTextBody = false
            var listDepth = 0
            var event = parser.eventType

            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "text" -> inTextBody = true
                            "list" -> if (inTextBody) listDepth++
                            "table" -> if (inTextBody) {
                                val (tableBlock, hadError) = parseTable(parser, resolveStyle)
                                if (hadError) damagedTableCount++
                                tableBlock?.let { blocks.add(it) }
                            }
                            "h", "p" -> if (inTextBody) {
                                val para = parseParagraph(
                                    parser = parser,
                                    isHeading = parser.name == "h",
                                    listType = if (listDepth > 0) "bullet" else null,
                                    resolveStyle = resolveStyle
                                )
                                blocks.add(para)
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "text" -> inTextBody = false
                            "list" -> if (listDepth > 0) listDepth--
                        }
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            if (blocks.isEmpty()) {
                throw IllegalStateException("Failed to parse ODT content.xml: ${e.message ?: "malformed XML"}", e)
            }
            bodyWarnings.add("This ODT file had malformed or truncated XML; recovered ${blocks.size} section(s) before the error.")
        }
        if (damagedTableCount > 0) {
            bodyWarnings.add("$damagedTableCount table(s) had damaged XML and were partially recovered.")
        }

        return BodyParseResult(
            blocks = blocks,
            warningMessage = bodyWarnings.takeIf { it.isNotEmpty() }?.joinToString(" ")
        )
    }

    private class OdtTableContext(
        val rows: MutableList<List<List<DocBlock.Paragraph>>> = mutableListOf(),
        var currentRow: MutableList<List<DocBlock.Paragraph>>? = null,
        var currentCell: MutableList<DocBlock.Paragraph>? = null
    )

    private fun parseTable(
        parser: XmlPullParser,
        resolveStyle: (String?) -> OdtStyle
    ): Pair<DocBlock.Table?, Boolean> {
        val tableStack = ArrayDeque<OdtTableContext>()
        val rootContext = OdtTableContext()
        tableStack.addLast(rootContext)
        var hadError = false

        fun finalizeTopTable(): DocBlock.Table? {
            if (tableStack.isEmpty()) return null
            val finished = tableStack.removeLast()
            finished.currentCell?.takeIf { it.isNotEmpty() }?.let { cell ->
                val row = finished.currentRow ?: mutableListOf<List<DocBlock.Paragraph>>().also { finished.currentRow = it }
                row.add(cell)
            }
            finished.currentCell = null
            finished.currentRow?.takeIf { it.isNotEmpty() }?.let { finished.rows.add(it) }
            finished.currentRow = null

            val parent = tableStack.lastOrNull()
            return if (parent == null) {
                if (finished.rows.isNotEmpty()) DocBlock.Table(rows = finished.rows) else null
            } else {
                val flattened = TableFlattenHelper.flattenNestedTableToParagraphs(finished.rows)
                if (flattened.isNotEmpty()) {
                    val parentCell = parent.currentCell ?: mutableListOf<DocBlock.Paragraph>().also { parent.currentCell = it }
                    parentCell.addAll(flattened)
                }
                null
            }
        }

        var finishedRoot: DocBlock.Table? = null
        try {
            while (tableStack.isNotEmpty()) {
                when (parser.next()) {
                    XmlPullParser.START_TAG -> {
                        val active = tableStack.last()
                        when (parser.name) {
                            "table" -> tableStack.addLast(OdtTableContext())
                            "table-row" -> active.currentRow = mutableListOf()
                            "table-cell" -> active.currentCell = mutableListOf()
                            "h", "p" -> {
                                val para = parseParagraph(
                                    parser = parser,
                                    isHeading = parser.name == "h",
                                    listType = null,
                                    resolveStyle = resolveStyle
                                )
                                val cell = active.currentCell ?: mutableListOf<DocBlock.Paragraph>().also { active.currentCell = it }
                                cell.add(para)
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val active = tableStack.last()
                        when (parser.name) {
                            "table-cell" -> {
                                active.currentCell?.let { cell ->
                                    if (cell.isEmpty()) cell.add(DocBlock.Paragraph(emptyList()))
                                    val row = active.currentRow ?: mutableListOf<List<DocBlock.Paragraph>>().also { active.currentRow = it }
                                    row.add(cell)
                                }
                                active.currentCell = null
                            }
                            "table-row" -> {
                                active.currentRow?.takeIf { it.isNotEmpty() }?.let { active.rows.add(it) }
                                active.currentRow = null
                            }
                            "table" -> {
                                val maybeRoot = finalizeTopTable()
                                if (tableStack.isEmpty()) {
                                    finishedRoot = maybeRoot
                                }
                            }
                        }
                    }
                    XmlPullParser.END_DOCUMENT -> break
                }
            }
        } catch (_: Exception) {
            hadError = true
        }

        // Unwind any unclosed nested/root tables cleanly on EOF or XML error
        while (tableStack.isNotEmpty()) {
            val maybeRoot = finalizeTopTable()
            if (tableStack.isEmpty()) {
                finishedRoot = maybeRoot
            }
        }
        return finishedRoot to hadError
    }

    private fun parseParagraph(
        parser: XmlPullParser,
        isHeading: Boolean,
        listType: String?,
        resolveStyle: (String?) -> OdtStyle
    ): DocBlock.Paragraph {
        val paraStyleName = attr(parser, "style-name")
        val baseStyle = resolveStyle(paraStyleName)
        val headingLevel = if (isHeading) {
            (attr(parser, "outline-level")?.toIntOrNull() ?: 1).coerceIn(1, 3)
        } else {
            0
        }

        val runs = mutableListOf<TextRun>()
        val styleStack = ArrayDeque<OdtStyle>()
        styleStack.addLast(baseStyle)

        val endTagName = parser.name
        var depth = 1

        fun currentEffectiveStyle(): OdtStyle {
            var font: String? = null
            var bold = false
            var italic = false
            var underline = false
            var color: String? = null
            for (s in styleStack) {
                s.fontFamily?.let { font = it }
                if (s.bold == true) bold = true
                if (s.italic == true) italic = true
                if (s.underline == true) underline = true
                s.colorHex?.let { color = it }
            }
            return OdtStyle(fontFamily = font, bold = bold, italic = italic, underline = underline, colorHex = color)
        }

        fun appendRun(text: String) {
            if (text.isEmpty()) return
            val eff = currentEffectiveStyle()
            runs.add(
                TextRun(
                    text = text,
                    fontFamily = eff.fontFamily,
                    bold = eff.bold == true,
                    italic = eff.italic == true,
                    underline = eff.underline == true,
                    colorHex = eff.colorHex
                )
            )
        }

        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name
                    if (tag == endTagName) {
                        depth++
                    } else when (tag) {
                        "span" -> {
                            styleStack.addLast(resolveStyle(attr(parser, "style-name")))
                        }
                        "s" -> {
                            val count = (attr(parser, "c")?.toIntOrNull() ?: 1).coerceIn(1, 32)
                            appendRun(" ".repeat(count))
                        }
                        "tab" -> appendRun("    ")
                        "line-break" -> appendRun("\n")
                    }
                }
                XmlPullParser.TEXT -> {
                    val t = parser.text
                    if (!t.isNullOrEmpty()) appendRun(t)
                }
                XmlPullParser.END_TAG -> {
                    val tag = parser.name
                    if (tag == endTagName) {
                        depth--
                    } else if (tag == "span" && styleStack.size > 1) {
                        styleStack.removeLast()
                    }
                }
                XmlPullParser.END_DOCUMENT -> break
            }
        }

        return DocBlock.Paragraph(
            runs = runs,
            headingLevel = headingLevel,
            alignment = baseStyle.alignment ?: "left",
            listType = listType
        )
    }

    private fun attr(parser: XmlPullParser, localName: String): String? {
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeName(i) == localName) {
                return parser.getAttributeValue(i)
            }
        }
        return null
    }
}
