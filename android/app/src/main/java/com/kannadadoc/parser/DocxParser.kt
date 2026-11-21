package com.kannadadoc.parser

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Pure Kotlin OOXML (.docx) parser with full 4-level font inheritance resolution:
 *   1. Direct run <w:rPr><w:rFonts w:ascii / w:hAnsi / w:cs />
 *   2. Character style <w:rStyle w:val="..." /> (walking <w:basedOn>)
 *   3. Paragraph style <w:pStyle w:val="..." /> (walking <w:basedOn>)
 *   4. Document defaults <w:docDefaults><w:rPrDefault><w:rPr><w:rFonts />
 */
class DocxParser {

    companion object {
        private const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    }

    private data class StyleDefinition(
        val id: String,
        val name: String?,
        val type: String?,
        val basedOn: String?,
        val fontFamily: String?,
        val bold: Boolean?,
        val italic: Boolean?,
        val underline: Boolean?,
        val fontSizePt: Float?,
        val colorHex: String?,
        val alignment: String?,
        val outlineLevel: Int?
    )

    private data class StyleCatalog(
        val defaultFontFamily: String? = null,
        val defaultFontSizePt: Float? = null,
        val styles: Map<String, StyleDefinition> = emptyMap(),
        val defaultParagraphStyleId: String? = null
    )

    fun parse(inputStream: InputStream, sourceUri: String, fileName: String): ParsedDocument {
        val entries = readZipEntries(inputStream)
        val documentBytes = entries["word/document.xml"]
            ?: error("Missing word/document.xml inside DOCX archive")

        val styleCatalog = entries["word/styles.xml"]?.let { parseStyles(it) } ?: StyleCatalog()
        val numberingFormats = entries["word/numbering.xml"]?.let { parseNumbering(it) } ?: emptyMap()

        val xmlDoc = parseXml(documentBytes)
        val body = findFirstChild(xmlDoc.documentElement, "body")
            ?: return ParsedDocument(sourceUri, fileName, DocFormat.DOCX, emptyList())

        val blocks = mutableListOf<DocBlock>()
        var node = body.firstChild
        while (node != null) {
            if (node.nodeType == Node.ELEMENT_NODE) {
                val el = node as Element
                when (el.localName) {
                    "p" -> blocks.add(parseParagraph(el, styleCatalog, numberingFormats))
                    "tbl" -> blocks.add(parseTable(el, styleCatalog, numberingFormats))
                }
            }
            node = node.nextSibling
        }

        return ParsedDocument(
            sourceUri = sourceUri,
            fileName = fileName,
            sourceFormat = DocFormat.DOCX,
            blocks = blocks
        )
    }

    private fun parseParagraph(
        pEl: Element,
        catalog: StyleCatalog,
        numberingFormats: Map<String, String>
    ): DocBlock.Paragraph {
        val pPr = findFirstChild(pEl, "pPr")
        val pStyleId = pPr?.let { findFirstChild(it, "pStyle")?.wAttr("val") }
            ?: catalog.defaultParagraphStyleId

        val alignment = pPr?.let { extractAlignment(it) }
            ?: resolveParagraphAlignment(pStyleId, catalog)
            ?: "left"

        val headingLevel = resolveHeadingLevel(pPr, pStyleId, catalog)

        val listType = pPr?.let { findFirstChild(it, "numPr") }?.let { numPr ->
            val numId = findFirstChild(numPr, "numId")?.wAttr("val")
            when (numberingFormats[numId]) {
                "decimal", "lowerLetter", "upperLetter", "lowerRoman", "upperRoman" -> "ordered"
                else -> "bullet"
            }
        }

        val runs = mutableListOf<TextRun>()
        var child = pEl.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE) {
                val el = child as Element
                when (el.localName) {
                    "r" -> extractRun(el, pStyleId, catalog)?.let { runs.add(it) }
                    "hyperlink" -> {
                        var hChild = el.firstChild
                        while (hChild != null) {
                            if (hChild.nodeType == Node.ELEMENT_NODE && (hChild as Element).localName == "r") {
                                extractRun(hChild, pStyleId, catalog)?.let { runs.add(it) }
                            }
                            hChild = hChild.nextSibling
                        }
                    }
                }
            }
            child = child.nextSibling
        }

        return DocBlock.Paragraph(
            runs = mergeAdjacentRuns(runs),
            headingLevel = headingLevel,
            alignment = alignment,
            listType = listType
        )
    }

    private fun parseTable(
        tblEl: Element,
        catalog: StyleCatalog,
        numberingFormats: Map<String, String>
    ): DocBlock.Table {
        val rows = mutableListOf<List<List<DocBlock.Paragraph>>>()
        var trNode = tblEl.firstChild
        while (trNode != null) {
            if (trNode.nodeType == Node.ELEMENT_NODE && (trNode as Element).localName == "tr") {
                val cells = mutableListOf<List<DocBlock.Paragraph>>()
                var tcNode = trNode.firstChild
                while (tcNode != null) {
                    if (tcNode.nodeType == Node.ELEMENT_NODE && (tcNode as Element).localName == "tc") {
                        val cellParagraphs = mutableListOf<DocBlock.Paragraph>()
                        var pNode = tcNode.firstChild
                        while (pNode != null) {
                            if (pNode.nodeType == Node.ELEMENT_NODE && (pNode as Element).localName == "p") {
                                cellParagraphs.add(parseParagraph(pNode, catalog, numberingFormats))
                            }
                            pNode = pNode.nextSibling
                        }
                        cells.add(cellParagraphs)
                    }
                    tcNode = tcNode.nextSibling
                }
                if (cells.isNotEmpty()) rows.add(cells)
            }
            trNode = trNode.nextSibling
        }
        return DocBlock.Table(rows = rows)
    }

    private fun extractRun(
        rEl: Element,
        pStyleId: String?,
        catalog: StyleCatalog
    ): TextRun? {
        val sb = StringBuilder()
        var child = rEl.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE) {
                val el = child as Element
                when (el.localName) {
                    "t", "delText" -> sb.append(el.textContent ?: "")
                    "tab" -> sb.append("\t")
                    "br", "cr" -> sb.append("\n")
                }
            }
            child = child.nextSibling
        }

        val text = sb.toString()
        if (text.isEmpty()) return null

        val rPr = findFirstChild(rEl, "rPr")
        val rStyleId = rPr?.let { findFirstChild(it, "rStyle")?.wAttr("val") }

        val resolvedFont = rPr?.let { extractFontFamily(it) }
            ?: resolveStyleProperty(rStyleId, catalog) { it.fontFamily }
            ?: resolveStyleProperty(pStyleId, catalog) { it.fontFamily }
            ?: catalog.defaultFontFamily

        val resolvedBold = rPr?.let { extractBooleanToggle(it, "b") }
            ?: resolveStyleProperty(rStyleId, catalog) { it.bold }
            ?: resolveStyleProperty(pStyleId, catalog) { it.bold }
            ?: false

        val resolvedItalic = rPr?.let { extractBooleanToggle(it, "i") }
            ?: resolveStyleProperty(rStyleId, catalog) { it.italic }
            ?: resolveStyleProperty(pStyleId, catalog) { it.italic }
            ?: false

        val resolvedUnderline = rPr?.let { extractUnderline(it) }
            ?: resolveStyleProperty(rStyleId, catalog) { it.underline }
            ?: resolveStyleProperty(pStyleId, catalog) { it.underline }
            ?: false

        val resolvedSize = rPr?.let { extractFontSizePt(it) }
            ?: resolveStyleProperty(rStyleId, catalog) { it.fontSizePt }
            ?: resolveStyleProperty(pStyleId, catalog) { it.fontSizePt }
            ?: catalog.defaultFontSizePt

        val resolvedColor = rPr?.let { extractColorHex(it) }
            ?: resolveStyleProperty(rStyleId, catalog) { it.colorHex }
            ?: resolveStyleProperty(pStyleId, catalog) { it.colorHex }

        return TextRun(
            text = text,
            fontFamily = resolvedFont,
            bold = resolvedBold,
            italic = resolvedItalic,
            underline = resolvedUnderline,
            fontSizePt = resolvedSize,
            colorHex = resolvedColor
        )
    }

    private fun parseStyles(bytes: ByteArray): StyleCatalog {
        val doc = parseXml(bytes)
        val root = doc.documentElement

        var defaultFont: String? = null
        var defaultSize: Float? = null
        findFirstChild(root, "docDefaults")
            ?.let { findFirstChild(it, "rPrDefault") }
            ?.let { findFirstChild(it, "rPr") }
            ?.let { rPr ->
                defaultFont = extractFontFamily(rPr)
                defaultSize = extractFontSizePt(rPr)
            }

        val styles = mutableMapOf<String, StyleDefinition>()
        var defaultParaStyleId: String? = null

        var node = root.firstChild
        while (node != null) {
            if (node.nodeType == Node.ELEMENT_NODE && (node as Element).localName == "style") {
                val styleId = node.wAttr("styleId")
                if (!styleId.isNullOrBlank()) {
                    val type = node.wAttr("type")
                    val isDefault = node.wAttr("default") == "1" || node.wAttr("default").equals("true", true)
                    if (isDefault && type == "paragraph") {
                        defaultParaStyleId = styleId
                    }
                    val name = findFirstChild(node, "name")?.wAttr("val")
                    val basedOn = findFirstChild(node, "basedOn")?.wAttr("val")
                    val rPr = findFirstChild(node, "rPr")
                    val pPr = findFirstChild(node, "pPr")

                    styles[styleId] = StyleDefinition(
                        id = styleId,
                        name = name,
                        type = type,
                        basedOn = basedOn,
                        fontFamily = rPr?.let { extractFontFamily(it) },
                        bold = rPr?.let { extractBooleanToggle(it, "b") },
                        italic = rPr?.let { extractBooleanToggle(it, "i") },
                        underline = rPr?.let { extractUnderline(it) },
                        fontSizePt = rPr?.let { extractFontSizePt(it) },
                        colorHex = rPr?.let { extractColorHex(it) },
                        alignment = pPr?.let { extractAlignment(it) },
                        outlineLevel = pPr?.let { findFirstChild(it, "outlineLvl")?.wAttr("val")?.toIntOrNull() }
                    )
                }
            }
            node = node.nextSibling
        }

        return StyleCatalog(
            defaultFontFamily = defaultFont,
            defaultFontSizePt = defaultSize,
            styles = styles,
            defaultParagraphStyleId = defaultParaStyleId
        )
    }

    private fun parseNumbering(bytes: ByteArray): Map<String, String> {
        return try {
            val doc = parseXml(bytes)
            val root = doc.documentElement
            val abstractFormats = mutableMapOf<String, String>()
            val numIdToFormat = mutableMapOf<String, String>()

            var node = root.firstChild
            while (node != null) {
                if (node.nodeType == Node.ELEMENT_NODE) {
                    val el = node as Element
                    if (el.localName == "abstractNum") {
                        val absId = el.wAttr("abstractNumId")
                        val lvl0 = findFirstChild(el, "lvl")
                        val numFmt = lvl0?.let { findFirstChild(it, "numFmt")?.wAttr("val") }
                        if (absId != null && numFmt != null) {
                            abstractFormats[absId] = numFmt
                        }
                    }
                }
                node = node.nextSibling
            }

            node = root.firstChild
            while (node != null) {
                if (node.nodeType == Node.ELEMENT_NODE) {
                    val el = node as Element
                    if (el.localName == "num") {
                        val numId = el.wAttr("numId")
                        val absId = findFirstChild(el, "abstractNumId")?.wAttr("val")
                        if (numId != null && absId != null) {
                            abstractFormats[absId]?.let { numIdToFormat[numId] = it }
                        }
                    }
                }
                node = node.nextSibling
            }
            numIdToFormat
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun <T> resolveStyleProperty(
        startStyleId: String?,
        catalog: StyleCatalog,
        selector: (StyleDefinition) -> T?
    ): T? {
        var currentId = startStyleId
        val visited = mutableSetOf<String>()
        while (currentId != null && visited.add(currentId)) {
            val def = catalog.styles[currentId] ?: break
            val value = selector(def)
            if (value != null) return value
            currentId = def.basedOn
        }
        return null
    }

    private fun resolveParagraphAlignment(pStyleId: String?, catalog: StyleCatalog): String? =
        resolveStyleProperty(pStyleId, catalog) { it.alignment }

    private fun resolveHeadingLevel(pPr: Element?, pStyleId: String?, catalog: StyleCatalog): Int {
        val directOutline = pPr?.let { findFirstChild(it, "outlineLvl")?.wAttr("val")?.toIntOrNull() }
        if (directOutline != null && directOutline in 0..2) return directOutline + 1

        val styleOutline = resolveStyleProperty(pStyleId, catalog) { it.outlineLevel }
        if (styleOutline != null && styleOutline in 0..2) return styleOutline + 1

        val styleName = catalog.styles[pStyleId]?.name ?: pStyleId ?: return 0
        return when {
            styleName.equals("Title", true) || styleName.equals("Heading 1", true) || styleName.equals("Heading1", true) -> 1
            styleName.equals("Heading 2", true) || styleName.equals("Heading2", true) -> 2
            styleName.equals("Heading 3", true) || styleName.equals("Heading3", true) -> 3
            else -> 0
        }
    }

    private fun extractFontFamily(rPr: Element): String? {
        val rFonts = findFirstChild(rPr, "rFonts") ?: return null
        return rFonts.wAttr("ascii")?.takeIf { it.isNotBlank() }
            ?: rFonts.wAttr("hAnsi")?.takeIf { it.isNotBlank() }
            ?: rFonts.wAttr("cs")?.takeIf { it.isNotBlank() }
    }

    private fun extractBooleanToggle(rPr: Element, tagName: String): Boolean? {
        val el = findFirstChild(rPr, tagName) ?: return null
        val valAttr = el.wAttr("val")
        return valAttr == null || (valAttr != "0" && !valAttr.equals("false", true))
    }

    private fun extractUnderline(rPr: Element): Boolean? {
        val el = findFirstChild(rPr, "u") ?: return null
        val valAttr = el.wAttr("val")
        return valAttr == null || !valAttr.equals("none", true)
    }

    private fun extractFontSizePt(rPr: Element): Float? {
        val halfPoints = findFirstChild(rPr, "sz")?.wAttr("val")?.toFloatOrNull() ?: return null
        return halfPoints / 2f
    }

    private fun extractColorHex(rPr: Element): String? {
        val raw = findFirstChild(rPr, "color")?.wAttr("val") ?: return null
        return if (raw.equals("auto", true) || raw.length != 6) null else "#$raw"
    }

    private fun extractAlignment(pPr: Element): String? {
        val jc = findFirstChild(pPr, "jc")?.wAttr("val") ?: return null
        return when (jc.lowercase()) {
            "center" -> "center"
            "right", "end" -> "right"
            "both", "distribute" -> "justify"
            else -> "left"
        }
    }

    private fun mergeAdjacentRuns(runs: List<TextRun>): List<TextRun> {
        if (runs.size <= 1) return runs
        val merged = mutableListOf<TextRun>()
        for (run in runs) {
            val prev = merged.lastOrNull()
            if (prev != null &&
                prev.fontFamily == run.fontFamily &&
                prev.bold == run.bold &&
                prev.italic == run.italic &&
                prev.underline == run.underline &&
                prev.fontSizePt == run.fontSizePt &&
                prev.colorHex == run.colorHex
            ) {
                merged[merged.lastIndex] = prev.copy(text = prev.text + run.text)
            } else {
                merged.add(run)
            }
        }
        return merged
    }

    private fun readZipEntries(inputStream: InputStream): Map<String, ByteArray> {
        val map = mutableMapOf<String, ByteArray>()
        ZipInputStream(inputStream).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name.lowercase()
                if (name == "word/document.xml" || name == "word/styles.xml" || name == "word/numbering.xml") {
                    map[name] = zis.readBytes()
                }
                entry = zis.nextEntry
            }
        }
        return map
    }

    private fun parseXml(bytes: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun findFirstChild(parent: Element, localName: String): Element? {
        var node = parent.firstChild
        while (node != null) {
            if (node.nodeType == Node.ELEMENT_NODE && (node as Element).localName == localName) {
                return node
            }
            node = node.nextSibling
        }
        return null
    }

    private fun Element.wAttr(localName: String): String? {
        val nsVal = getAttributeNS(W_NS, localName)
        if (!nsVal.isNullOrEmpty()) return nsVal
        val prefixed = getAttribute("w:$localName")
        if (!prefixed.isNullOrEmpty()) return prefixed
        val plain = getAttribute(localName)
        return plain.ifEmpty { null }
    }
}
