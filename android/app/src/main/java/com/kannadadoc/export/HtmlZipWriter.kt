package com.kannadadoc.export

import com.kannadadoc.parser.DocBlock
import com.kannadadoc.parser.ParsedDocument
import com.kannadadoc.parser.TextRun
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class HtmlZipWriter {

    fun writeZippedWebPage(doc: ParsedDocument, outputStream: OutputStream) {
        val title = doc.fileName.substringBeforeLast('.').ifBlank { "Document" }
        val safeHtmlName = title.replace(Regex("[^a-zA-Z0-9._\\-\\u0C80-\\u0CFF ]"), "_").ifBlank { "document" } + ".html"
        val htmlContent = renderStandaloneHtml(doc, title)

        ZipOutputStream(outputStream).use { zos ->
            zos.putNextEntry(ZipEntry(safeHtmlName))
            zos.write(htmlContent.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
    }

    fun renderBodyHtml(doc: ParsedDocument, xhtmlMode: Boolean = false): String {
        val sb = StringBuilder()
        var activeList: String? = null

        fun closeList() {
            activeList?.let { sb.append("</$it>\n") }
            activeList = null
        }

        fun renderRuns(runs: List<TextRun>): String {
            if (runs.isEmpty()) return if (xhtmlMode) "&#160;" else "&nbsp;"
            val rsb = StringBuilder()
            for (run in runs) {
                if (run.text.isEmpty()) continue
                val styles = mutableListOf<String>()
                if (run.bold) styles.add("font-weight:700")
                if (run.italic) styles.add("font-style:italic")
                if (run.underline) styles.add("text-decoration:underline")
                if (!run.colorHex.isNullOrBlank()) styles.add("color:${esc(run.colorHex)}")
                val escapedText = esc(run.text).replace("\n", if (xhtmlMode) "<br/>" else "<br>")
                if (styles.isNotEmpty()) {
                    rsb.append("<span style=\"${styles.joinToString(";")}\">$escapedText</span>")
                } else {
                    rsb.append(escapedText)
                }
            }
            return rsb.toString()
        }

        fun renderParagraphTag(p: DocBlock.Paragraph): String {
            val tag = when (p.headingLevel) {
                1 -> "h1"
                2 -> "h2"
                3 -> "h3"
                else -> "p"
            }
            val alignAttr = if (p.alignment != "left") " style=\"text-align:${esc(p.alignment)}\"" else ""
            return "<$tag$alignAttr>${renderRuns(p.runs)}</$tag>"
        }

        for (block in doc.blocks) {
            when (block) {
                is DocBlock.Paragraph -> {
                    val listTag = when (block.listType) {
                        "ordered" -> "ol"
                        "bullet" -> "ul"
                        else -> null
                    }
                    if (listTag != null) {
                        if (activeList != listTag) {
                            closeList()
                            sb.append("<$listTag>\n")
                            activeList = listTag
                        }
                        sb.append("  <li>${renderRuns(block.runs)}</li>\n")
                    } else {
                        closeList()
                        sb.append(renderParagraphTag(block)).append('\n')
                    }
                }
                is DocBlock.Table -> {
                    closeList()
                    sb.append("<table><tbody>\n")
                    for (row in block.rows) {
                        sb.append("  <tr>")
                        for (cell in row) {
                            sb.append("<td>")
                            cell.forEach { sb.append(renderParagraphTag(it)) }
                            sb.append("</td>")
                        }
                        sb.append("</tr>\n")
                    }
                    sb.append("</tbody></table>\n")
                }
            }
        }
        closeList()
        return sb.toString()
    }

    private fun renderStandaloneHtml(doc: ParsedDocument, title: String): String = """<!DOCTYPE html>
<html lang="kn">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>${esc(title)}</title>
  <style>
    body {
      font-family: 'Noto Sans Kannada', 'Tunga', system-ui, -apple-system, sans-serif;
      max-width: 820px;
      margin: 28px auto;
      padding: 0 20px;
      line-height: 1.75;
      color: #1d242b;
      background: #fffdf8;
      font-size: 16pt;
    }
    h1 { color: #133c61; }
    h2 { color: #1a4d7a; }
    table { width: 100%; border-collapse: collapse; margin: 16px 0; }
    td, th { border: 1px solid #c9bea6; padding: 8px 10px; vertical-align: top; }
  </style>
</head>
<body>
${renderBodyHtml(doc, xhtmlMode = false)}
</body>
</html>
"""

    private fun esc(s: String): String = XmlSanitizer.escapeHtml(s)
}
