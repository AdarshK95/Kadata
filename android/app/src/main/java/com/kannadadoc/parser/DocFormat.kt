package com.kannadadoc.parser

import kotlinx.serialization.Serializable

@Serializable
enum class DocFormat(val badgeLabel: String) {
    DOCX("WORD"),
    DOC("DOC"),
    PDF("PDF"),
    ODT("ODT"),
    RTF("RTF"),
    TXT("TEXT"),
    HTML("WEB"),
    EPUB("EPUB"),
    PDF_EXTRACTED("PDF TEXT"),
    UNSUPPORTED("UNKNOWN");

    companion object {
        /**
         * Separates internal parser routing from user-facing type badges:
         * Word-compatible `.doc` exports store RTF bytes internally (routed safely to RtfParser),
         * while displaying as DOC on the Home screen so icon and Type match the `.doc` filename.
         */
        fun displayFormatFor(fileName: String, detectedFormat: DocFormat): DocFormat {
            if (detectedFormat == RTF && fileName.trim().endsWith(".doc", ignoreCase = true)) {
                return DOC
            }
            return detectedFormat
        }
    }
}
