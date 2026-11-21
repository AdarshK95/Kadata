package com.kannadadoc.parser

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Plain-text parser with strict UTF-8 detection and Windows-1252 (Cp1252) fallback.
 */
class TxtParser {

    companion object {
        private val CP1252: Charset = Charset.forName("windows-1252")
    }

    fun parse(inputStream: InputStream, sourceUri: String, fileName: String): ParsedDocument {
        val rawBytes = inputStream.readBytes()
        val hasUtf8Bom = rawBytes.size >= 3 &&
            rawBytes[0] == 0xEF.toByte() &&
            rawBytes[1] == 0xBB.toByte() &&
            rawBytes[2] == 0xBF.toByte()

        val offset = if (hasUtf8Bom) 3 else 0
        val text = decodeTextStrictUtf8OrCp1252(rawBytes, offset)

        val blocks = text
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .split("\n")
            .map { line ->
                DocBlock.Paragraph(
                    runs = if (line.isEmpty()) emptyList() else listOf(
                        TextRun(text = line, fontFamily = null)
                    )
                )
            }

        return ParsedDocument(
            sourceUri = sourceUri,
            fileName = fileName,
            sourceFormat = DocFormat.TXT,
            blocks = blocks
        )
    }

    internal fun decodeTextStrictUtf8OrCp1252(bytes: ByteArray, offset: Int = 0): String {
        val slice = ByteBuffer.wrap(bytes, offset, bytes.size - offset)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(slice)
                .toString()
        } catch (_: CharacterCodingException) {
            String(bytes, offset, bytes.size - offset, CP1252)
        }
    }
}
