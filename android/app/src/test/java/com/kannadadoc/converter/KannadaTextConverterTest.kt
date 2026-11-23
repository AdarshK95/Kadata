package com.kannadadoc.converter

import com.kannadadoc.parser.DocBlock
import com.kannadadoc.parser.DocFormat
import com.kannadadoc.parser.ParsedDocument
import com.kannadadoc.parser.TextRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KannadaTextConverterTest {

    @Test
    fun roundTripCommonKannadaWords() {
        val samples = listOf(
            "ಕನ್ನಡ",
            "ಕರ್ನಾಟಕ",
            "ಬೆಂಗಳೂರು",
            "ಕೃಷ್ಣ",
            "ಮನೆ",
            "ಯಶಸ್ಸು",
            "ವಂದನೆ",
            "ವಿಶ್ವವಿದ್ಯಾಲಯ",
            "ಪುಸ್ತಕ"
        )
        for (word in samples) {
            val nudi = KannadaTextConverter.convertUnicodeToNudi(word)
            val back = KannadaTextConverter.convertNudiToUnicode(nudi)
            assertEquals("Round-trip mismatch for $word (nudi=$nudi)", word, back)
        }
    }

    @Test
    fun distinguishesVoAndMoInNudi() {
        assertEquals("ವೊ", KannadaTextConverter.convertNudiToUnicode("ªÉÇ"))
        assertEquals("ವೋ", KannadaTextConverter.convertNudiToUnicode("ªÉÇÃ"))
        assertEquals("ಮೊ", KannadaTextConverter.convertNudiToUnicode("ªÉÆ"))
        assertEquals("ಮೋ", KannadaTextConverter.convertNudiToUnicode("ªÉÆÃ"))
    }

    @Test
    fun handlesMahapranaAndArkavattu() {
        assertEquals("ಧರ್ಮ", KannadaTextConverter.convertNudiToUnicode("zsÀªÀÄð"))
        assertEquals("ಭಾರತ", KannadaTextConverter.convertNudiToUnicode("¨sÁgÀvÀ"))
    }

    @Test
    fun splitRunsPreservesEnglishInCalibriAndKannadaInLegacyFont() {
        val doc = ParsedDocument(
            sourceUri = "test://doc",
            fileName = "mixed.docx",
            sourceFormat = DocFormat.DOCX,
            blocks = listOf(
                DocBlock.Paragraph(
                    runs = listOf(TextRun(text = "Report: ಕನ್ನಡ 2026", fontFamily = "Noto Sans Kannada"))
                )
            )
        )
        val exported = KannadaTextConverter.prepareDocumentForLegacyExport(doc, "Nudi 01 e")
        val runs = (exported.blocks.first() as DocBlock.Paragraph).runs
        assertTrue(runs.any { it.fontFamily == "Calibri" && it.text.contains("Report:") })
        assertTrue(runs.any { it.fontFamily == "Nudi 01 e" && !KannadaTextConverter.hasKannadaUnicode(it.text) })
        assertTrue(runs.any { it.fontFamily == "Calibri" && it.text.contains("2026") })
    }

    @Test
    fun normalizeDocumentWithReportCountsLegacyRuns() {
        val nudiWord = KannadaTextConverter.convertUnicodeToNudi("ಕನ್ನಡ")
        val doc = ParsedDocument(
            sourceUri = "test://legacy",
            fileName = "legacy.docx",
            sourceFormat = DocFormat.DOCX,
            blocks = listOf(
                DocBlock.Paragraph(
                    runs = listOf(
                        TextRun(text = nudiWord, fontFamily = "Nudi 01 e"),
                        TextRun(text = " English", fontFamily = "Calibri")
                    )
                )
            )
        )
        val report = KannadaTextConverter.normalizeDocumentWithReport(doc, "auto")
        assertEquals(1, report.convertedRuns)
        assertTrue(report.detectedSummary.contains("Nudi"))
        val outRuns = (report.document.blocks.first() as DocBlock.Paragraph).runs
        assertEquals("ಕನ್ನಡ", outRuns[0].text)
        assertEquals(" English", outRuns[1].text)
        assertFalse(KannadaTextConverter.hasLegacyAsciiSignature(outRuns[0].text))
    }

    @Test
    fun healsMixedNudiRunsWithStrayUnicodeHalant() {
        val glitch1 = "1. ಈ ಹಸ್ತಪ್ರತಿಗಳಲ್ಲಿನ ಎಲ್ಲ ವಚನಗಳನ್ನು ಪ್ರತಿ ಮಾಡಿ, ಕೆಲವು ಸಂದರ್ಭದಲ್ಲಿ ¸ÀºÁAiÀÄPÀjAzÀ ¥À್gÀw ªÀiÁr¹zÀgÀÄ."
        val glitch2 = "ವಚನ ಸಾಹಿತ್ಯ ಸಂಪಾದನೆಯ ದೃಷ್ಟಿಯಿಂದ ಇವರು ಮಾಡಿದ ಕಾರ್ಯ CvÀ್AiÀÄCªÀÄÆ®å ¸ÀÄÛvÁåºÀðªÁzÀÄzÁVzÉ."
        val glitch3 = "2. ಶ್ರೀ ಚನ್ನಮಲ್ಲಿಕಾರ್ಜುನರು: ಪ್ರಕಟಿಸಿದವರಲ್ಲಿ ಶ್ರೀ ZÉ£À್£ÀªÀÄ°èPÁdÄð£ÀgÀÄ M§âgÀÄ."

        val doc = ParsedDocument(
            sourceUri = "test://mixed_halant",
            fileName = "V.S.K. 19-11-2026_Nudi.docx",
            sourceFormat = DocFormat.DOCX,
            blocks = listOf(
                DocBlock.Paragraph(runs = listOf(TextRun(text = glitch1, fontFamily = "Calibri"))),
                DocBlock.Paragraph(runs = listOf(TextRun(text = glitch2, fontFamily = "Nudi 01 e"))),
                DocBlock.Paragraph(runs = listOf(TextRun(text = glitch3, fontFamily = "Noto Sans Kannada")))
            )
        )

        val report = KannadaTextConverter.normalizeDocumentWithReport(doc, "auto")
        assertEquals(3, report.convertedRuns)
        val p0 = (report.document.blocks[0] as DocBlock.Paragraph).runs.joinToString("") { it.text }
        val p1 = (report.document.blocks[1] as DocBlock.Paragraph).runs.joinToString("") { it.text }
        val p2 = (report.document.blocks[2] as DocBlock.Paragraph).runs.joinToString("") { it.text }

        assertTrue(p0.contains("ಸಹಾಯಕರಿಂದ ಪ್ರತಿ ಮಾಡಿಸಿದರು."))
        assertTrue(p1.contains("ಅತ್ಯಅಮೂಲ್ಯ ಸ್ತುತ್ಯಾರ್ಹವಾದುದಾಗಿದೆ."))
        assertTrue(p2.contains("ಚೆನ್ನಮಲ್ಲಿಕಾರ್ಜುನರು ಒಬ್ಬರು."))
        assertFalse(KannadaTextConverter.hasNudiHighAscii(p0 + p1 + p2))
    }

    @Test
    fun unicodeToNudiNeverLeaksLiteralHalantOrJoiners() {
        val withJoiners = "ಪ್ರತಿ‍ ಚೆನ್ನ‌ ್"
        val nudi = KannadaTextConverter.convertUnicodeToNudi(withJoiners)
        assertFalse("Literal Unicode halant leaked into Nudi output: $nudi", nudi.contains('್'))
        assertFalse(nudi.contains('‌'))
        assertFalse(nudi.contains('‍'))
    }
}
