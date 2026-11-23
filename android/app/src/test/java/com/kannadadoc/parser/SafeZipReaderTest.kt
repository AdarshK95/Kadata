package com.kannadadoc.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SafeZipReaderTest {

    @Test
    fun normalizesBackslashesLeadingSlashesAndCase() {
        assertEquals("word/document.xml", SafeZipReader.normalizeEntryPath("""word\document.xml"""))
        assertEquals("word/document.xml", SafeZipReader.normalizeEntryPath("/Word/Document.XML"))
        assertEquals("meta-inf/container.xml", SafeZipReader.normalizeEntryPath("""\META-INF\..\META-INF\container.xml"""))
    }

    @Test
    fun readsNonStandardZipEntriesSafely() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("""/Word\Document.XML"""))
            zos.write("<w:document/>".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        val entries = SafeZipReader.readMatchingEntries(
            ByteArrayInputStream(baos.toByteArray()),
            fileName = "sample.docx",
            formatLabel = "DOCX"
        ) { it == "word/document.xml" }
        assertTrue(entries.containsKey("word/document.xml"))
        assertEquals("<w:document/>", String(entries["word/document.xml"]!!, Charsets.UTF_8))
    }

    @Test
    fun rejectsEntryExceedingMaxEntryByteLimitWithClearException() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("huge.xml"))
            zos.write(ByteArray(4096) { 'A'.code.toByte() })
            zos.closeEntry()
        }
        try {
            SafeZipReader.readMatchingEntries(
                ByteArrayInputStream(baos.toByteArray()),
                maxEntryBytes = 512,
                fileName = "huge.docx",
                formatLabel = "DOCX"
            ) { it == "huge.xml" }
            fail("Expected DocumentSizeLimitException for oversized ZIP entry")
        } catch (ex: DocumentSizeLimitException) {
            assertEquals("huge.docx", ex.fileName)
            assertEquals("DOCX", ex.formatLabel)
            assertEquals(512L, ex.maxAllowedBytes)
            assertTrue(ex.userMessageEn.contains("huge.docx"))
            assertTrue(ex.userMessageKn.contains("huge.docx"))
        }
    }

    @Test
    fun routerClassifiesBackslashDocxOdtEpubAndRejectsPptx() {
        val router = DocumentRouter()

        fun buildZip(vararg items: Pair<String, String>): ByteArray {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zos ->
                for ((name, body) in items) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(body.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
            }
            return out.toByteArray()
        }

        val backslashDocx = buildZip("""word\document.xml""" to "<w:document/>")
        assertEquals(DocFormat.DOCX, router.classifyZipContainer(ByteArrayInputStream(backslashDocx)))

        val odt = buildZip("mimetype" to "application/vnd.oasis.opendocument.text", "content.xml" to "<office:document-content/>")
        assertEquals(DocFormat.ODT, router.classifyZipContainer(ByteArrayInputStream(odt)))

        val epub = buildZip("mimetype" to "application/epub+zip", "META-INF/container.xml" to "<container/>")
        assertEquals(DocFormat.EPUB, router.classifyZipContainer(ByteArrayInputStream(epub)))

        val zippedHtml = buildZip("notes/index.html" to "<html><body>ಕನ್ನಡ</body></html>")
        assertEquals(DocFormat.HTML, router.classifyZipContainer(ByteArrayInputStream(zippedHtml)))

        val pptx = buildZip("ppt/presentation.xml" to "<p:presentation/>")
        assertEquals(DocFormat.UNSUPPORTED, router.classifyZipContainer(ByteArrayInputStream(pptx)))
    }
}
