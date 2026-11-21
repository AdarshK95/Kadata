package com.kannadadoc.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocxParserTest {

    private val parser = DocxParser()

    @Test
    fun resolvesAllFourFontInheritanceLevels() {
        val stylesXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:docDefaults>
                <w:rPrDefault><w:rPr><w:rFonts w:ascii="Nudi 01 e"/></w:rPr></w:rPrDefault>
              </w:docDefaults>
              <w:style w:type="paragraph" w:styleId="KannadaBase">
                <w:rPr><w:rFonts w:ascii="Nudi 05 e"/></w:rPr>
              </w:style>
              <w:style w:type="paragraph" w:styleId="KannadaBody">
                <w:basedOn w:val="KannadaBase"/>
              </w:style>
              <w:style w:type="character" w:styleId="BarahaChar">
                <w:rPr><w:rFonts w:ascii="BRH Kannada"/><w:b/></w:rPr>
              </w:style>
            </w:styles>
        """.trimIndent()

        val documentXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p>
                  <w:r><w:rPr><w:rFonts w:ascii="Shree-Kan-0850"/></w:rPr><w:t>Direct</w:t></w:r>
                </w:p>
                <w:p>
                  <w:r><w:rPr><w:rStyle w:val="BarahaChar"/></w:rPr><w:t>CharStyle</w:t></w:r>
                </w:p>
                <w:p>
                  <w:pPr><w:pStyle w:val="KannadaBody"/></w:pPr>
                  <w:r><w:t>ParaBasedOn</w:t></w:r>
                </w:p>
                <w:p>
                  <w:r><w:t>DocDefault</w:t></w:r>
                </w:p>
              </w:body>
            </w:document>
        """.trimIndent()

        val bytes = buildDocxBytes(documentXml, stylesXml)
        val parsed = parser.parse(ByteArrayInputStream(bytes), "test://inheritance", "test.docx")

        assertEquals(4, parsed.blocks.size)
        val p0 = parsed.blocks[0] as DocBlock.Paragraph
        val p1 = parsed.blocks[1] as DocBlock.Paragraph
        val p2 = parsed.blocks[2] as DocBlock.Paragraph
        val p3 = parsed.blocks[3] as DocBlock.Paragraph

        assertEquals("Shree-Kan-0850", p0.runs[0].fontFamily)
        assertEquals("BRH Kannada", p1.runs[0].fontFamily)
        assertTrue(p1.runs[0].bold)
        assertEquals("Nudi 05 e", p2.runs[0].fontFamily)
        assertEquals("Nudi 01 e", p3.runs[0].fontFamily)
    }

    private fun buildDocxBytes(documentXml: String, stylesXml: String): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("word/styles.xml"))
            zos.write(stylesXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("word/document.xml"))
            zos.write(documentXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return baos.toByteArray()
    }
}
