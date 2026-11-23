package com.kannadadoc.export

import com.kannadadoc.parser.DocBlock
import com.kannadadoc.parser.ParsedDocument
import com.kannadadoc.parser.TextRun
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OdtWriter {

    fun write(doc: ParsedDocument, outputStream: OutputStream, defaultFont: String = "Noto Sans Kannada") {
        val fontNames = linkedSetOf(defaultFont, "Calibri", "Noto Sans Kannada")
        val textStyleMap = linkedMapOf<String, String>() // key -> styleName (T1, T2...)
        val textStyleDefs = StringBuilder()

        fun registerTextStyle(run: TextRun): String {
            val font = run.fontFamily?.takeIf { it.isNotBlank() } ?: defaultFont
            fontNames.add(font)
            val key = "$font|${run.bold}|${run.italic}|${run.underline}|${run.colorHex.orEmpty()}"
            return textStyleMap.getOrPut(key) {
                val name = "T${textStyleMap.size + 1}"
                val attrs = StringBuilder()
                attrs.append(" style:font-name=\"${esc(font)}\"")
                if (run.bold) attrs.append(" fo:font-weight=\"bold\"")
                if (run.italic) attrs.append(" fo:font-style=\"italic\"")
                if (run.underline) attrs.append(" style:text-underline-style=\"solid\" style:text-underline-width=\"auto\" style:text-underline-color=\"font-color\"")
                if (!run.colorHex.isNullOrBlank()) attrs.append(" fo:color=\"${esc(run.colorHex)}\"")
                textStyleDefs.append(
                    "<style:style style:name=\"$name\" style:family=\"text\">" +
                        "<style:text-properties$attrs/>" +
                        "</style:style>\n"
                )
                name
            }
        }

        val bodyXml = StringBuilder()
        fun appendParagraph(p: DocBlock.Paragraph) {
            val pStyle = when (p.alignment) {
                "center" -> "PCenter"
                "right" -> "PRight"
                "justify" -> "PJustify"
                else -> "PLeft"
            }
            val openTag = if (p.headingLevel in 1..3) {
                "<text:h text:style-name=\"H${p.headingLevel}\" text:outline-level=\"${p.headingLevel}\">"
            } else {
                "<text:p text:style-name=\"$pStyle\">"
            }
            val closeTag = if (p.headingLevel in 1..3) "</text:h>" else "</text:p>"

            bodyXml.append(openTag)
            for (run in p.runs) {
                if (run.text.isEmpty()) continue
                val styleName = registerTextStyle(run)
                val lines = run.text.split("\n")
                lines.forEachIndexed { idx, segment ->
                    if (idx > 0) bodyXml.append("<text:line-break/>")
                    if (segment.isNotEmpty()) {
                        bodyXml.append("<text:span text:style-name=\"$styleName\">${esc(segment)}</text:span>")
                    }
                }
            }
            bodyXml.append(closeTag).append('\n')
        }

        var activeListType: String? = null
        var tableCounter = 0

        fun closeListIfOpen() {
            if (activeListType != null) {
                bodyXml.append("</text:list>\n")
                activeListType = null
            }
        }

        for (block in doc.blocks) {
            when (block) {
                is DocBlock.Paragraph -> {
                    val itemListType = block.listType?.takeIf { it == "bullet" || it == "ordered" }
                    if (itemListType != null) {
                        if (activeListType != itemListType) {
                            closeListIfOpen()
                            val listStyle = if (itemListType == "ordered") "OrderedList" else "BulletList"
                            bodyXml.append("<text:list text:style-name=\"$listStyle\">\n")
                            activeListType = itemListType
                        }
                        bodyXml.append("<text:list-item>")
                        appendParagraph(block)
                        bodyXml.append("</text:list-item>\n")
                    } else {
                        closeListIfOpen()
                        appendParagraph(block)
                    }
                }
                is DocBlock.Table -> {
                    closeListIfOpen()
                    tableCounter++
                    val maxCols = block.rows.maxOfOrNull { it.size } ?: 1
                    bodyXml.append("<table:table table:name=\"Table$tableCounter\">\n")
                    repeat(maxCols) { bodyXml.append("<table:table-column/>\n") }
                    for (row in block.rows) {
                        bodyXml.append("<table:table-row>\n")
                        for (cell in row) {
                            bodyXml.append("<table:table-cell office:value-type=\"string\">\n")
                            if (cell.isEmpty()) {
                                bodyXml.append("<text:p/>\n")
                            } else {
                                cell.forEach { appendParagraph(it) }
                            }
                            bodyXml.append("</table:table-cell>\n")
                        }
                        bodyXml.append("</table:table-row>\n")
                    }
                    bodyXml.append("</table:table>\n")
                }
            }
        }
        closeListIfOpen()

        val fontFaceDecls = fontNames.joinToString("\n") { fn ->
            "<style:font-face style:name=\"${esc(fn)}\" svg:font-family=\"'${esc(fn)}'\"/>"
        }

        val contentXml = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-content
    xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"
    xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
    xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0"
    xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"
    xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0"
    office:version="1.2">
  <office:font-face-decls>
    $fontFaceDecls
  </office:font-face-decls>
  <office:automatic-styles>
    <style:style style:name="PLeft" style:family="paragraph"><style:paragraph-properties fo:text-align="start"/></style:style>
    <style:style style:name="PCenter" style:family="paragraph"><style:paragraph-properties fo:text-align="center"/></style:style>
    <style:style style:name="PRight" style:family="paragraph"><style:paragraph-properties fo:text-align="end"/></style:style>
    <style:style style:name="PJustify" style:family="paragraph"><style:paragraph-properties fo:text-align="justify"/></style:style>
    <style:style style:name="H1" style:family="paragraph"><style:text-properties fo:font-size="18pt" fo:font-weight="bold"/></style:style>
    <style:style style:name="H2" style:family="paragraph"><style:text-properties fo:font-size="15pt" fo:font-weight="bold"/></style:style>
    <style:style style:name="H3" style:family="paragraph"><style:text-properties fo:font-size="13pt" fo:font-weight="bold"/></style:style>
    $textStyleDefs
  </office:automatic-styles>
  <office:body>
    <office:text>
      $bodyXml
    </office:text>
  </office:body>
</office:document-content>
"""

        val stylesXml = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-styles
    xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"
    xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
    xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"
    office:version="1.2">
  <office:styles>
    <style:default-style style:family="paragraph">
      <style:text-properties fo:font-size="12pt"/>
    </style:default-style>
    <text:list-style style:name="BulletList">
      <text:list-level-style-bullet text:level="1" text:bullet-char="•"/>
    </text:list-style>
    <text:list-style style:name="OrderedList">
      <text:list-level-style-number text:level="1" style:num-suffix="." style:num-format="1"/>
    </text:list-style>
  </office:styles>
</office:document-styles>
"""

        val manifestXml = """<?xml version="1.0" encoding="UTF-8"?>
<manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" manifest:version="1.2">
  <manifest:file-entry manifest:full-path="/" manifest:media-type="application/vnd.oasis.opendocument.text"/>
  <manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/>
  <manifest:file-entry manifest:full-path="styles.xml" manifest:media-type="text/xml"/>
</manifest:manifest>
"""

        ZipOutputStream(outputStream).use { zos ->
            // ODF spec mandates uncompressed `mimetype` as the very first entry
            val mimeBytes = "application/vnd.oasis.opendocument.text".toByteArray(Charsets.US_ASCII)
            val mimeEntry = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mimeBytes.size.toLong()
                compressedSize = mimeBytes.size.toLong()
                crc = CRC32().apply { update(mimeBytes) }.value
            }
            zos.putNextEntry(mimeEntry)
            zos.write(mimeBytes)
            zos.closeEntry()

            putUtf8Entry(zos, "META-INF/manifest.xml", manifestXml)
            putUtf8Entry(zos, "styles.xml", stylesXml)
            putUtf8Entry(zos, "content.xml", contentXml)
        }
    }

    private fun putUtf8Entry(zos: ZipOutputStream, name: String, content: String) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(content.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    private fun esc(s: String): String = XmlSanitizer.escapeXml(s)
}
