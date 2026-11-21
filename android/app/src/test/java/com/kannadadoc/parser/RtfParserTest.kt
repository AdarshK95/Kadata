package com.kannadadoc.parser

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class RtfParserTest {

    private val parser = RtfParser()

    @Test
    fun extractsFontTableAndDecodesCp1252NudiHexEscapes() {
        val rtf = """
            {\rtf1\ansi\ansicpg1252\deff0
            {\fonttbl{\f0\fnil Nudi 01 e;}{\f1\fswiss Arial;}}
            {\pard\f0 P\'c0\'a3\'c0\'dfq\'c0\par}
            {\pard\f1 English Line\par}
            }
        """.trimIndent()

        val parsed = parser.parse(
            ByteArrayInputStream(rtf.toByteArray(Charsets.US_ASCII)),
            "test://rtf",
            "sample.rtf"
        )

        assertEquals(2, parsed.blocks.size)
        val p0 = parsed.blocks[0] as DocBlock.Paragraph
        val p1 = parsed.blocks[1] as DocBlock.Paragraph

        assertEquals("Nudi 01 e", p0.runs[0].fontFamily)
        assertEquals("PÀ£ÀßqÀ", p0.runs[0].text)
        assertEquals("Arial", p1.runs[0].fontFamily)
        assertEquals("English Line", p1.runs[0].text)
    }
}
