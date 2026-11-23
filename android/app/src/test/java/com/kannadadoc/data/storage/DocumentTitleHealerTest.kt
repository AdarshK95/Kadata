package com.kannadadoc.data.storage

import com.kannadadoc.converter.KannadaTextConverter
import com.kannadadoc.parser.DocBlock
import com.kannadadoc.parser.DocFormat
import com.kannadadoc.parser.ParsedDocument
import com.kannadadoc.parser.TextRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentTitleHealerTest {

    @Test
    fun detectsAllWhatsAppAndGenericNameVariants() {
        assertTrue(DocumentTitleHealer.isGenericDisplayName("DOC-20261122-WA0000.docx"))
        assertTrue(DocumentTitleHealer.isGenericDisplayName("DOC-20261122-WA0002 (1).pdf"))
        assertTrue(DocumentTitleHealer.isGenericDisplayName("a1b2c3d4e5f6_DOC-20261122-WA0003_2.docx"))
        assertTrue(DocumentTitleHealer.isGenericDisplayName("WhatsApp Document 2026-11-22.pdf"))
        assertTrue(DocumentTitleHealer.isGenericDisplayName("msf:10482"))
        assertTrue(DocumentTitleHealer.isGenericDisplayName("Document1.docx"))
        assertTrue(DocumentTitleHealer.isGenericDisplayName("Untitled.docx"))
        assertTrue(DocumentTitleHealer.isGenericDisplayName("CamScanner 11-22-2026.pdf"))

        assertFalse(DocumentTitleHealer.isGenericDisplayName("PolicySchedule.pdf"))
        assertFalse(DocumentTitleHealer.isGenericDisplayName("ಸೋಬಾನೆ ಪದಗಳು.docx"))
        assertFalse(DocumentTitleHealer.isGenericDisplayName("Note_22Novtest.docx"))
    }

    @Test
    fun cleansExporterPrefixesAndConvertsLegacyNudiTitlesToUnicode() {
        assertEquals(
            "Quarterly Report",
            DocumentTitleHealer.cleanCandidateTitle("Microsoft Word - Quarterly Report.docx")
        )
        assertNull(DocumentTitleHealer.cleanCandidateTitle("Microsoft Word - Document1"))
        assertNull(DocumentTitleHealer.cleanCandidateTitle("Page 1 / 10"))
        assertNull(DocumentTitleHealer.cleanCandidateTitle("22/11/2026"))

        val nudiHeading = KannadaTextConverter.convertUnicodeToNudi("ಕರ್ನಾಟಕ ಸಾಹಿತ್ಯ")
        assertEquals(
            "ಕರ್ನಾಟಕ ಸಾಹಿತ್ಯ",
            DocumentTitleHealer.cleanCandidateTitle(nudiHeading)
        )
    }

    @Test
    fun extractsHeadingOrFirstMeaningfulLineFromLegacyNudiDocument() {
        val nudiTitle = KannadaTextConverter.convertUnicodeToNudi("ವಚನಗಳ ಅಧ್ಯಯನ")
        val doc = ParsedDocument(
            sourceUri = "content://wa/doc1",
            fileName = "DOC-20261122-WA0000.docx",
            sourceFormat = DocFormat.DOCX,
            blocks = listOf(
                DocBlock.Paragraph(runs = listOf(TextRun(text = "22/11/2026", fontFamily = "Calibri"))),
                DocBlock.Paragraph(
                    runs = listOf(TextRun(text = nudiTitle, fontFamily = "Nudi 01 e")),
                    headingLevel = 1
                ),
                DocBlock.Paragraph(runs = listOf(TextRun(text = "Body text here", fontFamily = "Calibri")))
            )
        )
        val extracted = DocumentTitleHealer.extractTitleFromParsedDocument(doc)
        assertEquals("ವಚನಗಳ ಅಧ್ಯಯನ", extracted)
        assertEquals(
            "ವಚನಗಳ ಅಧ್ಯಯನ.docx",
            DocumentTitleHealer.attachExtensionIfMissing(extracted!!, "DOC-20261122-WA0000.docx", DocFormat.DOCX)
        )
    }
}
