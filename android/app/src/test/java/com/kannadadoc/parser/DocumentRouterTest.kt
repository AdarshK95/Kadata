package com.kannadadoc.parser

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocumentRouterTest {

    private val router = DocumentRouter()

    @Test
    fun detectsDocxFromZipMagicAndWordEntry() {
        val docxBytes = buildZipWithEntry("word/document.xml", "<w:document/>")
        val format = router.detectFromStream(
            ByteArrayInputStream(docxBytes),
            fileName = "renamed_file.doc"
        )
        assertEquals(DocFormat.DOCX, format)
    }

    @Test
    fun rejectsNonDocxZipArchive() {
        val xlsxBytes = buildZipWithEntry("xl/workbook.xml", "<workbook/>")
        val format = router.detectFromStream(ByteArrayInputStream(xlsxBytes))
        assertEquals(DocFormat.UNSUPPORTED, format)
    }

    @Test
    fun detectsLegacyDocFromOle2Magic() {
        val ole2Header = byteArrayOf(
            0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
            0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()
        )
        assertEquals(DocFormat.DOC, router.detectFromBytes(ole2Header))
    }

    @Test
    fun detectsPdfFromMagicBytes() {
        val pdfHeader = "%PDF-1.7\n...".toByteArray(Charsets.US_ASCII)
        assertEquals(DocFormat.PDF, router.detectFromBytes(pdfHeader))
    }

    @Test
    fun detectsRtfEvenWhenNamedDotDoc() {
        val rtfHeader = "{\\rtf1\\ansi{\\fonttbl\\f0 Nudi 01 e;}}".toByteArray(Charsets.US_ASCII)
        assertEquals(DocFormat.RTF, router.detectFromBytes(rtfHeader, fileName = "circular.doc"))
    }

    @Test
    fun detectsCp1252NudiPlainText() {
        val nudiBytes = byteArrayOf(
            0x50, 0xC0.toByte(), 0xA3.toByte(), 0xC0.toByte(),
            0xDF.toByte(), 0x71, 0xC0.toByte(), 0x0A
        )
        assertEquals(DocFormat.TXT, router.detectFromBytes(nudiBytes, fileName = "notes.txt"))
    }

    @Test
    fun rejectsBinaryPngFile() {
        val pngHeader = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00
        )
        assertEquals(DocFormat.UNSUPPORTED, router.detectFromBytes(pngHeader))
    }

    private fun buildZipWithEntry(entryName: String, content: String): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry(entryName))
            zos.write(content.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return baos.toByteArray()
    }
}
