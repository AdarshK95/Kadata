package com.kannadadoc.export

import com.kannadadoc.parser.DocBlock
import com.kannadadoc.parser.DocFormat
import com.kannadadoc.parser.DocxParser
import com.kannadadoc.parser.ParsedDocument
import com.kannadadoc.parser.TextRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class DocxWriterTest {

    @Test
    fun roundTripsUnicodeKannadaParagraphsAndTables() {
        val original = ParsedDocument(
            sourceUri = "test://roundtrip",
            fileName = "roundtrip.docx",
            sourceFormat = DocFormat.DOCX,
            blocks = listOf(
                DocBlock.Paragraph(
                    runs = listOf(TextRun(text = "ಕರ್ನಾಟಕ ಸರ್ಕಾರ", bold = true)),
                    headingLevel = 1,
                    alignment = "center"
                ),
                DocBlock.Table(
                    rows = listOf(
                        listOf(
                            listOf(DocBlock.Paragraph(runs = listOf(TextRun("ಹೆಸರು")))),
                            listOf(DocBlock.Paragraph(runs = listOf(TextRun("ಬಾಗಲಕೋಟೆ"))))
                        )
                    )
                )
            )
        )

        val baos = ByteArrayOutputStream()
        DocxWriter().write(original, baos)

        val reparsed = DocxParser().parse(
            ByteArrayInputStream(baos.toByteArray()),
            "test://roundtrip",
            "roundtrip.docx"
        )

        assertEquals(2, reparsed.blocks.size)
        val p0 = reparsed.blocks[0] as DocBlock.Paragraph
        assertEquals("ಕರ್ನಾಟಕ ಸರ್ಕಾರ", p0.runs[0].text)
        assertTrue(p0.runs[0].bold)
        assertEquals("center", p0.alignment)

        val t1 = reparsed.blocks[1] as DocBlock.Table
        assertEquals("ಬಾಗಲಕೋಟೆ", t1.rows[0][1][0].runs[0].text)
    }
}
