package com.kannadadoc.parser

import com.kannadadoc.export.OdtWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ParserHardeningTest {

    @Test
    fun boundedStreamReaderRejectsOversizedStreamAndAllowsExactBoundary() {
        val exactPayload = ByteArray(1024) { 'A'.code.toByte() }
        val ok = BoundedStreamReader.readStrict(
            inputStream = ByteArrayInputStream(exactPayload),
            maxBytes = 1024,
            fileName = "exact.txt",
            formatLabel = "TXT"
        )
        assertEquals(1024, ok.size)

        val oversized = ByteArray(1025) { 'A'.code.toByte() }
        try {
            BoundedStreamReader.readStrict(
                inputStream = ByteArrayInputStream(oversized),
                maxBytes = 1024,
                fileName = "big.txt",
                formatLabel = "TXT"
            )
            fail("Expected DocumentSizeLimitException when stream exceeds maxBytes by 1 byte")
        } catch (ex: DocumentSizeLimitException) {
            assertEquals("big.txt", ex.fileName)
            assertEquals("TXT", ex.formatLabel)
            assertTrue(ex.userMessageEn.contains("big.txt"))
            assertTrue(ex.userMessageKn.contains("big.txt"))
        }
    }

    @Test
    fun peekHeaderPreservesEntireUnderlyingStream() {
        val data = "Hello Kadata ಕನ್ನಡ".toByteArray(Charsets.UTF_8)
        val bis = BufferedInputStream(ByteArrayInputStream(data))
        val head = BoundedStreamReader.peekHeader(bis, 5)
        assertEquals("Hello", String(head, Charsets.UTF_8))
        val full = BoundedStreamReader.readStrict(bis, 1024, "test.txt", "TXT")
        assertEquals("Hello Kadata ಕನ್ನಡ", String(full, Charsets.UTF_8))
    }

    @Test
    fun htmlParserRejectsOversizedRawHtmlInsteadOfSilentlyTruncating() {
        val parser = HtmlParser()
        val hugeHtml = "<html><head><style>body{color:red;}</style></head><body><!-- comment -->" +
            "<p>ಕನ್ನಡ ಪಠ್ಯ</p>".repeat(500) + "</body></html>"
        try {
            parser.parse(
                inputStream = ByteArrayInputStream(hugeHtml.toByteArray(Charsets.UTF_8)),
                sourceUri = "test://huge.html",
                fileName = "huge.html",
                maxHtmlBytes = 512
            )
            fail("Expected DocumentSizeLimitException for oversized raw HTML")
        } catch (ex: DocumentSizeLimitException) {
            assertEquals("huge.html", ex.fileName)
            assertEquals("HTML", ex.formatLabel)
            assertEquals(512L, ex.maxAllowedBytes)
        }
    }

    @Test
    fun htmlParserStreamsZippedWebPageWithoutBufferingEmbeddedImages() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            // Large 2 MB embedded PNG inside a Google Docs zipped web page is skipped completely
            zos.putNextEntry(ZipEntry("images/photo.png"))
            zos.write(ByteArray(2 * 1024 * 1024) { 0x7F })
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("index.html"))
            zos.write("<html><body><h1>ಕಡತ</h1><p>ನಮಸ್ಕಾರ</p></body></html>".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        val parser = HtmlParser()
        val doc = parser.parse(
            inputStream = ByteArrayInputStream(baos.toByteArray()),
            sourceUri = "test://web.zip",
            fileName = "web.zip",
            maxHtmlBytes = 4096
        )
        val allText = doc.blocks.filterIsInstance<DocBlock.Paragraph>()
            .flatMap { it.runs }
            .joinToString(" ") { it.text }
        assertTrue(allText.contains("ಕಡತ"))
        assertTrue(allText.contains("ನಮಸ್ಕಾರ"))
    }

    @Test
    fun epubResolveOpfHrefNormalizesCaseBackslashPercentEncodingAndFragments() {
        assertEquals(
            "oebps/chapters/chapter 1.xhtml",
            EpubParser.resolveOpfHref("OEBPS", """Chapters\Chapter%201.xhtml#section-2""")
        )
        assertEquals(
            "text/intro.html",
            EpubParser.resolveOpfHref("OEBPS/Sub", "../../Text/Intro.HTML?v=1")
        )
    }

    @Test
    fun odtWriterEmitsUniqueTableNamesAndDistinctListStyles() {
        val tableBlock = DocBlock.Table(
            rows = listOf(listOf(listOf(DocBlock.Paragraph(runs = listOf(TextRun("A"))))))
        )
        val doc = ParsedDocument(
            sourceUri = "test://multi.odt",
            fileName = "multi.odt",
            sourceFormat = DocFormat.ODT,
            blocks = listOf(
                DocBlock.Paragraph(runs = listOf(TextRun("Item 1")), listType = "ordered"),
                tableBlock,
                DocBlock.Paragraph(runs = listOf(TextRun("Bullet 1")), listType = "bullet"),
                tableBlock
            )
        )
        val out = ByteArrayOutputStream()
        OdtWriter().write(doc, out)

        var contentXml = ""
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == "content.xml") {
                    contentXml = String(zis.readBytes(), Charsets.UTF_8)
                }
                entry = zis.nextEntry
            }
        }
        assertTrue(contentXml.contains("table:name=\"Table1\""))
        assertTrue(contentXml.contains("table:name=\"Table2\""))
        assertTrue(contentXml.contains("text:style-name=\"OrderedList\""))
        assertTrue(contentXml.contains("text:style-name=\"BulletList\""))
    }
}
