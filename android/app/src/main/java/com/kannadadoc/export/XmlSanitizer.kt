package com.kannadadoc.export

/**
 * W3C XML 1.0 sanitizer & entity escaper shared by DOCX, ODT, EPUB, and HTML writers.
 *
 * Strips characters forbidden by the XML 1.0 specification:
 * - C0 control characters 0x00..0x08, 0x0B (VT), 0x0C (FF), 0x0E..0x1F
 *   (frequently present in PDFBox text extracts and legacy Apache POI HWPF .doc files)
 * - Non-characters 0xFFFE, 0xFFFF
 * - Unpaired UTF-16 surrogates (while preserving valid supplementary-plane surrogate pairs)
 */
object XmlSanitizer {

    fun isValidXml10BmpChar(ch: Char): Boolean {
        val code = ch.code
        return code == 0x09 ||
            code == 0x0A ||
            code == 0x0D ||
            (code in 0x20..0xD7FF) ||
            (code in 0xE000..0xFFFD)
    }

    /**
     * Removes XML 1.0 illegal control characters and unpaired surrogates.
     * Fast-path returns [input] unmodified with zero allocations when already clean.
     */
    fun stripInvalidXmlChars(input: String): String {
        val len = input.length
        var needsClean = false
        var i = 0
        while (i < len) {
            val ch = input[i]
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 < len && Character.isLowSurrogate(input[i + 1])) {
                    i += 2
                    continue
                }
                needsClean = true
                break
            } else if (Character.isLowSurrogate(ch) || !isValidXml10BmpChar(ch)) {
                needsClean = true
                break
            }
            i++
        }
        if (!needsClean) return input

        val sb = StringBuilder(len)
        i = 0
        while (i < len) {
            val ch = input[i]
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 < len && Character.isLowSurrogate(input[i + 1])) {
                    sb.append(ch).append(input[i + 1])
                    i += 2
                    continue
                }
            } else if (!Character.isLowSurrogate(ch) && isValidXml10BmpChar(ch)) {
                sb.append(ch)
            }
            i++
        }
        return sb.toString()
    }

    /**
     * Strips XML 1.0 illegal characters and escapes `&`, `<`, `>`, `"`, and `'`.
     */
    fun escapeXml(input: String): String = escape(input, escapeApostrophe = true)

    /**
     * Strips XML 1.0 illegal characters and escapes `&`, `<`, `>`, and `"`.
     */
    fun escapeHtml(input: String): String = escape(input, escapeApostrophe = false)

    private fun escape(input: String, escapeApostrophe: Boolean): String {
        val len = input.length
        if (len == 0) return input
        val sb = StringBuilder(len + 8)
        var i = 0
        while (i < len) {
            val ch = input[i]
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 < len && Character.isLowSurrogate(input[i + 1])) {
                    sb.append(ch).append(input[i + 1])
                    i += 2
                    continue
                }
                i++
                continue
            }
            if (Character.isLowSurrogate(ch) || !isValidXml10BmpChar(ch)) {
                i++
                continue
            }
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> if (escapeApostrophe) sb.append("&apos;") else sb.append('\'')
                else -> sb.append(ch)
            }
            i++
        }
        return sb.toString()
    }
}
