package com.kannadadoc

import com.kannadadoc.converter.KannadaTextConverter
import com.kannadadoc.data.local.AppDatabase
import com.kannadadoc.export.DocxWriter
import com.kannadadoc.export.EpubWriter
import com.kannadadoc.export.HtmlZipWriter
import com.kannadadoc.export.OdtWriter
import com.kannadadoc.export.XmlSanitizer
import com.kannadadoc.parser.DocBlock
import com.kannadadoc.parser.DocFormat
import com.kannadadoc.parser.ParsedDocument
import com.kannadadoc.parser.RtfParser
import com.kannadadoc.parser.TextRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class BugfixRegressionTest {

    // -------------------------------------------------------------------------
    // Bug #2: RtfParser \uN\'3f hex fallback, \uc0, \uc2, and nested \uc groups
    // -------------------------------------------------------------------------
    @Test
    fun rtfParserSkipsWordHexFallbackWithoutLeakingQuestionMarks() {
        val rtf = """{\rtf1\ansi\deff0 \u3205\'3f\u3206\'3f \u3221\'3f\u3240\'3f\u3277\'3f\u3240\'3f\u3233\'3f}"""
        val doc = RtfParser().parse(
            ByteArrayInputStream(rtf.toByteArray(Charsets.ISO_8859_1)),
            "content://test/word.rtf",
            "word.rtf"
        )
        val text = (doc.blocks.first() as DocBlock.Paragraph).runs.joinToString("") { it.text }
        assertEquals("ಅಆ ಕನ್ನಡ", text)
        assertFalse("Must not leak fallback question marks", text.contains('?'))
    }

    @Test
    fun rtfParserHonorsUc0Uc2AndGroupScoping() {
        // \uc0 must NOT eat following real characters
        val rtfUc0 = """{\rtf1\ansi\uc0\u3205\u3206ABC}"""
        val doc0 = RtfParser().parse(
            ByteArrayInputStream(rtfUc0.toByteArray(Charsets.ISO_8859_1)),
            "content://test/uc0.rtf",
            "uc0.rtf"
        )
        assertEquals("ಅಆABC", (doc0.blocks.first() as DocBlock.Paragraph).runs.joinToString("") { it.text })

        // \uc2 skips two fallback bytes per \uN, and inner group restores outer \uc1
        val rtfNested = """{\rtf1\ansi {\uc2 \u3205\'a1\'b2}\u3206\'3f!}"""
        val docNested = RtfParser().parse(
            ByteArrayInputStream(rtfNested.toByteArray(Charsets.ISO_8859_1)),
            "content://test/nested.rtf",
            "nested.rtf"
        )
        assertEquals("ಅಆ!", (docNested.blocks.first() as DocBlock.Paragraph).runs.joinToString("") { it.text })
    }

    // -------------------------------------------------------------------------
    // Bug #3: XML 1.0 illegal control characters stripped across all writers
    // -------------------------------------------------------------------------
    @Test
    fun xmlSanitizerStripsProhibitedControlCharsAndPreservesValidTextAndEmoji() {
        val dirty = "ಕನ್ನಡ\u0000\u0007\u000B\u000C\u001F\uFFFE\tOK\n😀\uD83D"
        val clean = XmlSanitizer.stripInvalidXmlChars(dirty)
        assertEquals("ಕನ್ನಡ\tOK\n😀", clean)
        assertEquals("a &amp; b &lt; c &gt; &quot;d&quot; &apos;e&apos;", XmlSanitizer.escapeXml("a\u0000 & b < c > \"d\" 'e'\u000C"))
    }

    @Test
    fun docxOdtEpubAndHtmlWritersProduceStrictValidXmlWhenInputHasControlChars() {
        val dirtyText = "ವಚನ\u0000ಸಾಹಿತ್ಯ\u000Cಪುಟ\u000B\u0007 & <1>"
        val doc = ParsedDocument(
            sourceUri = "content://test/dirty.docx",
            fileName = "Dirty\u0000Title.docx",
            sourceFormat = DocFormat.DOCX,
            blocks = listOf(
                DocBlock.Paragraph(runs = listOf(TextRun(text = dirtyText, fontFamily = "Noto Sans Kannada")))
            )
        )

        // DOCX
        val docxBytes = ByteArrayOutputStream().also { DocxWriter().write(doc, it) }.toByteArray()
        assertZipXmlEntryParsesCleanly(docxBytes, "word/document.xml")

        // ODT
        val odtBytes = ByteArrayOutputStream().also { OdtWriter().write(doc, it) }.toByteArray()
        assertZipXmlEntryParsesCleanly(odtBytes, "content.xml")

        // EPUB (XHTML + OPF)
        val epubBytes = ByteArrayOutputStream().also { EpubWriter(HtmlZipWriter()).write(doc, it) }.toByteArray()
        assertZipXmlEntryParsesCleanly(epubBytes, "OEBPS/content.opf")
        assertZipXmlEntryParsesCleanly(epubBytes, "OEBPS/chapter1.xhtml")

        // HTML ZIP
        val htmlBytes = ByteArrayOutputStream().also { HtmlZipWriter().writeZippedWebPage(doc, it) }.toByteArray()
        val htmlStr = extractFirstZipEntryString(htmlBytes)
        assertFalse(htmlStr.contains('\u0000'))
        assertFalse(htmlStr.contains('\u000C'))
        assertTrue(htmlStr.contains("ವಚನಸಾಹಿತ್ಯಪುಟ"))
    }

    // -------------------------------------------------------------------------
    // Bug #4: Kannada numerals ೦-೯ in Nudi export (zero tofu, lossless round-trip)
    // -------------------------------------------------------------------------
    @Test
    fun kannadaDigitsAreNeverStyledWithEightBitNudiFontAndRoundTripCleanly() {
        val inputDoc = ParsedDocument(
            sourceUri = "content://test/digits.docx",
            fileName = "digits.docx",
            sourceFormat = DocFormat.DOCX,
            blocks = listOf(
                DocBlock.Paragraph(
                    runs = listOf(TextRun(text = "ದಿನಾಂಕ ೧೫ ಮತ್ತು Page ೨೦೨೬", fontFamily = "Noto Sans Kannada"))
                )
            )
        )

        val exported = KannadaTextConverter.prepareDocumentForLegacyExport(inputDoc, "Nudi 01 e")
        val runs = (exported.blocks.first() as DocBlock.Paragraph).runs

        // No legacy Nudi run may contain raw Kannada Unicode digits (\u0CE6..\u0CEF)
        for (run in runs) {
            if (run.fontFamily == "Nudi 01 e") {
                assertFalse(
                    "Nudi 01 e run must not contain unrenderable Unicode Kannada digits: ${run.text}",
                    run.text.any { it in '\u0CE6'..'\u0CEF' }
                )
            }
        }

        // Kannada digits must be preserved in a Windows-compatible Unicode Kannada font ("Tunga")
        val digitRuns = runs.filter { it.text.any { ch -> ch in '\u0CE6'..'\u0CEF' } }
        assertEquals(2, digitRuns.size)
        assertTrue(digitRuns.all { it.fontFamily == "Tunga" })
        assertEquals("೧೫", digitRuns[0].text)
        assertEquals("೨೦೨೬", digitRuns[1].text)

        // Round-trip back to Unicode Kannada must preserve original text identically
        val roundTripped = KannadaTextConverter.normalizeDocumentToUnicode(exported)
        val restored = (roundTripped.blocks.first() as DocBlock.Paragraph).runs.joinToString("") { it.text }
        assertEquals("ದಿನಾಂಕ ೧೫ ಮತ್ತು Page ೨೦೨೬", restored)

        // Direct plain-text convertUnicodeToNudi must also emit zero >0xFF characters for Kannada digits
        val plainNudi = KannadaTextConverter.convertUnicodeToNudi("ದಿನಾಂಕ ೧೫")
        assertFalse("Direct Nudi string must not contain Unicode Kannada digits", plainNudi.any { it.code > 0xFF })
        assertTrue(plainNudi.endsWith("15"))
    }

    // -------------------------------------------------------------------------
    // Bug #5: Room MIGRATION_1_2 registered
    // -------------------------------------------------------------------------
    @Test
    fun appDatabaseDefinesMigration1To2() {
        assertEquals(1, AppDatabase.MIGRATION_1_2.startVersion)
        assertEquals(2, AppDatabase.MIGRATION_1_2.endVersion)
    }

    private fun assertZipXmlEntryParsesCleanly(zipBytes: ByteArray, targetEntry: String) {
        var foundXml: ByteArray? = null
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.name == targetEntry) {
                    foundXml = zis.readBytes()
                    break
                }
            }
        }
        val bytes = requireNotNull(foundXml) { "Missing ZIP entry $targetEntry" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        }
        factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun extractFirstZipEntryString(zipBytes: ByteArray): String {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            zis.nextEntry ?: error("Empty ZIP")
            return zis.readBytes().toString(Charsets.UTF_8)
        }
    }
}
