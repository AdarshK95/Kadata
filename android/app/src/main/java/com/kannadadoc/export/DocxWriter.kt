package com.kannadadoc.export

import com.kannadadoc.parser.DocBlock
import com.kannadadoc.parser.ParsedDocument
import com.kannadadoc.parser.TextRun
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Pure Kotlin OOXML (.docx) writer producing clean, standards-compliant Word documents
 * with explicit Kannada Unicode font tags (Noto Sans Kannada) or legacy Nudi 01 e tags.
 */
class DocxWriter {

    fun write(document: ParsedDocument, outputStream: OutputStream, defaultFont: String = "Noto Sans Kannada") {
        ZipOutputStream(outputStream).use { zos ->
            putXml(zos, "[Content_Types].xml", buildContentTypes())
            putXml(zos, "_rels/.rels", buildRootRels())
            putXml(zos, "word/_rels/document.xml.rels", buildDocumentRels())
            putXml(zos, "word/styles.xml", buildStylesXml(defaultFont))
            putXml(zos, "word/document.xml", buildDocumentXml(document, defaultFont))
        }
    }

    private fun putXml(zos: ZipOutputStream, name: String, xml: String) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(xml.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    private fun buildContentTypes(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
          <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
          <Default Extension="xml" ContentType="application/xml"/>
          <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
          <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
        </Types>
    """.trimIndent()

    private fun buildRootRels(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
        </Relationships>
    """.trimIndent()

    private fun buildDocumentRels(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
        </Relationships>
    """.trimIndent()

    private fun buildStylesXml(defaultFont: String): String {
        val escFont = escapeXml(defaultFont)
        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:docDefaults>
                <w:rPrDefault>
                  <w:rPr>
                    <w:rFonts w:ascii="$escFont" w:hAnsi="$escFont" w:cs="$escFont"/>
                    <w:sz w:val="24"/>
                    <w:szCs w:val="24"/>
                  </w:rPr>
                </w:rPrDefault>
              </w:docDefaults>
              <w:style w:type="paragraph" w:default="1" w:styleId="Normal">
                <w:name w:val="Normal"/>
              </w:style>
              <w:style w:type="paragraph" w:styleId="Heading1">
                <w:name w:val="Heading 1"/>
                <w:basedOn w:val="Normal"/>
                <w:rPr><w:b/><w:sz w:val="36"/><w:szCs w:val="36"/></w:rPr>
              </w:style>
              <w:style w:type="paragraph" w:styleId="Heading2">
                <w:name w:val="Heading 2"/>
                <w:basedOn w:val="Normal"/>
                <w:rPr><w:b/><w:sz w:val="30"/><w:szCs w:val="30"/></w:rPr>
              </w:style>
              <w:style w:type="paragraph" w:styleId="Heading3">
                <w:name w:val="Heading 3"/>
                <w:basedOn w:val="Normal"/>
                <w:rPr><w:b/><w:sz w:val="26"/><w:szCs w:val="26"/></w:rPr>
              </w:style>
            </w:styles>
        """.trimIndent()
    }

    private fun buildDocumentXml(document: ParsedDocument, defaultFont: String): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""")

        var orderedCounter = 0
        for (block in document.blocks) {
            when (block) {
                is DocBlock.Paragraph -> {
                    if (block.listType == "ordered") orderedCounter++ else orderedCounter = 0
                    val prefix = when (block.listType) {
                        "bullet" -> "• "
                        "ordered" -> "$orderedCounter. "
                        else -> null
                    }
                    appendParagraphXml(sb, block, defaultFont, prefix)
                }
                is DocBlock.Table -> {
                    orderedCounter = 0
                    appendTableXml(sb, block, defaultFont)
                }
            }
        }

        sb.append(
            """<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr>"""
        )
        sb.append("</w:body></w:document>")
        return sb.toString()
    }

    private fun appendParagraphXml(
        sb: StringBuilder,
        para: DocBlock.Paragraph,
        defaultFont: String,
        listPrefix: String? = null
    ) {
        sb.append("<w:p>")
        val hasStyle = para.headingLevel in 1..3
        val jc = when (para.alignment) {
            "center" -> "center"
            "right" -> "right"
            "justify" -> "both"
            else -> null
        }
        if (hasStyle || jc != null) {
            sb.append("<w:pPr>")
            if (hasStyle) sb.append("""<w:pStyle w:val="Heading${para.headingLevel}"/>""")
            if (jc != null) sb.append("""<w:jc w:val="$jc"/>""")
            sb.append("</w:pPr>")
        }

        if (listPrefix != null) {
            appendRunXml(sb, TextRun(text = listPrefix, fontFamily = defaultFont), defaultFont)
        }
        for (run in para.runs) {
            appendRunXml(sb, run, defaultFont)
        }
        sb.append("</w:p>")
    }

    private fun appendTableXml(sb: StringBuilder, table: DocBlock.Table, defaultFont: String) {
        sb.append("<w:tbl>")
        sb.append(
            """<w:tblPr><w:tblW w:w="5000" w:type="pct"/><w:tblBorders>""" +
                """<w:top w:val="single" w:sz="4" w:color="A0A0A0"/>""" +
                """<w:left w:val="single" w:sz="4" w:color="A0A0A0"/>""" +
                """<w:bottom w:val="single" w:sz="4" w:color="A0A0A0"/>""" +
                """<w:right w:val="single" w:sz="4" w:color="A0A0A0"/>""" +
                """<w:insideH w:val="single" w:sz="4" w:color="C0C0C0"/>""" +
                """<w:insideV w:val="single" w:sz="4" w:color="C0C0C0"/>""" +
                """</w:tblBorders></w:tblPr>"""
        )
        for (row in table.rows) {
            sb.append("<w:tr>")
            for (cellParas in row) {
                sb.append("<w:tc>")
                if (cellParas.isEmpty()) {
                    sb.append("<w:p/>")
                } else {
                    for (p in cellParas) {
                        appendParagraphXml(sb, p, defaultFont)
                    }
                }
                sb.append("</w:tc>")
            }
            sb.append("</w:tr>")
        }
        sb.append("</w:tbl>")
    }

    private fun appendRunXml(sb: StringBuilder, run: TextRun, defaultFont: String) {
        val font = escapeXml(run.fontFamily ?: defaultFont)
        sb.append("<w:r><w:rPr>")
        sb.append("""<w:rFonts w:ascii="$font" w:hAnsi="$font" w:cs="$font"/>""")
        if (run.bold) sb.append("<w:b/>")
        if (run.italic) sb.append("<w:i/>")
        if (run.underline) sb.append("""<w:u w:val="single"/>""")
        run.fontSizePt?.let { pt ->
            val halfPts = (pt * 2).toInt()
            if (halfPts > 0) sb.append("""<w:sz w:val="$halfPts"/><w:szCs w:val="$halfPts"/>""")
        }
        run.colorHex?.removePrefix("#")?.takeIf { it.length == 6 }?.let { hex ->
            sb.append("""<w:color w:val="$hex"/>""")
        }
        sb.append("</w:rPr>")

        val lines = run.text.split("\n")
        lines.forEachIndexed { idx, segment ->
            if (idx > 0) sb.append("<w:br/>")
            if (segment.isNotEmpty()) {
                sb.append("""<w:t xml:space="preserve">""")
                sb.append(escapeXml(segment))
                sb.append("</w:t>")
            }
        }
        sb.append("</w:r>")
    }

    private fun escapeXml(input: String): String = XmlSanitizer.escapeXml(input)
}
