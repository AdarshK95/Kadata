package com.kannadadoc

import com.kannadadoc.export.PdfPaginationPolicy
import com.kannadadoc.parser.DocBlock
import com.kannadadoc.parser.DocxParser
import com.kannadadoc.parser.HtmlParser
import com.kannadadoc.parser.OdtParser
import com.kannadadoc.parser.TableFlattenHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.Reader
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

class PdfAndTableHardeningTest {

    // =========================================================================
    // 1. PDF Multi-Page Paragraph & Tall Table Row Pagination
    // =========================================================================

    @Test
    fun pdfPaginationSlicesOversizedParagraphAcrossMultiplePagesWithoutClipping() {
        // Simulate a 120-line Kannada paragraph (20pt per line = 2400pt total) on A4 printable height (734pt)
        val lineCount = 120
        val lineH = 20
        val tops = IntArray(lineCount) { it * lineH }
        val bottoms = IntArray(lineCount) { (it + 1) * lineH }

        val marginTop = 54f
        val maxPageY = 788f // 842 - 54 -> 734pt printable height -> 36 lines per full page
        val startCursorY = 400f // Starting mid-page on page 1

        val (slices, finalCursorY) = PdfPaginationPolicy.paginateSingleLayout(
            lineTops = tops,
            lineBottoms = bottoms,
            startCursorY = startCursorY,
            marginTop = marginTop,
            maxPageY = maxPageY,
            keepWholeMaxLines = 3
        )

        assertTrue("120-line paragraph must span at least 4 pages", slices.size >= 4)

        // Verify every line 0..119 is emitted contiguously with zero skips and zero duplicates
        var expectedNextLine = 0
        var pageCursor = startCursorY
        for (slice in slices) {
            if (slice.pageBreakBefore) pageCursor = marginTop
            assertEquals(expectedNextLine, slice.startLine)
            assertTrue(slice.endLine >= slice.startLine)
            assertTrue(
                "Slice must never overflow maxPageY (cursor=$pageCursor, h=${slice.sliceHeight}, max=$maxPageY)",
                pageCursor + slice.sliceHeight <= maxPageY + 0.01f
            )
            pageCursor += slice.sliceHeight
            expectedNextLine = slice.endLine + 1
        }
        assertEquals(lineCount, expectedNextLine)
        assertEquals(pageCursor, finalCursorY, 0.01f)
    }

    @Test
    fun pdfPaginationKeepsShortHeadingTogetherAndTerminatesOnUltraTallSingleLine() {
        // Short 2-line heading near bottom of page moves cleanly to next page
        val (headingSlices, _) = PdfPaginationPolicy.paginateSingleLayout(
            lineTops = intArrayOf(0, 24),
            lineBottoms = intArrayOf(24, 48),
            startCursorY = 760f,
            marginTop = 54f,
            maxPageY = 788f,
            keepWholeMaxLines = Int.MAX_VALUE
        )
        assertEquals(1, headingSlices.size)
        assertTrue(headingSlices[0].pageBreakBefore)
        assertEquals(0, headingSlices[0].startLine)
        assertEquals(1, headingSlices[0].endLine)

        // Pathological single line taller than full page height must still terminate cleanly
        val (hugeSlices, _) = PdfPaginationPolicy.paginateSingleLayout(
            lineTops = intArrayOf(0),
            lineBottoms = intArrayOf(1200),
            startCursorY = 54f,
            marginTop = 54f,
            maxPageY = 788f,
            keepWholeMaxLines = 3
        )
        assertEquals(1, hugeSlices.size)
        assertEquals(0, hugeSlices[0].startLine)
        assertEquals(0, hugeSlices[0].endLine)
    }

    @Test
    fun pdfPaginationSlicesTallMultiColumnTableRowAcrossPagesWithUnequalColumns() {
        // Col 0: 90 lines, Col 1: 5 lines, Col 2: 45 lines (18pt/line)
        fun mkTops(n: Int) = IntArray(n) { it * 18 }
        fun mkBottoms(n: Int) = IntArray(n) { (it + 1) * 18 }

        val colCounts = intArrayOf(90, 5, 45)
        val marginTop = 54f
        val maxPageY = 788f
        val cellPad = 6f

        val (slices, _) = PdfPaginationPolicy.paginateTableRow(
            colLineTops = colCounts.map { mkTops(it) },
            colLineBottoms = colCounts.map { mkBottoms(it) },
            cellPad = cellPad,
            startCursorY = 500f,
            marginTop = marginTop,
            maxPageY = maxPageY
        )

        assertTrue("90-line table row must span at least 3 pages", slices.size >= 3)

        val covered = IntArray(3) { 0 }
        var pageCursor = 500f
        for (slice in slices) {
            if (slice.pageBreakBefore) pageCursor = marginTop
            assertTrue(
                "Table row slice must fit within printable page height",
                pageCursor + slice.sliceRowHeight <= maxPageY + 0.01f
            )
            for (c in 0..2) {
                val s = slice.colStartLines[c]
                val e = slice.colEndLines[c]
                if (s <= e) {
                    assertEquals("Column $c must advance contiguously", covered[c], s)
                    covered[c] = e + 1
                }
            }
            pageCursor += slice.sliceRowHeight
        }
        assertEquals(90, covered[0])
        assertEquals(5, covered[1])
        assertEquals(45, covered[2])
    }

    // =========================================================================
    // 2. HTML Parser Stack-Based Nested Tables & Unclosed HTML5 Cells
    // =========================================================================

    @Test
    fun htmlParserPreservesOuterTableRowsAroundNestedTableAndFlattensInnerDataLosslessly() {
        val html = """
            <html><body>
              <table>
                <tr>
                  <td>Outer-R1-C1</td>
                  <td>
                    Outer-R1-C2-Before
                    <table>
                      <tr><td>Inner-Key</td><td>Inner-Value</td></tr>
                      <tr><td>ಕನ್ನಡ</td><td>ಸಾಹಿತ್ಯ</td></tr>
                    </table>
                    Outer-R1-C2-After
                  </td>
                </tr>
                <tr>
                  <td>Outer-R2-C1</td>
                  <td>Outer-R2-C2</td>
                </tr>
              </table>
            </body></html>
        """.trimIndent()

        val blocks = HtmlParser().parseHtmlToBlocks(html)
        val tables = blocks.filterIsInstance<DocBlock.Table>()
        assertEquals("Nested table must stay inside outer table (only 1 top-level Table)", 1, tables.size)

        val table = tables.first()
        assertEquals("Outer table must keep both Row 1 and Row 2", 2, table.rows.size)
        assertEquals(2, table.rows[0].size)
        assertEquals(2, table.rows[1].size)

        val r1c1 = table.rows[0][0].joinToString("\n") { p -> p.runs.joinToString("") { it.text } }
        val r1c2 = table.rows[0][1].joinToString("\n") { p -> p.runs.joinToString("") { it.text } }
        val r2c1 = table.rows[1][0].joinToString("\n") { p -> p.runs.joinToString("") { it.text } }
        val r2c2 = table.rows[1][1].joinToString("\n") { p -> p.runs.joinToString("") { it.text } }

        assertTrue(r1c1.contains("Outer-R1-C1"))
        assertTrue(r1c2.contains("Outer-R1-C2-Before"))
        assertTrue(r1c2.contains("Inner-Key  |  Inner-Value"))
        assertTrue(r1c2.contains("ಕನ್ನಡ  |  ಸಾಹಿತ್ಯ"))
        assertTrue(r1c2.contains("Outer-R1-C2-After"))
        assertTrue(r2c1.contains("Outer-R2-C1"))
        assertTrue(r2c2.contains("Outer-R2-C2"))
    }

    @Test
    fun htmlParserHandlesImplicitlyClosedHtml5CellsAndRows() {
        val html = "<table><tr><td>CellA<td>CellB<tr><td>CellC<td>CellD</table>"
        val blocks = HtmlParser().parseHtmlToBlocks(html)
        val table = blocks.filterIsInstance<DocBlock.Table>().single()
        assertEquals(2, table.rows.size)
        assertEquals(2, table.rows[0].size)
        assertEquals(2, table.rows[1].size)
        assertEquals("CellD", table.rows[1][1].first().runs.joinToString("") { it.text })
    }

    // =========================================================================
    // 3. DOCX Parser Nested Tables, w:sdt Content Controls & w:ins Tracked Changes
    // =========================================================================

    @Test
    fun docxParserExtractsNestedTablesSdtContentControlsAndTrackedInsertionsWithoutDrops() {
        val docXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:sdt>
                  <w:sdtContent>
                    <w:p><w:r><w:t>Body SDT Heading</w:t></w:r></w:p>
                  </w:sdtContent>
                </w:sdt>
                <w:p>
                  <w:r><w:t>Keep </w:t></w:r>
                  <w:ins><w:r><w:t>Inserted</w:t></w:r></w:ins>
                  <w:del><w:r><w:delText>DeletedNoise</w:delText></w:r></w:del>
                </w:p>
                <w:tbl>
                  <w:tr>
                    <w:tc>
                      <w:p><w:r><w:t>Outer Cell</w:t></w:r></w:p>
                      <w:tbl>
                        <w:tr>
                          <w:tc><w:p><w:r><w:t>NestedA</w:t></w:r></w:p></w:tc>
                          <w:tc><w:p><w:r><w:t>NestedB</w:t></w:r></w:p></w:tc>
                        </w:tr>
                      </w:tbl>
                      <w:sdt>
                        <w:sdtContent>
                          <w:p><w:r><w:t>Cell SDT Text</w:t></w:r></w:p>
                        </w:sdtContent>
                      </w:sdt>
                    </w:tc>
                    <w:tc>
                      <w:p><w:r><w:t>Sibling Cell</w:t></w:r></w:p>
                    </w:tc>
                  </w:tr>
                </w:tbl>
              </w:body>
            </w:document>
        """.trimIndent()

        val docxBytes = buildZipBytes(mapOf("word/document.xml" to docXml))
        val parsed = DocxParser().parse(ByteArrayInputStream(docxBytes), "content://test/nested.docx", "nested.docx")

        val paras = parsed.blocks.filterIsInstance<DocBlock.Paragraph>()
        val allTopText = paras.joinToString("\n") { p -> p.runs.joinToString("") { it.text } }
        assertTrue("Body SDT content must be extracted", allTopText.contains("Body SDT Heading"))
        assertTrue("Tracked insertion must be extracted", allTopText.contains("Keep Inserted"))
        assertFalse("Tracked deletion must be excluded", allTopText.contains("DeletedNoise"))

        val table = parsed.blocks.filterIsInstance<DocBlock.Table>().single()
        assertEquals(1, table.rows.size)
        assertEquals(2, table.rows[0].size)

        val cell0Text = table.rows[0][0].joinToString("\n") { p -> p.runs.joinToString("") { it.text } }
        assertTrue(cell0Text.contains("Outer Cell"))
        assertTrue(cell0Text.contains("NestedA  |  NestedB"))
        assertTrue(cell0Text.contains("Cell SDT Text"))
        assertEquals("Sibling Cell", table.rows[0][1].first().runs.joinToString("") { it.text })
    }

    // =========================================================================
    // 4. ODT Parser Stack-Based Nested Table State Preservation
    // =========================================================================

    @Test
    fun odtParserPreservesOuterTableRowsWhenNestedTableAppearsInsideCell() {
        val contentXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <office:document-content
                xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
                xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0">
              <office:body>
                <office:text>
                  <table:table table:name="Outer">
                    <table:table-row>
                      <table:table-cell><text:p>Outer-R1-C1</text:p></table:table-cell>
                      <table:table-cell>
                        <text:p>Outer-R1-C2-Top</text:p>
                        <table:table table:name="Inner">
                          <table:table-row>
                            <table:table-cell><text:p>Sub1</text:p></table:table-cell>
                            <table:table-cell><text:p>Sub2</text:p></table:table-cell>
                          </table:table-row>
                        </table:table>
                        <text:p>Outer-R1-C2-Bottom</text:p>
                      </table:table-cell>
                    </table:table-row>
                    <table:table-row>
                      <table:table-cell><text:p>Outer-R2-C1</text:p></table:table-cell>
                      <table:table-cell><text:p>Outer-R2-C2</text:p></table:table-cell>
                    </table:table-row>
                  </table:table>
                </office:text>
              </office:body>
            </office:document-content>
        """.trimIndent()

        val odtBytes = buildZipBytes(mapOf("content.xml" to contentXml))
        val odtParser = OdtParser { DomBackedXmlPullParser() }
        val parsed = odtParser.parse(ByteArrayInputStream(odtBytes), "content://test/nested.odt", "nested.odt")

        val table = parsed.blocks.filterIsInstance<DocBlock.Table>().single()
        assertEquals("Outer ODT table must keep both rows intact", 2, table.rows.size)
        assertEquals(2, table.rows[0].size)
        assertEquals(2, table.rows[1].size)

        val r1c1 = table.rows[0][0].joinToString("\n") { p -> p.runs.joinToString("") { it.text } }
        val r1c2 = table.rows[0][1].joinToString("\n") { p -> p.runs.joinToString("") { it.text } }
        val r2c1 = table.rows[1][0].joinToString("\n") { p -> p.runs.joinToString("") { it.text } }
        val r2c2 = table.rows[1][1].joinToString("\n") { p -> p.runs.joinToString("") { it.text } }

        assertEquals("Outer-R1-C1", r1c1)
        assertTrue(r1c2.contains("Outer-R1-C2-Top"))
        assertTrue(r1c2.contains("Sub1  |  Sub2"))
        assertTrue(r1c2.contains("Outer-R1-C2-Bottom"))
        assertEquals("Outer-R2-C1", r2c1)
        assertEquals("Outer-R2-C2", r2c2)
    }

    @Test
    fun tableFlattenHelperSkipsCompletelyEmptyNestedTables() {
        val empty = TableFlattenHelper.flattenNestedTableToParagraphs(
            listOf(listOf(listOf(DocBlock.Paragraph(emptyList()))))
        )
        assertTrue(empty.isEmpty())
    }

    private fun buildZipBytes(entries: Map<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    /**
     * Minimal JDK-DOM-backed XmlPullParser so OdtParser runs verbatim on plain JVM unit tests
     * without requiring Robolectric or external XML pull dependencies.
     */
    private class DomBackedXmlPullParser : XmlPullParser {
        private data class Ev(
            val type: Int,
            val name: String? = null,
            val text: String? = null,
            val attrs: List<Pair<String, String>> = emptyList()
        )

        private var events: List<Ev> = listOf(Ev(XmlPullParser.START_DOCUMENT))
        private var idx = 0

        override fun setInput(inputStream: InputStream, inputEncoding: String?) {
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val doc = factory.newDocumentBuilder().parse(inputStream)
            val list = mutableListOf(Ev(XmlPullParser.START_DOCUMENT))
            fun walk(node: Node) {
                when (node.nodeType) {
                    Node.ELEMENT_NODE -> {
                        val el = node as Element
                        val attrs = mutableListOf<Pair<String, String>>()
                        val map = el.attributes
                        for (i in 0 until map.length) {
                            val a = map.item(i)
                            val local = a.localName ?: a.nodeName.substringAfter(':')
                            if (local != "xmlns" && !a.nodeName.startsWith("xmlns:")) {
                                attrs.add(local to a.nodeValue.orEmpty())
                            }
                        }
                        val localName = el.localName ?: el.nodeName.substringAfter(':')
                        list.add(Ev(XmlPullParser.START_TAG, name = localName, attrs = attrs))
                        var ch = el.firstChild
                        while (ch != null) {
                            walk(ch)
                            ch = ch.nextSibling
                        }
                        list.add(Ev(XmlPullParser.END_TAG, name = localName))
                    }
                    Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> {
                        val t = node.nodeValue.orEmpty()
                        if (t.isNotEmpty()) list.add(Ev(XmlPullParser.TEXT, text = t))
                    }
                }
            }
            doc.documentElement?.let { walk(it) }
            list.add(Ev(XmlPullParser.END_DOCUMENT))
            events = list
            idx = 0
        }

        override fun getEventType(): Int = events[idx].type
        override fun next(): Int {
            if (idx + 1 < events.size) idx++
            return events[idx].type
        }
        override fun getName(): String? = events[idx].name
        override fun getText(): String? = events[idx].text
        override fun getAttributeCount(): Int = events[idx].attrs.size
        override fun getAttributeName(index: Int): String = events[idx].attrs[index].first
        override fun getAttributeValue(index: Int): String = events[idx].attrs[index].second

        override fun setFeature(name: String?, state: Boolean) = Unit
        override fun getFeature(name: String?): Boolean = false
        override fun setProperty(name: String?, value: Any?) = Unit
        override fun getProperty(name: String?): Any? = null
        override fun setInput(`in`: Reader?) = throw UnsupportedOperationException()
        override fun getInputEncoding(): String? = "UTF-8"
        override fun defineEntityReplacementText(entityName: String?, replacementText: String?) = Unit
        override fun getNamespaceCount(depth: Int): Int = 0
        override fun getNamespacePrefix(pos: Int): String? = null
        override fun getNamespaceUri(pos: Int): String? = null
        override fun getNamespace(prefix: String?): String? = null
        override fun getDepth(): Int = 0
        override fun getPositionDescription(): String = ""
        override fun getLineNumber(): Int = -1
        override fun getColumnNumber(): Int = -1
        override fun isWhitespace(): Boolean = false
        override fun getTextCharacters(holderForStartAndLength: IntArray?): CharArray = CharArray(0)
        override fun getNamespace(): String = ""
        override fun getPrefix(): String? = null
        override fun isEmptyElementTag(): Boolean = false
        override fun getAttributeNamespace(index: Int): String = ""
        override fun getAttributePrefix(index: Int): String? = null
        override fun getAttributeType(index: Int): String = "CDATA"
        override fun isAttributeDefault(index: Int): Boolean = false
        override fun getAttributeValue(namespace: String?, name: String?): String? =
            events[idx].attrs.firstOrNull { it.first == name }?.second
        override fun nextToken(): Int = next()
        override fun require(type: Int, namespace: String?, name: String?) = Unit
        override fun nextText(): String = ""
        override fun nextTag(): Int = next()
    }
}
