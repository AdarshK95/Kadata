package com.kannadadoc.parser

import kotlinx.serialization.Serializable

@Serializable
enum class DocFormat(val badgeLabel: String) {
    DOCX("WORD"),
    DOC("WORD 97"),
    PDF("PDF"),
    RTF("RTF"),
    TXT("TEXT"),
    PDF_EXTRACTED("PDF TEXT"),
    UNSUPPORTED("UNKNOWN")
}
