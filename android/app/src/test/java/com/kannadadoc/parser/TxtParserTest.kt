package com.kannadadoc.parser

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.Charset

class TxtParserTest {

    private val parser = TxtParser()

    @Test
    fun fallsBackToCp1252ForLegacyNudiText() {
        val cp1252 = Charset.forName("windows-1252")
        val nudiRaw = "PÀ£ÀßqÀ\r\nEnglish line".toByteArray(cp1252)

        val parsed = parser.parse(ByteArrayInputStream(nudiRaw), "test://txt", "nudi.txt")
        assertEquals(2, parsed.blocks.size)
        assertEquals("PÀ£ÀßqÀ", (parsed.blocks[0] as DocBlock.Paragraph).runs[0].text)
        assertEquals("English line", (parsed.blocks[1] as DocBlock.Paragraph).runs[0].text)
    }

    @Test
    fun readsValidUtf8KannadaCleanly() {
        val utf8Bytes = "ಕರ್ನಾಟಕ ಸರ್ಕಾರ".toByteArray(Charsets.UTF_8)
        val parsed = parser.parse(ByteArrayInputStream(utf8Bytes), "test://utf8", "unicode.txt")
        assertEquals("ಕರ್ನಾಟಕ ಸರ್ಕಾರ", (parsed.blocks[0] as DocBlock.Paragraph).runs[0].text)
    }
}
